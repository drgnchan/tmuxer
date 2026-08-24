package com.tmuxer.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.tmuxer.app.data.AppScreen
import com.tmuxer.app.data.ConnectionRestoreState
import com.tmuxer.app.data.ConnectionRestoreStore
import com.tmuxer.app.data.ConnectionState
import com.tmuxer.app.data.RecentPiDirectoryStore
import com.tmuxer.app.data.SecureProfileStore
import com.tmuxer.app.data.SshProfile
import com.tmuxer.app.data.TmuxWindow
import com.tmuxer.app.data.resolveRestoredWindow
import com.tmuxer.app.ssh.ImageStreamCheckpoint
import com.tmuxer.app.ssh.NoActiveConnectionException
import com.tmuxer.app.ssh.PiNotInstalledException
import com.tmuxer.app.ssh.RemoteDirectoryListing
import com.tmuxer.app.ssh.SshManager
import com.tmuxer.app.ssh.TmuxNotInstalledException
import com.tmuxer.app.terminal.TerminalEmulator
import com.tmuxer.app.terminal.TerminalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val BRACKETED_PASTE_START = "\u001B[200~"
private const val BRACKETED_PASTE_END = "\u001B[201~"

internal fun buildPiUploadInsertion(uploadedPaths: List<String>): String {
    val references = uploadedPaths.joinToString(separator = " ", postfix = " ") { "@$it" }
    // Sending @ references as ordinary keystrokes opens Pi's file autocomplete. Bracketed paste
    // inserts the same references atomically through Pi's paste path, which cancels autocomplete.
    return "$BRACKETED_PASTE_START$references$BRACKETED_PASTE_END"
}

data class FileUploadProgress(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val bytesSent: Long,
    val totalBytes: Long
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0 }?.let { (bytesSent.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

sealed interface ConnectionRecoveryStatus {
    data object Idle : ConnectionRecoveryStatus
    data class Restoring(val message: String) : ConnectionRecoveryStatus
    data object Restored : ConnectionRecoveryStatus
}

class TmuxerViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = SecureProfileStore(application)
    private val restoreStore = ConnectionRestoreStore(application)
    private val recentPiDirectoryStore = RecentPiDirectoryStore(application)
    private val sshManager = SshManager(application)

    private val _profiles = MutableStateFlow(profileStore.load())
    val profiles = _profiles.asStateFlow()

    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Hosts)
    val screen = _screen.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection = _connection.asStateFlow()

    private val _windows = MutableStateFlow<List<TmuxWindow>>(emptyList())
    val windows = _windows.asStateFlow()

    private val _windowPreviews =
        MutableStateFlow<Map<String, WindowPreviewUiState>>(emptyMap())
    internal val windowPreviews = _windowPreviews.asStateFlow()

    private val _recentPiDirectories = MutableStateFlow(
        restoreStore.load()?.profileId?.let(recentPiDirectoryStore::load).orEmpty()
    )
    val recentPiDirectories = _recentPiDirectories.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    private val _dashboardMessage = MutableStateFlow<String?>(null)
    val dashboardMessage = _dashboardMessage.asStateFlow()

    private val _selectedWindow = MutableStateFlow<TmuxWindow?>(null)
    val selectedWindow = _selectedWindow.asStateFlow()

    private val _terminalConnected = MutableStateFlow(false)
    val terminalConnected = _terminalConnected.asStateFlow()

    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive = _ctrlActive.asStateFlow()

    private val _terminalTheme = MutableStateFlow(TerminalTheme.DARK)
    val terminalTheme = _terminalTheme.asStateFlow()

    private val _uploadProgress = MutableStateFlow<FileUploadProgress?>(null)
    val uploadProgress = _uploadProgress.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices = _notices.asSharedFlow()

    private val _connectionRecoveryStatus =
        MutableStateFlow<ConnectionRecoveryStatus>(ConnectionRecoveryStatus.Idle)
    val connectionRecoveryStatus = _connectionRecoveryStatus.asStateFlow()

    val terminal = TerminalEmulator(reply = { response ->
        sshManager.writeTerminal(response.toByteArray(Charsets.UTF_8))
    })
    // New Pi panes put OSC 8 image links directly in tmux's main output. Keep the shadow graphics
    // parser only for workspaces created by older tmuxer versions that still expose a side stream.
    private val imageTerminal = TerminalEmulator(
        retainScreenContent = false
    ).apply {
        mirrorKittyGraphicsTo(terminal)
    }

    private var refreshJob: Job? = null
    private var windowPreviewJob: Job? = null
    private val requestedWindowPreviewIds = linkedSetOf<String>()
    private var visibleWindowPreviewIds = emptySet<String>()
    private var connectJob: Job? = null
    private var recoveryJob: Job? = null
    private var recoveryStatusClearJob: Job? = null
    @Volatile private var appInForeground = false
    @Volatile private var terminalGeneration = 0
    @Volatile private var imageCheckpoint: ImageStreamCheckpoint? = null
    private var imageStateProfileId: String? = null
    private var imageStateWindowId: String? = null
    private var activeTerminalWindowId: String? = null
    private var warmTerminalCloseJob: Job? = null
    private var uploadJob: Job? = null
    private var terminalColumns = 80
    private var terminalRows = 24

    fun onAppForegrounded() {
        appInForeground = true
        ensureConnectionRestored()
    }

    fun onAppBackgrounded() {
        appInForeground = false
    }

    private fun ensureConnectionRestored() {
        val restore = restoreStore.load() ?: return
        val profile = _profiles.value.firstOrNull { it.id == restore.profileId }
        if (profile == null) {
            restoreStore.clear()
            _recentPiDirectories.value = emptyList()
            return
        }
        if (connectJob?.isActive == true || recoveryJob?.isActive == true) return

        applyTerminalTheme(profile.terminalTheme)
        if (_connection.value is ConnectionState.Disconnected || currentProfile()?.id != profile.id) {
            prepareRestoreUi(profile, restore)
        } else if (restore.terminalTarget != null && !sshManager.isTerminalConnected()) {
            _terminalConnected.value = false
            showRecoveryInProgress()
        }
        recoveryJob = viewModelScope.launch {
            val healthy = sshManager.isConnectionHealthy()
            if (healthy && _connection.value is ConnectionState.Connected) {
                try {
                    restoreHealthyConnection(profile, restore)
                    return@launch
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                }
            }
            reconnectWithRetry(profile, restore)
        }
    }

    private fun prepareRestoreUi(profile: SshProfile, restore: ConnectionRestoreState) {
        _recentPiDirectories.value = recentPiDirectoryStore.load(profile.id)
        _connection.value = ConnectionState.Connecting(profile)
        restore.terminalTarget?.let { target ->
            _selectedWindow.value = target.placeholder()
            _screen.value = AppScreen.Terminal
        } ?: run {
            _selectedWindow.value = null
            _screen.value = AppScreen.Windows(profile.id)
        }
        _terminalConnected.value = false
        _dashboardMessage.value = "正在恢复连接…"
        showRecoveryInProgress()
    }

    private suspend fun restoreHealthyConnection(
        profile: SshProfile,
        restore: ConnectionRestoreState
    ) {
        val latest = sshManager.listWindows()
        applyWindowList(latest)
        _dashboardMessage.value = null
        val target = restore.terminalTarget
        if (target == null) {
            _screen.value = AppScreen.Windows(profile.id)
            showRecoveryCompleteIfActive()
        } else {
            val window = resolveRestoredWindow(latest, target)
            if (window == null) {
                _selectedWindow.value = null
                _screen.value = AppScreen.Windows(profile.id)
                restoreStore.saveDashboard(profile.id)
                hideRecoveryStatus()
                _notices.tryEmit("之前的 tmux 窗口已不存在")
            } else {
                _selectedWindow.value = window
                _screen.value = AppScreen.Terminal
                restoreStore.saveTerminal(profile.id, window)
                if (!sshManager.isTerminalConnected()) {
                    openTerminal(window, completeRecoveryOnConnect = true)
                } else {
                    showRecoveryCompleteIfActive()
                }
            }
        }
        startAutoRefresh()
    }

    private suspend fun reconnectWithRetry(
        profile: SshProfile,
        restore: ConnectionRestoreState
    ) {
        prepareRestoreUi(profile, restore)
        terminalGeneration++
        refreshJob?.cancel()
        var lastError: Throwable = NoActiveConnectionException()
        val retryDelays = longArrayOf(1_000, 2_000, 4_000, 8_000)

        for (attempt in 0..retryDelays.size) {
            try {
                val info = sshManager.connect(profile)
                val latest = sshManager.listWindows()
                _connection.value = ConnectionState.Connected(info)
                applyWindowList(latest)
                _dashboardMessage.value = null
                startAutoRefresh()

                val target = restore.terminalTarget
                if (target == null) {
                    _screen.value = AppScreen.Windows(profile.id)
                    restoreStore.saveDashboard(profile.id)
                    showRecoveryCompleteIfActive()
                } else {
                    val window = resolveRestoredWindow(latest, target)
                    if (window == null) {
                        _selectedWindow.value = null
                        _screen.value = AppScreen.Windows(profile.id)
                        restoreStore.saveDashboard(profile.id)
                        hideRecoveryStatus()
                        _notices.tryEmit("已重连，但之前的 tmux 窗口已不存在")
                    } else {
                        _selectedWindow.value = window
                        _screen.value = AppScreen.Terminal
                        restoreStore.saveTerminal(profile.id, window)
                        openTerminal(window, completeRecoveryOnConnect = true)
                    }
                }
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                sshManager.disconnect()
                if (attempt < retryDelays.size && appInForeground) {
                    val retryMessage =
                        "正在自动重连（${attempt + 2}/${retryDelays.size + 1}）…"
                    _dashboardMessage.value = "连接中断，$retryMessage"
                    showRecoveryInProgress(retryMessage)
                    delay(retryDelays[attempt])
                } else {
                    break
                }
            }
        }

        _terminalConnected.value = false
        hideRecoveryStatus()
        _connection.value = ConnectionState.Failed(profile, friendlyError(lastError))
        _dashboardMessage.value = friendlyError(lastError)
        _screen.value = AppScreen.Windows(profile.id)
    }

    fun showAddProfile() {
        _screen.value = AppScreen.ProfileEditor()
    }

    fun showEditProfile(profileId: String) {
        _screen.value = AppScreen.ProfileEditor(profileId)
    }

    fun profile(profileId: String?): SshProfile? =
        _profiles.value.firstOrNull { it.id == profileId }

    fun saveProfile(profile: SshProfile, connectAfterSave: Boolean) {
        val updated = _profiles.value.toMutableList()
        val existingIndex = updated.indexOfFirst { it.id == profile.id }
        if (existingIndex >= 0) updated[existingIndex] = profile else updated += profile
        _profiles.value = updated
        profileStore.save(updated)
        if (connectAfterSave) connect(profile) else _screen.value = AppScreen.Hosts
    }

    fun deleteProfile(profile: SshProfile) {
        if (restoreStore.load()?.profileId == profile.id) restoreStore.clear()
        recentPiDirectoryStore.removeProfile(profile.id)
        val updated = _profiles.value.filterNot { it.id == profile.id }
        _profiles.value = updated
        profileStore.save(updated)
        _recentPiDirectories.value = emptyList()
        _screen.value = AppScreen.Hosts
    }

    fun connect(profile: SshProfile) {
        cancelWarmTerminalClose()
        hideRecoveryStatus()
        activeTerminalWindowId = null
        imageCheckpoint = null
        imageStateProfileId = null
        imageStateWindowId = null
        applyTerminalTheme(profile.terminalTheme)
        recoveryJob?.cancel()
        recoveryJob = null
        restoreStore.saveDashboard(profile.id)
        _recentPiDirectories.value = recentPiDirectoryStore.load(profile.id)
        connectJob?.cancel()
        refreshJob?.cancel()
        terminalGeneration++
        _screen.value = AppScreen.Windows(profile.id)
        _connection.value = ConnectionState.Connecting(profile)
        resetWindowPreviews(clearVisibility = true)
        applyWindowList(emptyList())
        _dashboardMessage.value = null
        _selectedWindow.value = null

        connectJob = viewModelScope.launch {
            try {
                val info = sshManager.connect(profile)
                _connection.value = ConnectionState.Connected(info)
                if (info.newlyTrustedHost) {
                    _notices.tryEmit("已首次信任主机密钥 · ${info.hostKeyFingerprint}")
                }
                refreshWindowsInternal()
                startAutoRefresh()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                sshManager.disconnect()
                _connection.value = ConnectionState.Failed(profile, friendlyError(error))
            }
        }
    }

    fun retryConnection() {
        val profile = when (val state = _connection.value) {
            is ConnectionState.Connecting -> state.profile
            is ConnectionState.Connected -> state.info.profile
            is ConnectionState.Failed -> state.profile
            ConnectionState.Disconnected -> return
        }
        if (restoreStore.load()?.profileId == profile.id) {
            recoveryJob?.cancel()
            recoveryJob = null
            ensureConnectionRestored()
        } else {
            connect(profile)
        }
    }

    fun refreshWindows() {
        if (_connection.value !is ConnectionState.Connected || _refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                refreshWindowsInternal()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun refreshWindowsInternal() {
        try {
            val latest = sshManager.listWindows()
            applyWindowList(latest)
            requestWindowPreviews(visibleWindowPreviewIds, force = true)
            _dashboardMessage.value = null
            _selectedWindow.value?.let { selected ->
                _selectedWindow.value = latest.firstOrNull { it.windowId == selected.windowId } ?: selected
            }
        } catch (error: Throwable) {
            _dashboardMessage.value = friendlyError(error)
            if (error is NoActiveConnectionException) {
                val profile = currentProfile() ?: return
                _connection.value = ConnectionState.Failed(profile, friendlyError(error))
                refreshJob?.cancel()
            }
            if (appInForeground) ensureConnectionRestored()
        }
    }

    fun updateVisibleWindowPreviews(windowIds: Set<String>) {
        val currentIds = _windows.value.asSequence().map { it.windowId }.toSet()
        visibleWindowPreviewIds = windowIds.intersect(currentIds)
        requestWindowPreviews(visibleWindowPreviewIds, force = false)
    }

    private fun applyWindowList(latest: List<TmuxWindow>) {
        _windows.value = latest
        val currentIds = latest.asSequence().map { it.windowId }.toSet()
        _windowPreviews.value = _windowPreviews.value.filterKeys(currentIds::contains)
        visibleWindowPreviewIds = visibleWindowPreviewIds.intersect(currentIds)
        requestedWindowPreviewIds.retainAll(currentIds)
    }

    private fun resetWindowPreviews(clearVisibility: Boolean) {
        windowPreviewJob?.cancel()
        windowPreviewJob = null
        requestedWindowPreviewIds.clear()
        _windowPreviews.value = emptyMap()
        if (clearVisibility) visibleWindowPreviewIds = emptySet()
    }

    private fun requestWindowPreviews(windowIds: Set<String>, force: Boolean) {
        if (_connection.value !is ConnectionState.Connected || _screen.value !is AppScreen.Windows) return
        val currentIds = _windows.value.asSequence().map { it.windowId }.toSet()
        val targets = windowIds.intersect(currentIds).filterTo(linkedSetOf()) { windowId ->
            force || _windowPreviews.value[windowId] == null
        }
        if (targets.isEmpty()) return
        requestedWindowPreviewIds += targets
        if (windowPreviewJob?.isActive == true) return

        windowPreviewJob = viewModelScope.launch {
            try {
                // Let LazyColumn settle so a quick fling is represented by one batched SSH call.
                delay(WINDOW_PREVIEW_DEBOUNCE_MILLIS)
                while (isActive && _connection.value is ConnectionState.Connected) {
                    val availableIds = _windows.value.asSequence().map { it.windowId }.toSet()
                    requestedWindowPreviewIds.retainAll(visibleWindowPreviewIds.intersect(availableIds))
                    val batch = requestedWindowPreviewIds.take(MAX_WINDOW_PREVIEW_BATCH)
                    if (batch.isEmpty()) break
                    requestedWindowPreviewIds.removeAll(batch.toSet())

                    val beforeCapture = _windowPreviews.value.toMutableMap()
                    batch.forEach { windowId ->
                        if (beforeCapture[windowId] !is WindowPreviewUiState.Ready) {
                            beforeCapture[windowId] = WindowPreviewUiState.Loading
                        }
                    }
                    _windowPreviews.value = beforeCapture

                    try {
                        val captures = sshManager.captureWindowPreviews(batch).associateBy { it.windowId }
                        val theme = _terminalTheme.value
                        val updated = _windowPreviews.value.toMutableMap()
                        batch.forEach { windowId ->
                            val capture = captures[windowId]
                            updated[windowId] = if (capture == null) {
                                WindowPreviewUiState.Unavailable
                            } else {
                                WindowPreviewUiState.Ready(
                                    buildWindowTerminalPreview(capture, theme)
                                )
                            }
                        }
                        _windowPreviews.value = updated
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        val updated = _windowPreviews.value.toMutableMap()
                        batch.forEach { windowId ->
                            if (updated[windowId] !is WindowPreviewUiState.Ready) {
                                updated[windowId] = WindowPreviewUiState.Unavailable
                            }
                        }
                        _windowPreviews.value = updated
                    }
                }
            } finally {
                windowPreviewJob = null
            }
        }
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive && _connection.value is ConnectionState.Connected) {
                // The dashboard benefits from quick discovery. Inside a live terminal, polling via
                // a second SSH channel less often reduces network contention and battery use.
                delay(if (_screen.value == AppScreen.Terminal) 15_000 else 5_000)
                if (_screen.value !is AppScreen.ProfileEditor) refreshWindowsInternal()
            }
        }
    }

    fun createSession(
        name: String,
        launchPi: Boolean = false,
        workingDirectory: String = ""
    ) {
        val profileId = currentProfile()?.id
        viewModelScope.launch {
            try {
                val safeName = if (launchPi) {
                    resolvePiSessionName(
                        requestedName = name,
                        workingDirectory = workingDirectory,
                        existingSessionNames = _windows.value.map { it.sessionName }
                    )
                } else {
                    name.trim()
                }
                sshManager.createSession(safeName, launchPi, workingDirectory)
                val latest = sshManager.listWindows()
                applyWindowList(latest)
                _dashboardMessage.value = null
                val createdWindow = latest.firstOrNull { it.sessionName == safeName }
                if (launchPi) {
                    val openedDirectory = createdWindow?.path
                        ?.takeIf { it.isNotBlank() }
                        ?: workingDirectory.trim().ifEmpty { "~" }
                    profileId?.let { id ->
                        val updated = recentPiDirectoryStore.record(id, openedDirectory)
                        if (currentProfile()?.id == id) _recentPiDirectories.value = updated
                    }
                }
                if (launchPi && createdWindow != null) {
                    _notices.tryEmit("Pi 工作区 “$safeName” 已启动")
                    openWindow(createdWindow)
                } else {
                    _notices.tryEmit("会话 “$safeName” 已创建")
                }
            } catch (error: Throwable) {
                _notices.tryEmit(friendlyError(error))
            }
        }
    }

    suspend fun listRemoteDirectories(path: String): RemoteDirectoryListing =
        sshManager.listRemoteDirectories(path)

    suspend fun downloadTerminalImage(remotePath: String): ByteArray =
        sshManager.downloadTerminalImage(remotePath)

    fun openWindow(window: TmuxWindow) {
        cancelWarmTerminalClose()
        _selectedWindow.value = window
        currentProfile()?.let { restoreStore.saveTerminal(it.id, window) }
        _screen.value = AppScreen.Terminal
        if (activeTerminalWindowId == window.windowId && sshManager.isTerminalConnected()) {
            // Leaving for the dashboard keeps the channels warm briefly. Returning to the same
            // window can therefore reuse its terminal, cursor, scrollback and decoded image state.
            _terminalConnected.value = true
        } else {
            openTerminal(window)
        }
    }

    private fun openTerminal(
        window: TmuxWindow,
        completeRecoveryOnConnect: Boolean = false
    ) {
        cancelWarmTerminalClose()
        terminalGeneration++
        val generation = terminalGeneration
        val profileId = currentProfile()?.id
        val captureImages = isPiWindow(window)
        val resumeCheckpoint = imageCheckpoint?.takeIf {
            captureImages && imageStateProfileId == profileId &&
                imageStateWindowId == window.windowId && it.windowId == window.windowId
        }

        terminal.reset()
        if (resumeCheckpoint == null) {
            imageTerminal.reset()
            imageTerminal.resize(terminalColumns, terminalRows)
        }
        if (captureImages) {
            imageStateProfileId = profileId
            imageStateWindowId = window.windowId
        } else {
            imageCheckpoint = null
            imageStateProfileId = null
            imageStateWindowId = null
        }
        activeTerminalWindowId = window.windowId
        // Replay historical graphics into the shadow terminal first. Publishing each old placement
        // immediately would flash already-deleted images while returning to a Pi session.
        if (captureImages) {
            imageTerminal.setKittyGraphicsSink(null)
        } else {
            imageTerminal.mirrorKittyGraphicsTo(terminal)
        }
        _terminalConnected.value = false
        viewModelScope.launch {
            try {
                sshManager.openTerminal(
                    sessionId = window.sessionId,
                    windowId = window.windowId,
                    columns = terminalColumns,
                    rows = terminalRows,
                    captureImages = captureImages,
                    imageCheckpoint = resumeCheckpoint,
                    onBytes = { bytes, length ->
                        if (generation == terminalGeneration) terminal.feed(bytes, length)
                    },
                    onImageBytes = { bytes, length ->
                        if (generation == terminalGeneration) imageTerminal.feed(bytes, length)
                    },
                    onImageReplayStart = { resumed ->
                        if (generation == terminalGeneration && !resumed && resumeCheckpoint != null) {
                            // The file was replaced or truncated, so its old parser/image state can
                            // no longer be paired with the new byte stream.
                            imageCheckpoint = null
                            imageTerminal.reset()
                            imageTerminal.resize(terminalColumns, terminalRows)
                            imageTerminal.setKittyGraphicsSink(null)
                        }
                    },
                    onImageCheckpoint = { checkpoint ->
                        if (generation == terminalGeneration) imageCheckpoint = checkpoint
                    },
                    onImageReplayComplete = {
                        if (generation == terminalGeneration) {
                            terminal.replaceKittyGraphicsStateFrom(imageTerminal)
                            imageTerminal.mirrorKittyGraphicsTo(terminal)
                        }
                    },
                    onClosed = { exitCode ->
                        viewModelScope.launch {
                            if (generation == terminalGeneration) {
                                activeTerminalWindowId = null
                                _terminalConnected.value = false
                                if (_screen.value == AppScreen.Terminal) {
                                    // An unexpected channel close is immediately represented by the
                                    // centered recovery state. Avoid stacking a second transient
                                    // “连接已关闭” notice, especially if it happened in background.
                                    if (exitCode == 0) _notices.tryEmit("终端会话已结束")
                                    if (appInForeground) ensureConnectionRestored()
                                }
                            }
                        }
                    }
                )
                if (generation == terminalGeneration) {
                    _terminalConnected.value = true
                    if (completeRecoveryOnConnect) showRecoveryCompleteIfActive()
                }
            } catch (error: Throwable) {
                if (generation == terminalGeneration) {
                    activeTerminalWindowId = null
                    _terminalConnected.value = false
                    if (completeRecoveryOnConnect) hideRecoveryStatus()
                    _notices.tryEmit(friendlyError(error))
                }
            }
        }
    }

    fun switchWindow(window: TmuxWindow) {
        val current = _selectedWindow.value
        if (current?.windowId == window.windowId) return
        _selectedWindow.value = window
        currentProfile()?.let { restoreStore.saveTerminal(it.id, window) }
        if (current?.sessionId != window.sessionId || isPiWindow(current) || isPiWindow(window)) {
            // A Pi pane has a window-specific raw graphics stream, so replace both channels even
            // when switching inside the same tmux session.
            viewModelScope.launch {
                terminalGeneration++
                activeTerminalWindowId = null
                sshManager.closeTerminal()
                openTerminal(window)
            }
        } else {
            viewModelScope.launch {
                try {
                    sshManager.selectWindow(window.windowId)
                    activeTerminalWindowId = window.windowId
                    _windows.value = _windows.value.map {
                        if (it.sessionId == window.sessionId) it.copy(active = it.windowId == window.windowId) else it
                    }
                } catch (error: Throwable) {
                    _notices.tryEmit(friendlyError(error))
                }
            }
        }
    }

    fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty() || uploadJob?.isActive == true) return
        val files = uris.take(MAX_UPLOAD_FILES)
        if (uris.size > MAX_UPLOAD_FILES) {
            _notices.tryEmit("单次最多上传 $MAX_UPLOAD_FILES 个文件")
        }
        uploadJob = viewModelScope.launch {
            val uploadedPaths = mutableListOf<String>()
            try {
                // Some vendor ROMs reclaim the app while Android's document picker is open. The
                // activity-result URI survives, so wait briefly for the persisted SSH target to
                // reconnect instead of discarding the selected file.
                val firstName = resolveUploadName(files.first())
                _uploadProgress.value = FileUploadProgress(firstName, 1, files.size, 0, -1)
                var reconnectChecks = 0
                while (_connection.value !is ConnectionState.Connected && reconnectChecks < 60) {
                    delay(500)
                    reconnectChecks++
                }
                if (_connection.value !is ConnectionState.Connected) {
                    throw NoActiveConnectionException()
                }
                val selected = _selectedWindow.value
                    ?: throw IllegalStateException("之前的终端窗口已不存在")
                files.forEachIndexed { index, uri ->
                    val name = resolveUploadName(uri)
                    _uploadProgress.value = FileUploadProgress(name, index + 1, files.size, 0, -1)
                    var lastProgressAt = 0L
                    val uploaded = sshManager.uploadFile(uri, name) { sent, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 100 || sent == 0L || total > 0 && sent >= total) {
                            lastProgressAt = now
                            _uploadProgress.value = FileUploadProgress(
                                name, index + 1, files.size, sent, total
                            )
                        }
                    }
                    uploadedPaths += uploaded.remotePath
                }
                _ctrlActive.value = false
                val insertion = if (isPiWindow(selected)) {
                    buildPiUploadInsertion(uploadedPaths)
                } else {
                    uploadedPaths.joinToString(separator = " ", postfix = " ") { shellQuoteForTerminal(it) }
                }
                sshManager.writeTerminal(insertion.toByteArray(Charsets.UTF_8))
                _notices.tryEmit(
                    if (uploadedPaths.size == 1) "文件已上传，远程路径已填入"
                    else "${uploadedPaths.size} 个文件已上传，远程路径已填入"
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _notices.tryEmit(friendlyError(error))
            } finally {
                _uploadProgress.value = null
            }
        }
    }

    fun showNotice(message: String) {
        if (message.isNotBlank()) _notices.tryEmit(message)
    }

    fun sendTerminalInput(text: String) {
        if (text.isEmpty()) return
        val bytes = if (_ctrlActive.value) {
            _ctrlActive.value = false
            val first = text.first()
            val control = when (first) {
                '?' -> 0x7F.toChar()
                ' ' -> 0.toChar()
                else -> (first.uppercaseChar().code and 0x1F).toChar()
            }
            (control + text.drop(1)).toByteArray(Charsets.UTF_8)
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
        sshManager.writeTerminal(bytes)
    }

    fun sendSpecialKey(sequence: String) {
        _ctrlActive.value = false
        sshManager.writeTerminal(sequence.toByteArray(Charsets.UTF_8))
    }

    fun toggleControl() {
        _ctrlActive.value = !_ctrlActive.value
    }

    fun toggleTerminalTheme() {
        val next = if (_terminalTheme.value == TerminalTheme.DARK) {
            TerminalTheme.LIGHT
        } else {
            TerminalTheme.DARK
        }
        applyTerminalTheme(next)

        val profileId = currentProfile()?.id ?: return
        var updatedProfile: SshProfile? = null
        val updated = _profiles.value.map { profile ->
            if (profile.id == profileId) profile.copy(terminalTheme = next).also {
                updatedProfile = it
            } else profile
        }
        _profiles.value = updated
        profileStore.save(updated)
        updatedProfile?.let { profile ->
            _connection.value = when (val state = _connection.value) {
                is ConnectionState.Connecting -> state.copy(profile = profile)
                is ConnectionState.Connected -> state.copy(info = state.info.copy(profile = profile))
                is ConnectionState.Failed -> state.copy(profile = profile)
                ConnectionState.Disconnected -> state
            }
        }
        _notices.tryEmit(if (next == TerminalTheme.LIGHT) "已切换为浅色终端" else "已切换为深色终端")
    }

    private fun applyTerminalTheme(theme: TerminalTheme) {
        _terminalTheme.value = theme
        terminal.setTheme(theme)
        resetWindowPreviews(clearVisibility = false)
        requestWindowPreviews(visibleWindowPreviewIds, force = true)
    }

    fun resizeTerminal(columns: Int, rows: Int) {
        if (columns == terminalColumns && rows == terminalRows) return
        terminalColumns = columns
        terminalRows = rows
        imageTerminal.resize(columns, rows)
        sshManager.resizeTerminal(columns, rows)
    }

    fun leaveTerminal() {
        uploadJob?.cancel()
        uploadJob = null
        _uploadProgress.value = null
        _ctrlActive.value = false
        _selectedWindow.value?.let {
            val profileId = currentProfile()?.id ?: return@let
            restoreStore.saveDashboard(profileId)
            _screen.value = AppScreen.Windows(profileId)
            scheduleWarmTerminalClose()
        } ?: disconnectAndShowHosts()
    }

    private fun scheduleWarmTerminalClose() {
        cancelWarmTerminalClose()
        val generation = terminalGeneration
        val windowId = activeTerminalWindowId ?: return
        warmTerminalCloseJob = viewModelScope.launch {
            delay(WARM_TERMINAL_REUSE_MILLIS)
            if (_screen.value != AppScreen.Terminal &&
                generation == terminalGeneration && activeTerminalWindowId == windowId
            ) {
                terminalGeneration++
                activeTerminalWindowId = null
                _terminalConnected.value = false
                sshManager.closeTerminal()
            }
            warmTerminalCloseJob = null
        }
    }

    private fun cancelWarmTerminalClose() {
        warmTerminalCloseJob?.cancel()
        warmTerminalCloseJob = null
    }

    fun terminateTmuxSession() {
        cancelWarmTerminalClose()
        if (uploadJob?.isActive == true) {
            _notices.tryEmit("请等待文件上传完成")
            return
        }
        val window = _selectedWindow.value ?: return
        val profileId = currentProfile()?.id ?: return
        terminalGeneration++
        activeTerminalWindowId = null
        if (imageStateWindowId == window.windowId) {
            imageCheckpoint = null
            imageStateProfileId = null
            imageStateWindowId = null
        }
        _terminalConnected.value = false
        _ctrlActive.value = false
        restoreStore.saveDashboard(profileId)
        _selectedWindow.value = null
        _screen.value = AppScreen.Windows(profileId)
        viewModelScope.launch {
            try {
                sshManager.closeTerminal()
                sshManager.terminateSession(window.sessionId)
                applyWindowList(sshManager.listWindows())
                requestWindowPreviews(visibleWindowPreviewIds, force = true)
                _dashboardMessage.value = null
                _notices.tryEmit("tmux 会话 “${window.sessionName}” 已退出")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _notices.tryEmit(friendlyError(error))
                refreshWindowsInternal()
            }
        }
    }

    fun disconnectAndShowHosts() {
        cancelWarmTerminalClose()
        uploadJob?.cancel()
        uploadJob = null
        _uploadProgress.value = null
        restoreStore.clear()
        recoveryJob?.cancel()
        recoveryJob = null
        hideRecoveryStatus()
        connectJob?.cancel()
        refreshJob?.cancel()
        terminalGeneration++
        activeTerminalWindowId = null
        imageCheckpoint = null
        imageStateProfileId = null
        imageStateWindowId = null
        _connection.value = ConnectionState.Disconnected
        resetWindowPreviews(clearVisibility = true)
        applyWindowList(emptyList())
        _recentPiDirectories.value = emptyList()
        _selectedWindow.value = null
        _dashboardMessage.value = null
        _terminalConnected.value = false
        _screen.value = AppScreen.Hosts
        viewModelScope.launch { sshManager.disconnect() }
    }

    fun navigateBack() {
        when (_screen.value) {
            AppScreen.Hosts -> Unit
            is AppScreen.ProfileEditor -> _screen.value = AppScreen.Hosts
            is AppScreen.Windows -> disconnectAndShowHosts()
            AppScreen.Terminal -> leaveTerminal()
        }
    }

    private fun showRecoveryInProgress(message: String = "正在恢复连接…") {
        recoveryStatusClearJob?.cancel()
        recoveryStatusClearJob = null
        _connectionRecoveryStatus.value = ConnectionRecoveryStatus.Restoring(message)
    }

    private fun showRecoveryCompleteIfActive() {
        if (_connectionRecoveryStatus.value !is ConnectionRecoveryStatus.Restoring) return
        recoveryStatusClearJob?.cancel()
        _connectionRecoveryStatus.value = ConnectionRecoveryStatus.Restored
        recoveryStatusClearJob = viewModelScope.launch {
            delay(RECOVERY_COMPLETE_VISIBLE_MILLIS)
            if (_connectionRecoveryStatus.value is ConnectionRecoveryStatus.Restored) {
                _connectionRecoveryStatus.value = ConnectionRecoveryStatus.Idle
            }
            recoveryStatusClearJob = null
        }
    }

    private fun hideRecoveryStatus() {
        recoveryStatusClearJob?.cancel()
        recoveryStatusClearJob = null
        _connectionRecoveryStatus.value = ConnectionRecoveryStatus.Idle
    }

    private fun isPiWindow(window: TmuxWindow?): Boolean =
        window?.let { it.command == "pi" || it.name.equals("pi", ignoreCase = true) } == true

    private fun resolveUploadName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "upload.bin"
    }

    private fun shellQuoteForTerminal(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun currentProfile(): SshProfile? = when (val state = _connection.value) {
        is ConnectionState.Connecting -> state.profile
        is ConnectionState.Connected -> state.info.profile
        is ConnectionState.Failed -> state.profile
        ConnectionState.Disconnected -> null
    }

    private fun friendlyError(error: Throwable): String {
        if (error is TmuxNotInstalledException || error is PiNotInstalledException) {
            return error.message.orEmpty()
        }
        if (error is NoActiveConnectionException) return "SSH 连接已断开，请重新连接"
        if (error is UnknownHostException) return "找不到这台主机，请检查地址"
        if (error is SocketTimeoutException) return "连接超时，请检查网络和端口"
        if (error is ConnectException) return "无法连接到主机，请检查 SSH 服务"
        if (error is IllegalArgumentException) return error.message ?: "配置有误"
        if (error is SecurityException) return "无法读取所选文件，请重新选择"
        if (error is SftpException) {
            return error.message?.takeIf { it.isNotBlank() }?.let { "上传失败：$it" } ?: "上传文件失败"
        }
        if (error is JSchException) {
            val message = error.message.orEmpty()
            return when {
                message.contains("Auth fail", ignoreCase = true) ||
                    message.contains("authentication failures", ignoreCase = true) ||
                    message.contains("Permission denied", ignoreCase = true) ->
                    "认证失败，请检查用户名和密码"
                message.contains("HostKey has been changed", ignoreCase = true) ||
                    message.contains("reject HostKey", ignoreCase = true) -> "主机密钥与首次连接不一致，已阻止连接"
                message.contains("timeout", ignoreCase = true) -> "SSH 连接超时"
                message.contains("invalid privatekey", ignoreCase = true) -> "私钥格式不受支持或口令错误"
                message.contains("UnknownHost", ignoreCase = true) -> "找不到这台主机，请检查地址"
                else -> message.takeIf { it.isNotBlank() } ?: "SSH 连接失败"
            }
        }
        return error.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
    }

    override fun onCleared() {
        cancelWarmTerminalClose()
        recoveryStatusClearJob?.cancel()
        windowPreviewJob?.cancel()
        uploadJob?.cancel()
        sshManager.dispose()
        super.onCleared()
    }

    companion object {
        private const val MAX_UPLOAD_FILES = 20
        private const val MAX_WINDOW_PREVIEW_BATCH = 12
        private const val WINDOW_PREVIEW_DEBOUNCE_MILLIS = 120L
        private const val WARM_TERMINAL_REUSE_MILLIS = 30_000L
        private const val RECOVERY_COMPLETE_VISIBLE_MILLIS = 1_600L
    }
}
