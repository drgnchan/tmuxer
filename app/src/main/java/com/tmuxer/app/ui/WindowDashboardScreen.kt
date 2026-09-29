package com.tmuxer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmuxer.app.data.ConnectionState
import com.tmuxer.app.data.QuickLaunchPreset
import com.tmuxer.app.data.SshProfile
import com.tmuxer.app.data.TmuxWindow
import com.tmuxer.app.ssh.RemoteDirectoryListing
import com.tmuxer.app.ssh.RemotePiModel
import com.tmuxer.app.ui.theme.Amber
import com.tmuxer.app.ui.theme.DeepSurface
import com.tmuxer.app.ui.theme.Ink
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.RaisedSurface
import com.tmuxer.app.ui.theme.TerminalBlue
import com.tmuxer.app.ui.theme.TextPrimary
import com.tmuxer.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WindowDashboardScreen(
    profile: SshProfile?,
    connection: ConnectionState,
    recovering: Boolean,
    windows: List<TmuxWindow>,
    recentPiDirectories: List<String>,
    defaultPiDirectory: String,
    quickLaunchPresets: List<QuickLaunchPreset>,
    refreshing: Boolean,
    dashboardMessage: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onWindow: (TmuxWindow) -> Unit,
    onCreateSession: (String, Boolean, String, Boolean, String) -> Unit,
    onRemoveRecentPiDirectory: (String) -> Unit,
    onSetDefaultPiDirectory: (String) -> Unit,
    onSaveQuickLaunchPreset: (QuickLaunchPreset) -> Unit,
    onRemoveQuickLaunchPreset: (String) -> Unit,
    onLaunchQuickTask: (QuickLaunchPreset, String) -> Unit,
    onListRemoteDirectories: suspend (String) -> RemoteDirectoryListing,
    onListPiModels: suspend (String, Boolean) -> List<RemotePiModel>
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingQuickPreset by remember { mutableStateOf<QuickLaunchPreset?>(null) }
    var showNewQuickPreset by remember { mutableStateOf(false) }
    var launchingQuickPreset by remember { mutableStateOf<QuickLaunchPreset?>(null) }
    val groups = remember(windows) { windows.groupBy { it.sessionId } }
    val existingSessionNames = remember(windows) { windows.map { it.sessionName }.toSet() }
    val connected = connection is ConnectionState.Connected

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = topBarColors(),
                navigationIcon = {
                    HighContrastBackButton(onClick = onBack, description = "断开并返回")
                },
                title = {
                    Column {
                        Text(profile?.name ?: "SSH 主机", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionDot(connected)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (connection) {
                                    is ConnectionState.Connecting -> "正在连接 ${profile?.endpoint.orEmpty()}"
                                    is ConnectionState.Connected -> profile?.endpoint.orEmpty()
                                    is ConnectionState.Failed -> "连接失败"
                                    ConnectionState.Disconnected -> "已断开"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = connected && !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Mint)
                        } else {
                            Icon(Icons.Rounded.Refresh, "刷新窗口")
                        }
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.PowerSettingsNew, "断开连接", tint = Amber)
                    }
                }
            )
        },
        floatingActionButton = {
            if (connected && windows.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("新建会话") },
                    containerColor = Mint,
                    contentColor = Ink
                )
            }
        }
    ) { padding ->
        when (connection) {
            is ConnectionState.Connecting -> ConnectingDashboard(
                recovering = recovering,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            is ConnectionState.Failed -> ConnectionError(
                message = connection.message,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            ConnectionState.Disconnected -> ConnectionError(
                message = "SSH 连接已断开",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            is ConnectionState.Connected -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 108.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (dashboardMessage != null) {
                        item { InlineMessage(dashboardMessage) }
                    }
                    item {
                        QuickLaunchSection(
                            presets = quickLaunchPresets,
                            enabled = connected,
                            onAdd = { showNewQuickPreset = true },
                            onLaunch = { launchingQuickPreset = it },
                            onEdit = { editingQuickPreset = it },
                            onDelete = onRemoveQuickLaunchPreset
                        )
                    }
                    if (windows.isEmpty() && dashboardMessage == null) {
                        item { EmptyWindows { showCreateDialog = true } }
                    } else {
                        groups.forEach { (_, sessionWindows) ->
                            items(sessionWindows, key = { it.windowId }) { window ->
                                WindowCard(window = window, onClick = { onWindow(window) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateSessionDialog(
            recentPiDirectories = recentPiDirectories,
            defaultPiDirectory = defaultPiDirectory,
            existingSessionNames = existingSessionNames,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, launchPi, workingDirectory, launchWithoutSession, initialPrompt ->
                showCreateDialog = false
                onCreateSession(name, launchPi, workingDirectory, launchWithoutSession, initialPrompt)
            },
            onRemoveRecentPiDirectory = onRemoveRecentPiDirectory,
            onSetDefaultPiDirectory = onSetDefaultPiDirectory,
            onListRemoteDirectories = onListRemoteDirectories
        )
    }

    if (showNewQuickPreset || editingQuickPreset != null) {
        QuickLaunchPresetEditor(
            preset = editingQuickPreset,
            onDismiss = {
                showNewQuickPreset = false
                editingQuickPreset = null
            },
            onSave = {
                onSaveQuickLaunchPreset(it)
                showNewQuickPreset = false
                editingQuickPreset = null
            },
            onListRemoteDirectories = onListRemoteDirectories,
            onListPiModels = onListPiModels
        )
    }

    launchingQuickPreset?.let { preset ->
        QuickLaunchDialog(
            preset = preset,
            onDismiss = { launchingQuickPreset = null },
            onLaunch = { input ->
                launchingQuickPreset = null
                onLaunchQuickTask(preset, input)
            }
        )
    }
}

/**
 * Dashboard cards lead with the tmux session name, which is what Pi and the user set to describe a
 * task. Window names are tmux defaults such as `pi` or `bash`, so they only appear as a trailing cue
 * when they add information the title and the foreground command do not already carry.
 */
internal fun windowCardTitle(window: TmuxWindow): String {
    val sessionName = window.sessionName.trim()
    val windowName = window.name.trim()
    if (sessionName.isNotEmpty()) return sessionName
    return windowName.ifEmpty { "未命名会话" }
}

internal fun windowCardWindowLabel(window: TmuxWindow): String? {
    val windowName = window.name.trim()
    if (windowName.isEmpty()) return null
    if (windowName.equals(windowCardTitle(window), ignoreCase = true)) return null
    if (windowName.equals(window.command.trim(), ignoreCase = true)) return null
    return windowName
}

@Composable
private fun WindowCard(window: TmuxWindow, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (window.active) Mint.copy(alpha = 0.075f) else DeepSurface
        ),
        border = BorderStroke(1.dp, if (window.active) Mint.copy(alpha = 0.7f) else Outline.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(11.dp),
                color = if (window.active) Mint else RaisedSurface,
                contentColor = if (window.active) Ink else TextPrimary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        window.index.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        windowCardTitle(window),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    windowCardWindowLabel(window)?.let { label ->
                        Spacer(Modifier.width(7.dp))
                        Text(
                            label,
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (window.activity) {
                        Box(Modifier.size(6.dp).background(Amber, CircleShape))
                        Spacer(Modifier.width(7.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        window.command.ifBlank { "shell" },
                        color = TerminalBlue,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("  ·  ", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        window.path,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun ConnectingDashboard(
    recovering: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = RoundedCornerShape(26.dp), color = RaisedSurface, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Mint, strokeWidth = 3.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (recovering) "正在恢复连接" else "正在建立安全连接",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (recovering) "正在重连 SSH 并恢复 tmux 窗口…" else "连接 SSH 并读取 tmux 窗口…",
            color = TextSecondary
        )
    }
}

@Composable
private fun ConnectionError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("连接未完成", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = TextSecondary)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("重试")
        }
    }
}

@Composable
private fun EmptyWindows(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.Terminal, null, tint = TextSecondary, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(15.dp))
        Text("暂无 tmux 会话", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("在这台主机上创建一个新会话即可开始", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onCreate,
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(7.dp))
            Text("新建会话")
        }
    }
}

@Composable
private fun InlineMessage(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp)
        )
    }
}
