package com.tmuxer.app.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmuxer.app.data.QUICK_LAUNCH_INPUT_PLACEHOLDER
import com.tmuxer.app.data.QuickLaunchPreset
import com.tmuxer.app.data.buildQuickLaunchPrompt
import com.tmuxer.app.ssh.RemoteDirectoryListing
import com.tmuxer.app.ssh.RemotePiModel
import com.tmuxer.app.ui.theme.DeepSurface
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.RaisedSurface
import com.tmuxer.app.ui.theme.TerminalBlue
import com.tmuxer.app.ui.theme.TextSecondary

@Composable
internal fun QuickLaunchSection(
    presets: List<QuickLaunchPreset>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onLaunch: (QuickLaunchPreset) -> Unit,
    onEdit: (QuickLaunchPreset) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Mint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("快捷任务", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加")
            }
        }
        if (presets.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd),
                color = DeepSurface,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, Outline.copy(alpha = 0.75f))
            ) {
                Text(
                    "保存常用 Skill 和 Prompt，下次只输入几个字即可启动",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(presets, key = { it.id }) { preset ->
                    OutlinedCard(
                        onClick = { if (enabled) onLaunch(preset) },
                        modifier = Modifier.width(250.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = DeepSurface),
                        border = BorderStroke(1.dp, Outline.copy(alpha = 0.75f))
                    ) {
                        Column(Modifier.padding(start = 13.dp, top = 10.dp, bottom = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    preset.title,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onEdit(preset) }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Rounded.Edit, "编辑${preset.title}", modifier = Modifier.size(17.dp))
                                }
                                IconButton(onClick = { onDelete(preset.id) }, modifier = Modifier.size(34.dp)) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        "删除${preset.title}",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                            Text(
                                preset.promptTemplate.ifBlank { "直接使用输入内容" },
                                color = TerminalBlue,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 13.dp)
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "${preset.workingDirectory} · ${if (preset.launchWithoutSession) "临时" else "正常"}",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.padding(end = 13.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickLaunchPresetEditor(
    preset: QuickLaunchPreset?,
    onDismiss: () -> Unit,
    onSave: (QuickLaunchPreset) -> Unit,
    onListRemoteDirectories: suspend (String) -> RemoteDirectoryListing,
    onListPiModels: suspend (String, Boolean) -> List<RemotePiModel>
) {
    var title by rememberSaveable(preset?.id) { mutableStateOf(preset?.title.orEmpty()) }
    var promptTemplate by rememberSaveable(preset?.id) { mutableStateOf(preset?.promptTemplate.orEmpty()) }
    var workingDirectory by rememberSaveable(preset?.id) {
        mutableStateOf(preset?.workingDirectory ?: "~")
    }
    var launchWithoutSession by rememberSaveable(preset?.id) {
        mutableStateOf(preset?.launchWithoutSession ?: false)
    }
    var sessionName by rememberSaveable(preset?.id) { mutableStateOf(preset?.sessionName.orEmpty()) }
    var model by rememberSaveable(preset?.id) { mutableStateOf(preset?.model.orEmpty()) }
    var thinkingEffort by rememberSaveable(preset?.id) { mutableStateOf(preset?.thinkingEffort.orEmpty()) }
    var showThinkingMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showDirectoryPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null, tint = Mint) },
        title = { Text(if (preset == null) "添加快捷任务" else "编辑快捷任务") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            ) {
                AppTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    label = "名称",
                    placeholder = "例如：查 Doris"
                )
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = promptTemplate,
                    onValueChange = { promptTemplate = it.take(16_384) },
                    label = "Prompt 模板",
                    placeholder = "/skill:doris 查询 $QUICK_LAUNCH_INPUT_PLACEHOLDER",
                    minLines = 3,
                    maxLines = 7,
                    textStyleMonospace = true
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "用 $QUICK_LAUNCH_INPUT_PLACEHOLDER 标记每次输入的位置；不写占位符时，输入会自动追加。",
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
                Spacer(Modifier.height(10.dp))
                Text("模型（可选）", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { showModelPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        model.ifEmpty { "默认（沿用 Pi 配置）" },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Rounded.ArrowDropDown, "选择远程 Pi 模型")
                }
                Spacer(Modifier.height(10.dp))
                Text("Thinking effort", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(onClick = { showThinkingMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(thinkingEffort.ifEmpty { "默认（沿用 Pi 配置）" })
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showThinkingMenu,
                        onDismissRequest = { showThinkingMenu = false }
                    ) {
                        (listOf("") + com.tmuxer.app.data.PI_THINKING_EFFORTS).forEach { effort ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(effort.ifEmpty { "默认（沿用 Pi 配置）" }) },
                                onClick = {
                                    thinkingEffort = effort
                                    showThinkingMenu = false
                                }
                            )
                        }
                    }
                }
                Text("支持的思考档位取决于模型和远程 Pi 版本。", color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(10.dp))
                Text("启动方式", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
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
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it.replace(' ', '-').take(40) },
                    label = "会话名称（可选）",
                    placeholder = "自动使用目录名"
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        QuickLaunchPreset(
                            id = preset?.id ?: java.util.UUID.randomUUID().toString(),
                            title = title,
                            promptTemplate = promptTemplate,
                            workingDirectory = workingDirectory,
                            launchWithoutSession = launchWithoutSession,
                            sessionName = sessionName,
                            model = model,
                            thinkingEffort = thinkingEffort
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showModelPicker) {
        RemotePiModelPicker(
            workingDirectory = workingDirectory,
            selectedModel = model,
            onLoad = onListPiModels,
            onDismiss = { showModelPicker = false },
            onSelect = {
                model = it
                showModelPicker = false
            }
        )
    }

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
internal fun QuickLaunchDialog(
    preset: QuickLaunchPreset,
    onDismiss: () -> Unit,
    onLaunch: (String) -> Unit
) {
    var input by rememberSaveable(preset.id) { mutableStateOf("") }
    val prompt = buildQuickLaunchPrompt(preset.promptTemplate, input)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null, tint = Mint) },
        title = { Text(preset.title) },
        text = {
            Column {
                AppTextField(
                    value = input,
                    onValueChange = { input = it.take(16_384) },
                    label = "补充内容（可选）",
                    placeholder = if (QUICK_LAUNCH_INPUT_PLACEHOLDER in preset.promptTemplate) {
                        "替换 $QUICK_LAUNCH_INPUT_PLACEHOLDER"
                    } else {
                        "追加到 Prompt"
                    },
                    minLines = 2,
                    maxLines = 5
                )
                Spacer(Modifier.height(10.dp))
                Text("将提交", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(5.dp))
                Surface(color = DeepSurface, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        prompt.ifBlank { "不提交初始 Prompt" },
                        color = if (prompt.isBlank()) TextSecondary else TerminalBlue,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(10.dp)
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "${preset.workingDirectory} · ${if (preset.launchWithoutSession) "临时任务" else "正常任务"}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onLaunch(input) }) { Text("立即启动") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
