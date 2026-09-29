package com.tmuxer.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmuxer.app.data.AuthType
import com.tmuxer.app.data.SshProfile
import com.tmuxer.app.terminal.TerminalTheme
import com.tmuxer.app.ui.theme.DeepSurface
import com.tmuxer.app.ui.theme.Ink
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.RaisedSurface
import com.tmuxer.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostListScreen(
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
internal fun ProfileEditorScreen(
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
    // Secrets stay out of the saved-instance Bundle, which is held by the system process.
    var password by remember(profile?.id) { mutableStateOf(profile?.password.orEmpty()) }
    var privateKey by remember(profile?.id) { mutableStateOf(profile?.privateKey.orEmpty()) }
    var passphrase by remember(profile?.id) { mutableStateOf(profile?.passphrase.orEmpty()) }
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
