package com.tmuxer.app.ssh

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
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

// tmux sanitizes tab/control characters in format output to underscores. A long printable
// separator is stable across tmux versions and exceedingly unlikely in user-defined names.
internal const val TMUX_FIELD_SEPARATOR = "__TMUXER_FIELD_7F3A__"
private const val MAX_IMAGE_STREAM_REPLAY_BYTES = 32 * 1024 * 1024
private const val KITTY_FILTER_BASE64 =
    "Y29uc3QgZnMgPSByZXF1aXJlKCdub2RlOmZzJyk7CmNvbnN0IG91dHB1dCA9IGZzLmNyZWF0ZVdyaXRlU3RyZWFtKHByb2Nlc3MuYXJndlsyXSwgeyBmbGFnczogJ2EnLCBtb2RlOiAwbzYwMCB9KTsKbGV0IHN0YXRlID0gMDsKcHJvY2Vzcy5zdGRpbi5vbignZGF0YScsIChjaHVuaykgPT4gewogIG91dHB1dC53cml0ZShjaHVuayk7CiAgY29uc3QgY2xlYW4gPSBCdWZmZXIuYWxsb2NVbnNhZmUoY2h1bmsubGVuZ3RoKTsKICBsZXQgbGVuZ3RoID0gMDsKICBmb3IgKGNvbnN0IGJ5dGUgb2YgY2h1bmspIHsKICAgIGlmIChzdGF0ZSA9PT0gMCkgewogICAgICBpZiAoYnl0ZSA9PT0gMHgxYikgc3RhdGUgPSAxOwogICAgICBlbHNlIGNsZWFuW2xlbmd0aCsrXSA9IGJ5dGU7CiAgICB9IGVsc2UgaWYgKHN0YXRlID09PSAxKSB7CiAgICAgIGlmIChieXRlID09PSAweDVmKSBzdGF0ZSA9IDI7CiAgICAgIGVsc2UgewogICAgICAgIGNsZWFuW2xlbmd0aCsrXSA9IDB4MWI7CiAgICAgICAgaWYgKGJ5dGUgPT09IDB4MWIpIHN0YXRlID0gMTsKICAgICAgICBlbHNlIHsgY2xlYW5bbGVuZ3RoKytdID0gYnl0ZTsgc3RhdGUgPSAwOyB9CiAgICAgIH0KICAgIH0gZWxzZSBpZiAoc3RhdGUgPT09IDIpIHsKICAgICAgaWYgKGJ5dGUgPT09IDB4MWIpIHN0YXRlID0gMzsKICAgIH0gZWxzZSBpZiAoYnl0ZSA9PT0gMHg1YykgewogICAgICBzdGF0ZSA9IDA7CiAgICB9IGVsc2UgaWYgKGJ5dGUgIT09IDB4MWIpIHsKICAgICAgc3RhdGUgPSAyOwogICAgfQogIH0KICBpZiAobGVuZ3RoID4gMCkgcHJvY2Vzcy5zdGRvdXQud3JpdGUoY2xlYW4uc3ViYXJyYXkoMCwgbGVuZ3RoKSk7Cn0pOwpwcm9jZXNzLnN0ZGluLm9uKCdlbmQnLCAoKSA9PiBvdXRwdXQuZW5kKCkpOwo="
private const val PYTHON_PTY_PROXY_BASE64 =
    "aW1wb3J0IGVycm5vCmltcG9ydCBmY250bAppbXBvcnQgb3MKaW1wb3J0IHNlbGVjdAppbXBvcnQgc2lnbmFsCmltcG9ydCBzeXMKaW1wb3J0IHRlcm1pb3MKaW1wb3J0IHR0eQoKaWYgbGVuKHN5cy5hcmd2KSA8IDI6CiAgICByYWlzZSBTeXN0ZW1FeGl0KCJ1c2FnZTogcHR5LXByb3h5LnB5IENPTU1BTkQgW0FSRyAuLi5dIikKCnN0ZGluX2ZkID0gc3lzLnN0ZGluLmZpbGVubygpCnN0ZG91dF9mZCA9IHN5cy5zdGRvdXQuZmlsZW5vKCkKcGlkLCBtYXN0ZXJfZmQgPSBvcy5mb3JrcHR5KCkKaWYgcGlkID09IDA6CiAgICBvcy5leGVjdnAoc3lzLmFyZ3ZbMV0sIHN5cy5hcmd2WzE6XSkKCm9sZF90ZXJtaW9zID0gTm9uZQoKZGVmIHJlc2l6ZSgqXyk6CiAgICBpZiBub3Qgb3MuaXNhdHR5KHN0ZGluX2ZkKToKICAgICAgICByZXR1cm4KICAgIHRyeToKICAgICAgICBzaXplID0gZmNudGwuaW9jdGwoc3RkaW5fZmQsIHRlcm1pb3MuVElPQ0dXSU5TWiwgYiJcMCIgKiA4KQogICAgICAgIGZjbnRsLmlvY3RsKG1hc3Rlcl9mZCwgdGVybWlvcy5USU9DU1dJTlNaLCBzaXplKQogICAgZXhjZXB0IE9TRXJyb3I6CiAgICAgICAgcGFzcwoKZGVmIGZvcndhcmQoc2lnbnVtLCBfZnJhbWUpOgogICAgdHJ5OgogICAgICAgIG9zLmtpbGwocGlkLCBzaWdudW0pCiAgICBleGNlcHQgUHJvY2Vzc0xvb2t1cEVycm9yOgogICAgICAgIHBhc3MKCmRlZiB3cml0ZV9hbGwoZmQsIGRhdGEpOgogICAgdmlldyA9IG1lbW9yeXZpZXcoZGF0YSkKICAgIHdoaWxlIHZpZXc6CiAgICAgICAgd3JpdHRlbiA9IG9zLndyaXRlKGZkLCB2aWV3KQogICAgICAgIHZpZXcgPSB2aWV3W3dyaXR0ZW46XQoKc2lnbmFsLnNpZ25hbChzaWduYWwuU0lHV0lOQ0gsIHJlc2l6ZSkKZm9yIGZvcndhcmRlZF9zaWduYWwgaW4gKHNpZ25hbC5TSUdIVVAsIHNpZ25hbC5TSUdURVJNKToKICAgIHNpZ25hbC5zaWduYWwoZm9yd2FyZGVkX3NpZ25hbCwgZm9yd2FyZCkKCmlmIG9zLmlzYXR0eShzdGRpbl9mZCk6CiAgICBvbGRfdGVybWlvcyA9IHRlcm1pb3MudGNnZXRhdHRyKHN0ZGluX2ZkKQogICAgdHR5LnNldHJhdyhzdGRpbl9mZCkKcmVzaXplKCkKaW5wdXRzID0gW21hc3Rlcl9mZCwgc3RkaW5fZmRdCnRyeToKICAgIHdoaWxlIG1hc3Rlcl9mZCBpbiBpbnB1dHM6CiAgICAgICAgcmVhZGFibGUsIF8sIF8gPSBzZWxlY3Quc2VsZWN0KGlucHV0cywgW10sIFtdKQogICAgICAgIGlmIHN0ZGluX2ZkIGluIHJlYWRhYmxlOgogICAgICAgICAgICBkYXRhID0gb3MucmVhZChzdGRpbl9mZCwgNjU1MzYpCiAgICAgICAgICAgIGlmIGRhdGE6CiAgICAgICAgICAgICAgICB3cml0ZV9hbGwobWFzdGVyX2ZkLCBkYXRhKQogICAgICAgICAgICBlbHNlOgogICAgICAgICAgICAgICAgaW5wdXRzLnJlbW92ZShzdGRpbl9mZCkKICAgICAgICBpZiBtYXN0ZXJfZmQgaW4gcmVhZGFibGU6CiAgICAgICAgICAgIHRyeToKICAgICAgICAgICAgICAgIGRhdGEgPSBvcy5yZWFkKG1hc3Rlcl9mZCwgNjU1MzYpCiAgICAgICAgICAgIGV4Y2VwdCBPU0Vycm9yIGFzIGVycm9yOgogICAgICAgICAgICAgICAgaWYgZXJyb3IuZXJybm8gPT0gZXJybm8uRUlPOgogICAgICAgICAgICAgICAgICAgIGJyZWFrCiAgICAgICAgICAgICAgICByYWlzZQogICAgICAgICAgICBpZiBub3QgZGF0YToKICAgICAgICAgICAgICAgIGJyZWFrCiAgICAgICAgICAgIHdyaXRlX2FsbChzdGRvdXRfZmQsIGRhdGEpCmV4Y2VwdCBCcm9rZW5QaXBlRXJyb3I6CiAgICB0cnk6CiAgICAgICAgb3Mua2lsbChwaWQsIHNpZ25hbC5TSUdIVVApCiAgICBleGNlcHQgUHJvY2Vzc0xvb2t1cEVycm9yOgogICAgICAgIHBhc3MKZmluYWxseToKICAgIGlmIG9sZF90ZXJtaW9zIGlzIG5vdCBOb25lOgogICAgICAgIHRlcm1pb3MudGNzZXRhdHRyKHN0ZGluX2ZkLCB0ZXJtaW9zLlRDU0FGTFVTSCwgb2xkX3Rlcm1pb3MpCiAgICBvcy5jbG9zZShtYXN0ZXJfZmQpCgpfLCBzdGF0dXMgPSBvcy53YWl0cGlkKHBpZCwgMCkKaWYgaGFzYXR0cihvcywgIndhaXRzdGF0dXNfdG9fZXhpdGNvZGUiKToKICAgIHJhaXNlIFN5c3RlbUV4aXQob3Mud2FpdHN0YXR1c190b19leGl0Y29kZShzdGF0dXMpKQppZiBvcy5XSUZFWElURUQoc3RhdHVzKToKICAgIHJhaXNlIFN5c3RlbUV4aXQob3MuV0VYSVRTVEFUVVMoc3RhdHVzKSkKaWYgb3MuV0lGU0lHTkFMRUQoc3RhdHVzKToKICAgIHJhaXNlIFN5c3RlbUV4aXQoMTI4ICsgb3MuV1RFUk1TSUcoc3RhdHVzKSkKcmFpc2UgU3lzdGVtRXhpdCgxKQo="

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

    suspend fun createSession(name: String, launchPi: Boolean = false) = withContext(Dispatchers.IO) {
        val safeName = name.trim()
        require(safeName.isNotEmpty()) { "会话名不能为空" }
        val piCheck = if (launchPi) {
            "PI_BIN=\$(command -v pi 2>/dev/null || true); " +
                "if [ -z \"\$PI_BIN\" ] && [ -x \"\$HOME/.local/share/pi-node/current/bin/pi\" ]; " +
                "then PI_BIN=\"\$HOME/.local/share/pi-node/current/bin/pi\"; fi; " +
                "if [ -z \"\$PI_BIN\" ]; then printf '__TMUXER_PI_MISSING__\\n'; exit 127; fi; "
        } else {
            ""
        }
        val createCommand = "tmux new-session -d -s ${shellQuote(safeName)}"
        val piLaunchCommand = if (launchPi) {
            val target = shellQuote("$safeName:0")
            // Pi normally disables images under tmux. Hide only the multiplexer environment from
            // Pi, advertise Kitty graphics support, and mirror the pane's raw output before tmux
            // strips graphics commands. A short delay gives pipe-pane time to attach first.
            val piEnvironment = "env -u TMUX TERM=xterm-256color " +
                "TERM_PROGRAM=kitty KITTY_WINDOW_ID=tmuxer COLORTERM=truecolor " +
                "PATH=\"\$(dirname \"\$TMUXER_PI_BIN\"):\$PATH\""
            val piCommand = "exec $piEnvironment \"\$TMUXER_PI_BIN\" --tui-mode fullscreen"
            val pythonPiCommand = "exec $piEnvironment python3 \"\$TMUXER_PTY_PROXY\" " +
                "\"\$TMUXER_PI_BIN\" --tui-mode fullscreen"
            val paneCommand = "sleep 1; " +
                "if [ -n \"\$TMUXER_PI_NODE\" ] && command -v script >/dev/null 2>&1; then " +
                "script -qefc ${shellQuote(piCommand)} /dev/null | " +
                "exec \"\$TMUXER_PI_NODE\" \"\$TMUXER_IMAGE_FILTER\" \"\$TMUXER_IMAGE_STREAM\"; " +
                "elif [ -n \"\$TMUXER_PI_NODE\" ] && command -v python3 >/dev/null 2>&1; then " +
                "$pythonPiCommand | " +
                "exec \"\$TMUXER_PI_NODE\" \"\$TMUXER_IMAGE_FILTER\" \"\$TMUXER_IMAGE_STREAM\"; " +
                "else $piCommand; fi"
            " && (tmux set-option -g extended-keys on 2>/dev/null || true; " +
                "tmux set-option -g extended-keys-format csi-u 2>/dev/null || true; " +
                "PI_NODE=\$(command -v node 2>/dev/null || true); " +
                "if [ -z \"\$PI_NODE\" ] && [ -x \"\$(dirname \"\$PI_BIN\")/node\" ]; " +
                "then PI_NODE=\"\$(dirname \"\$PI_BIN\")/node\"; fi; " +
                "tmux set-environment -t $target TMUXER_PI_BIN \"\$PI_BIN\"; " +
                "mkdir -p \"\$HOME/.cache/tmuxer\"; chmod 700 \"\$HOME/.cache/tmuxer\"; " +
                "find \"\$HOME/.cache/tmuxer\" -type f -name 'pi-w*.stream' " +
                "-mtime +7 -delete 2>/dev/null || true; " +
                "WINDOW_KEY=\$(tmux display-message -p -t $target '#{window_id}' | tr -cd '0-9'); " +
                "IMAGE_STREAM=\"\$HOME/.cache/tmuxer/pi-w\${WINDOW_KEY}.stream\"; " +
                "IMAGE_FILTER=\"\$HOME/.cache/tmuxer/kitty-filter.cjs\"; " +
                "PTY_PROXY=\"\$HOME/.cache/tmuxer/pty-proxy.py\"; " +
                ": > \"\$IMAGE_STREAM\"; chmod 600 \"\$IMAGE_STREAM\"; " +
                "printf '%s' ${shellQuote(KITTY_FILTER_BASE64)} | base64 -d > \"\$IMAGE_FILTER\"; " +
                "printf '%s' ${shellQuote(PYTHON_PTY_PROXY_BASE64)} | base64 -d > \"\$PTY_PROXY\"; " +
                "chmod 600 \"\$IMAGE_FILTER\" \"\$PTY_PROXY\"; " +
                "if [ -n \"\$PI_NODE\" ] && " +
                "(command -v script >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1); then " +
                "tmux set-option -w -t $target @tmuxer_image_stream \"\$IMAGE_STREAM\"; " +
                "else tmux set-option -wu -t $target @tmuxer_image_stream 2>/dev/null || true; fi; " +
                "tmux set-environment -t $target TMUXER_PI_NODE \"\$PI_NODE\"; " +
                "tmux set-environment -t $target TMUXER_IMAGE_FILTER \"\$IMAGE_FILTER\"; " +
                "tmux set-environment -t $target TMUXER_IMAGE_STREAM \"\$IMAGE_STREAM\"; " +
                "tmux set-environment -t $target TMUXER_PTY_PROXY \"\$PTY_PROXY\"; " +
                "tmux rename-window -t $target pi; " +
                "tmux respawn-pane -k -t $target ${shellQuote(paneCommand)})"
        } else {
            ""
        }
        val result = execute(piCheck + createCommand + piLaunchCommand)
        if (result.output.lineSequence().any { it.trim() == "__TMUXER_PI_MISSING__" }) {
            throw PiNotInstalledException()
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

    suspend fun openTerminal(
        sessionId: String,
        windowId: String,
        columns: Int,
        rows: Int,
        captureImages: Boolean,
        onBytes: (ByteArray, Int) -> Unit,
        onImageBytes: (ByteArray, Int) -> Unit,
        onClosed: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(sessionId.matches(Regex("\\$[0-9]+"))) { "无效的 tmux 会话" }
        require(windowId.matches(Regex("@[0-9]+"))) { "无效的 tmux 窗口" }
        closeTerminalBlocking()

        if (captureImages) {
            // Pi intentionally disables inline graphics when it detects tmux. App-created Pi panes
            // mirror their raw output to a private stream, allowing Kitty images to be decoded
            // without weakening tmux's normal escape-sequence filtering.
            runCatching { openImageStream(windowId, onImageBytes) }
        }

        val activeSession = requireSession()
        val channel = activeSession.openChannel("exec") as ChannelExec
        channel.setPty(true)
        channel.setPtyType("xterm-256color")
        channel.setPtySize(columns.coerceAtLeast(20), rows.coerceAtLeast(5), 0, 0)
        channel.setCommand(
            // tmux marks clients without a UTF-8 locale as legacy and replaces CJK/emoji with
            // underscores even though the session stores Unicode correctly.
            "export LANG=C.UTF-8 LC_ALL=C.UTF-8; " +
                "tmux select-window -t ${shellQuote(windowId)} && " +
                "exec tmux attach-session -t ${shellQuote(sessionId)}"
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
        onImageBytes: (ByteArray, Int) -> Unit
    ) {
        val option = execute(
            "tmux show-options -wv -t ${shellQuote(windowId)} @tmuxer_image_stream 2>/dev/null",
            timeoutMillis = 5_000
        )
        val path = option.output.lineSequence().firstOrNull()?.trim().orEmpty()
        if (option.exitCode != 0 || path.isEmpty()) return

        val channel = requireSession().openChannel("exec") as ChannelExec
        channel.setCommand("tail -c $MAX_IMAGE_STREAM_REPLAY_BYTES -F -- ${shellQuote(path)}")
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
        imageReader = ioScope.launch {
            val buffer = ByteArray(16 * 1024)
            try {
                while (isActive && channel.isConnected) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) onImageBytes(buffer, count)
                }
            } catch (_: Throwable) {
                // Closing or replacing a terminal also interrupts its image stream.
            } finally {
                if (imageChannel === channel) {
                    imageChannel = null
                    imageInput = null
                    imageReader = null
                }
            }
        }
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
        channel.setCommand(command)
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

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private data class TerminalWrite(val output: OutputStream, val bytes: ByteArray)

    private fun sha256Fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private data class CommandResult(val output: String, val error: String, val exitCode: Int)
}

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
