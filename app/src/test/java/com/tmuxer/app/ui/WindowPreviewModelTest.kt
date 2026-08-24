package com.tmuxer.app.ui

import com.tmuxer.app.ssh.TmuxPaneCapture
import com.tmuxer.app.ssh.TmuxWindowCapture
import com.tmuxer.app.terminal.TerminalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WindowPreviewModelTest {
    @Test
    fun replaysCaptureRowsAtColumnZeroWithoutScrollingTheLastLine() {
        val capture = TmuxWindowCapture(
            windowId = "@4",
            columns = 10,
            rows = 2,
            panes = listOf(
                TmuxPaneCapture(
                    paneId = "%8",
                    left = 0,
                    top = 0,
                    width = 10,
                    height = 2,
                    cursorColumn = 6,
                    cursorRow = 1,
                    active = true,
                    content = "first\nsecond\n".toByteArray()
                )
            )
        )

        val snapshot = buildWindowTerminalPreview(capture, TerminalTheme.DARK)
            .panes.single().snapshot

        assertEquals("first", rowText(snapshot.cells, snapshot.columns, 0))
        assertEquals("second", rowText(snapshot.cells, snapshot.columns, 1))
    }

    @Test
    fun preservesAnsiColorsInThumbnailCells() {
        val capture = TmuxWindowCapture(
            windowId = "@5",
            columns = 8,
            rows = 1,
            panes = listOf(
                TmuxPaneCapture(
                    paneId = "%9",
                    left = 0,
                    top = 0,
                    width = 8,
                    height = 1,
                    cursorColumn = 3,
                    cursorRow = 0,
                    active = true,
                    content = "\u001B[31mred\u001B[0m".toByteArray()
                )
            )
        )

        val preview = buildWindowTerminalPreview(capture, TerminalTheme.DARK)
        val firstCell = preview.panes.single().snapshot.cells.first()

        assertEquals("r", firstCell.text)
        assertNotEquals(TerminalTheme.DARK.foregroundColor, firstCell.foreground)
    }

    private fun rowText(
        cells: Array<com.tmuxer.app.terminal.TerminalCell>,
        columns: Int,
        row: Int
    ): String = buildString {
        for (column in 0 until columns) {
            val cell = cells[row * columns + column]
            if (cell.width != 0) append(cell.text)
        }
    }.trimEnd()
}
