package com.tmuxer.app.ssh

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import com.tmuxer.app.BuildConfig
import com.tmuxer.app.data.AuthType
import com.tmuxer.app.data.ConnectionInfo
import com.tmuxer.app.data.SshProfile
import com.tmuxer.app.data.TmuxWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

class TmuxNotInstalledException : Exception("远程主机尚未安装 tmux")
class PiNotInstalledException : Exception("远程主机尚未安装 Pi")
class NoActiveConnectionException : Exception("SSH 连接已断开")

data class UploadedRemoteFile(
    val originalName: String,
    val remotePath: String,
    val size: Long
)

data class RemoteDirectoryListing(
    val currentPath: String,
    val parentPath: String?,
    val directories: List<String>
)

internal data class ImageStreamCheckpoint(
    val windowId: String,
    val streamIdentity: String,
    val byteOffset: Long
)

internal data class ImageReplayPlan(
    val firstByte: Long,
    val replayBytes: Long,
    val resumed: Boolean
)

internal fun planImageReplay(
    windowId: String,
    streamIdentity: String,
    streamSize: Long,
    checkpoint: ImageStreamCheckpoint?
): ImageReplayPlan {
    val safeSize = streamSize.coerceAtLeast(0)
    val canResume = checkpoint != null &&
        checkpoint.windowId == windowId &&
        checkpoint.streamIdentity == streamIdentity &&
        checkpoint.byteOffset in 0..safeSize
    val replayBytes = if (canResume) {
        safeSize - checkpoint.byteOffset
    } else {
        minOf(safeSize, MAX_IMAGE_STREAM_REPLAY_BYTES.toLong())
    }
    return ImageReplayPlan(
        firstByte = safeSize - replayBytes + 1,
        replayBytes = replayBytes,
        resumed = canResume
    )
}

// tmux sanitizes tab/control characters in format output to underscores. A long printable
// separator is stable across tmux versions and exceedingly unlikely in user-defined names.
internal const val TMUX_FIELD_SEPARATOR = "__TMUXER_FIELD_7F3A__"
internal const val MAX_IMAGE_STREAM_REPLAY_BYTES = 32 * 1024 * 1024
private const val MAX_TERMINAL_IMAGE_DOWNLOAD_BYTES = 18 * 1024 * 1024
private val TERMINAL_IMAGE_RELATIVE_PATH = Regex(
    "(?:images|pi-w[0-9]+(?:\\.stream)?\\.images)/[0-9a-f]{64}\\.(png|jpg|gif|webp|bin)"
)

internal fun isAllowedTerminalImagePath(home: String, path: String): Boolean {
    val safeHome = home.trimEnd('/').ifEmpty { "/" }
    val cachePrefix = if (safeHome == "/") "/.cache/tmuxer/" else "$safeHome/.cache/tmuxer/"
    return path.startsWith(cachePrefix) &&
        TERMINAL_IMAGE_RELATIVE_PATH.matches(path.removePrefix(cachePrefix))
}

class SshManager(context: Context) {
    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostKeyRepository = TofuHostKeyRepository(appContext)

    @Volatile
    private var session: Session? = null
    @Volatile
    private var terminalChannel: ChannelExec? = null
    @Volatile
    private var terminalInput: InputStream? = null
    @Volatile
    private var terminalOutput: OutputStream? = null
    @Volatile
    private var imageChannel: ChannelExec? = null
    @Volatile
    private var imageInput: InputStream? = null
    private var terminalReader: Job? = null
    private var imageReader: Job? = null
    private var terminalResizeJob: Job? = null
    private val terminalWrites = Channel<TerminalWrite>(Channel.UNLIMITED)

    init {
        // Keep high-frequency wheel and key events ordered. Launching one writer coroutine per
        // event could race JSch's OutputStream and made quick drags feel uneven on real networks.
        ioScope.launch {
            for (write in terminalWrites) {
                if (terminalOutput !== write.output) continue
                runCatching {
                    write.output.write(write.bytes)
                    write.output.flush()
                }
            }
        }
    }

    suspend fun connect(profile: SshProfile): ConnectionInfo = withContext(Dispatchers.IO) {
        disconnectBlocking()
        hostKeyRepository.resetObservation()

        val jsch = JSch().apply {
            hostKeyRepository = this@SshManager.hostKeyRepository
        }
        if (profile.authType == AuthType.PRIVATE_KEY) {
            jsch.addIdentity(
                "tmuxer-${profile.id}",
                profile.privateKey.toByteArray(Charsets.UTF_8),
                null,
                profile.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            )
        }

        val newSession = jsch.getSession(profile.username, profile.host, profile.port).apply {
            if (profile.authType == AuthType.PASSWORD) setPassword(profile.password)
            setConfig(
                Properties().apply {
                    put("StrictHostKeyChecking", "yes")
                    put("PreferredAuthentications", preferredAuthentication(profile))
                    put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
                }
            )
            serverAliveInterval = 15_000
            serverAliveCountMax = 3
            timeout = 15_000
            userInfo = NonInteractiveUserInfo(profile)
        }

        try {
            newSession.connect(15_000)
            session = newSession
            val keyBytes = Base64.decode(newSession.hostKey.key, Base64.DEFAULT)
            ConnectionInfo(
                profile = profile,
                hostKeyFingerprint = sha256Fingerprint(keyBytes),
                newlyTrustedHost = hostKeyRepository.newlyTrusted.get()
            )
        } catch (error: Throwable) {
            runCatching { newSession.disconnect() }
            throw error
        }
    }

    suspend fun listWindows(): List<TmuxWindow> = withContext(Dispatchers.IO) {
        val format = listOf(
            "#{session_name}", "#{session_id}", "#{window_index}", "#{window_id}",
            "#{window_name}", "#{window_active}", "#{window_panes}",
            "#{pane_current_command}", "#{pane_current_path}",
            "#{window_activity_flag}", "#{window_last_flag}"
        ).joinToString(TMUX_FIELD_SEPARATOR)
        val command = "if ! command -v tmux >/dev/null 2>&1; then " +
            "printf '__TMUXER_MISSING__\\n'; exit 127; " +
            "elif tmux has-session 2>/dev/null; then tmux list-windows -a -F ${shellQuote(format)}; fi"
        val result = execute(command)
        if (result.output.lineSequence().any { it.trim() == "__TMUXER_MISSING__" }) {
            throw TmuxNotInstalledException()
        }
        if (result.exitCode != 0) {
            throw IllegalStateException(result.error.ifBlank { "读取 tmux 窗口失败" }.trim())
        }
        result.output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(::parseTmuxWindow)
            .toList()
    }

    suspend fun isConnectionHealthy(): Boolean = withContext(Dispatchers.IO) {
        if (session?.isConnected != true) return@withContext false
        runCatching { execute("true", timeoutMillis = 5_000).exitCode == 0 }.getOrDefault(false)
    }

    fun isTerminalConnected(): Boolean = terminalChannel?.isConnected == true

    suspend fun createSession(
        name: String,
        launchPi: Boolean = false,
        workingDirectory: String = "",
        launchWithoutSession: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val safeName = name.trim()
        require(safeName.isNotEmpty()) { "会话名不能为空" }
        val piCheck = if (launchPi) buildPiExecutableCheckCommand() else ""
        val requestedDirectory = workingDirectory.trim()
        val directorySetup = if (launchPi && requestedDirectory.isNotEmpty()) {
            "START_DIR=${shellQuote(requestedDirectory)}; " +
                "if [ \"\$START_DIR\" = '~' ]; then START_DIR=\"\$HOME\"; " +
                "elif [ \"\${START_DIR#\\~/}\" != \"\$START_DIR\" ]; " +
                "then START_DIR=\"\$HOME/\${START_DIR#\\~/}\"; fi; " +
                "if [ ! -d \"\$START_DIR\" ]; then " +
                "printf '__TMUXER_DIRECTORY_MISSING__\\n'; exit 2; fi; "
        } else {
            ""
        }
        val createCommand = buildTmuxNewSessionCommand(
            sessionName = safeName,
            captureWindowId = launchPi,
            useStartDirectory = directorySetup.isNotEmpty()
        )
        val piLaunchCommand = if (launchPi) {
            val imageExtensionBase64 = Base64.encodeToString(
                appContext.assets.open("tmuxer-image-links.ts").use { it.readBytes() },
                Base64.NO_WRAP
            )
            // A user's tmux base-index may start at 1 (or another value), so target the stable
            // window ID printed by new-session instead of assuming every initial window is :0.
            val target = "\"\$TMUXER_TARGET_WINDOW\""
            // Keep Pi's normal tmux capability detection (so it does not reserve inline-image
            // rows). A temporary extension stores image tool results and inserts one OSC 8 link.
            val piEnvironment = "env TERM=xterm-256color COLORTERM=truecolor " +
                "PATH=\"\$(dirname \"\$TMUXER_PI_BIN\"):\$PATH\""
            val paneCommand = buildPiPaneCommand(piEnvironment, launchWithoutSession)
            " && (tmux set-option -g extended-keys on 2>/dev/null || true; " +
                "tmux set-option -g extended-keys-format csi-u 2>/dev/null || true; " +
                "tmux show-options -gv terminal-features 2>/dev/null | " +
                "grep -Eq '(^|,)xterm-256color:[^,]*hyperlinks' || " +
                "tmux set-option -as terminal-features ',xterm-256color:hyperlinks' 2>/dev/null || true; " +
                "tmux set-environment -t $target TMUXER_PI_BIN \"\$PI_BIN\"; " +
                "mkdir -p \"\$HOME/.cache/tmuxer\"; chmod 700 \"\$HOME/.cache/tmuxer\"; " +
                "find \"\$HOME/.cache/tmuxer\" -type f " +
                "\\( -name 'pi-w*.stream' -o -path '*/pi-w*.stream.images/*' " +
                "-o -path '*/pi-w*.images/*' \\) -mtime +7 -delete 2>/dev/null || true; " +
                "find \"\$HOME/.cache/tmuxer/images\" -type f -mtime +7 " +
                "-delete 2>/dev/null || true; " +
                "find \"\$HOME/.cache/tmuxer\" -depth -type d " +
                "\\( -name 'pi-w*.stream.images' -o -name 'pi-w*.images' \\) " +
                "-empty -delete 2>/dev/null || true; " +
                "IMAGE_DIR=\"\$HOME/.cache/tmuxer/images\"; " +
                "IMAGE_EXTENSION=\"\$HOME/.cache/tmuxer/image-links.ts\"; " +
                "mkdir -p \"\$IMAGE_DIR\"; chmod 700 \"\$IMAGE_DIR\"; " +
                "printf '%s' ${shellQuote(imageExtensionBase64)} | base64 -d > \"\$IMAGE_EXTENSION\"; " +
                "chmod 600 \"\$IMAGE_EXTENSION\"; " +
                "tmux set-option -w -t $target @tmuxer_image_links 1; " +
                "tmux set-option -wu -t $target @tmuxer_image_stream 2>/dev/null || true; " +
                "tmux set-environment -t $target TMUXER_IMAGE_DIR \"\$IMAGE_DIR\"; " +
                "tmux set-environment -t $target TMUXER_IMAGE_EXTENSION \"\$IMAGE_EXTENSION\"; " +
                "tmux rename-window -t $target pi; " +
                "tmux respawn-pane -k -t $target ${shellQuote(paneCommand)})"
        } else {
            ""
        }
        val result = execute(piCheck + directorySetup + createCommand + piLaunchCommand)
        if (result.output.lineSequence().any { it.trim() == "__TMUXER_PI_MISSING__" }) {
            throw PiNotInstalledException()
        }
        if (result.output.lineSequence().any { it.trim() == "__TMUXER_DIRECTORY_MISSING__" }) {
            throw IllegalArgumentException("工作目录不存在或无权访问：$requestedDirectory")
        }
        if (result.exitCode != 0) {
            throw IllegalStateException(result.error.ifBlank { result.output }.trim())
        }
    }

    suspend fun selectWindow(windowId: String) = withContext(Dispatchers.IO) {
        require(windowId.matches(Regex("@[0-9]+"))) { "无效的 tmux 窗口" }
        val result = execute("tmux select-window -t ${shellQuote(windowId)}")
        if (result.exitCode != 0) {
            throw IllegalStateException(result.error.ifBlank { "切换窗口失败" }.trim())
        }
    }

    suspend fun terminateSession(sessionId: String) = withContext(Dispatchers.IO) {
        require(sessionId.matches(Regex("\\$[0-9]+"))) { "无效的 tmux 会话" }
        val result = execute("tmux kill-session -t ${shellQuote(sessionId)}")
        if (result.exitCode != 0) {
            throw IllegalStateException(result.error.ifBlank { "退出 tmux 会话失败" }.trim())
        }
    }

    suspend fun listRemoteDirectories(path: String): RemoteDirectoryListing = withContext(Dispatchers.IO) {
        val activeSession = requireSession()
        val channel = activeSession.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(12_000)
            val home = channel.home.trimEnd('/').ifEmpty { "/" }
            val requested = path.trim().ifEmpty { "~" }
            val resolved = when {
                requested == "~" -> home
                requested.startsWith("~/") -> "$home/${requested.removePrefix("~/")}".replace("//", "/")
                else -> requested
            }
            channel.cd(resolved)
            val current = channel.pwd().trimEnd('/').ifEmpty { "/" }
            val parent = current.takeUnless { it == "/" }
                ?.substringBeforeLast('/', "")
                ?.ifEmpty { "/" }
            val directories = channel.ls(".")
                .asSequence()
                .filter { entry -> entry.attrs.isDir && entry.filename != "." && entry.filename != ".." }
                .map { entry ->
                    if (current == "/") "/${entry.filename}" else "$current/${entry.filename}"
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.substringAfterLast('/') })
                .toList()
            RemoteDirectoryListing(current, parent, directories)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    suspend fun uploadFile(
        uri: Uri,
        displayName: String,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): UploadedRemoteFile = withContext(Dispatchers.IO) {
        runCatching {
            execute(
                "umask 077; mkdir -p \"\$HOME/.cache/tmuxer/uploads\"; " +
                    "find \"\$HOME/.cache/tmuxer/uploads\" -type f -mtime +7 -delete 2>/dev/null || true",
                timeoutMillis = 5_000
            )
        }
        val activeSession = requireSession()
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取所选文件")
        val declaredSize = runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 } ?: -1L
        var channel: ChannelSftp? = null
        var remotePath: String? = null
        var completed = false
        try {
            val sftp = (activeSession.openChannel("sftp") as ChannelSftp).also { channel = it }
            sftp.connect(12_000)
            val home = sftp.home.trimEnd('/')
            val uploadDirectory = "$home/.cache/tmuxer/uploads"
            ensureSftpDirectory(sftp, uploadDirectory)
            runCatching {
                sftp.chmod(0b111000000, "$home/.cache/tmuxer")
                sftp.chmod(0b111000000, uploadDirectory)
            }
            val safeName = sanitizeUploadName(displayName)
            remotePath = "$uploadDirectory/${System.currentTimeMillis().toString(36)}-$safeName"
            var transferred = 0L
            val operationJob = coroutineContext[Job]
            val monitor = object : SftpProgressMonitor {
                private var total = declaredSize

                override fun init(op: Int, src: String?, dest: String?, max: Long) {
                    if (max >= 0) total = max
                    onProgress(0, total)
                }

                override fun count(count: Long): Boolean {
                    transferred += count
                    onProgress(transferred, total)
                    return operationJob?.isActive != false
                }

                override fun end() {
                    onProgress(transferred, total)
                }
            }
            input.use {
                sftp.put(it, remotePath, monitor, ChannelSftp.OVERWRITE)
            }
            runCatching { sftp.chmod(0b110000000, remotePath) }
            completed = true
            UploadedRemoteFile(displayName, remotePath, transferred)
        } finally {
            if (!completed) remotePath?.let { path -> runCatching { channel?.rm(path) } }
            runCatching { input.close() }
            runCatching { channel?.disconnect() }
        }
    }

    suspend fun downloadTerminalImage(remotePath: String): ByteArray = withContext(Dispatchers.IO) {
        val activeSession = requireSession()
        val channel = activeSession.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(12_000)
            val home = channel.home.trimEnd('/').ifEmpty { "/" }
            val canonicalPath = channel.realpath(remotePath)
            require(isAllowedTerminalImagePath(home, canonicalPath)) { "图片链接无效或已经过期" }
            val attributes = channel.lstat(canonicalPath)
            require(attributes.isReg) { "图片链接不是普通文件" }
            require(attributes.size in 1..MAX_TERMINAL_IMAGE_DOWNLOAD_BYTES.toLong()) {
                "图片过大，无法预览"
            }
            ByteArrayOutputStream(attributes.size.toInt()).use { output ->
                channel.get(canonicalPath, output)
                output.toByteArray().also { data ->
                    require(data.size <= MAX_TERMINAL_IMAGE_DOWNLOAD_BYTES) { "图片过大，无法预览" }
                }
            }
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    internal suspend fun openTerminal(
        sessionId: String,
        windowId: String,
        columns: Int,
        rows: Int,
        captureImages: Boolean,
        imageCheckpoint: ImageStreamCheckpoint?,
        onBytes: (ByteArray, Int) -> Unit,
        onImageBytes: (ByteArray, Int) -> Unit,
        onImageReplayStart: (Boolean) -> Unit,
        onImageCheckpoint: (ImageStreamCheckpoint) -> Unit,
        onImageReplayComplete: () -> Unit,
        onClosed: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(sessionId.matches(Regex("\\$[0-9]+"))) { "无效的 tmux 会话" }
        require(windowId.matches(Regex("@[0-9]+"))) { "无效的 tmux 窗口" }
        closeTerminalBlocking()

        if (captureImages) {
            // Workspaces created before OSC 8 image links used a private graphics side stream.
            // Keep opening it when the legacy window option is present; new workspaces skip it.
            val imageStreamOpened = runCatching {
                openImageStream(
                    windowId = windowId,
                    checkpoint = imageCheckpoint,
                    onImageBytes = onImageBytes,
                    onReplayStart = onImageReplayStart,
                    onCheckpoint = onImageCheckpoint,
                    onReplayComplete = onImageReplayComplete
                )
            }.getOrDefault(false)
            if (!imageStreamOpened) {
                onImageReplayStart(false)
                onImageReplayComplete()
            }
        }

        val activeSession = requireSession()
        val channel = activeSession.openChannel("exec") as ChannelExec
        channel.setPty(true)
        channel.setPtyType("xterm-256color")
        channel.setPtySize(columns.coerceAtLeast(20), rows.coerceAtLeast(5), 0, 0)
        channel.setCommand(
            withRemoteExecutablePath(
                // tmux marks clients without a UTF-8 locale as legacy and replaces CJK/emoji with
                // underscores even though the session stores Unicode correctly.
                "export LANG=C.UTF-8 LC_ALL=C.UTF-8; " +
                    "tmux select-window -t ${shellQuote(windowId)} && " +
                    "exec tmux attach-session -t ${shellQuote(sessionId)}"
            )
        )
        val input = channel.inputStream
        val output = channel.outputStream
        val error = ByteArrayOutputStream()
        channel.setErrStream(error)
        channel.connect(12_000)

        terminalChannel = channel
        terminalInput = input
        terminalOutput = output
        terminalReader = ioScope.launch {
            val buffer = ByteArray(16 * 1024)
            try {
                while (isActive && channel.isConnected) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    // The callback consumes bytes synchronously before the next read, so avoid a
                    // 16 KiB copy for every SSH packet.
                    if (count > 0) onBytes(buffer, count)
                }
            } catch (_: Throwable) {
                // Closing or replacing a channel interrupts its blocking read.
            } finally {
                // A stale reader must never mark a newer terminal as disconnected. This can happen
                // while the IME is animating and Compose is relaying a new PTY size.
                if (terminalChannel === channel) {
                    terminalChannel = null
                    terminalInput = null
                    terminalOutput = null
                    val errorBytes = error.toByteArray()
                    if (errorBytes.isNotEmpty()) onBytes(errorBytes, errorBytes.size)
                    onClosed(channel.exitStatus)
                }
            }
        }
    }

    private fun openImageStream(
        windowId: String,
        checkpoint: ImageStreamCheckpoint?,
        onImageBytes: (ByteArray, Int) -> Unit,
        onReplayStart: (Boolean) -> Unit,
        onCheckpoint: (ImageStreamCheckpoint) -> Unit,
        onReplayComplete: () -> Unit
    ): Boolean {
        val option = execute(
            "tmux show-options -wv -t ${shellQuote(windowId)} @tmuxer_image_stream 2>/dev/null",
            timeoutMillis = 5_000
        )
        val path = option.output.lineSequence().firstOrNull()?.trim().orEmpty()
        if (option.exitCode != 0 || path.isEmpty()) return false

        // Device/inode prevents a checkpoint from being reused if a tmux server restart creates a
        // different stream at the same path. Older/minimal systems without GNU stat fall back to
        // the path and still reject checkpoints whose offsets are beyond a truncated file.
        val statResult = execute("stat -Lc '%d:%i:%s' -- ${shellQuote(path)}", timeoutMillis = 5_000)
        val statParts = statResult.output.lineSequence().firstOrNull()?.trim()?.split(':', limit = 3)
        val streamSize: Long
        val streamIdentity: String
        if (statResult.exitCode == 0 && statParts?.size == 3 && statParts[2].toLongOrNull() != null) {
            streamSize = statParts[2].toLong().coerceAtLeast(0)
            streamIdentity = "$path:${statParts[0]}:${statParts[1]}"
        } else {
            val sizeResult = execute("wc -c < ${shellQuote(path)}", timeoutMillis = 5_000)
            streamSize = sizeResult.output.trim().toLongOrNull()?.coerceAtLeast(0) ?: return false
            streamIdentity = path
        }
        val plan = planImageReplay(windowId, streamIdentity, streamSize, checkpoint)
        if (BuildConfig.DEBUG) {
            Log.d(
                "TmuxerPerf",
                "image-replay window=$windowId resumed=${plan.resumed} " +
                    "bytes=${plan.replayBytes} streamSize=$streamSize"
            )
        }

        val channel = requireSession().openChannel("exec") as ChannelExec
        channel.setCommand(
            withRemoteExecutablePath("tail -c +${plan.firstByte} -F -- ${shellQuote(path)}")
        )
        channel.setInputStream(null)
        channel.setErrStream(ByteArrayOutputStream())
        val input = channel.inputStream
        try {
            channel.connect(10_000)
        } catch (error: Throwable) {
            runCatching { channel.disconnect() }
            throw error
        }
        imageChannel = channel
        imageInput = input
        onReplayStart(plan.resumed)
        imageReader = ioScope.launch {
            val buffer = ByteArray(16 * 1024)
            var replayRemaining = plan.replayBytes
            var streamOffset = plan.firstByte - 1
            var replayCompleted = false

            fun publishCheckpoint() {
                onCheckpoint(ImageStreamCheckpoint(windowId, streamIdentity, streamOffset))
            }

            fun finishReplay() {
                if (replayCompleted) return
                replayCompleted = true
                onReplayComplete()
            }

            publishCheckpoint()
            if (replayRemaining == 0L) finishReplay()
            try {
                while (isActive && channel.isConnected) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count <= 0) continue

                    val replayCount = minOf(replayRemaining, count.toLong()).toInt()
                    if (replayCount > 0) {
                        onImageBytes(buffer, replayCount)
                        replayRemaining -= replayCount
                        if (replayRemaining == 0L) finishReplay()
                    }
                    if (replayCount < count) {
                        if (replayCount == 0) {
                            onImageBytes(buffer, count)
                        } else {
                            val liveBytes = buffer.copyOfRange(replayCount, count)
                            onImageBytes(liveBytes, liveBytes.size)
                        }
                    }
                    streamOffset += count
                    publishCheckpoint()
                }
            } catch (_: Throwable) {
                // Closing or replacing a terminal also interrupts its image stream.
            } finally {
                if (!replayCompleted && imageChannel === channel) finishReplay()
                if (imageChannel === channel) {
                    imageChannel = null
                    imageInput = null
                    imageReader = null
                }
            }
        }
        return true
    }

    fun writeTerminal(bytes: ByteArray) {
        val output = terminalOutput ?: return
        terminalWrites.trySend(TerminalWrite(output, bytes.copyOf()))
    }

    fun resizeTerminal(columns: Int, rows: Int) {
        // TerminalView already waits for IME inset animation to settle. Keep only a short guard
        // here to collapse duplicate layouts without making the final tmux reflow feel delayed.
        terminalResizeJob?.cancel()
        terminalResizeJob = ioScope.launch {
            delay(80)
            val channel = terminalChannel ?: return@launch
            if (!channel.isConnected) return@launch
            runCatching {
                channel.setPtySize(
                    columns.coerceAtLeast(20),
                    rows.coerceAtLeast(5),
                    0,
                    0
                )
            }
        }
    }

    suspend fun closeTerminal() = withContext(Dispatchers.IO) {
        closeTerminalBlocking()
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectBlocking()
    }

    fun dispose() {
        disconnectBlocking()
        ioScope.cancel()
    }

    private fun execute(command: String, timeoutMillis: Long = 15_000): CommandResult {
        val channel = requireSession().openChannel("exec") as ChannelExec
        val error = ByteArrayOutputStream()
        channel.setCommand(withRemoteExecutablePath(command))
        channel.setInputStream(null)
        channel.setErrStream(error)
        val input = channel.inputStream
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        val startedAt = System.currentTimeMillis()

        try {
            channel.connect(10_000)
            while (true) {
                while (input.available() > 0) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                if (channel.isClosed && input.available() == 0) break
                if (System.currentTimeMillis() - startedAt > timeoutMillis) {
                    throw java.net.SocketTimeoutException("远程命令执行超时")
                }
                Thread.sleep(20)
            }
            return CommandResult(
                output.toString(Charsets.UTF_8.name()),
                error.toString(Charsets.UTF_8.name()),
                channel.exitStatus
            )
        } finally {
            channel.disconnect()
        }
    }

    private fun requireSession(): Session = session?.takeIf { it.isConnected }
        ?: throw NoActiveConnectionException()

    private fun closeTerminalBlocking() {
        terminalResizeJob?.cancel()
        terminalResizeJob = null
        // Clear identity first so the reader's finally block knows this is an intentional close.
        val reader = terminalReader
        val input = terminalInput
        val output = terminalOutput
        val channel = terminalChannel
        val graphicsReader = imageReader
        val graphicsInput = imageInput
        val graphicsChannel = imageChannel
        terminalReader = null
        terminalInput = null
        terminalOutput = null
        terminalChannel = null
        imageReader = null
        imageInput = null
        imageChannel = null
        reader?.cancel()
        graphicsReader?.cancel()
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { channel?.disconnect() }
        runCatching { graphicsInput?.close() }
        runCatching { graphicsChannel?.disconnect() }
    }

    private fun disconnectBlocking() {
        closeTerminalBlocking()
        runCatching { session?.disconnect() }
        session = null
    }

    private fun preferredAuthentication(profile: SshProfile): String = when (profile.authType) {
        AuthType.PASSWORD -> "password,keyboard-interactive"
        AuthType.PRIVATE_KEY -> "publickey"
    }

    private fun ensureSftpDirectory(channel: ChannelSftp, path: String) {
        var current = if (path.startsWith('/')) "/" else ""
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = if (current == "/") "/$segment" else if (current.isEmpty()) segment else "$current/$segment"
            val exists = runCatching { channel.stat(current).isDir }.getOrDefault(false)
            if (!exists) channel.mkdir(current)
        }
    }

    private fun sanitizeUploadName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val safe = buildString {
            leaf.forEach { char ->
                append(if (char.isLetterOrDigit() || char in charArrayOf('.', '_', '-')) char else '_')
            }
        }.trim('.', '_')
        val shortened = if (safe.length <= 96) {
            safe
        } else {
            val extension = safe.substringAfterLast('.', "").take(15)
            if (extension.isEmpty()) safe.take(96)
            else safe.substringBeforeLast('.').take(80) + "." + extension
        }
        return shortened.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "upload.bin"
    }

    private data class TerminalWrite(val output: OutputStream, val bytes: ByteArray)

    private fun sha256Fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private data class CommandResult(val output: String, val error: String, val exitCode: Int)
}

// SSH exec channels are non-login shells, so macOS Homebrew, Nix and user-local tools may be
// absent from PATH even though the same command works in an interactive terminal.
internal fun withRemoteExecutablePath(command: String): String =
    "export PATH=\"\$PATH:\$HOME/.local/bin:\$HOME/bin:\$HOME/.local/share/mise/shims:" +
        "\$HOME/.asdf/shims:\$HOME/.nix-profile/bin:/opt/homebrew/bin:/usr/local/bin:" +
        "/opt/local/bin:/home/linuxbrew/.linuxbrew/bin:/run/current-system/sw/bin:" +
        "/nix/var/nix/profiles/default/bin\"; $command"

internal fun buildPiExecutableCheckCommand(): String =
    "PI_BIN=\$(command -v pi 2>/dev/null || true); " +
        "if [ -z \"\$PI_BIN\" ] && [ -x \"\$HOME/.local/share/pi-node/current/bin/pi\" ]; " +
        "then PI_BIN=\"\$HOME/.local/share/pi-node/current/bin/pi\"; fi; " +
        // Version managers such as nvm are commonly initialized only by interactive shell files.
        "if [ -z \"\$PI_BIN\" ]; then " +
        "PI_BIN=\$(\"\${SHELL:-/bin/sh}\" -lic 'command -v pi 2>/dev/null' " +
        "2>/dev/null | tail -n 1); fi; " +
        "if [ ! -x \"\$PI_BIN\" ]; then printf '__TMUXER_PI_MISSING__\\n'; exit 127; fi; "

internal fun buildPiPaneCommand(
    environment: String,
    launchWithoutSession: Boolean
): String {
    val sessionOption = if (launchWithoutSession) " --no-session" else ""
    return "sleep 1; exec $environment \"\$TMUXER_PI_BIN\"$sessionOption " +
        "--tui-mode fullscreen -e \"\$TMUXER_IMAGE_EXTENSION\""
}

internal fun buildTmuxNewSessionCommand(
    sessionName: String,
    captureWindowId: Boolean,
    useStartDirectory: Boolean
): String {
    val command = buildString {
        append("tmux new-session -d")
        if (captureWindowId) append(" -P -F '#{window_id}'")
        append(" -s ").append(shellQuote(sessionName))
        if (useStartDirectory) append(" -c \"\$START_DIR\"")
    }
    return if (captureWindowId) {
        "TMUXER_TARGET_WINDOW=\$($command)"
    } else {
        command
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun parseTmuxWindow(line: String): TmuxWindow? {
    val parts = line.split(TMUX_FIELD_SEPARATOR, limit = 11)
    if (parts.size < 11) return null
    return TmuxWindow(
        sessionName = parts[0],
        sessionId = parts[1],
        index = parts[2].toIntOrNull() ?: return null,
        windowId = parts[3],
        name = parts[4],
        active = parts[5] == "1",
        paneCount = parts[6].toIntOrNull() ?: 1,
        command = parts[7],
        path = parts[8],
        activity = parts[9] == "1",
        last = parts[10] == "1"
    )
}

/** Trust-on-first-use repository: the first key is pinned; later key changes are rejected. */
private class TofuHostKeyRepository(context: Context) : HostKeyRepository {
    private val preferences = context.getSharedPreferences("ssh_host_keys", Context.MODE_PRIVATE)
    val newlyTrusted = AtomicBoolean(false)

    fun resetObservation() {
        newlyTrusted.set(false)
    }

    @Synchronized
    override fun check(host: String, key: ByteArray): Int {
        val preferenceKey = Base64.encodeToString(host.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
        val existing = preferences.getString(preferenceKey, null)
        return when {
            existing == null -> {
                preferences.edit().putString(preferenceKey, encoded).commit()
                newlyTrusted.set(true)
                HostKeyRepository.OK
            }
            MessageDigest.isEqual(
                Base64.decode(existing, Base64.NO_WRAP),
                key
            ) -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
    override fun remove(host: String, type: String?) = Unit
    override fun remove(host: String, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "tmuxer://trusted-hosts"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private class NonInteractiveUserInfo(private val profile: SshProfile) : UserInfo {
    override fun getPassphrase(): String = profile.passphrase
    override fun getPassword(): String = profile.password
    override fun promptPassword(message: String?): Boolean = profile.password.isNotEmpty()
    override fun promptPassphrase(message: String?): Boolean = profile.passphrase.isNotEmpty()
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
}
