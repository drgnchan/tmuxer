package com.tmuxer.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmuxer.app.data.TmuxWindow
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TerminalScreen(
    selected: TmuxWindow?,
    windows: List<TmuxWindow>,
    hostLabel: String,
    connected: Boolean,
    recovering: Boolean,
    ctrlActive: State<Boolean>,
    shiftActive: State<Boolean>,
    altActive: State<Boolean>,
    terminalTheme: TerminalTheme,
    terminalViewModel: TmuxerViewModel,
    onBack: () -> Unit,
    onExitSession: () -> Unit,
    onSwitchWindow: (TmuxWindow) -> Unit,
    onControl: () -> Unit,
    onShift: () -> Unit,
    onAlt: () -> Unit,
    onToggleTheme: () -> Unit,
    onSpecialKey: (String) -> Unit
) {
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    var imagePreview by remember { mutableStateOf<TerminalImagePreview?>(null) }
    var loadingImage by remember { mutableStateOf(false) }
    var imageLoadGeneration by remember { mutableStateOf(0L) }
    var showExitSessionDialog by remember(selected?.sessionId) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val openTerminalWebLink: (String) -> Unit = { url ->
        val uri = Uri.parse(url)
        val chromeOpened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .setPackage("com.android.chrome")
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }.isSuccess
        if (!chromeOpened) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                )
            }.onFailure { terminalViewModel.showNotice("无法打开链接") }
        }
    }
    // Progress ticks ~10 times a second; only the bubble reads the value; the rest of the screen
    // observes whether an upload is running at all.
    val uploadProgress = terminalViewModel.uploadProgress.collectAsStateWithLifecycle()
    val uploading by remember { derivedStateOf { uploadProgress.value != null } }
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
    val piMode = selected?.let { it.command == "pi" || it.name.equals("pi", true) } == true
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
            Text(
                text = hostLabel,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onToggleTheme, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (terminalTheme == TerminalTheme.DARK) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    if (terminalTheme == TerminalTheme.DARK) "切换为浅色终端" else "切换为深色终端",
                    tint = if (terminalTheme == TerminalTheme.DARK) Amber else TerminalBlue,
                    modifier = Modifier.size(21.dp)
                )
            }
            IconButton(
                enabled = connected && !uploading,
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.size(44.dp)
            ) {
                if (!uploading) {
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
                enabled = connected && !uploading,
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
                        onWebLinkClick = openTerminalWebLink
                        onNotice = terminalViewModel::showNotice
                    }
                },
                update = { view ->
                    view.terminalTheme = terminalTheme
                    view.emulator = terminalViewModel.terminal
                    view.onInput = terminalViewModel::sendTerminalInput
                    view.onTerminalResize = terminalViewModel::resizeTerminal
                    view.onImageClick = openTerminalImage
                    view.onWebLinkClick = openTerminalWebLink
                    view.onNotice = terminalViewModel::showNotice
                },
                modifier = Modifier.fillMaxSize()
            )
            if (piMode) {
                PiPageNavigationOverlay(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onPrevious = { onSpecialKey("\u001B[1;6A") },
                    onNext = { onSpecialKey("\u001B[1;6B") }
                )
            }
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
            UploadProgressBubble(
                state = uploadProgress,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            )
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
            ctrlActive = ctrlActive,
            shiftActive = shiftActive,
            altActive = altActive,
            onControl = onControl,
            onShift = onShift,
            onAlt = onAlt,
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
private fun UploadProgressBubble(state: State<FileUploadProgress?>, modifier: Modifier = Modifier) {
    val progress = state.value ?: return
    Surface(
        modifier = modifier,
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
