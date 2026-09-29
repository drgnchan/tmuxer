package com.tmuxer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmuxer.app.data.AppScreen
import com.tmuxer.app.data.ConnectionState
import com.tmuxer.app.ui.theme.Amber
import com.tmuxer.app.ui.theme.Ink
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.RaisedSurface
import com.tmuxer.app.ui.theme.TextPrimary
import com.tmuxer.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxerApp(viewModel: TmuxerViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val windows by viewModel.windows.collectAsStateWithLifecycle()
    val recentPiDirectories by viewModel.recentPiDirectories.collectAsStateWithLifecycle()
    val defaultPiDirectory by viewModel.defaultPiDirectory.collectAsStateWithLifecycle()
    val quickLaunchPresets by viewModel.quickLaunchPresets.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val dashboardMessage by viewModel.dashboardMessage.collectAsStateWithLifecycle()
    val selectedWindow by viewModel.selectedWindow.collectAsStateWithLifecycle()
    val terminalConnected by viewModel.terminalConnected.collectAsStateWithLifecycle()
    // Modifier toggles are read only by SpecialKeyBar, so toggling one recomposes just the key bar.
    val ctrlActive = viewModel.ctrlActive.collectAsStateWithLifecycle()
    val shiftActive = viewModel.shiftActive.collectAsStateWithLifecycle()
    val altActive = viewModel.altActive.collectAsStateWithLifecycle()
    val terminalTheme by viewModel.terminalTheme.collectAsStateWithLifecycle()
    val connectionRecoveryStatus by viewModel.connectionRecoveryStatus.collectAsStateWithLifecycle()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.notices.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    BackHandler(enabled = screen != AppScreen.Hosts) { viewModel.navigateBack() }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                fadeIn(tween(durationMillis = 140)) togetherWith
                    fadeOut(tween(durationMillis = 90))
            },
            label = "screen"
        ) { destination ->
            when (destination) {
                AppScreen.Hosts -> HostListScreen(
                    profiles = profiles,
                    onAdd = viewModel::showAddProfile,
                    onConnect = viewModel::connect,
                    onEdit = viewModel::showEditProfile
                )
                is AppScreen.ProfileEditor -> ProfileEditorScreen(
                    profile = profiles.firstOrNull { it.id == destination.profileId },
                    onBack = viewModel::navigateBack,
                    onSave = viewModel::saveProfile,
                    onDelete = viewModel::deleteProfile
                )
                is AppScreen.Windows -> WindowDashboardScreen(
                    profile = profiles.firstOrNull { it.id == destination.profileId },
                    connection = connection,
                    recovering = connectionRecoveryStatus is ConnectionRecoveryStatus.Restoring,
                    windows = windows,
                    recentPiDirectories = recentPiDirectories,
                    defaultPiDirectory = defaultPiDirectory,
                    quickLaunchPresets = quickLaunchPresets,
                    refreshing = refreshing,
                    dashboardMessage = dashboardMessage,
                    onBack = viewModel::disconnectAndShowHosts,
                    onRefresh = viewModel::refreshWindows,
                    onRetry = viewModel::retryConnection,
                    onWindow = viewModel::openWindow,
                    onCreateSession = { name, launchPi, directory, temporary, prompt ->
                        viewModel.createSession(name, launchPi, directory, temporary, prompt)
                    },
                    onRemoveRecentPiDirectory = viewModel::removeRecentPiDirectory,
                    onSetDefaultPiDirectory = viewModel::setDefaultPiDirectory,
                    onSaveQuickLaunchPreset = viewModel::saveQuickLaunchPreset,
                    onRemoveQuickLaunchPreset = viewModel::removeQuickLaunchPreset,
                    onLaunchQuickTask = viewModel::launchQuickTask,
                    onListRemoteDirectories = viewModel::listRemoteDirectories,
                    onListPiModels = viewModel::listPiModels
                )
                AppScreen.Terminal -> TerminalScreen(
                    selected = selectedWindow,
                    windows = windows,
                    hostLabel = when (val state = connection) {
                        is ConnectionState.Connecting -> state.profile
                        is ConnectionState.Connected -> state.info.profile
                        is ConnectionState.Failed -> state.profile
                        ConnectionState.Disconnected -> null
                    }?.let { profile -> profile.name.ifBlank { profile.host } }.orEmpty(),
                    connected = terminalConnected,
                    recovering = connectionRecoveryStatus is ConnectionRecoveryStatus.Restoring,
                    ctrlActive = ctrlActive,
                    shiftActive = shiftActive,
                    altActive = altActive,
                    terminalTheme = terminalTheme,
                    terminalViewModel = viewModel,
                    onBack = viewModel::leaveTerminal,
                    onExitSession = viewModel::terminateTmuxSession,
                    onSwitchWindow = viewModel::switchWindow,
                    onControl = viewModel::toggleControl,
                    onShift = viewModel::toggleShift,
                    onAlt = viewModel::toggleAlt,
                    onToggleTheme = viewModel::toggleTerminalTheme,
                    onSpecialKey = viewModel::sendSpecialKey
                )
            }
        }
        val visibleRecoveryStatus = when {
            connectionRecoveryStatus is ConnectionRecoveryStatus.Restored -> connectionRecoveryStatus
            screen == AppScreen.Terminal &&
                connectionRecoveryStatus is ConnectionRecoveryStatus.Restoring -> connectionRecoveryStatus
            else -> null
        }
        AnimatedContent(
            targetState = visibleRecoveryStatus,
            transitionSpec = {
                fadeIn(tween(durationMillis = 140)) togetherWith
                    fadeOut(tween(durationMillis = 120))
            },
            modifier = Modifier.align(Alignment.Center),
            label = "connection recovery status"
        ) { status ->
            if (status != null) ConnectionRecoveryStatusPopup(status)
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            snackbar = { data -> CenteredNoticePopup(data.visuals.message) }
        )
    }

    hostKeyPrompt?.let { prompt ->
        HostKeyConfirmationDialog(
            prompt = prompt,
            onTrust = viewModel::trustHostKeyAndConnect,
            onDismiss = viewModel::dismissHostKeyPrompt
        )
    }
}

@Composable
private fun HostKeyConfirmationDialog(
    prompt: HostKeyPrompt,
    onTrust: () -> Unit,
    onDismiss: () -> Unit
) {
    val hostKey = prompt.hostKey
    val hostLabel = if (prompt.profile.port == 22) prompt.profile.host
    else "${prompt.profile.host}:${prompt.profile.port}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hostKey.changed) "主机密钥已变更" else "确认主机密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (hostKey.changed) {
                        "$hostLabel 提供的密钥与之前保存的不一致。可能是服务器重装或更换了密钥，" +
                            "也可能有人正在拦截连接。只有确认服务器确实更换了密钥时才继续。"
                    } else {
                        "首次连接 $hostLabel。请核对服务器指纹，确认后才会发送密码或私钥。"
                    }
                )
                hostKey.previousFingerprint?.let { previous ->
                    FingerprintLine("之前", previous)
                }
                FingerprintLine(if (hostKey.changed) "现在 · ${hostKey.keyType}" else hostKey.keyType, hostKey.fingerprint)
                Text(
                    "在服务器上核对：ssh-keygen -lf /etc/ssh/${hostKeyFileName(hostKey.keyType)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(
                    if (hostKey.changed) "信任新密钥" else "信任并连接",
                    color = if (hostKey.changed) Amber else Mint,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun hostKeyFileName(keyType: String): String = when {
    keyType == "ssh-ed25519" -> "ssh_host_ed25519_key.pub"
    keyType.startsWith("ecdsa-") -> "ssh_host_ecdsa_key.pub"
    keyType == "ssh-rsa" -> "ssh_host_rsa_key.pub"
    else -> "ssh_host_*_key.pub"
}

@Composable
private fun FingerprintLine(label: String, fingerprint: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            fingerprint,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary
        )
    }
}

@Composable
private fun ConnectionRecoveryStatusPopup(status: ConnectionRecoveryStatus) {
    val restored = status is ConnectionRecoveryStatus.Restored
    val message = when (status) {
        ConnectionRecoveryStatus.Idle -> return
        is ConnectionRecoveryStatus.Restoring -> status.message
        ConnectionRecoveryStatus.Restored -> "连接已自动恢复"
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = RaisedSurface.copy(alpha = 0.97f),
        contentColor = TextPrimary,
        border = BorderStroke(1.dp, if (restored) Mint.copy(alpha = 0.65f) else Outline),
        shadowElevation = 8.dp,
        modifier = Modifier.semantics { contentDescription = message }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (restored) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Mint,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Mint,
                    strokeWidth = 2.5.dp
                )
            }
            Spacer(Modifier.width(11.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun CenteredNoticePopup(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 320.dp)
            .semantics { contentDescription = message },
        shape = RoundedCornerShape(18.dp),
        color = RaisedSurface.copy(alpha = 0.97f),
        contentColor = TextPrimary,
        border = BorderStroke(1.dp, Outline),
        shadowElevation = 8.dp
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
