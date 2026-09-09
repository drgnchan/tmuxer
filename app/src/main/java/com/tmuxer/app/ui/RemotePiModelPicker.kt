package com.tmuxer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmuxer.app.ssh.RemotePiModel
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException

@Composable
internal fun RemotePiModelPicker(
    workingDirectory: String,
    selectedModel: String,
    onLoad: suspend (String, Boolean) -> List<RemotePiModel>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var models by remember(workingDirectory) { mutableStateOf<List<RemotePiModel>?>(null) }
    var loading by remember(workingDirectory) { mutableStateOf(true) }
    var error by remember(workingDirectory) { mutableStateOf<String?>(null) }
    var refreshGeneration by remember(workingDirectory) { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var manualModel by rememberSaveable { mutableStateOf(selectedModel) }
    val load by rememberUpdatedState(onLoad)

    LaunchedEffect(workingDirectory, refreshGeneration) {
        loading = true
        error = null
        try {
            models = load(workingDirectory, refreshGeneration > 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "获取模型失败，请重试或手动输入"
        } finally {
            loading = false
        }
    }
    val groups = remember(models, query) {
        val search = query.trim()
        models.orEmpty().filter { it.selection.contains(search, ignoreCase = true) }.groupBy { it.provider }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择 Pi 模型", modifier = Modifier.weight(1f))
                IconButton(enabled = !loading, onClick = { refreshGeneration++ }) {
                    Icon(Icons.Rounded.Refresh, "刷新远程模型列表")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("当前 SSH 主机 · ${workingDirectory.ifBlank { "~" }}", color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("来源：pi --list-models；结果按连接和目录缓存，授权变更后请刷新。",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                if (selectedModel.isNotEmpty()) {
                    Text("当前：$selectedModel", maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall)
                }
                Row {
                    TextButton(onClick = { onSelect("") }) { Text("沿用默认") }
                    TextButton(onClick = { manual = !manual }) { Text(if (manual) "返回列表" else "手动输入") }
                }
                if (manual) {
                    OutlinedTextField(
                        value = manualModel,
                        onValueChange = { manualModel = it.replace("\u0000", "").take(512) },
                        label = { Text("模型 ID / provider/model") },
                        supportingText = { Text("留空沿用默认；不在列表中的自定义模型也可填写。") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索厂商或模型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (loading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在读取远程模型…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        if (!models.isNullOrEmpty()) {
                            Text("刷新失败，以下仍为上次结果。", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!loading && error == null && models.isNullOrEmpty()) {
                        Text("未发现可用模型，请在远程 Pi 中 /login 或配置 API key 后刷新。", color = TextSecondary)
                    } else if (!models.isNullOrEmpty() && groups.isEmpty()) {
                        Text("没有匹配的模型", color = TextSecondary)
                    }
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        groups.forEach { (provider, providerModels) ->
                            item(key = "provider:$provider") {
                                Text(provider, color = Mint, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 8.dp))
                            }
                            items(providerModels, key = { "model:${it.selection}" }) { model ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(model.selection) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(model.id, style = MaterialTheme.typography.bodyMedium)
                                        Text("上下文 ${model.context} · 输出 ${model.maxOutput} · Thinking ${if (model.thinking) "支持" else "不支持"} · 图片 ${if (model.images) "支持" else "不支持"}",
                                            color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (model.selection == selectedModel) {
                                        Icon(Icons.Rounded.CheckCircle, "已选择", tint = Mint, modifier = Modifier.size(20.dp))
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (manual) {
                TextButton(onClick = { onSelect(manualModel.trim()) }) { Text("使用此模型") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (manual) TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
