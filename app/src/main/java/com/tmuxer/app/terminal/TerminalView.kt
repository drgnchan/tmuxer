package com.tmuxer.app.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.os.Trace
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.KeyEvent
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
import java.util.concurrent.Executors
import com.tmuxer.app.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val FLING_VELOCITY_SCALE = 0.48f
private const val RESIZE_SETTLE_DELAY_MILLIS = 64L
private const val PERF_TAG = "TmuxerPerf"
private const val MAX_DECODED_IMAGE_PIXELS = 8_000_000L
private const val MAX_DECODED_IMAGE_DIMENSION = 4_096

/** A decoded inline image retained by the terminal while its fullscreen preview is open. */
data class TerminalImagePreview(
    val imageId: Long,
    val bitmap: Bitmap,
    val encodedData: ByteArray
)

private data class TerminalImageCacheKey(val imageId: Long, val generation: Long)
private data class DecodedTerminalImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int
)
private data class TerminalImageHitTarget(val bounds: RectF, val preview: TerminalImagePreview)

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
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageBitmapCache = object : LruCache<TerminalImageCacheKey, DecodedTerminalImage>(32 * 1024) {
        override fun sizeOf(key: TerminalImageCacheKey, value: DecodedTerminalImage): Int =
            (value.bitmap.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val imageHitTargets = ArrayList<TerminalImageHitTarget>()
    private val failedImageKeys = LinkedHashSet<TerminalImageCacheKey>()
    private val pendingImageKeys = HashSet<TerminalImageCacheKey>()
    private val imageDecodeExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tmuxer-image-decode").apply { isDaemon = true }
    }
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
    // Termux uses a representative monospace glyph and the font's own line spacing.
    private val characterWidth = textPaint.measureText("X")
    private val fontMetrics = textPaint.fontMetrics
    private val lineHeight = textPaint.fontSpacing
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
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
    private var renderSamples = 0
    private var renderTotalNanos = 0L
    private var renderMaxNanos = 0L

    private val applyPendingResize = Runnable {
        applyTerminalSize(pendingColumns, pendingRows)
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
            value?.onChanged = {
                snapshotDirty = true
                renderDirty = true
                postInvalidateOnAnimation()
            }
            cachedSnapshot = null
            imageHitTargets.clear()
            imageBitmapCache.evictAll()
            failedImageKeys.clear()
            pendingImageKeys.clear()
            snapshotDirty = true
            renderDirty = true
            appliedColumns = 0
            appliedRows = 0
            scheduleTerminalSize(width, height, immediate = true)
            invalidate()
        }

    var onInput: (String) -> Unit = {}
    var onTerminalResize: (columns: Int, rows: Int) -> Unit = { _, _ -> }
    var onImageClick: (TerminalImagePreview) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(terminalTheme.backgroundColor)
        contentDescription = "SSH 终端，点按打开键盘"
        setOnLongClickListener { pasteClipboard() }
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
    }

    private fun drawSnapshot(canvas: Canvas, snapshot: TerminalSnapshot) {
        for (row in 0 until snapshot.rows) {
            val rowOffset = row * snapshot.columns
            val top = verticalPadding + row * lineHeight
            val baseline = top - fontMetrics.ascent

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
                        top,
                        horizontalPadding + column * characterWidth + 0.5f,
                        top + lineHeight,
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
                val foreground = if (cell.inverse) cell.background else cell.foreground
                val bold = cell.bold
                val underline = cell.underline
                var hasVisibleText = false
                textRun.clear()
                while (column < snapshot.columns) {
                    val next = snapshot.cells[rowOffset + column]
                    val nextForeground = if (next.inverse) next.background else next.foreground
                    if (next.width != 1 || nextForeground != foreground ||
                        next.bold != bold || next.underline != underline
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
        drawTerminalImages(canvas, snapshot)
        if (snapshot.cursorVisible && hasFocus()) {
            val left = horizontalPadding + snapshot.cursorColumn * characterWidth
            val top = verticalPadding + snapshot.cursorRow * lineHeight
            canvas.drawRect(left, top, left + characterWidth, top + lineHeight, cursorPaint)
        }
    }

    private fun drawWideGlyph(canvas: Canvas, cell: TerminalCell, column: Int, baseline: Float) {
        if (cell.text == " ") return
        val foreground = if (cell.inverse) cell.background else cell.foreground
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
        if (cell.underline) {
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

    private fun drawTerminalImages(canvas: Canvas, snapshot: TerminalSnapshot) {
        imageHitTargets.clear()
        snapshot.images.forEach { placement ->
            if (placement.row >= snapshot.rows || placement.column >= snapshot.columns) return@forEach
            val key = TerminalImageCacheKey(placement.imageId, placement.generation)
            if (key in failedImageKeys) return@forEach
            val decoded = imageBitmapCache.get(key)
            if (decoded == null) {
                scheduleImageDecode(key, placement.encodedData)
                return@forEach
            }

            val sourceLeft = placement.sourceX.coerceIn(0, decoded.sourceWidth - 1)
            val sourceTop = placement.sourceY.coerceIn(0, decoded.sourceHeight - 1)
            val sourceRight = (sourceLeft + (placement.sourceWidth ?: (decoded.sourceWidth - sourceLeft)))
                .coerceIn(sourceLeft + 1, decoded.sourceWidth)
            val sourceBottom = (sourceTop + (placement.sourceHeight ?: (decoded.sourceHeight - sourceTop)))
                .coerceIn(sourceTop + 1, decoded.sourceHeight)
            val scaleX = decoded.bitmap.width.toFloat() / decoded.sourceWidth
            val scaleY = decoded.bitmap.height.toFloat() / decoded.sourceHeight
            val source = Rect(
                floor(sourceLeft * scaleX).toInt().coerceIn(0, decoded.bitmap.width - 1),
                floor(sourceTop * scaleY).toInt().coerceIn(0, decoded.bitmap.height - 1),
                ceil(sourceRight * scaleX).toInt().coerceIn(1, decoded.bitmap.width),
                ceil(sourceBottom * scaleY).toInt().coerceIn(1, decoded.bitmap.height)
            )
            val left = horizontalPadding + placement.column * characterWidth + placement.offsetX
            val top = verticalPadding + placement.row * lineHeight + placement.offsetY
            val destination = RectF(
                left,
                top,
                left + placement.columns * characterWidth,
                top + placement.rows * lineHeight
            )
            canvas.drawBitmap(decoded.bitmap, source, destination, bitmapPaint)
            val hitBounds = RectF(destination)
            if (hitBounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
                imageHitTargets += TerminalImageHitTarget(
                    hitBounds,
                    TerminalImagePreview(placement.imageId, decoded.bitmap, placement.encodedData)
                )
            }
        }
    }

    private fun scheduleImageDecode(key: TerminalImageCacheKey, data: ByteArray) {
        if (!pendingImageKeys.add(key)) return
        imageDecodeExecutor.execute {
            val startedNanos = SystemClock.elapsedRealtimeNanos()
            val decoded = decodeTerminalImage(data)
            post {
                pendingImageKeys.remove(key)
                if (decoded != null) {
                    imageBitmapCache.put(key, decoded)
                    perfLog(
                        "image-decode-ready source=${decoded.sourceWidth}x${decoded.sourceHeight} " +
                            "decoded=${decoded.bitmap.width}x${decoded.bitmap.height} " +
                            "durationMs=${"%.2f".format((SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0)}"
                    )
                } else {
                    failedImageKeys += key
                    while (failedImageKeys.size > 32) {
                        failedImageKeys.remove(failedImageKeys.first())
                    }
                }
                renderDirty = true
                invalidate()
            }
        }
    }

    private fun decodeTerminalImage(data: ByteArray): DecodedTerminalImage? {
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
        val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options) }
            .getOrNull() ?: return null
        return DecodedTerminalImage(bitmap, bounds.outWidth, bounds.outHeight)
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
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val totalX = event.x - downX
                val totalY = event.y - downY
                if (abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                    movedBeyondTouchSlop = true
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
                velocityTracker?.addMovement(event)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (scrolledWithFinger) {
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
                    performClick()
                    val image = imageHitTargets.lastOrNull { it.bounds.contains(event.x, event.y) }
                    if (image != null) {
                        onImageClick(image.preview)
                    } else {
                        showKeyboard()
                    }
                }
                recycleVelocityTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                recycleVelocityTracker()
                scrolledWithFinger = false
                movedBeyondTouchSlop = false
                scrollRemainder = 0f
            }
        }
        return true
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
        removeCallbacks(applyPendingResize)
        recycleVelocityTracker()
        imageDecodeExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    fun pasteClipboard(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return false
        onInput(text)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return true
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
        // Keep a real Editable, as Termux does. The previous dummy connection discarded IME
        // composition/selection state, so Android positioned its insertion cursor several cells
        // to the left while composing text.
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            flushEditableToTerminal()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            flushEditableToTerminal()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtLeast(1)) { onInput("\u007F") }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        private fun flushEditableToTerminal() {
            val content = editable ?: return
            if (content.isNotEmpty()) onInput(content.toString())
            content.clear()
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                return this@TerminalView.dispatchKeyEvent(event)
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            onInput("\r")
            return true
        }
    }
}
