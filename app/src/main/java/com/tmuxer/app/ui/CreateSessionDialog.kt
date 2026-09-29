package com.tmuxer.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmuxer.app.ssh.RemoteDirectoryListing
import com.tmuxer.app.ui.theme.Amber
import com.tmuxer.app.ui.theme.DeepSurface
import com.tmuxer.app.ui.theme.Ink
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.RaisedSurface
import com.tmuxer.app.ui.theme.TextPrimary
import com.tmuxer.app.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class SessionLaunchMode { SHELL, PI }

@Composable
private fun PiWorkingDirectoryDropdown(
    value: String,
    recentDirectories: List<String>,
    defaultDirectory: String,
    onSelect: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onBrowse: () -> Unit,
    onRemoveRecentDirectory: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDirectory = value.ifBlank { "~" }
    val savedDefaultDirectory = defaultDirectory.ifBlank { "~" }
    val directoryOptions = remember(selectedDirectory, recentDirectories, savedDefaultDirectory) {
        buildList {
            (listOf(savedDefaultDirectory, "~") + recentDirectories).forEach { path ->
                if (path != selectedDirectory && path !in this) add(path)
            }
        }
    }

    Column {
        Text(
            "工作目录",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(7.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = DeepSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (expanded) Mint else Outline)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = Mint, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        selectedDirectory,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            onSetDefault(selectedDirectory)
                            expanded = false
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        val isDefault = selectedDirectory == savedDefaultDirectory
                        Icon(
                            if (isDefault) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if (isDefault) "当前默认目录" else "将当前目录设为默认",
                            tint = if (isDefault) Amber else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        if (expanded) "收起工作目录" else "展开工作目录",
                        tint = TextSecondary
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column {
                        HorizontalDivider(color = Outline.copy(alpha = 0.7f))
                        directoryOptions.forEach { path ->
                            val selected = path == selectedDirectory
                            val isDefault = path == savedDefaultDirectory
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onSelect(path)
                                    expanded = false
                                }.padding(start = 14.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.FolderOpen,
                                    null,
                                    tint = if (selected) Mint else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    path,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.StartEllipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        onSelect(path)
                                        onSetDefault(path)
                                        expanded = false
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        if (isDefault) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        if (isDefault) "当前默认目录" else "设为默认目录：$path",
                                        tint = if (isDefault) Amber else TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (path in recentDirectories) {
                                    IconButton(
                                        onClick = { onRemoveRecentDirectory(path) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            "删除打开记录：$path",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.width(34.dp))
                                }
                            }
                        }
                        HorizontalDivider(color = Outline.copy(alpha = 0.7f))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                expanded = false
                                onBrowse()
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                null,
                                tint = Mint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                "浏览远程目录…",
                                color = Mint,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreateSessionDialog(
    recentPiDirectories: List<String>,
    defaultPiDirectory: String,
    existingSessionNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, String, Boolean, String) -> Unit,
    onRemoveRecentPiDirectory: (String) -> Unit,
    onSetDefaultPiDirectory: (String) -> Unit,
    onListRemoteDirectories: suspend (String) -> RemoteDirectoryListing
) {
    var name by rememberSaveable { mutableStateOf("") }
    var mode by remember { mutableStateOf(SessionLaunchMode.PI) }
    var launchWithoutSession by rememberSaveable { mutableStateOf(false) }
    var workingDirectory by rememberSaveable { mutableStateOf(defaultPiDirectory) }
    var initialPrompt by rememberSaveable { mutableStateOf("") }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    val resolvedSessionName = if (mode == SessionLaunchMode.PI) {
        resolvePiSessionName(
            requestedName = name,
            workingDirectory = workingDirectory,
            existingSessionNames = existingSessionNames
        )
    } else {
        resolveShellSessionName(
            requestedName = name,
            existingSessionNames = existingSessionNames
        )
    }
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
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            ) {
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
                if (mode == SessionLaunchMode.PI) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "启动方式",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().background(RaisedSurface, RoundedCornerShape(13.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SessionModeButton(
                            label = "正常任务",
                            icon = { Text("pi", fontFamily = FontFamily.Monospace) },
                            selected = !launchWithoutSession,
                            modifier = Modifier.weight(1f),
                            onClick = { launchWithoutSession = false }
                        )
                        SessionModeButton(
                            label = "临时任务",
                            icon = { Text("pi", fontFamily = FontFamily.Monospace) },
                            selected = launchWithoutSession,
                            modifier = Modifier.weight(1f),
                            onClick = { launchWithoutSession = true }
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (launchWithoutSession) "启动命令：pi --no-session" else "启动命令：pi",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(10.dp))
                    PiWorkingDirectoryDropdown(
                        value = workingDirectory,
                        recentDirectories = recentPiDirectories,
                        defaultDirectory = defaultPiDirectory,
                        onSelect = { workingDirectory = it },
                        onSetDefault = onSetDefaultPiDirectory,
                        onBrowse = { showDirectoryPicker = true },
                        onRemoveRecentDirectory = onRemoveRecentPiDirectory
                    )
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = name,
                        onValueChange = { name = it.replace(' ', '-').take(40) },
                        label = "会话名称（可选）",
                        placeholder = "自动：$resolvedSessionName"
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "将使用会话名称：$resolvedSessionName",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = initialPrompt,
                        onValueChange = { initialPrompt = it.take(16_384) },
                        label = "启动提示（可选）",
                        placeholder = "/skill:example 任务内容",
                        maxLines = 5,
                        textStyleMonospace = true
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "启动 Pi 后立即提交，支持普通 Prompt、/skill:name 和 Prompt 模板命令。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    AppTextField(
                        value = name,
                        onValueChange = { name = it.replace(' ', '-').take(40) },
                        label = "会话名称（可选）",
                        placeholder = "自动：$resolvedSessionName"
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "将使用会话名称：$resolvedSessionName",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        name,
                        mode == SessionLaunchMode.PI,
                        workingDirectory.takeIf { mode == SessionLaunchMode.PI }?.trim().orEmpty(),
                        mode == SessionLaunchMode.PI && launchWithoutSession,
                        initialPrompt.takeIf { mode == SessionLaunchMode.PI }.orEmpty()
                    )
                }
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
internal fun RemoteDirectoryPicker(
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
    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun load(path: String) {
        // Only the latest navigation may publish; a slower earlier listing would otherwise
        // replace it and make "选择此目录" submit the wrong path.
        loadJob?.cancel()
        loading = true
        error = null
        loadJob = scope.launch {
            try {
                listing = onLoad(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: "无法读取远程目录"
            }
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
internal fun SessionModeButton(
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
