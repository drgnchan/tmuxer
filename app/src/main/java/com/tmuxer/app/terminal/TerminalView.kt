package com.tmuxer.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.os.Trace
import android.text.InputType
import android.text.Selection
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tmuxer.app.BuildConfig
import com.tmuxer.app.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val FLING_VELOCITY_SCALE = 0.48f
private const val TERMINAL_LINE_SPACING_MULTIPLIER = 1.15f
private const val RESIZE_SETTLE_DELAY_MILLIS = 64L
private const val PERF_TAG = "TmuxerPerf"
private const val MAX_DECODED_IMAGE_PIXELS = 8_000_000L
private const val MAX_DECODED_IMAGE_DIMENSION = 4_096
private const val TERMINAL_IMAGE_LINK_PREFIX = "tmuxer-image://"
private const val MAX_IME_CONTEXT_CHARS = 1_024

/** A decoded image retained only while its fullscreen preview is open. */
data class TerminalImagePreview(
    val imageId: Long,
    val bitmap: Bitmap,
    val encodedData: ByteArray
)

data class TerminalImageOpenRequest(
    val imageId: Long,
    val encodedData: ByteArray,
    val remotePath: String?,
    val mimeType: String?
)

private data class TerminalImageHitTarget(val bounds: RectF, val request: TerminalImageOpenRequest)
private data class TerminalTextSelection(
    val start: Int,
    val end: Int,
    val anchorStart: Int,
    val anchorEnd: Int
)

private enum class TerminalSelectionHandle { START, END }

private data class TerminalSelectionHandleGeometry(
    val x: Float,
    val anchorY: Float,
    val centerY: Float,
    val cellCenterX: Float,
    val cellCenterY: Float
)

internal fun terminalImagePathFromHyperlink(hyperlink: String?): String? {
    if (hyperlink?.startsWith(TERMINAL_IMAGE_LINK_PREFIX) != true) return null
    val encodedPath = hyperlink.removePrefix(TERMINAL_IMAGE_LINK_PREFIX)
    if (encodedPath.isEmpty() || encodedPath.length > 8_192) return null
    return runCatching {
        String(java.util.Base64.getUrlDecoder().decode(encodedPath), Charsets.UTF_8)
    }.getOrNull()?.takeIf {
        it.startsWith('/') && it.length <= 4_096 && '\u0000' !in it
    }
}

internal fun decodeTerminalImagePreview(data: ByteArray): Bitmap? {
    if (data.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > MAX_DECODED_IMAGE_DIMENSION ||
        bounds.outHeight / sampleSize > MAX_DECODED_IMAGE_DIMENSION ||
        bounds.outWidth.toLong() * bounds.outHeight / sampleSize / sampleSize > MAX_DECODED_IMAGE_PIXELS
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options) }.getOrNull()
}

internal fun terminalSelectionText(snapshot: TerminalSnapshot, selectionStart: Int, selectionEnd: Int): String {
    if (snapshot.cells.isEmpty()) return ""
    val first = minOf(selectionStart, selectionEnd).coerceIn(snapshot.cells.indices)
    val last = maxOf(selectionStart, selectionEnd).coerceIn(snapshot.cells.indices)
    val firstRow = first / snapshot.columns
    val lastRow = last / snapshot.columns
    val result = StringBuilder()
    for (row in firstRow..lastRow) {
        val startColumn = if (row == firstRow) first % snapshot.columns else 0
        val endColumn = if (row == lastRow) last % snapshot.columns else snapshot.columns - 1
        val line = StringBuilder()
        for (column in startColumn..endColumn) {
            val cell = snapshot.cells[row * snapshot.columns + column]
            if (cell.width != 0) line.append(cell.text)
        }
        result.append(line.toString().trimEnd())
        if (row != lastRow) result.append('\n')
    }
    return result.toString().trimEnd('\n')
}

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    // Keep compact Latin metrics while using Android's font fallback for CJK and emoji glyphs that
    // Ubuntu Mono does not contain.
    private val terminalTypeface =
        ResourcesCompat.getFont(context, R.font.ubuntu_mono_regular) ?: Typeface.MONOSPACE
    private val cjkTypeface by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { Typeface.createFromFile("/system/fonts/NotoSansCJK-Regular.ttc") }
            .getOrDefault(Typeface.DEFAULT)
    }
    private val emojiTypeface by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { Typeface.createFromFile("/system/fonts/NotoColorEmoji.ttf") }
            .getOrDefault(Typeface.DEFAULT)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = terminalTypeface
        textSize = 13f * resources.displayMetrics.scaledDensity
        color = TERMINAL_DEFAULT_FOREGROUND
        isSubpixelText = true
        hinting = Paint.HINTING_ON
    }
    private val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint().apply {
        color = TerminalTheme.DARK.cursorColor
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val selectionPaint = Paint().apply {
        color = 0x6665DDA5
        style = Paint.Style.FILL
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF65DDA5.toInt()
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageLinkBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val imageLinkTextPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 13f * resources.displayMetrics.scaledDensity
        isSubpixelText = true
    }
    private val imageHitTargets = ArrayList<TerminalImageHitTarget>()
    private val regularGlyphWidths = LruCache<String, Float>(512)
    private val boldGlyphWidths = LruCache<String, Float>(512)
    private val textRun = StringBuilder(256)
    private val loggedWideCodePoints = HashSet<Int>()
    private var renderedBitmap: Bitmap? = null
    private var spareBitmap: Bitmap? = null
    private var cachedSnapshot: TerminalSnapshot? = null
    @Volatile private var snapshotDirty = true
    @Volatile private var renderDirty = true
    private val horizontalPadding = 3f * density
    private val verticalPadding = 2f * density
    private val selectionHandleRadius = 6f * density
    private val selectionHandleStemLength = 4f * density
    private val selectionHandleTouchRadius = 24f * density
    // Termux uses a representative monospace glyph and the font's own line spacing.
    private val characterWidth = textPaint.measureText("X")
    private val fontMetrics = textPaint.fontMetrics
    private val baseLineHeight = textPaint.fontSpacing
    // CJK glyphs fill much more of the em box than Ubuntu Mono. Extra leading prevents adjacent
    // Chinese lines from visually touching while preserving a fixed terminal cell grid.
    private val lineHeight = ceil(baseLineHeight * TERMINAL_LINE_SPACING_MULTIPLIER)
    private val baselineOffset = -fontMetrics.ascent + (lineHeight - baseLineHeight) / 2f
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
    private val doubleTapSlop = viewConfiguration.scaledDoubleTapSlop.toFloat()
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity.toFloat()
    private val flingScroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrollRemainder = 0f
    private var scrolledWithFinger = false
    private var movedBeyondTouchSlop = false
    private var flingLastY = 0
    private var appliedColumns = 0
    private var appliedRows = 0
    private var pendingColumns = 0
    private var pendingRows = 0
    private var resizeSequenceStartedAt = 0L
    private var resizeEventCount = 0
    private var gestureStartedAt = 0L
    private var dragRows = 0
    private var flingStartedAt = 0L
    private var flingRows = 0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var keyboardTapSuppressedUntil = 0L
    private var selectingWithTouch = false
    private var selectionDraggedAfterLongPress = false
    private var draggedSelectionHandle: TerminalSelectionHandle? = null
    private var selectionHandleDragOffsetX = 0f
    private var selectionHandleDragOffsetY = 0f
    private var textSelection: TerminalTextSelection? = null
    private var selectionActionMode: ActionMode? = null
    private var renderSamples = 0
    private var renderTotalNanos = 0L
    private var renderMaxNanos = 0L

    private val applyPendingResize = Runnable {
        applyTerminalSize(pendingColumns, pendingRows)
    }

    private val beginLongPressSelection = Runnable {
        if (!movedBeyondTouchSlop && !scrolledWithFinger) {
            selectingWithTouch = performLongClick()
            selectionDraggedAfterLongPress = false
        }
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!flingScroller.computeScrollOffset()) {
                finishFling(cancelled = false)
                return
            }
            val currentY = flingScroller.currY
            val distanceY = currentY - flingLastY
            flingLastY = currentY
            dispatchScrollDistance(distanceY.toFloat(), lastX, lastY, SystemClock.uptimeMillis())
            if (!flingScroller.isFinished) {
                postOnAnimation(this)
            } else {
                finishFling(cancelled = false)
            }
        }
    }

    private val emulatorChangedCallback: () -> Unit = {
        snapshotDirty = true
        renderDirty = true
        postInvalidateOnAnimation()
    }

    var terminalTheme: TerminalTheme = TerminalTheme.DARK
        set(value) {
            if (field == value) return
            field = value
            cursorPaint.color = value.cursorColor
            setBackgroundColor(value.backgroundColor)
            renderDirty = true
            invalidate()
        }

    var emulator: TerminalEmulator? = null
        set(value) {
            if (field === value) return
            field?.onChanged = null
            field = value
            value?.onChanged = emulatorChangedCallback
            clearTextSelection()
            cachedSnapshot = null
            imageHitTargets.clear()
            snapshotDirty = true
            renderDirty = true
            appliedColumns = 0
            appliedRows = 0
            scheduleTerminalSize(width, height, immediate = true)
            invalidate()
        }

    var onInput: (String) -> Unit = {}
    var onTerminalResize: (columns: Int, rows: Int) -> Unit = { _, _ -> }
    var onImageClick: (TerminalImageOpenRequest) -> Unit = {}
    var onNotice: (String) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(terminalTheme.backgroundColor)
        contentDescription = "SSH 终端，双击打开键盘，长按选择文本"
        setOnLongClickListener { beginTextSelection(downX, downY) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val renderStarted = if (BuildConfig.DEBUG && (snapshotDirty || renderDirty)) {
            SystemClock.elapsedRealtimeNanos()
        } else {
            0L
        }
        val snapshot = if (snapshotDirty || cachedSnapshot == null) {
            emulator?.snapshot(cachedSnapshot)?.also {
                cachedSnapshot = it
                snapshotDirty = false
                renderDirty = true
            }
        } else {
            cachedSnapshot
        } ?: return

        val bitmap = renderedBitmap ?: return
        if (renderDirty) {
            bitmap.eraseColor(terminalTheme.backgroundColor)
            drawSnapshot(Canvas(bitmap), snapshot)
            renderDirty = false
            if (renderStarted != 0L) recordRenderSample(SystemClock.elapsedRealtimeNanos() - renderStarted)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        drawTextSelection(canvas, snapshot)
        drawTextSelectionHandles(canvas, snapshot)
    }

    private fun drawSnapshot(canvas: Canvas, snapshot: TerminalSnapshot) {
        imageHitTargets.clear()
        for (row in 0 until snapshot.rows) {
            val rowOffset = row * snapshot.columns
            val top = verticalPadding + row * lineHeight
            // Fractional font spacing can leave a one-pixel seam between adjacent background rows.
            val backgroundTop = floor(top)
            val backgroundBottom = ceil(top + lineHeight)
            val baseline = top + baselineOffset

            // Paint backgrounds as runs instead of issuing one draw call per terminal cell.
            var column = 0
            while (column < snapshot.columns) {
                val start = column
                val first = snapshot.cells[rowOffset + column]
                val background = if (first.inverse) first.foreground else first.background
                column++
                while (column < snapshot.columns) {
                    val next = snapshot.cells[rowOffset + column]
                    val nextBackground = if (next.inverse) next.foreground else next.background
                    if (nextBackground != background) break
                    column++
                }
                if (background != terminalTheme.backgroundColor) {
                    backgroundPaint.color = background
                    canvas.drawRect(
                        horizontalPadding + start * characterWidth,
                        backgroundTop,
                        horizontalPadding + column * characterWidth + 0.5f,
                        backgroundBottom,
                        backgroundPaint
                    )
                }
            }

            column = 0
            while (column < snapshot.columns) {
                val cell = snapshot.cells[rowOffset + column]
                if (cell.width == 0) {
                    column++
                    continue
                }
                if (cell.width > 1) {
                    drawWideGlyph(canvas, cell, column, baseline)
                    column += cell.width
                    continue
                }

                val start = column
                val imageLink = terminalImagePathFromHyperlink(cell.hyperlink) != null
                val foreground = if (imageLink) {
                    terminalTheme.cursorColor
                } else if (cell.inverse) {
                    cell.background
                } else {
                    cell.foreground
                }
                val bold = cell.bold
                val underline = cell.underline || imageLink
                var hasVisibleText = false
                textRun.clear()
                while (column < snapshot.columns) {
                    val next = snapshot.cells[rowOffset + column]
                    val nextImageLink = terminalImagePathFromHyperlink(next.hyperlink) != null
                    val nextForeground = if (nextImageLink) {
                        terminalTheme.cursorColor
                    } else if (next.inverse) {
                        next.background
                    } else {
                        next.foreground
                    }
                    if (next.width != 1 || nextForeground != foreground ||
                        next.bold != bold || (next.underline || nextImageLink) != underline ||
                        next.hyperlink != cell.hyperlink
                    ) {
                        break
                    }
                    textRun.append(next.text)
                    if (next.text != " ") hasVisibleText = true
                    column++
                }
                if (hasVisibleText) {
                    val left = horizontalPadding + start * characterWidth
                    val expectedWidth = (column - start) * characterWidth
                    textPaint.color = foreground
                    textPaint.typeface = terminalTypeface
                    textPaint.isFakeBoldText = bold
                    val measuredWidth = textPaint.measureText(textRun, 0, textRun.length)
                    if (measuredWidth > 0f && abs(measuredWidth - expectedWidth) > 0.5f) {
                        canvas.save()
                        canvas.scale(expectedWidth / measuredWidth, 1f, left, baseline)
                        canvas.drawText(textRun, 0, textRun.length, left, baseline, textPaint)
                        canvas.restore()
                    } else {
                        canvas.drawText(textRun, 0, textRun.length, left, baseline, textPaint)
                    }
                }
                if (underline) {
                    backgroundPaint.color = foreground
                    canvas.drawRect(
                        horizontalPadding + start * characterWidth,
                        baseline + density,
                        horizontalPadding + column * characterWidth,
                        baseline + 1.5f * density,
                        backgroundPaint
                    )
                }
            }
        }
        collectTerminalImageLinkTargets(snapshot)
        drawTerminalImages(canvas, snapshot)
        if (snapshot.cursorVisible && hasFocus()) {
            val left = horizontalPadding + snapshot.cursorColumn * characterWidth
            val top = verticalPadding + snapshot.cursorRow * lineHeight
            canvas.drawRect(left, top, left + characterWidth, top + lineHeight, cursorPaint)
        }
    }

    private fun drawTextSelection(canvas: Canvas, snapshot: TerminalSnapshot) {
        val selection = textSelection ?: return
        if (snapshot.cells.isEmpty()) return
        val first = minOf(selection.start, selection.end).coerceIn(snapshot.cells.indices)
        val last = maxOf(selection.start, selection.end).coerceIn(snapshot.cells.indices)
        val firstRow = first / snapshot.columns
        val lastRow = last / snapshot.columns
        for (row in firstRow..lastRow) {
            val startColumn = if (row == firstRow) first % snapshot.columns else 0
            val endColumn = if (row == lastRow) last % snapshot.columns else snapshot.columns - 1
            val left = horizontalPadding + startColumn * characterWidth
            val top = verticalPadding + row * lineHeight
            canvas.drawRect(
                left,
                top,
                horizontalPadding + (endColumn + 1) * characterWidth,
                top + lineHeight,
                selectionPaint
            )
        }
    }

    private fun drawTextSelectionHandles(canvas: Canvas, snapshot: TerminalSnapshot) {
        val selection = textSelection ?: return
        if (snapshot.cells.isEmpty()) return
        val first = minOf(selection.start, selection.end).coerceIn(snapshot.cells.indices)
        val last = maxOf(selection.start, selection.end).coerceIn(snapshot.cells.indices)
        drawTextSelectionHandle(canvas, selectionHandleGeometry(first, false, snapshot))
        drawTextSelectionHandle(canvas, selectionHandleGeometry(last, true, snapshot))
    }

    private fun drawTextSelectionHandle(
        canvas: Canvas,
        geometry: TerminalSelectionHandleGeometry
    ) {
        selectionHandlePaint.strokeWidth = 2f * density
        canvas.drawLine(
            geometry.x,
            geometry.anchorY,
            geometry.x,
            geometry.centerY,
            selectionHandlePaint
        )
        canvas.drawCircle(
            geometry.x,
            geometry.centerY,
            selectionHandleRadius,
            selectionHandlePaint
        )
    }

    private fun drawWideGlyph(canvas: Canvas, cell: TerminalCell, column: Int, baseline: Float) {
        if (cell.text == " ") return
        val imageLink = terminalImagePathFromHyperlink(cell.hyperlink) != null
        val foreground = if (imageLink) {
            terminalTheme.cursorColor
        } else if (cell.inverse) {
            cell.background
        } else {
            cell.foreground
        }
        val left = horizontalPadding + column * characterWidth
        val glyphWidth = characterWidth * cell.width
        textPaint.color = foreground
        val firstCodePoint = cell.text.codePointAt(0)
        textPaint.typeface = if (firstCodePoint >= 0x1F000 || cell.text.contains('\uFE0F')) {
            emojiTypeface
        } else {
            cjkTypeface
        }
        if (BuildConfig.DEBUG && loggedWideCodePoints.add(firstCodePoint)) {
            perfLog(
                "wide-glyph codePoint=U+${firstCodePoint.toString(16).uppercase()} " +
                    "hasGlyph=${textPaint.hasGlyph(cell.text)} width=${cell.width}"
            )
        }
        textPaint.isFakeBoldText = cell.bold
        val widthCache = if (cell.bold) boldGlyphWidths else regularGlyphWidths
        val measuredWidth = widthCache.get(cell.text) ?: textPaint.measureText(cell.text).also {
            widthCache.put(cell.text, it)
        }
        if (measuredWidth > 0f && abs(measuredWidth - glyphWidth) > 0.5f) {
            canvas.save()
            canvas.scale(glyphWidth / measuredWidth, 1f, left, baseline)
            canvas.drawText(cell.text, left, baseline, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(cell.text, left, baseline, textPaint)
        }
        if (cell.underline || imageLink) {
            backgroundPaint.color = foreground
            canvas.drawRect(
                left,
                baseline + density,
                left + glyphWidth,
                baseline + 1.5f * density,
                backgroundPaint
            )
        }
    }

    private fun collectTerminalImageLinkTargets(snapshot: TerminalSnapshot) {
        for (row in 0 until snapshot.rows) {
            var column = 0
            while (column < snapshot.columns) {
                val hyperlink = snapshot.cells[row * snapshot.columns + column].hyperlink
                val remotePath = terminalImagePathFromHyperlink(hyperlink)
                if (remotePath == null) {
                    column++
                    continue
                }
                val start = column
                while (
                    column < snapshot.columns &&
                    snapshot.cells[row * snapshot.columns + column].hyperlink == hyperlink
                ) {
                    column++
                }
                imageHitTargets += TerminalImageHitTarget(
                    bounds = RectF(
                        horizontalPadding + start * characterWidth,
                        verticalPadding + row * lineHeight,
                        horizontalPadding + column * characterWidth,
                        verticalPadding + (row + 1) * lineHeight
                    ),
                    request = TerminalImageOpenRequest(
                        imageId = hyperlink.hashCode().toLong(),
                        encodedData = ByteArray(0),
                        remotePath = remotePath,
                        mimeType = null
                    )
                )
            }
        }
    }

    private fun drawTerminalImages(canvas: Canvas, snapshot: TerminalSnapshot) {
        snapshot.images.forEach { placement ->
            if (placement.row >= snapshot.rows || placement.column >= snapshot.columns) return@forEach
            val left = horizontalPadding + placement.column * characterWidth + placement.offsetX
            val top = verticalPadding + placement.row * lineHeight + placement.offsetY
            val destination = RectF(
                left,
                top,
                left + placement.columns * characterWidth,
                top + placement.rows * lineHeight
            )
            val visibleBounds = RectF(destination)
            if (!visibleBounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) return@forEach

            drawImageLink(canvas, visibleBounds)
            imageHitTargets += TerminalImageHitTarget(
                bounds = visibleBounds,
                request = TerminalImageOpenRequest(
                    imageId = placement.imageId,
                    encodedData = placement.encodedData,
                    remotePath = placement.remotePath,
                    mimeType = placement.mimeType
                )
            )
        }
    }

    private fun drawImageLink(canvas: Canvas, bounds: RectF) {
        val label = "图片 · 点击预览"
        val horizontalInset = 6f * density
        val verticalInset = 3f * density
        imageLinkTextPaint.color = terminalTheme.cursorColor
        val desiredWidth = imageLinkTextPaint.measureText(label) + horizontalInset * 2
        val chipWidth = desiredWidth.coerceAtMost(bounds.width())
        val chipHeight = (imageLinkTextPaint.fontSpacing + verticalInset * 2).coerceAtMost(bounds.height())
        if (chipWidth <= 2f || chipHeight <= 2f) return

        val chip = RectF(
            bounds.centerX() - chipWidth / 2f,
            bounds.centerY() - chipHeight / 2f,
            bounds.centerX() + chipWidth / 2f,
            bounds.centerY() + chipHeight / 2f
        )
        imageLinkBackgroundPaint.color = if (terminalTheme == TerminalTheme.DARK) {
            0xE61A2A25.toInt()
        } else {
            0xE6E3F3EB.toInt()
        }
        canvas.drawRoundRect(chip, 6f * density, 6f * density, imageLinkBackgroundPaint)

        val metrics = imageLinkTextPaint.fontMetrics
        val baseline = chip.centerY() - (metrics.ascent + metrics.descent) / 2f
        val textLeft = chip.centerX() - imageLinkTextPaint.measureText(label) / 2f
        canvas.save()
        canvas.clipRect(chip)
        canvas.drawText(label, textLeft, baseline, imageLinkTextPaint)
        canvas.drawRect(
            textLeft,
            baseline + density,
            textLeft + imageLinkTextPaint.measureText(label),
            baseline + 1.5f * density,
            imageLinkTextPaint
        )
        canvas.restore()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        renderDirty = true
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // IME insets animate the height on every frame. Keep drawing the existing grid (the canvas
        // clips it naturally) and resize only after the animation settles, instead of reallocating
        // both screen buffers and rebuilding the transcript dozens of times.
        if (oldHeight > 0 && height != oldHeight && width == oldWidth) {
            if (resizeEventCount == 0) {
                resizeSequenceStartedAt = SystemClock.uptimeMillis()
                perfLog("resize-sequence-start height=$oldHeight->$height")
            }
            resizeEventCount++
        }
        val immediate = oldWidth == 0 || oldHeight == 0 || width != oldWidth
        scheduleTerminalSize(width, height, immediate)
    }

    private fun scheduleTerminalSize(width: Int, height: Int, immediate: Boolean) {
        if (width <= 0 || height <= 0 || emulator == null) return
        val columns = floor((width - horizontalPadding * 2) / characterWidth).toInt().coerceAtLeast(20)
        val rows = floor((height - verticalPadding * 2) / lineHeight).toInt().coerceAtLeast(5)
        pendingColumns = columns
        pendingRows = rows
        removeCallbacks(applyPendingResize)
        if (columns == appliedColumns && rows == appliedRows) return
        if (immediate || columns != appliedColumns) {
            applyTerminalSize(columns, rows)
        } else {
            postDelayed(applyPendingResize, RESIZE_SETTLE_DELAY_MILLIS)
        }
    }

    private fun applyTerminalSize(columns: Int, rows: Int) {
        removeCallbacks(applyPendingResize)
        if (columns <= 0 || rows <= 0 || (columns == appliedColumns && rows == appliedRows)) return
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("tmuxer-terminal-resize")
        try {
            emulator?.resize(columns, rows) ?: return
            prepareRenderBitmap(rows)
        } finally {
            Trace.endSection()
        }
        val oldGrid = "${appliedColumns}x${appliedRows}"
        appliedColumns = columns
        appliedRows = rows
        onTerminalResize(columns, rows)
        if (resizeEventCount > 0) {
            val duration = SystemClock.uptimeMillis() - resizeSequenceStartedAt
            val applyMillis = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0
            perfLog(
                "resize-sequence-settled events=$resizeEventCount durationMs=$duration " +
                    "grid=$oldGrid->${columns}x$rows applyMs=${"%.2f".format(applyMillis)}"
            )
            resizeEventCount = 0
            resizeSequenceStartedAt = 0L
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                removeCallbacks(beginLongPressSelection)
                val touchedSelectionHandle = selectionHandleAt(event.x, event.y)
                if (textSelection != null && touchedSelectionHandle == null) {
                    clearTextSelection()
                    lastTapTime = 0L
                }
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                gestureStartedAt = event.eventTime
                dragRows = 0
                scrollRemainder = 0f
                scrolledWithFinger = false
                movedBeyondTouchSlop = false
                selectionDraggedAfterLongPress = false
                draggedSelectionHandle = touchedSelectionHandle
                selectingWithTouch = touchedSelectionHandle != null
                val handleGeometry = touchedSelectionHandle?.let { handle ->
                    cachedSnapshot?.let { snapshot -> selectionHandleGeometry(handle, snapshot) }
                }
                if (handleGeometry != null) {
                    selectionHandleDragOffsetX = handleGeometry.cellCenterX - event.x
                    selectionHandleDragOffsetY = handleGeometry.cellCenterY - event.y
                    lastTapTime = 0L
                } else {
                    selectionHandleDragOffsetX = 0f
                    selectionHandleDragOffsetY = 0f
                    postDelayed(beginLongPressSelection, ViewConfiguration.getLongPressTimeout().toLong())
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (selectingWithTouch) {
                    val handle = draggedSelectionHandle
                    if (handle == null) {
                        if (
                            selectionDraggedAfterLongPress ||
                            abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                        ) {
                            selectionDraggedAfterLongPress = true
                            updateTextSelection(event.x, event.y)
                        }
                    } else {
                        updateSelectionHandle(
                            handle,
                            event.x + selectionHandleDragOffsetX,
                            event.y + selectionHandleDragOffsetY
                        )
                    }
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                val totalX = event.x - downX
                val totalY = event.y - downY
                if (abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                    movedBeyondTouchSlop = true
                    removeCallbacks(beginLongPressSelection)
                }
                val startedScrolling = !scrolledWithFinger &&
                    abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)
                if (startedScrolling) scrolledWithFinger = true
                if (scrolledWithFinger) {
                    // Keep the movement accumulated before crossing touch slop. Discarding it made
                    // the terminal wait almost two text rows before reacting to a finger drag.
                    val distanceY = if (startedScrolling) downY - event.y else lastY - event.y
                    dispatchScrollDistance(distanceY, event.x, event.y, event.eventTime)
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(beginLongPressSelection)
                velocityTracker?.addMovement(event)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (selectingWithTouch) {
                    val handle = draggedSelectionHandle
                    if (handle == null) {
                        if (selectionDraggedAfterLongPress) updateTextSelection(event.x, event.y)
                    } else {
                        updateSelectionHandle(
                            handle,
                            event.x + selectionHandleDragOffsetX,
                            event.y + selectionHandleDragOffsetY
                        )
                    }
                    selectingWithTouch = false
                    selectionDraggedAfterLongPress = false
                    draggedSelectionHandle = null
                } else if (scrolledWithFinger) {
                    velocityTracker?.computeCurrentVelocity(1000, maximumFlingVelocity)
                    val fingerVelocityY = velocityTracker?.yVelocity ?: 0f
                    perfLog(
                        "scroll-release durationMs=${event.eventTime - gestureStartedAt} " +
                            "dragRows=$dragRows velocityY=${fingerVelocityY.toInt()}"
                    )
                    if (abs(fingerVelocityY) >= minimumFlingVelocity) {
                        startFling(-fingerVelocityY)
                    } else {
                        scrollRemainder = 0f
                    }
                } else if (!movedBeyondTouchSlop) {
                    val image = imageHitTargets.lastOrNull { it.bounds.contains(event.x, event.y) }
                    if (image != null) {
                        suppressKeyboardDoubleTap()
                        onImageClick(image.request)
                    } else if (event.eventTime < keyboardTapSuppressedUntil) {
                        lastTapTime = 0L
                    } else {
                        val elapsed = event.eventTime - lastTapTime
                        val deltaX = event.x - lastTapX
                        val deltaY = event.y - lastTapY
                        val isDoubleTap = lastTapTime != 0L &&
                            elapsed <= ViewConfiguration.getDoubleTapTimeout() &&
                            deltaX * deltaX + deltaY * deltaY <= doubleTapSlop * doubleTapSlop
                        if (isDoubleTap) {
                            lastTapTime = 0L
                            performClick()
                        } else {
                            lastTapTime = event.eventTime
                            lastTapX = event.x
                            lastTapY = event.y
                        }
                    }
                }
                recycleVelocityTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(beginLongPressSelection)
                parent?.requestDisallowInterceptTouchEvent(false)
                recycleVelocityTracker()
                selectingWithTouch = false
                selectionDraggedAfterLongPress = false
                draggedSelectionHandle = null
                scrolledWithFinger = false
                movedBeyondTouchSlop = false
                scrollRemainder = 0f
            }
        }
        return true
    }

    private fun beginTextSelection(x: Float, y: Float): Boolean {
        val snapshot = cachedSnapshot ?: return false
        var index = cellIndexAt(x, y, snapshot)
        if (snapshot.cells[index].width == 0 && index > 0) index--
        if (!isWordCell(snapshot.cells[index])) return false
        val rowStart = index / snapshot.columns * snapshot.columns
        val rowEnd = rowStart + snapshot.columns - 1
        var wordStart = index
        var wordEnd = index
        while (wordStart > rowStart && isWordCell(snapshot.cells[wordStart - 1])) wordStart--
        while (wordEnd < rowEnd && isWordCell(snapshot.cells[wordEnd + 1])) wordEnd++
        textSelection = TerminalTextSelection(wordStart, wordEnd, wordStart, wordEnd)
        invalidate()
        showSelectionActionMode()
        return true
    }

    private fun updateTextSelection(x: Float, y: Float) {
        val snapshot = cachedSnapshot ?: return
        val current = textSelection ?: return
        var index = cellIndexAt(x, y, snapshot)
        if (snapshot.cells[index].width == 0 && index > 0) index--
        textSelection = if (index < current.anchorStart) {
            current.copy(start = index, end = current.anchorEnd)
        } else {
            current.copy(start = current.anchorStart, end = index)
        }
        invalidate()
    }

    private fun updateSelectionHandle(handle: TerminalSelectionHandle, x: Float, y: Float) {
        val snapshot = cachedSnapshot?.takeIf { it.cells.isNotEmpty() } ?: return
        val current = textSelection ?: return
        var index = cellIndexAt(x, y, snapshot)
        if (handle == TerminalSelectionHandle.START && snapshot.cells[index].width == 0 && index > 0) {
            index--
        }
        when (handle) {
            TerminalSelectionHandle.START -> {
                if (index <= current.end) {
                    textSelection = current.copy(start = index)
                } else {
                    textSelection = current.copy(start = current.end, end = index)
                    draggedSelectionHandle = TerminalSelectionHandle.END
                }
            }
            TerminalSelectionHandle.END -> {
                if (index >= current.start) {
                    textSelection = current.copy(end = index)
                } else {
                    textSelection = current.copy(start = index, end = current.start)
                    draggedSelectionHandle = TerminalSelectionHandle.START
                }
            }
        }
        invalidate()
    }

    private fun selectionHandleAt(x: Float, y: Float): TerminalSelectionHandle? {
        val snapshot = cachedSnapshot?.takeIf { it.cells.isNotEmpty() } ?: return null
        val start = selectionHandleGeometry(TerminalSelectionHandle.START, snapshot) ?: return null
        val end = selectionHandleGeometry(TerminalSelectionHandle.END, snapshot) ?: return null
        val touchRadiusSquared = selectionHandleTouchRadius * selectionHandleTouchRadius
        val startDistance = (x - start.x) * (x - start.x) +
            (y - start.centerY) * (y - start.centerY)
        val endDistance = (x - end.x) * (x - end.x) +
            (y - end.centerY) * (y - end.centerY)
        return when {
            startDistance > touchRadiusSquared && endDistance > touchRadiusSquared -> null
            startDistance <= endDistance -> TerminalSelectionHandle.START
            else -> TerminalSelectionHandle.END
        }
    }

    private fun selectionHandleGeometry(
        handle: TerminalSelectionHandle,
        snapshot: TerminalSnapshot
    ): TerminalSelectionHandleGeometry? {
        val selection = textSelection ?: return null
        val index = when (handle) {
            TerminalSelectionHandle.START -> minOf(selection.start, selection.end)
            TerminalSelectionHandle.END -> maxOf(selection.start, selection.end)
        }.coerceIn(snapshot.cells.indices)
        return selectionHandleGeometry(index, handle == TerminalSelectionHandle.END, snapshot)
    }

    private fun selectionHandleGeometry(
        index: Int,
        isEnd: Boolean,
        snapshot: TerminalSnapshot
    ): TerminalSelectionHandleGeometry {
        val safeIndex = index.coerceIn(snapshot.cells.indices)
        val row = safeIndex / snapshot.columns
        val column = safeIndex % snapshot.columns
        val rowTop = verticalPadding + row * lineHeight
        val rowBottom = rowTop + lineHeight
        val drawBelow = rowBottom + selectionHandleStemLength + selectionHandleRadius * 2f <= height
        val anchorY = if (drawBelow) rowBottom else rowTop
        val centerY = if (drawBelow) {
            anchorY + selectionHandleStemLength + selectionHandleRadius
        } else {
            anchorY - selectionHandleStemLength - selectionHandleRadius
        }
        return TerminalSelectionHandleGeometry(
            x = horizontalPadding + (column + if (isEnd) 1 else 0) * characterWidth,
            anchorY = anchorY,
            centerY = centerY,
            cellCenterX = horizontalPadding + (column + 0.5f) * characterWidth,
            cellCenterY = rowTop + lineHeight / 2f
        )
    }

    private fun cellIndexAt(x: Float, y: Float, snapshot: TerminalSnapshot): Int {
        val column = floor((x - horizontalPadding) / characterWidth).toInt()
            .coerceIn(0, snapshot.columns - 1)
        val row = floor((y - verticalPadding) / lineHeight).toInt()
            .coerceIn(0, snapshot.rows - 1)
        return row * snapshot.columns + column
    }

    private fun isWordCell(cell: TerminalCell): Boolean =
        cell.width == 0 || cell.text.any { !it.isWhitespace() }

    private fun showSelectionActionMode() {
        selectionActionMode?.finish()
        selectionActionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 2, 1, "全选")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
                1 -> {
                    copySelectedText()
                    mode.finish()
                    true
                }
                2 -> {
                    selectAllVisibleText()
                    true
                }
                else -> false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (selectionActionMode === mode) selectionActionMode = null
                textSelection = null
                invalidate()
            }
        }, ActionMode.TYPE_FLOATING)
    }

    private fun selectAllVisibleText() {
        val snapshot = cachedSnapshot ?: return
        val first = snapshot.cells.indexOfFirst(::isWordCell).takeIf { it >= 0 } ?: return
        val last = snapshot.cells.indexOfLast(::isWordCell).takeIf { it >= first } ?: return
        textSelection = TerminalTextSelection(first, last, first, last)
        invalidate()
    }

    private fun copySelectedText() {
        val snapshot = cachedSnapshot ?: return
        val selection = textSelection ?: return
        val text = terminalSelectionText(snapshot, selection.start, selection.end)
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("终端文本", text))
        onNotice("已复制终端文本")
    }

    private fun clearTextSelection(finishActionMode: Boolean = true) {
        textSelection = null
        selectingWithTouch = false
        selectionDraggedAfterLongPress = false
        draggedSelectionHandle = null
        invalidate()
        if (finishActionMode) {
            val mode = selectionActionMode
            selectionActionMode = null
            mode?.finish()
        }
    }

    private fun dispatchScrollDistance(distanceY: Float, x: Float, y: Float, eventTime: Long) {
        // Positive distance means the finger moved up, matching a wheel-down event.
        scrollRemainder += distanceY
        val rowsDown = (scrollRemainder / lineHeight).toInt()
        if (rowsDown == 0) return
        val column = floor((x - horizontalPadding) / characterWidth).toInt() + 1
        val row = floor((y - verticalPadding) / lineHeight).toInt() + 1
        emulator?.scroll(rowsDown, column, row)
        if (flingStartedAt != 0L) {
            flingRows += abs(rowsDown)
        } else {
            if (dragRows == 0) {
                perfLog(
                    "scroll-first-step gestureMs=${eventTime - gestureStartedAt} " +
                        "eventLagMs=${SystemClock.uptimeMillis() - eventTime} " +
                        "distancePx=${abs(scrollRemainder).toInt()} rows=${abs(rowsDown)}"
                )
            }
            dragRows += abs(rowsDown)
        }
        scrollRemainder -= rowsDown * lineHeight
    }

    private fun prepareRenderBitmap(rows: Int) {
        val bitmapWidth = width.coerceAtLeast(1)
        val bitmapHeight = ceil(verticalPadding * 2 + rows * lineHeight).toInt().coerceAtLeast(1)
        val current = renderedBitmap
        if (current?.width == bitmapWidth && current.height == bitmapHeight) return
        val reusable = spareBitmap?.takeIf {
            it.width == bitmapWidth && it.height == bitmapHeight
        }
        spareBitmap = current
        renderedBitmap = reusable ?: Bitmap.createBitmap(
            bitmapWidth,
            bitmapHeight,
            Bitmap.Config.ARGB_8888
        )
        renderDirty = true
    }

    private fun startFling(scrollVelocityY: Float) {
        flingLastY = 0
        flingRows = 0
        flingStartedAt = SystemClock.uptimeMillis()
        // Mouse-aware TUIs need network wheel events, so keep their momentum shorter than local
        // scrollback while still allowing a natural coast after a quick swipe.
        val remoteMouse = emulator?.isMouseTrackingActive() == true
        val limit = if (remoteMouse) height * 3 / 4 else height * 3
        perfLog(
            "fling-start velocityY=${scrollVelocityY.toInt()} limitPx=$limit remoteMouse=$remoteMouse"
        )
        flingScroller.fling(
            0,
            0,
            0,
            (scrollVelocityY * FLING_VELOCITY_SCALE).toInt(),
            0,
            0,
            -limit.coerceAtLeast(1),
            limit.coerceAtLeast(1)
        )
        removeCallbacks(flingStep)
        postOnAnimation(flingStep)
    }

    private fun stopFling() {
        removeCallbacks(flingStep)
        if (!flingScroller.isFinished) flingScroller.abortAnimation()
        if (flingStartedAt != 0L) finishFling(cancelled = true)
    }

    private fun finishFling(cancelled: Boolean) {
        if (flingStartedAt == 0L) return
        perfLog(
            "fling-end durationMs=${SystemClock.uptimeMillis() - flingStartedAt} " +
                "rows=$flingRows cancelled=$cancelled"
        )
        flingStartedAt = 0L
        flingRows = 0
        scrollRemainder = 0f
    }

    private fun recordRenderSample(durationNanos: Long) {
        renderSamples++
        renderTotalNanos += durationNanos
        renderMaxNanos = maxOf(renderMaxNanos, durationNanos)
        if (renderSamples >= 30) {
            perfLog(
                "terminal-render samples=$renderSamples " +
                    "avgMs=${"%.2f".format(renderTotalNanos / renderSamples / 1_000_000.0)} " +
                    "maxMs=${"%.2f".format(renderMaxNanos / 1_000_000.0)}"
            )
            renderSamples = 0
            renderTotalNanos = 0L
            renderMaxNanos = 0L
        }
    }

    private fun perfLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(PERF_TAG, message)
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        stopFling()
        if (emulator?.onChanged === emulatorChangedCallback) emulator?.onChanged = null
        removeCallbacks(applyPendingResize)
        removeCallbacks(beginLongPressSelection)
        clearTextSelection()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    fun pasteClipboard(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return false
        onInput(text)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    /**
     * Keeps a tap used to open or dismiss an overlay from becoming one half of the terminal's
     * keyboard double-tap gesture. Dialog dismissal can return its final touch event to this view.
     */
    internal fun suppressKeyboardDoubleTap() {
        lastTapTime = 0L
        keyboardTapSuppressedUntil = SystemClock.uptimeMillis() +
            ViewConfiguration.getDoubleTapTimeout()
    }

    fun toggleKeyboard() {
        val keyboardVisible = ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (keyboardVisible) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(windowToken, 0)
        } else {
            showKeyboard()
        }
    }

    fun showKeyboard() {
        requestFocus()
        post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        showKeyboard()
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // VISIBLE_PASSWORD triggers Xiaomi's separate secure keyboard, which is slow to start and
        // makes repeated terminal keyboard toggles feel like a full-screen transition. Normal text
        // with learning/suggestions disabled keeps the user's regular IME without storing commands.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_NORMAL or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return TerminalInputConnection()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        keySequence(keyCode, event)?.let {
            onInput(it)
            return true
        }
        val unicode = event.unicodeChar
        if (unicode > 0) {
            val char = unicode.toChar()
            onInput(
                if (event.isCtrlPressed) ((char.code and 0x1F).toChar()).toString()
                else char.toString()
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun keySequence(keyCode: Int, event: KeyEvent): String? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
        KeyEvent.KEYCODE_DEL -> "\u007F"
        KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~"
        KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) "\u001B[Z" else "\t"
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> if (keyCode == KeyEvent.KEYCODE_ESCAPE) "\u001B" else null
        KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
        KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H"
        KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
        KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
        KeyEvent.KEYCODE_F1 -> "\u001BOP"
        KeyEvent.KEYCODE_F2 -> "\u001BOQ"
        KeyEvent.KEYCODE_F3 -> "\u001BOR"
        KeyEvent.KEYCODE_F4 -> "\u001BOS"
        else -> null
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, true) {
        // Keep committed text in this private Editable as IME-only context. Clearing it after every
        // character made the terminal and keyboard disagree: backspace changed the terminal while
        // candidate text stayed unchanged, and the empty context kept one-shot Shift enabled.
        private val inputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        private var batchEditDepth = 0
        private var imeStateUpdatePending = false

        override fun beginBatchEdit(): Boolean {
            batchEditDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchEditDepth > 0) batchEditDepth--
            if (batchEditDepth == 0 && imeStateUpdatePending) reportImeState()
            return batchEditDepth > 0
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val result = super.setComposingText(text, newCursorPosition)
            scheduleImeStateUpdate()
            return result
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            val result = super.setComposingRegion(start, end)
            scheduleImeStateUpdate()
            return result
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            val result = super.setSelection(start, end)
            scheduleImeStateUpdate()
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val result = super.commitText(text, newCursorPosition)
            text?.takeIf { it.isNotEmpty() }?.let { sendCommittedText(it) }
            if (text?.any { it == '\n' || it == '\r' } == true) resetEditableContext()
            else {
                trimEditableContext()
                scheduleImeStateUpdate()
            }
            return result
        }

        override fun finishComposingText(): Boolean {
            val content = editable
            val composingStart = content?.let(BaseInputConnection::getComposingSpanStart) ?: -1
            val composingEnd = content?.let(BaseInputConnection::getComposingSpanEnd) ?: -1
            val committedComposition = if (
                content != null && composingStart >= 0 && composingEnd > composingStart
            ) {
                content.subSequence(composingStart, composingEnd).toString()
            } else {
                ""
            }

            val result = super.finishComposingText()
            if (committedComposition.isNotEmpty()) sendCommittedText(committedComposition)
            trimEditableContext()
            scheduleImeStateUpdate()
            return result
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val safeBeforeLength = beforeLength.coerceAtLeast(0)
            val safeAfterLength = afterLength.coerceAtLeast(0)
            val content = editable
            val composingStart = content?.let(BaseInputConnection::getComposingSpanStart) ?: -1
            val composingEnd = content?.let(BaseInputConnection::getComposingSpanEnd) ?: -1
            val cursor = content?.let(Selection::getSelectionStart)?.coerceAtLeast(0) ?: 0
            val deleteStart = (cursor - safeBeforeLength).coerceAtLeast(0)
            val unsentComposingCharacters = if (
                composingStart >= 0 && composingEnd > composingStart
            ) {
                (minOf(cursor, composingEnd) - maxOf(deleteStart, composingStart)).coerceAtLeast(0)
            } else {
                0
            }

            repeat((safeBeforeLength - unsentComposingCharacters).coerceAtLeast(0)) {
                onInput("\u007F")
            }
            repeat(safeAfterLength) { onInput("\u001B[3~") }
            val result = super.deleteSurroundingText(safeBeforeLength, safeAfterLength)
            scheduleImeStateUpdate()
            return result
        }

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true

            if (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                finishComposingText()
            }
            val handled = this@TerminalView.dispatchKeyEvent(event)
            if (handled) mirrorKeyEventInEditable(event)
            return handled
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            finishComposingText()
            onInput("\r")
            resetEditableContext()
            return true
        }

        private fun sendCommittedText(text: CharSequence) {
            onInput(text.toString().replace("\n", "\r"))
        }

        private fun mirrorKeyEventInEditable(event: KeyEvent) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> resetEditableContext()
                KeyEvent.KEYCODE_DEL -> {
                    super.deleteSurroundingText(1, 0)
                    scheduleImeStateUpdate()
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    super.deleteSurroundingText(0, 1)
                    scheduleImeStateUpdate()
                }
                else -> {
                    val unicode = event.unicodeChar
                    if (unicode > 0 && !event.isCtrlPressed) {
                        super.commitText(String(Character.toChars(unicode)), 1)
                        trimEditableContext()
                        scheduleImeStateUpdate()
                    }
                }
            }
        }

        private fun trimEditableContext() {
            val content = editable ?: return
            val composingStart = BaseInputConnection.getComposingSpanStart(content)
            val removableLength = if (composingStart >= 0) composingStart else content.length
            val overflow = (content.length - MAX_IME_CONTEXT_CHARS).coerceAtMost(removableLength)
            if (overflow > 0) content.delete(0, overflow)
        }

        private fun resetEditableContext() {
            val content = editable ?: return
            BaseInputConnection.removeComposingSpans(content)
            content.clear()
            Selection.setSelection(content, 0)
            scheduleImeStateUpdate()
        }

        private fun scheduleImeStateUpdate() {
            if (batchEditDepth > 0) imeStateUpdatePending = true
            else reportImeState()
        }

        private fun reportImeState() {
            imeStateUpdatePending = false
            val content = editable ?: return
            val selectionStart = Selection.getSelectionStart(content).coerceAtLeast(0)
            val selectionEnd = Selection.getSelectionEnd(content).coerceAtLeast(0)
            inputMethodManager.updateSelection(
                this@TerminalView,
                selectionStart,
                selectionEnd,
                BaseInputConnection.getComposingSpanStart(content),
                BaseInputConnection.getComposingSpanEnd(content)
            )
        }
    }
}
