package com.tmuxer.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.tmuxer.app.R
import com.tmuxer.app.terminal.TerminalCell
import com.tmuxer.app.terminal.TerminalSnapshot
import kotlin.math.abs

private data class WindowPreviewPaints(
    val text: Paint,
    val background: Paint,
    val border: Paint,
    val regularTypeface: Typeface,
    val cjkTypeface: Typeface,
    val emojiTypeface: Typeface,
    val characterWidth: Float,
    val lineHeight: Float,
    val baselineOffset: Float
)

/** Read-only, ANSI-colored reconstruction of a tmux window, including split panes. */
@Composable
internal fun WindowTerminalPreviewCanvas(
    preview: WindowTerminalPreview,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textSize = with(density) { 11.sp.toPx() }
    val paints = remember(context, textSize, preview.theme) {
        val regular = ResourcesCompat.getFont(context, R.font.ubuntu_mono_regular) ?: Typeface.MONOSPACE
        val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = regular
            this.textSize = textSize
            color = preview.theme.foregroundColor
            isSubpixelText = true
            hinting = Paint.HINTING_ON
        }
        val metrics = text.fontMetrics
        val baseLineHeight = text.fontSpacing
        val lineHeight = baseLineHeight * 1.12f
        WindowPreviewPaints(
            text = text,
            background = Paint().apply { style = Paint.Style.FILL },
            border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE },
            regularTypeface = regular,
            cjkTypeface = runCatching {
                Typeface.createFromFile("/system/fonts/NotoSansCJK-Regular.ttc")
            }.getOrDefault(Typeface.DEFAULT),
            emojiTypeface = runCatching {
                Typeface.createFromFile("/system/fonts/NotoColorEmoji.ttf")
            }.getOrDefault(Typeface.DEFAULT),
            characterWidth = text.measureText("X").coerceAtLeast(1f),
            lineHeight = lineHeight,
            baselineOffset = -metrics.ascent + (lineHeight - baseLineHeight) / 2f
        )
    }

    Canvas(modifier = modifier) {
        drawRect(Color(preview.theme.backgroundColor))
        if (preview.columns <= 0 || preview.rows <= 0) return@Canvas

        val contentWidth = preview.columns * paints.characterWidth
        val contentHeight = preview.rows * paints.lineHeight
        if (contentWidth <= 0f || contentHeight <= 0f) return@Canvas
        val scale = size.width / contentWidth
        val scaledHeight = contentHeight * scale
        // Track tmux's real cursor instead of blindly bottom-aligning. A fresh shell often has its
        // prompt near the top with empty rows below; this keeps that useful region in frame while
        // still showing a little context beneath the current input line.
        val activePane = preview.panes.firstOrNull { it.active } ?: preview.panes.firstOrNull()
        val focusRow = activePane?.let { it.top + it.cursorRow + 0.5f } ?: preview.rows / 2f
        val focusedTop = size.height * 0.68f - focusRow * paints.lineHeight * scale
        val top = if (scaledHeight <= size.height) {
            (size.height - scaledHeight) / 2f
        } else {
            focusedTop.coerceIn(size.height - scaledHeight, 0f)
        }

        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.save()
            canvas.clipRect(0f, 0f, size.width, size.height)
            canvas.translate(0f, top)
            canvas.scale(scale, scale)
            preview.panes.forEach { pane ->
                val left = pane.left * paints.characterWidth
                val paneTop = pane.top * paints.lineHeight
                val right = left + pane.width * paints.characterWidth
                val bottom = paneTop + pane.height * paints.lineHeight

                paints.background.color = preview.theme.backgroundColor
                canvas.drawRect(left, paneTop, right, bottom, paints.background)
                canvas.save()
                canvas.clipRect(left, paneTop, right, bottom)
                canvas.translate(left, paneTop)
                drawPaneSnapshot(canvas, pane.snapshot, preview, paints)
                canvas.restore()

                if (preview.panes.size > 1 && pane.active) {
                    paints.border.color = preview.theme.cursorColor
                    paints.border.strokeWidth = 1.5f / scale.coerceAtLeast(0.01f)
                    canvas.drawRect(left, paneTop, right, bottom, paints.border)
                }
            }
            canvas.restore()
        }
    }
}

private fun drawPaneSnapshot(
    canvas: android.graphics.Canvas,
    snapshot: TerminalSnapshot,
    preview: WindowTerminalPreview,
    paints: WindowPreviewPaints
) {
    for (row in 0 until snapshot.rows) {
        val rowOffset = row * snapshot.columns
        val top = row * paints.lineHeight
        val baseline = top + paints.baselineOffset

        var column = 0
        while (column < snapshot.columns) {
            val start = column
            val first = snapshot.cells[rowOffset + column]
            val background = terminalCellBackground(first)
            column++
            while (column < snapshot.columns &&
                terminalCellBackground(snapshot.cells[rowOffset + column]) == background
            ) {
                column++
            }
            if (background != preview.theme.backgroundColor) {
                paints.background.color = background
                canvas.drawRect(
                    start * paints.characterWidth,
                    top,
                    column * paints.characterWidth + 0.5f,
                    top + paints.lineHeight + 0.5f,
                    paints.background
                )
            }
        }

        for (cellColumn in 0 until snapshot.columns) {
            val cell = snapshot.cells[rowOffset + cellColumn]
            if (cell.width == 0 || cell.text == " ") continue
            val foreground = if (cell.inverse) cell.background else cell.foreground
            val left = cellColumn * paints.characterWidth
            val expectedWidth = paints.characterWidth * cell.width.coerceAtLeast(1)
            val firstCodePoint = cell.text.codePointAt(0)
            paints.text.typeface = when {
                firstCodePoint >= 0x1F000 || cell.text.contains('\uFE0F') -> paints.emojiTypeface
                cell.width > 1 -> paints.cjkTypeface
                else -> paints.regularTypeface
            }
            paints.text.isFakeBoldText = cell.bold
            paints.text.color = foreground
            val measuredWidth = paints.text.measureText(cell.text)
            if (measuredWidth > 0f && abs(measuredWidth - expectedWidth) > 0.5f) {
                canvas.save()
                canvas.scale(expectedWidth / measuredWidth, 1f, left, baseline)
                canvas.drawText(cell.text, left, baseline, paints.text)
                canvas.restore()
            } else {
                canvas.drawText(cell.text, left, baseline, paints.text)
            }
            if (cell.underline) {
                paints.background.color = foreground
                canvas.drawRect(
                    left,
                    baseline + 1f,
                    left + expectedWidth,
                    baseline + 1.5f,
                    paints.background
                )
            }
        }
    }
}

private fun terminalCellBackground(cell: TerminalCell): Int =
    if (cell.inverse) cell.foreground else cell.background
