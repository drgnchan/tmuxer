package com.tmuxer.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmuxer.app.data.AppScreen
import com.tmuxer.app.data.AuthType
import com.tmuxer.app.data.ConnectionState
import com.tmuxer.app.data.SshProfile
import com.tmuxer.app.data.TmuxWindow
import com.tmuxer.app.ssh.RemoteDirectoryListing
import com.tmuxer.app.terminal.TerminalImageOpenRequest
import com.tmuxer.app.terminal.TerminalImagePreview
import com.tmuxer.app.terminal.TerminalTheme
import com.tmuxer.app.terminal.TerminalView
import com.tmuxer.app.terminal.decodeTerminalImagePreview
import com.tmuxer.app.ui.theme.Amber
import com.tmuxer.app.ui.theme.DeepSurface
import com.tmuxer.app.ui.theme.Ink
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.RaisedSurface
import com.tmuxer.app.ui.theme.TerminalBlue
import com.tmuxer.app.ui.theme.TextPrimary
import com.tmuxer.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxerApp(viewModel: TmuxerViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val windows by viewModel.windows.collectAsStateWithLifecycle()
    val recentPiDirectories by viewModel.recentPiDirectories.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val dashboardMessage by viewModel.dashboardMessage.collectAsStateWithLifecycle()
    val selectedWindow by viewModel.selectedWindow.collectAsStateWithLifecycle()
    val terminalConnected by viewModel.terminalConnected.collectAsStateWithLifecycle()
    val ctrlActive by viewModel.ctrlActive.collectAsStateWithLifecycle()
    val terminalTheme by viewModel.terminalTheme.collectAsStateWithLifecycle()
    val connectionRecoveryStatus by viewModel.connectionRecoveryStatus.collectAsStateWithLifecycle()
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
                    refreshing = refreshing,
                    dashboardMessage = dashboardMessage,
                    onBack = viewModel::disconnectAndShowHosts,
                    onRefresh = viewModel::refreshWindows,
                    onRetry = viewModel::retryConnection,
                    onWindow = viewModel::openWindow,
                    onCreateSession = viewModel::createSession,
                    onListRemoteDirectories = viewModel::listRemoteDirectories
                )
                AppScreen.Terminal -> TerminalScreen(
                    selected = selectedWindow,
                    windows = windows,
                    connected = terminalConnected,
                    recovering = connectionRecoveryStatus is ConnectionRecoveryStatus.Restoring,
                    ctrlActive = ctrlActive,
                    terminalTheme = terminalTheme,
                    terminalViewModel = viewModel,
                    onBack = viewModel::leaveTerminal,
                    onExitSession = viewModel::terminateTmuxSession,
                    onSwitchWindow = viewModel::switchWindow,
                    onControl = viewModel::toggleControl,
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
private fun CenteredNoticePopup(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostListScreen(
    profiles: List<SshProfile>,
    onAdd: () -> Unit,
    onConnect: (SshProfile) -> Unit,
    onEdit: (String) -> Unit
) {
    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = topBarColors(),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LogoMark()
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("tmuxer", fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                            Text(
                                "远程终端工作台",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (profiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("添加主机") },
                    containerColor = Mint,
                    contentColor = Ink,
                    elevation = FloatingActionButtonDefaults.elevation(3.dp)
                )
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            EmptyHosts(
                onAdd = onAdd,
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "远程工作区",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "连接主机，继续你的 tmux 会话",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("主机", "${profiles.size}")
                }
                items(profiles, key = { it.id }) { profile ->
                    HostCard(profile, { onConnect(profile) }, { onEdit(profile.id) })
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    SecurityNote()
                }
            }
        }
    }
}

@Composable
private fun EmptyHosts(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = RoundedCornerShape(24.dp),
            color = RaisedSurface,
            border = BorderStroke(1.dp, Outline)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    ">_",
                    color = Mint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "添加远程主机",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "通过 SSH 自动发现和管理 tmux 会话", 
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 13.dp)
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加 SSH 主机", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(20.dp))
        SecurityNote()
    }
}

@Composable
private fun HostCard(profile: SshProfile, onClick: () -> Unit, onEdit: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = DeepSurface),
        border = BorderStroke(1.dp, Outline.copy(alpha = 0.8f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = Mint.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Computer, null, tint = Mint, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    profile.endpoint,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                color = RaisedSurface,
                shape = CircleShape
            ) {
                Icon(
                    if (profile.authType == AuthType.PASSWORD) Icons.Rounded.Password else Icons.Rounded.Key,
                    if (profile.authType == AuthType.PASSWORD) "密码认证" else "私钥认证",
                    tint = TextSecondary,
                    modifier = Modifier.padding(8.dp).size(15.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Edit, "编辑 ${profile.name}", tint = TextSecondary, modifier = Modifier.size(19.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Mint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SecurityNote() {
    Surface(color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "凭据由 Android Keystore 加密保存在此设备",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorScreen(
    profile: SshProfile?,
    onBack: () -> Unit,
    onSave: (SshProfile, Boolean) -> Unit,
    onDelete: (SshProfile) -> Unit
) {
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var host by rememberSaveable(profile?.id) { mutableStateOf(profile?.host.orEmpty()) }
    var port by rememberSaveable(profile?.id) { mutableStateOf((profile?.port ?: 22).toString()) }
    var username by rememberSaveable(profile?.id) { mutableStateOf(profile?.username.orEmpty()) }
    var authType by rememberSaveable(profile?.id) { mutableStateOf(profile?.authType ?: AuthType.PASSWORD) }
    var password by rememberSaveable(profile?.id) { mutableStateOf(profile?.password.orEmpty()) }
    var privateKey by rememberSaveable(profile?.id) { mutableStateOf(profile?.privateKey.orEmpty()) }
    var passphrase by rememberSaveable(profile?.id) { mutableStateOf(profile?.passphrase.orEmpty()) }
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteConfirmation by remember { mutableStateOf(false) }

    fun buildProfile(): SshProfile? {
        val parsedPort = port.toIntOrNull()
        error = when {
            host.trim().isEmpty() -> "请输入主机地址"
            username.trim().isEmpty() -> "请输入 SSH 用户名"
            parsedPort == null || parsedPort !in 1..65535 -> "端口应在 1–65535 之间"
            authType == AuthType.PASSWORD && password.isEmpty() -> "请输入 SSH 密码"
            authType == AuthType.PRIVATE_KEY && !privateKey.contains("PRIVATE KEY") -> "请粘贴有效的 OpenSSH 或 PEM 私钥"
            else -> null
        }
        if (error != null) return null
        return SshProfile(
            id = profile?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { host.trim() },
            host = host.trim(),
            port = parsedPort!!,
            username = username.trim(),
            authType = authType,
            password = if (authType == AuthType.PASSWORD) password else "",
            privateKey = if (authType == AuthType.PRIVATE_KEY) privateKey.trim() else "",
            passphrase = if (authType == AuthType.PRIVATE_KEY) passphrase else "",
            terminalTheme = profile?.terminalTheme ?: TerminalTheme.DARK
        )
    }

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = topBarColors(),
                navigationIcon = {
                    HighContrastBackButton(onClick = onBack, description = "返回")
                },
                title = {
                    Column {
                        Text(if (profile == null) "添加 SSH 主机" else "编辑主机")
                        Text(
                            if (profile == null) "创建安全连接配置" else profile.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    if (profile != null) {
                        IconButton(onClick = { deleteConfirmation = true }) {
                            Icon(Icons.Rounded.Delete, "删除主机", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            SectionLabel("基本信息")
            Spacer(Modifier.height(10.dp))
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = "显示名称（可选）",
                placeholder = "例如：开发服务器",
                leading = { Icon(Icons.Rounded.Computer, null) }
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = "主机地址",
                placeholder = "server.example.com 或 192.168.1.10",
                leading = { Icon(Icons.Rounded.Terminal, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = "用户名",
                    placeholder = "ubuntu",
                    modifier = Modifier.weight(1.45f)
                )
                AppTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = "端口",
                    modifier = Modifier.weight(0.75f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(Modifier.height(24.dp))
            SectionLabel("认证方式")
            Spacer(Modifier.height(10.dp))
            AuthTypePicker(authType, onSelected = { authType = it })
            Spacer(Modifier.height(14.dp))
            AnimatedContent(targetState = authType, label = "auth-fields") { selectedType ->
                if (selectedType == AuthType.PASSWORD) {
                    AppTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "SSH 密码",
                        leading = { Icon(Icons.Rounded.Password, null) },
                        trailing = {
                            IconButton(onClick = { revealPassword = !revealPassword }) {
                                Icon(
                                    if (revealPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (revealPassword) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                } else {
                    Column {
                        AppTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            label = "私钥内容",
                            placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                            leading = { Icon(Icons.Rounded.Key, null) },
                            minLines = 6,
                            maxLines = 10,
                            textStyleMonospace = true
                        )
                        Spacer(Modifier.height(12.dp))
                        AppTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = "私钥口令（可选）",
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }
                }
            }
            AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            SecurityNote()
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { buildProfile()?.let { onSave(it, true) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
            ) {
                Icon(Icons.Rounded.Terminal, null)
                Spacer(Modifier.width(9.dp))
                Text("保存并连接", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { buildProfile()?.let { onSave(it, false) } },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                border = BorderStroke(1.dp, Outline)
            ) {
                Text("仅保存")
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (deleteConfirmation && profile != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除这台主机？") },
            text = { Text("“${profile.name}” 的 SSH 配置和凭据将从此设备移除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(profile) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun AuthTypePicker(selected: AuthType, onSelected: (AuthType) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(RaisedSurface, RoundedCornerShape(15.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AuthType.values().forEach { type ->
            val active = type == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelected(type) },
                color = if (active) Mint else Color.Transparent,
                contentColor = if (active) Ink else TextSecondary,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (type == AuthType.PASSWORD) Icons.Rounded.Password else Icons.Rounded.Key,
                        null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(if (type == AuthType.PASSWORD) "密码" else "私钥", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    minLines: Int = 1,
    maxLines: Int = 1,
    textStyleMonospace: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = TextSecondary.copy(alpha = 0.65f)) } },
        leadingIcon = leading,
        trailingIcon = trailing,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = if (textStyleMonospace) FontFamily.Monospace else FontFamily.Default
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Mint,
            unfocusedBorderColor = Outline,
            focusedContainerColor = DeepSurface,
            unfocusedContainerColor = DeepSurface,
            cursorColor = Mint
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowDashboardScreen(
    profile: SshProfile?,
    connection: ConnectionState,
    recovering: Boolean,
    windows: List<TmuxWindow>,
    recentPiDirectories: List<String>,
    refreshing: Boolean,
    dashboardMessage: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onWindow: (TmuxWindow) -> Unit,
    onCreateSession: (String, Boolean, String) -> Unit,
    onListRemoteDirectories: suspend (String) -> RemoteDirectoryListing
) {
    var showCreateDialog by remember { mutableStateOf(false) }
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
                    if (windows.isEmpty() && dashboardMessage == null) {
                        item { EmptyWindows { showCreateDialog = true } }
                    } else {
                        groups.forEach { (_, sessionWindows) ->
                            val first = sessionWindows.first()
                            item(key = "header-${first.sessionId}") {
                                SessionHeader(first.sessionName, sessionWindows.size)
                            }
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
            existingSessionNames = existingSessionNames,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, launchPi, workingDirectory ->
                showCreateDialog = false
                onCreateSession(name, launchPi, workingDirectory)
            },
            onListRemoteDirectories = onListRemoteDirectories
        )
    }
}

@Composable
private fun SessionHeader(name: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Layers, null, tint = Mint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count 个窗口", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
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
                        window.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
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
            Surface(color = RaisedSurface, shape = CircleShape) {
                Text(
                    "${window.paneCount}P",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
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

private enum class SessionLaunchMode { SHELL, PI }

@Composable
private fun CreateSessionDialog(
    recentPiDirectories: List<String>,
    existingSessionNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, String) -> Unit,
    onListRemoteDirectories: suspend (String) -> RemoteDirectoryListing
) {
    var name by rememberSaveable { mutableStateOf("") }
    var mode by remember { mutableStateOf(SessionLaunchMode.SHELL) }
    var workingDirectory by rememberSaveable { mutableStateOf("~") }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    val resolvedPiSessionName = resolvePiSessionName(
        requestedName = name,
        workingDirectory = workingDirectory,
        existingSessionNames = existingSessionNames
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (mode == SessionLaunchMode.PI) Icons.Rounded.AutoAwesome else Icons.Rounded.Terminal,
                null,
                tint = Mint
            )
        },
        title = { Text(if (mode == SessionLaunchMode.PI) "启动 Pi 工作区" else "新建 tmux 会话") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().background(RaisedSurface, RoundedCornerShape(13.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SessionModeButton(
                        label = "Shell",
                        icon = { Icon(Icons.Rounded.Terminal, null, Modifier.size(17.dp)) },
                        selected = mode == SessionLaunchMode.SHELL,
                        modifier = Modifier.weight(1f),
                        onClick = { mode = SessionLaunchMode.SHELL }
                    )
                    SessionModeButton(
                        label = "Pi",
                        icon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(17.dp)) },
                        selected = mode == SessionLaunchMode.PI,
                        modifier = Modifier.weight(1f),
                        onClick = { mode = SessionLaunchMode.PI }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (mode == SessionLaunchMode.PI) {
                        "创建 tmux 会话并以全屏模式启动 Pi，完成后直接进入工作区。"
                    } else {
                        "创建一个常规远程 Shell 会话。"
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = name,
                    onValueChange = { name = it.replace(' ', '-').take(40) },
                    label = if (mode == SessionLaunchMode.PI) "会话名称（可选）" else "会话名称",
                    placeholder = if (mode == SessionLaunchMode.PI) "自动：$resolvedPiSessionName" else "workspace"
                )
                if (mode == SessionLaunchMode.PI) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "将使用会话名称：$resolvedPiSessionName",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = workingDirectory,
                        onValueChange = { workingDirectory = it.take(512) },
                        label = "工作目录",
                        placeholder = "~",
                        trailing = {
                            IconButton(onClick = { showDirectoryPicker = true }) {
                                Icon(Icons.Rounded.FolderOpen, "选择远程工作目录")
                            }
                        },
                        textStyleMonospace = true
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "可直接填写，或点击文件夹浏览远程目录",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (recentPiDirectories.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "最近打开",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(7.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(recentPiDirectories, key = { it }) { path ->
                                val selectedPath = workingDirectory.trim().trimEnd('/').ifEmpty { "/" }
                                val selected = path == selectedPath
                                Surface(
                                    modifier = Modifier.widthIn(max = 250.dp).clickable {
                                        workingDirectory = path
                                    },
                                    color = if (selected) Mint.copy(alpha = 0.14f) else DeepSurface,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) Mint else Outline.copy(alpha = 0.75f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.FolderOpen,
                                            null,
                                            tint = Mint,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            path,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (mode == SessionLaunchMode.PI || name.isNotBlank()) {
                        onCreate(
                            name,
                            mode == SessionLaunchMode.PI,
                            workingDirectory.takeIf { mode == SessionLaunchMode.PI }?.trim().orEmpty()
                        )
                    }
                },
                enabled = mode == SessionLaunchMode.PI || name.isNotBlank()
            ) {
                Text(if (mode == SessionLaunchMode.PI) "启动 Pi" else "创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showDirectoryPicker) {
        RemoteDirectoryPicker(
            initialPath = workingDirectory.ifBlank { "~" },
            onLoad = onListRemoteDirectories,
            onDismiss = { showDirectoryPicker = false },
            onSelect = {
                workingDirectory = it
                showDirectoryPicker = false
            }
        )
    }
}

@Composable
private fun RemoteDirectoryPicker(
    initialPath: String,
    onLoad: suspend (String) -> RemoteDirectoryListing,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<RemoteDirectoryListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHiddenDirectories by remember { mutableStateOf(false) }

    fun load(path: String) {
        loading = true
        error = null
        scope.launch {
            runCatching { onLoad(path) }
                .onSuccess { listing = it }
                .onFailure { error = it.message ?: "无法读取远程目录" }
            loading = false
        }
    }

    LaunchedEffect(initialPath) { load(initialPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOpen, null, tint = Mint) },
        title = { Text("选择远程工作目录") },
        text = {
            Column {
                Text(
                    listing?.currentPath ?: initialPath,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = { showHiddenDirectories = !showHiddenDirectories },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        if (showHiddenDirectories) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if (showHiddenDirectories) "收起隐藏目录" else "显示隐藏目录")
                }
                Spacer(Modifier.height(2.dp))
                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Mint, strokeWidth = 2.dp)
                    }
                    error != null -> Column {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { load("~") }) { Text("返回主目录") }
                    }
                    else -> {
                        listing?.parentPath?.let { parent ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { load(parent) },
                                color = RaisedSurface,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("..  上一级", Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 310.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val directories = listing?.directories.orEmpty().filter { path ->
                                showHiddenDirectories || !path.substringAfterLast('/').startsWith('.')
                            }
                            if (directories.isEmpty()) {
                                item {
                                    Text(
                                        "没有子目录",
                                        color = TextSecondary,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                    )
                                }
                            } else {
                                items(directories, key = { it }) { path ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().clickable { load(path) },
                                        color = DeepSurface,
                                        shape = RoundedCornerShape(9.dp),
                                        border = BorderStroke(1.dp, Outline.copy(alpha = 0.7f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Rounded.FolderOpen,
                                                null,
                                                tint = Mint,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                path.substringAfterLast('/').ifEmpty { "/" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && error == null && listing != null,
                onClick = { listing?.currentPath?.let(onSelect) }
            ) { Text("选择此目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SessionModeButton(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) Mint else Color.Transparent,
        contentColor = if (selected) Ink else TextSecondary,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TerminalScreen(
    selected: TmuxWindow?,
    windows: List<TmuxWindow>,
    connected: Boolean,
    recovering: Boolean,
    ctrlActive: Boolean,
    terminalTheme: TerminalTheme,
    terminalViewModel: TmuxerViewModel,
    onBack: () -> Unit,
    onExitSession: () -> Unit,
    onSwitchWindow: (TmuxWindow) -> Unit,
    onControl: () -> Unit,
    onToggleTheme: () -> Unit,
    onSpecialKey: (String) -> Unit
) {
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    var imagePreview by remember { mutableStateOf<TerminalImagePreview?>(null) }
    var loadingImage by remember { mutableStateOf(false) }
    var imageLoadGeneration by remember { mutableStateOf(0L) }
    var showExitSessionDialog by remember(selected?.sessionId) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val uploadProgress by terminalViewModel.uploadProgress.collectAsStateWithLifecycle()
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> terminalViewModel.uploadFiles(uris) }
    val windowIds = remember(windows) { windows.map { it.windowId } }
    val openTerminalImage: (TerminalImageOpenRequest) -> Unit = { request ->
        imageLoadGeneration++
        val generation = imageLoadGeneration
        loadingImage = true
        coroutineScope.launch {
            val result = runCatching {
                val data = request.remotePath?.let { terminalViewModel.downloadTerminalImage(it) }
                    ?: request.encodedData
                val bitmap = withContext(Dispatchers.Default) { decodeTerminalImagePreview(data) }
                    ?: error("无法识别图片格式")
                TerminalImagePreview(request.imageId, bitmap, data)
            }
            if (generation == imageLoadGeneration) {
                loadingImage = false
                result.onSuccess { imagePreview = it }
                    .onFailure {
                        terminalViewModel.showNotice(it.message ?: "图片加载失败")
                    }
            }
        }
    }
    val tabListState = rememberLazyListState()
    LaunchedEffect(selected?.windowId, windowIds) {
        val selectedIndex = windowIds.indexOf(selected?.windowId)
        if (selectedIndex >= 0) tabListState.animateScrollToItem(selectedIndex)
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Ink)
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).background(DeepSurface).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalBackButton(connected = connected, onClick = onBack)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleTheme, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (terminalTheme == TerminalTheme.DARK) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    if (terminalTheme == TerminalTheme.DARK) "切换为浅色终端" else "切换为深色终端",
                    tint = if (terminalTheme == TerminalTheme.DARK) Amber else TerminalBlue,
                    modifier = Modifier.size(21.dp)
                )
            }
            IconButton(
                enabled = connected && uploadProgress == null,
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.size(44.dp)
            ) {
                if (uploadProgress == null) {
                    Icon(
                        Icons.Rounded.UploadFile,
                        "上传文件",
                        tint = TextSecondary,
                        modifier = Modifier.size(21.dp)
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Mint, strokeWidth = 2.dp)
                }
            }
            IconButton(
                onClick = { terminalViewRef?.toggleKeyboard() },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Rounded.Keyboard,
                    "显示或隐藏键盘",
                    tint = TextSecondary,
                    modifier = Modifier.size(21.dp)
                )
            }
            IconButton(
                enabled = connected && uploadProgress == null,
                onClick = { showExitSessionDialog = true },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    "退出当前 tmux 会话",
                    tint = Amber,
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        if (windows.isNotEmpty()) {
            LazyRow(
                state = tabListState,
                modifier = Modifier.fillMaxWidth().background(RaisedSurface),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(windows, key = { it.windowId }) { window ->
                    WindowTab(
                        window = window,
                        selected = selected?.windowId == window.windowId,
                        onClick = { onSwitchWindow(window) }
                    )
                }
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(terminalTheme.backgroundColor))
        ) {
            AndroidView(
                factory = { context ->
                    TerminalView(context).apply {
                        terminalViewRef = this
                        this.terminalTheme = terminalTheme
                        emulator = terminalViewModel.terminal
                        onInput = terminalViewModel::sendTerminalInput
                        onTerminalResize = terminalViewModel::resizeTerminal
                        onImageClick = openTerminalImage
                        onNotice = terminalViewModel::showNotice
                    }
                },
                update = { view ->
                    view.terminalTheme = terminalTheme
                    view.emulator = terminalViewModel.terminal
                    view.onInput = terminalViewModel::sendTerminalInput
                    view.onTerminalResize = terminalViewModel::resizeTerminal
                    view.onImageClick = openTerminalImage
                    view.onNotice = terminalViewModel::showNotice
                },
                modifier = Modifier.fillMaxSize()
            )
            if (loadingImage) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = RaisedSurface.copy(alpha = 0.96f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Outline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Mint, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载图片…", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                    }
                }
            }
            uploadProgress?.let { progress ->
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    color = RaisedSurface.copy(alpha = 0.95f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Outline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (progress.fraction == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Mint,
                                strokeWidth = 2.dp
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { progress.fraction ?: 0f },
                                modifier = Modifier.size(14.dp),
                                color = Mint,
                                trackColor = Outline,
                                strokeWidth = 2.dp
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            buildString {
                                append("上传 ")
                                if (progress.fileCount > 1) {
                                    append(progress.fileIndex).append('/').append(progress.fileCount).append(" · ")
                                }
                                append(progress.fileName)
                                progress.fraction?.let { append(" · ").append((it * 100).toInt()).append('%') }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (!connected && !recovering) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    color = RaisedSurface.copy(alpha = 0.92f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Outline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(12.dp), color = Mint, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(7.dp))
                        Text("正在接入 tmux…", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }
        }

        SpecialKeyBar(
            piMode = selected?.let { it.command == "pi" || it.name.equals("pi", true) } == true,
            ctrlActive = ctrlActive,
            onControl = onControl,
            onKey = onSpecialKey
        )
    }

    imagePreview?.let { preview ->
        TerminalImagePreviewDialog(
            preview = preview,
            onDismiss = {
                terminalViewRef?.suppressKeyboardDoubleTap()
                imagePreview = null
            }
        )
    }

    if (showExitSessionDialog) {
        AlertDialog(
            onDismissRequest = { showExitSessionDialog = false },
            title = { Text("退出 tmux 会话？") },
            text = {
                Text(
                    "将终止“${selected?.sessionName.orEmpty()}”中的全部窗口和运行程序。" +
                        "SSH 连接会保持。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitSessionDialog = false
                        onExitSession()
                    }
                ) {
                    Text("退出会话", color = Amber, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitSessionDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TerminalImagePreviewDialog(
    preview: TerminalImagePreview,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var saveNotice by remember(preview.imageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(saveNotice) {
        val visibleNotice = saveNotice ?: return@LaunchedEffect
        delay(1_600)
        if (saveNotice == visibleNotice) saveNotice = null
    }
    val imageFormat = remember(preview.imageId) { terminalImageFormat(preview.encodedData) }
    val saveImage = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(imageFormat.first)
    ) { destination ->
        if (destination != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val saved = runCatching {
                    context.contentResolver.openOutputStream(destination)?.use { output ->
                        output.write(preview.encodedData)
                    } ?: error("无法打开目标文件")
                }.isSuccess
                withContext(Dispatchers.Main) {
                    saveNotice = if (saved) "图片已保存" else "保存图片失败"
                }
            }
        }
    }
    var scale by remember(preview.imageId) { mutableStateOf(1f) }
    var translation by remember(preview.imageId) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 6f)
        scale = nextScale
        translation = if (nextScale <= 1f) Offset.Zero else translation + panChange
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = preview.bitmap.asImageBitmap(),
                    contentDescription = "终端图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = translation.x
                            translationY = translation.y
                        }
                        .transformable(transformState)
                )
                saveNotice?.let { message ->
                    CenteredNoticePopup(message, Modifier.align(Alignment.Center))
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.62f)
                ) {
                    Row {
                        IconButton(
                            onClick = {
                                saveImage.launch("tmuxer-image-${System.currentTimeMillis()}.${imageFormat.second}")
                            }
                        ) {
                            Icon(Icons.Rounded.Download, "保存图片", tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, "关闭图片预览", tint = Color.White)
                        }
                    }
                }
                Text(
                    "双指缩放 · 拖动查看 · 右上保存",
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding().padding(bottom = 18.dp)
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private fun terminalImageFormat(data: ByteArray): Pair<String, String> = when {
    data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
        data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "image/png" to "png"
    data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() ->
        "image/jpeg" to "jpg"
    data.size >= 6 && String(data, 0, 6, Charsets.US_ASCII).startsWith("GIF") ->
        "image/gif" to "gif"
    data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to "webp"
    else -> "application/octet-stream" to "bin"
}

internal fun windowTabLabel(window: TmuxWindow): String =
    "${window.sessionName}:${window.index}"

@Composable
private fun WindowTab(
    window: TmuxWindow,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        color = if (selected) Mint else DeepSurface,
        contentColor = if (selected) Ink else TextSecondary,
        border = if (selected) null else BorderStroke(1.dp, Outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                windowTabLabel(window),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall
            )
            if (window.activity && !selected) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(4.dp).background(Amber, CircleShape))
            }
        }
    }
}

@Composable
private fun SpecialKeyBar(
    piMode: Boolean,
    ctrlActive: Boolean,
    onControl: () -> Unit,
    onKey: (String) -> Unit
) {
    val firstRowScroll = rememberScrollState()
    val secondRowScroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxWidth().background(DeepSurface)
            // Keep the horizontally scrollable second row above Android's mandatory
            // bottom app-switch gesture region; that system gesture cannot be excluded.
            .padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(firstRowScroll)
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (piMode) {
                KeyButton("Esc", description = "停止生成", compact = true) { onKey("\u001B") }
                KeyButton("/", description = "输入斜杠命令", compact = true) { onKey("/") }
                KeyButton("^D", description = "删除字符或退出", compact = true) { onKey("\u0004") }
                KeyButton("^U", description = "清空输入", compact = true) { onKey("\u0015") }
                KeyButton("^J", description = "插入换行", compact = true) { onKey("\u000A") }
                CtrlShiftDirectionButton(
                    direction = Icons.Rounded.KeyboardArrowUp,
                    description = "跳到上一条信息"
                ) { onKey("\u001B[1;6A") }
                CtrlShiftDirectionButton(
                    direction = Icons.Rounded.KeyboardArrowDown,
                    description = "跳到下一条信息"
                ) { onKey("\u001B[1;6B") }
                KeyButton("^T", description = "展开或折叠思考内容", compact = true) { onKey("\u0014") }
                KeyButton("^O", description = "展开或折叠工具输出", compact = true) { onKey("\u000F") }
            } else {
                KeyButton("Esc", description = "Escape") { onKey("\u001B") }
                KeyButton("Ctrl", description = "Control", active = ctrlActive, onClick = onControl)
                KeyButton("Tab") { onKey("\t") }
                KeyButton("/", description = "输入斜杠") { onKey("/") }
                KeyButton(icon = Icons.Rounded.KeyboardDoubleArrowUp, description = "向上翻页") {
                    onKey("\u001B[5~")
                }
                KeyButton(icon = Icons.Rounded.KeyboardDoubleArrowDown, description = "向下翻页") {
                    onKey("\u001B[6~")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(secondRowScroll)
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (piMode) {
                KeyButton("Ctrl", description = "Control", active = ctrlActive, onClick = onControl)
                KeyButton("Tab") { onKey("\t") }
            }
            KeyButton(icon = Icons.Rounded.KeyboardArrowLeft, description = "左方向键") {
                onKey("\u001B[D")
            }
            KeyButton(icon = Icons.Rounded.KeyboardArrowDown, description = "下方向键") {
                onKey("\u001B[B")
            }
            KeyButton(icon = Icons.Rounded.KeyboardArrowUp, description = "上方向键") {
                onKey("\u001B[A")
            }
            KeyButton(icon = Icons.Rounded.KeyboardArrowRight, description = "右方向键") {
                onKey("\u001B[C")
            }
            if (piMode) {
                KeyButton(icon = Icons.Rounded.KeyboardDoubleArrowUp, description = "向上翻页") {
                    onKey("\u001B[5~")
                }
                KeyButton(icon = Icons.Rounded.KeyboardDoubleArrowDown, description = "向下翻页") {
                    onKey("\u001B[6~")
                }
            }
            KeyButton("-") { onKey("-") }
            KeyButton("|") { onKey("|") }
        }
    }
}

@Composable
private fun CtrlShiftDirectionButton(
    direction: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    KeyButton(
        description = description,
        compact = true,
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "^",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
                Icon(ShiftFilledIcon, contentDescription = null, modifier = Modifier.size(11.dp))
                Icon(direction, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        },
        onClick = onClick
    )
}

@Composable
private fun KeyButton(
    label: String? = null,
    icon: ImageVector? = null,
    description: String = label.orEmpty(),
    active: Boolean = false,
    compact: Boolean = false,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    require(label != null || icon != null || content != null) { "按键必须提供文字、图标或内容" }
    Surface(
        modifier = Modifier.height(30.dp).widthIn(min = if (compact) 34.dp else 40.dp)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (active) Mint else RaisedSurface,
        contentColor = if (active) Ink else TextPrimary,
        border = BorderStroke(1.dp, if (active) Mint else Outline)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 6.dp)
        ) {
            when {
                content != null -> content()
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                else -> Text(
                    label.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun TerminalBackButton(
    connected: Boolean,
    onClick: () -> Unit
) {
    Box(Modifier.padding(start = 2.dp).size(40.dp)) {
        HighContrastBackButton(
            onClick = onClick,
            description = "返回窗口列表",
            modifier = Modifier.align(Alignment.Center).size(36.dp)
        )
        Box(
            Modifier.align(Alignment.BottomEnd)
                .size(13.dp)
                .background(DeepSurface, CircleShape)
                .padding(3.dp)
                .background(if (connected) Mint else Amber, CircleShape)
                .semantics {
                    contentDescription = if (connected) "SSH 已连接" else "SSH 正在连接"
                }
        )
    }
}

@Composable
private fun HighContrastBackButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = Mint,
        contentColor = Ink,
        shadowElevation = 2.dp
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Rounded.ArrowBack, description, tint = Ink)
        }
    }
}

@Composable
private fun ConnectionDot(connected: Boolean) {
    Box(
        Modifier.size(7.dp).background(
            if (connected) Mint else Amber,
            CircleShape
        )
    )
}

@Composable
private fun SectionLabel(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun LogoMark() {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(11.dp),
        color = Mint
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                ">_",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun topBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Ink,
    scrolledContainerColor = DeepSurface,
    titleContentColor = TextPrimary,
    navigationIconContentColor = TextPrimary,
    actionIconContentColor = TextPrimary
)
