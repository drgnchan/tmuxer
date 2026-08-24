package com.tmuxer.app.ui

import com.tmuxer.app.ssh.TmuxWindowCapture
import com.tmuxer.app.terminal.TerminalEmulator
import com.tmuxer.app.terminal.TerminalSnapshot
import com.tmuxer.app.terminal.TerminalTheme
import java.io.ByteArrayOutputStream

internal sealed interface WindowPreviewUiState {
    data object Loading : WindowPreviewUiState
    data object Unavailable : WindowPreviewUiState
    data class Ready(val preview: WindowTerminalPreview) : WindowPreviewUiState
}

internal data class WindowTerminalPreview(
    val windowId: String,
    val columns: Int,
    val rows: Int,
    val theme: TerminalTheme,
    val panes: List<WindowPanePreview>
)

internal data class WindowPanePreview(
    val paneId: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val cursorColumn: Int,
    val cursorRow: Int,
    val active: Boolean,
    val snapshot: TerminalSnapshot
)

/** Converts tmux capture-pane output into the same VT cell model used by the full terminal. */
internal fun buildWindowTerminalPreview(
    capture: TmuxWindowCapture,
    theme: TerminalTheme
): WindowTerminalPreview = WindowTerminalPreview(
    windowId = capture.windowId,
    columns = capture.columns,
    rows = capture.rows,
    theme = theme,
    panes = capture.panes.map { pane ->
        val emulator = TerminalEmulator(
            initialColumns = pane.width,
            initialRows = pane.height
        )
        emulator.setTheme(theme)
        val content = normalizePaneCapture(pane.content)
        emulator.feed(content)
        WindowPanePreview(
            paneId = pane.paneId,
            left = pane.left,
            top = pane.top,
            width = pane.width,
            height = pane.height,
            cursorColumn = pane.cursorColumn,
            cursorRow = pane.cursorRow,
            active = pane.active,
            snapshot = emulator.snapshot()
        )
    }
)

/**
 * capture-pane separates rows with LF while a terminal LF does not return to column zero. Add CRs
 * for replay and drop only the final row separator so a full-height capture does not scroll once.
 */
private fun normalizePaneCapture(content: ByteArray): ByteArray {
    val end = if (content.lastOrNull() == '\n'.code.toByte()) content.size - 1 else content.size
    if (end <= 0) return ByteArray(0)
    val normalized = ByteArrayOutputStream(end + 32)
    for (index in 0 until end) {
        val byte = content[index]
        if (byte == '\n'.code.toByte() && (index == 0 || content[index - 1] != '\r'.code.toByte())) {
            normalized.write('\r'.code)
        }
        normalized.write(byte.toInt())
    }
    return normalized.toByteArray()
}
