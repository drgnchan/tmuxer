package com.tmuxer.app.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

const val TERMINAL_DEFAULT_FOREGROUND: Int = 0xFFE3ECE7.toInt()
const val TERMINAL_DEFAULT_BACKGROUND: Int = 0xFF07100D.toInt()
const val TERMINAL_LIGHT_FOREGROUND: Int = 0xFF20252A.toInt()
const val TERMINAL_LIGHT_BACKGROUND: Int = 0xFFF7F8F5.toInt()
private val EMPTY_BYTE_ARRAY = ByteArray(0)

enum class TerminalTheme(
    val foregroundColor: Int,
    val backgroundColor: Int,
    val cursorColor: Int
) {
    DARK(
        TERMINAL_DEFAULT_FOREGROUND,
        TERMINAL_DEFAULT_BACKGROUND,
        0xFF65DDA5.toInt()
    ),
    LIGHT(
        TERMINAL_LIGHT_FOREGROUND,
        TERMINAL_LIGHT_BACKGROUND,
        0xFF167A52.toInt()
    )
}

data class TerminalCell(
    var text: String = " ",
    /** 2 for wide glyphs, 1 for normal glyphs, 0 for the trailing cell of a wide glyph. */
    var width: Int = 1,
    var foreground: Int = TERMINAL_DEFAULT_FOREGROUND,
    var background: Int = TERMINAL_DEFAULT_BACKGROUND,
    var bold: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false
) {
    fun duplicate() = copy()
}

data class TerminalImagePlacement(
    val imageId: Long,
    val generation: Long,
    val encodedData: ByteArray,
    val remotePath: String? = null,
    val mimeType: String? = null,
    val column: Int,
    val row: Int,
    val columns: Int,
    val rows: Int,
    val sourceX: Int = 0,
    val sourceY: Int = 0,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val offsetX: Int = 0,
    val offsetY: Int = 0
)

data class TerminalSnapshot(
    val columns: Int,
    val rows: Int,
    val cells: Array<TerminalCell>,
    var cursorColumn: Int,
    var cursorRow: Int,
    var cursorVisible: Boolean,
    var images: List<TerminalImagePlacement> = emptyList()
)

data class KittyGraphicsCommand(
    val action: String,
    val controls: Map<String, String>,
    val data: ByteArray?,
    val column: Int,
    val row: Int
)

/**
 * A compact VT/xterm-compatible screen buffer. Rendering and touch behavior follow the same core
 * ideas as Termux: wcwidth-aware cells, a main-screen transcript, and xterm mouse-wheel reporting.
 */
class TerminalEmulator(
    initialColumns: Int = 80,
    initialRows: Int = 24,
    private val reply: (String) -> Unit = {},
    kittyGraphicsSink: ((KittyGraphicsCommand) -> Unit)? = null,
    private val retainScreenContent: Boolean = true
) {
    @Volatile
    private var kittyGraphicsSink = kittyGraphicsSink
    private var columns = initialColumns
    private var rows = initialRows

    var theme: TerminalTheme = TerminalTheme.DARK
        private set

    private var mainCells = freshBuffer(columns, rows)
    private var alternateCells = freshBuffer(columns, rows)
    private var useAlternate = false
    private val scrollback = ArrayDeque<Array<TerminalCell>>()
    private var viewportOffset = 0
    private var mouseTracking = false
    private var sgrMouseProtocol = false

    private var cursorColumn = 0
    private var cursorRow = 0
    private var savedColumn = 0
    private var savedRow = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var cursorVisible = true
    private var wrapPending = false

    private var foreground = theme.foregroundColor
    private var background = theme.backgroundColor
    private var bold = false
    private var underline = false
    private var inverse = false

    private var parserState = ParserState.NORMAL
    private val sequence = StringBuilder()
    private var charsetSequencePending = false
    private var pendingCharsetSlot = 0
    private var g0LineDrawing = false
    private var g1LineDrawing = false
    private var activeCharsetSlot = 0
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var decodeBuffer = CharBuffer.allocate(16 * 1024 + 4)
    private var pendingUtf8 = EMPTY_BYTE_ARRAY
    private var pendingHighSurrogate: Char? = null
    private var kittySequenceOverflow = false
    private var pendingKittyTransmission: PendingKittyTransmission? = null
    private val terminalImages = LinkedHashMap<Long, StoredTerminalImage>()
    private val terminalImagePlacements = LinkedHashMap<Long, StoredImagePlacement>()
    private var terminalImageBytes = 0
    private var terminalImageGeneration = 0L

    @Volatile
    var onChanged: (() -> Unit)? = null

    private val cells: Array<TerminalCell>
        get() = if (useAlternate) alternateCells else mainCells

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        synchronized(this) {
            val safeLength = length.coerceIn(0, bytes.size)
            val input = if (pendingUtf8.isEmpty()) {
                ByteBuffer.wrap(bytes, 0, safeLength)
            } else {
                ByteBuffer.wrap(ByteArray(pendingUtf8.size + safeLength).also { combined ->
                    pendingUtf8.copyInto(combined)
                    bytes.copyInto(combined, pendingUtf8.size, 0, safeLength)
                })
            }
            if (decodeBuffer.capacity() < input.remaining()) {
                decodeBuffer = CharBuffer.allocate(input.remaining())
            }
            decodeBuffer.clear()
            decoder.decode(input, decodeBuffer, false)
            pendingUtf8 = if (input.hasRemaining()) {
                ByteArray(input.remaining()).also { input.get(it) }
            } else {
                EMPTY_BYTE_ARRAY
            }
            decodeBuffer.flip()
            while (decodeBuffer.hasRemaining()) process(decodeBuffer.get())
        }
        onChanged?.invoke()
    }

    fun setTheme(newTheme: TerminalTheme) {
        var changed = false
        synchronized(this) {
            if (theme == newTheme) return
            val oldForeground = theme.foregroundColor
            val oldBackground = theme.backgroundColor
            theme = newTheme

            fun remap(line: Array<TerminalCell>) {
                line.forEach { cell ->
                    if (cell.foreground == oldForeground) cell.foreground = newTheme.foregroundColor
                    if (cell.background == oldBackground) cell.background = newTheme.backgroundColor
                }
            }
            remap(mainCells)
            remap(alternateCells)
            scrollback.forEach(::remap)
            if (foreground == oldForeground) foreground = newTheme.foregroundColor
            if (background == oldBackground) background = newTheme.backgroundColor
            changed = true
        }
        if (changed) onChanged?.invoke()
    }

    fun reset() {
        synchronized(this) {
            mainCells = freshBuffer(columns, rows)
            alternateCells = freshBuffer(columns, rows)
            useAlternate = false
            scrollback.clear()
            viewportOffset = 0
            mouseTracking = false
            sgrMouseProtocol = false
            cursorColumn = 0
            cursorRow = 0
            savedColumn = 0
            savedRow = 0
            scrollTop = 0
            scrollBottom = rows - 1
            cursorVisible = true
            wrapPending = false
            resetStyle()
            parserState = ParserState.NORMAL
            charsetSequencePending = false
            pendingCharsetSlot = 0
            g0LineDrawing = false
            g1LineDrawing = false
            activeCharsetSlot = 0
            pendingUtf8 = EMPTY_BYTE_ARRAY
            pendingHighSurrogate = null
            kittySequenceOverflow = false
            pendingKittyTransmission = null
            terminalImages.clear()
            terminalImagePlacements.clear()
            terminalImageBytes = 0
            decoder.reset()
        }
        onChanged?.invoke()
    }

    fun resize(newColumns: Int, newRows: Int) {
        val safeColumns = newColumns.coerceIn(20, 240)
        val safeRows = newRows.coerceIn(5, 100)
        synchronized(this) {
            if (safeColumns == columns && safeRows == rows) return
            mainCells = resizedBuffer(mainCells, columns, rows, safeColumns, safeRows)
            alternateCells = resizedBuffer(alternateCells, columns, rows, safeColumns, safeRows)
            // Showing or hiding the IME changes only row count. Rebuilding up to 2,000 transcript
            // lines for a row-only resize is unnecessary and caused visible animation jank.
            if (safeColumns != columns) {
                val resizedHistory = scrollback.map { resizedLine(it, columns, safeColumns) }
                scrollback.clear()
                scrollback.addAll(resizedHistory)
            }
            columns = safeColumns
            rows = safeRows
            cursorColumn = cursorColumn.coerceIn(0, columns - 1)
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            scrollTop = 0
            scrollBottom = rows - 1
            wrapPending = false
        }
        onChanged?.invoke()
    }

    @Synchronized
    fun snapshot(reuse: TerminalSnapshot? = null): TerminalSnapshot {
        val reusable = reuse?.takeIf {
            it.columns == columns && it.rows == rows && it.cells.size == cells.size
        }
        val visibleCells = reusable?.cells ?: freshBuffer(columns, rows)

        fun copyCell(source: TerminalCell, target: TerminalCell) {
            target.text = source.text
            target.width = source.width
            target.foreground = source.foreground
            target.background = source.background
            target.bold = source.bold
            target.underline = source.underline
            target.inverse = source.inverse
        }

        if (viewportOffset > 0) {
            val firstLine = scrollback.size - viewportOffset
            for (row in 0 until rows) {
                val sourceLine = firstLine + row
                if (sourceLine < scrollback.size) {
                    val historyRow = scrollback.elementAt(sourceLine.coerceAtLeast(0))
                    for (column in 0 until columns) {
                        copyCell(historyRow[column], visibleCells[row * columns + column])
                    }
                } else {
                    val screenRow = sourceLine - scrollback.size
                    if (screenRow in 0 until rows) {
                        for (column in 0 until columns) {
                            copyCell(
                                cells[screenRow * columns + column],
                                visibleCells[row * columns + column]
                            )
                        }
                    }
                }
            }
        } else {
            cells.indices.forEach { copyCell(cells[it], visibleCells[it]) }
        }

        val visibleImages = if (viewportOffset == 0) {
            terminalImagePlacements.values.mapNotNull { placement ->
                val image = terminalImages[placement.imageId] ?: return@mapNotNull null
                TerminalImagePlacement(
                    imageId = placement.imageId,
                    generation = image.generation,
                    encodedData = image.data,
                    remotePath = image.remotePath,
                    mimeType = image.mimeType,
                    column = placement.column,
                    row = placement.row,
                    columns = placement.columns,
                    rows = placement.rows,
                    sourceX = placement.sourceX,
                    sourceY = placement.sourceY,
                    sourceWidth = placement.sourceWidth,
                    sourceHeight = placement.sourceHeight,
                    offsetX = placement.offsetX,
                    offsetY = placement.offsetY
                )
            }
        } else {
            emptyList()
        }
        return reusable?.apply {
            cursorColumn = this@TerminalEmulator.cursorColumn
            cursorRow = this@TerminalEmulator.cursorRow
            cursorVisible = this@TerminalEmulator.cursorVisible && viewportOffset == 0
            images = visibleImages
        } ?: TerminalSnapshot(
            columns = columns,
            rows = rows,
            cells = visibleCells,
            cursorColumn = cursorColumn,
            cursorRow = cursorRow,
            cursorVisible = cursorVisible && viewportOffset == 0,
            images = visibleImages
        )
    }

    @Synchronized
    fun isMouseTrackingActive(): Boolean = mouseTracking

    /**
     * Handles a finger scroll like Termux: report wheel events to mouse-aware full-screen apps,
     * otherwise move through the local transcript without changing shell history.
     */
    fun scroll(rowsDown: Int, column: Int, row: Int) {
        if (rowsDown == 0) return
        var response: String? = null
        var changed = false
        synchronized(this) {
            val amount = kotlin.math.abs(rowsDown).coerceAtMost(rows * 2)
            if (mouseTracking) {
                val button = if (rowsDown < 0) 64 else 65
                val safeColumn = column.coerceIn(1, columns)
                val safeRow = row.coerceIn(1, rows)
                response = buildString {
                    repeat(amount) {
                        if (sgrMouseProtocol) {
                            append("\u001B[<").append(button).append(';')
                                .append(safeColumn).append(';').append(safeRow).append('M')
                        } else {
                            append("\u001B[M")
                            append((32 + button).coerceAtMost(255).toChar())
                            append((32 + safeColumn).coerceAtMost(255).toChar())
                            append((32 + safeRow).coerceAtMost(255).toChar())
                        }
                    }
                }
            } else {
                // Keep a local transcript even though tmux itself uses the alternate screen. Sending
                // arrow keys here changes shell history instead of scrolling the terminal content.
                val oldOffset = viewportOffset
                viewportOffset = (viewportOffset - rowsDown).coerceIn(0, scrollback.size)
                changed = oldOffset != viewportOffset
            }
        }
        response?.let(reply)
        if (changed) onChanged?.invoke()
    }

    private fun process(char: Char) {
        when (parserState) {
            ParserState.NORMAL -> processNormal(char)
            ParserState.ESCAPE -> processEscape(char)
            ParserState.CSI -> processCsi(char)
            ParserState.OSC -> {
                when (char) {
                    '\u0007' -> parserState = ParserState.NORMAL
                    '\u001B' -> parserState = ParserState.OSC_ESCAPE
                    else -> if (sequence.length < 1024) sequence.append(char)
                }
            }
            ParserState.OSC_ESCAPE -> {
                parserState = if (char == '\\') ParserState.NORMAL else ParserState.OSC
            }
            ParserState.APC -> {
                if (char == '\u001B') {
                    parserState = ParserState.APC_ESCAPE
                } else if (sequence.length < MAX_KITTY_CHUNK_CHARS) {
                    sequence.append(char)
                } else {
                    kittySequenceOverflow = true
                }
            }
            ParserState.APC_ESCAPE -> {
                if (char == '\\') {
                    if (!kittySequenceOverflow) processKittyGraphics(sequence.toString())
                    sequence.clear()
                    kittySequenceOverflow = false
                    parserState = ParserState.NORMAL
                } else {
                    if (sequence.length + 2 < MAX_KITTY_CHUNK_CHARS) {
                        sequence.append('\u001B').append(char)
                    } else {
                        kittySequenceOverflow = true
                    }
                    parserState = ParserState.APC
                }
            }
        }
    }

    private fun processNormal(char: Char) {
        pendingHighSurrogate?.let { high ->
            pendingHighSurrogate = null
            if (Character.isLowSurrogate(char)) {
                val codePoint = Character.toCodePoint(high, char)
                putText(String(charArrayOf(high, char)), wcWidth(codePoint))
                return
            }
            putText("�", 1)
        }
        if (Character.isHighSurrogate(char)) {
            pendingHighSurrogate = char
            return
        }
        when (char) {
            '\u001B' -> {
                parserState = ParserState.ESCAPE
                charsetSequencePending = false
            }
            '\r' -> {
                cursorColumn = 0
                wrapPending = false
            }
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\b' -> {
                cursorColumn = max(0, cursorColumn - 1)
                wrapPending = false
            }
            '\t' -> {
                cursorColumn = min(columns - 1, ((cursorColumn / 8) + 1) * 8)
                wrapPending = false
            }
            '\u000E' -> activeCharsetSlot = 1
            '\u000F' -> activeCharsetSlot = 0
            '\u0000', '\u0007' -> Unit
            else -> if (char >= ' ') {
                val mapped = mapActiveCharset(char)
                putText(mapped, wcWidth(mapped.codePointAt(0)))
            }
        }
    }

    private fun processEscape(char: Char) {
        if (charsetSequencePending) {
            val lineDrawing = char == '0'
            if (pendingCharsetSlot == 0) g0LineDrawing = lineDrawing else g1LineDrawing = lineDrawing
            charsetSequencePending = false
            parserState = ParserState.NORMAL
            return
        }
        when (char) {
            '[' -> {
                sequence.clear()
                parserState = ParserState.CSI
            }
            ']' -> {
                sequence.clear()
                parserState = ParserState.OSC
            }
            '_' -> {
                sequence.clear()
                kittySequenceOverflow = false
                parserState = ParserState.APC
            }
            '(', ')', '*', '+', '-', '.', '/' -> {
                pendingCharsetSlot = if (char == '(') 0 else 1
                charsetSequencePending = true
            }
            '7' -> {
                savedColumn = cursorColumn
                savedRow = cursorRow
                parserState = ParserState.NORMAL
            }
            '8' -> {
                cursorColumn = savedColumn.coerceIn(0, columns - 1)
                cursorRow = savedRow.coerceIn(0, rows - 1)
                parserState = ParserState.NORMAL
            }
            'D' -> {
                lineFeed()
                parserState = ParserState.NORMAL
            }
            'E' -> {
                cursorColumn = 0
                lineFeed()
                parserState = ParserState.NORMAL
            }
            'M' -> {
                reverseIndex()
                parserState = ParserState.NORMAL
            }
            'c' -> {
                reset()
                parserState = ParserState.NORMAL
            }
            '=', '>' -> parserState = ParserState.NORMAL
            else -> parserState = ParserState.NORMAL
        }
    }

    private fun processCsi(char: Char) {
        if (char.code in 0x40..0x7E) {
            applyCsi(sequence.toString(), char)
            sequence.clear()
            parserState = ParserState.NORMAL
        } else if (sequence.length < 256) {
            sequence.append(char)
        }
    }

    private fun applyCsi(raw: String, command: Char) {
        val privateMode = raw.startsWith('?')
        val clean = raw.trimStart('?', '>', '!')
            .filter { it.isDigit() || it == ';' || it == ':' }
        val parameters = if (clean.isEmpty()) {
            emptyList()
        } else {
            clean.split(';').map { token -> token.substringBefore(':').toIntOrNull() ?: 0 }
        }
        fun value(index: Int, fallback: Int = 1): Int =
            parameters.getOrNull(index)?.takeIf { it != 0 } ?: fallback

        when (command) {
            'A' -> cursorRow = max(scrollTop.takeIf { cursorRow >= it } ?: 0, cursorRow - value(0))
            'B', 'e' -> cursorRow = min(scrollBottom.takeIf { cursorRow <= it } ?: rows - 1, cursorRow + value(0))
            'C', 'a' -> cursorColumn = min(columns - 1, cursorColumn + value(0))
            'D' -> cursorColumn = max(0, cursorColumn - value(0))
            'E' -> {
                cursorRow = min(rows - 1, cursorRow + value(0))
                cursorColumn = 0
            }
            'F' -> {
                cursorRow = max(0, cursorRow - value(0))
                cursorColumn = 0
            }
            'G', '`' -> cursorColumn = (value(0) - 1).coerceIn(0, columns - 1)
            'd' -> cursorRow = (value(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (value(0) - 1).coerceIn(0, rows - 1)
                cursorColumn = (value(1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> eraseDisplay(parameters.firstOrNull() ?: 0)
            'K' -> eraseLine(parameters.firstOrNull() ?: 0)
            '@' -> insertCharacters(value(0))
            'P' -> deleteCharacters(value(0))
            'X' -> eraseCharacters(value(0))
            'L' -> insertLines(value(0))
            'M' -> deleteLines(value(0))
            'S' -> scrollUp(value(0))
            'T' -> scrollDown(value(0))
            'm' -> applyStyle(parameters)
            'r' -> {
                scrollTop = (value(0) - 1).coerceIn(0, rows - 1)
                scrollBottom = (value(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorColumn = 0
                cursorRow = scrollTop
            }
            's' -> {
                savedColumn = cursorColumn
                savedRow = cursorRow
            }
            'u' -> {
                // Plain CSI u restores the cursor. Private variants are Kitty keyboard protocol
                // negotiation; ignore them until input encoding supports the negotiated flags.
                val prefix = raw.firstOrNull()
                if (prefix == null || prefix !in charArrayOf('?', '>', '<', '=')) {
                    cursorColumn = savedColumn.coerceIn(0, columns - 1)
                    cursorRow = savedRow.coerceIn(0, rows - 1)
                }
            }
            'h', 'l' -> setModes(parameters, privateMode, command == 'h')
            'n' -> when (parameters.firstOrNull()) {
                5 -> reply("\u001B[0n")
                6 -> reply("\u001B[${cursorRow + 1};${cursorColumn + 1}R")
            }
            // Do not answer DA here. tmux can emit a late DA probe after attaching; on a few
            // servers that reply is forwarded to the pane and appears as literal "?1;2c" input.
        }
        if (command !in charArrayOf('m', 'h', 'l')) wrapPending = false
    }

    private fun processKittyGraphics(raw: String) {
        if (!raw.startsWith('G')) return
        val body = raw.substring(1)
        val separator = body.indexOf(';')
        val controlsText = if (separator >= 0) body.substring(0, separator) else body
        val payload = if (separator >= 0) body.substring(separator + 1) else ""
        val controls = controlsText.split(',')
            .mapNotNull { token ->
                val equals = token.indexOf('=')
                if (equals <= 0) null else token.substring(0, equals) to token.substring(equals + 1)
            }
            .toMap()
        val action = controls["a"]

        if (action == "T" || action == "t") {
            pendingKittyTransmission = PendingKittyTransmission(
                action = action,
                controls = controls,
                column = cursorColumn,
                row = cursorRow
            ).also { it.append(payload) }
            if (controls["m"] != "1") finishKittyTransmission()
            return
        }

        val pending = pendingKittyTransmission
        if (pending != null && action == null && controls.containsKey("m")) {
            pending.append(payload)
            if (controls["m"] != "1") finishKittyTransmission()
            return
        }

        if (action == "p" || action == "d") {
            dispatchKittyGraphics(
                KittyGraphicsCommand(action, controls, null, cursorColumn, cursorRow)
            )
        }
    }

    private fun finishKittyTransmission() {
        val pending = pendingKittyTransmission ?: return
        pendingKittyTransmission = null
        if (pending.overflowed) return
        val decoded = runCatching { Base64.getDecoder().decode(pending.base64.toString()) }.getOrNull()
            ?: return
        if (decoded.size > MAX_KITTY_IMAGE_BYTES) return
        dispatchKittyGraphics(
            KittyGraphicsCommand(
                action = pending.action,
                controls = pending.controls,
                data = decoded,
                column = pending.column,
                row = pending.row
            )
        )
    }

    private fun dispatchKittyGraphics(command: KittyGraphicsCommand) {
        val sink = kittyGraphicsSink
        if (sink != null) sink(command) else applyKittyGraphicsCommand(command)
    }

    internal fun setKittyGraphicsSink(sink: ((KittyGraphicsCommand) -> Unit)?) {
        kittyGraphicsSink = sink
    }

    /** Keeps this parser's checkpoint state current while also publishing live graphics. */
    internal fun mirrorKittyGraphicsTo(target: TerminalEmulator) {
        kittyGraphicsSink = { command ->
            applyKittyGraphicsCommand(command)
            target.applyKittyGraphicsCommand(command)
        }
    }

    /**
     * Replaces all decoded Kitty image state in one notification. Historical graphics streams can
     * contain many placements followed by deletes; publishing each replayed command would briefly
     * draw images that are no longer present when a terminal is restored.
     */
    internal fun replaceKittyGraphicsStateFrom(source: TerminalEmulator) {
        val state = source.copyKittyGraphicsState()
        synchronized(this) {
            terminalImages.clear()
            terminalImages.putAll(state.images)
            terminalImagePlacements.clear()
            terminalImagePlacements.putAll(state.placements)
            terminalImageBytes = state.totalBytes
            terminalImageGeneration = state.generation
        }
        onChanged?.invoke()
    }

    @Synchronized
    private fun copyKittyGraphicsState(): KittyGraphicsState = KittyGraphicsState(
        images = LinkedHashMap(terminalImages),
        placements = LinkedHashMap(terminalImagePlacements),
        totalBytes = terminalImageBytes,
        generation = terminalImageGeneration
    )

    @Synchronized
    internal fun applyKittyGraphicsCommand(command: KittyGraphicsCommand) {
        var changed = false
        when (command.action) {
            "T", "t" -> {
                val imageId = command.controls["i"]?.toLongOrNull() ?: return
                val payload = command.data ?: return
                if (payload.size > MAX_KITTY_IMAGE_BYTES) return
                val isRemoteReference = command.controls["tmuxer"] == "1" &&
                    command.controls["t"] == "f"
                val remotePath = if (isRemoteReference) {
                    payload.toString(Charsets.UTF_8).takeIf {
                        it.startsWith('/') && it.length <= MAX_REMOTE_IMAGE_PATH_CHARS && '\u0000' !in it
                    } ?: return
                } else {
                    null
                }
                val data = if (remotePath == null) payload else EMPTY_BYTE_ARRAY
                terminalImages.remove(imageId)?.let { terminalImageBytes -= it.data.size }
                terminalImageGeneration++
                terminalImages[imageId] = StoredTerminalImage(
                    generation = terminalImageGeneration,
                    data = data,
                    remotePath = remotePath,
                    mimeType = command.controls["M"]?.takeIf { remotePath != null },
                    columns = command.controls.intValue("c", 1, columns),
                    rows = command.controls.intValue("r", 1, rows)
                )
                terminalImageBytes += data.size
                if (command.action == "T") {
                    updateImagePlacement(imageId, command.controls, command.column, command.row)
                }
                evictOldTerminalImages()
                changed = true
            }
            "p" -> {
                val imageId = command.controls["i"]?.toLongOrNull() ?: return
                updateImagePlacement(imageId, command.controls, command.column, command.row)
                changed = true
            }
            "d" -> {
                when (command.controls["d"]) {
                    "A" -> {
                        changed = terminalImages.isNotEmpty() || terminalImagePlacements.isNotEmpty()
                        terminalImages.clear()
                        terminalImagePlacements.clear()
                        terminalImageBytes = 0
                    }
                    "a" -> {
                        changed = terminalImagePlacements.isNotEmpty()
                        terminalImagePlacements.clear()
                    }
                    "I" -> command.controls["i"]?.toLongOrNull()?.let { imageId ->
                        val image = terminalImages.remove(imageId)
                        if (image != null) {
                            terminalImageBytes -= image.data.size
                            changed = true
                        }
                        changed = terminalImagePlacements.remove(imageId) != null || changed
                    }
                    "i" -> command.controls["i"]?.toLongOrNull()?.let { imageId ->
                        changed = terminalImagePlacements.remove(imageId) != null
                    }
                }
            }
        }
        if (changed) onChanged?.invoke()
    }

    private fun updateImagePlacement(
        imageId: Long,
        controls: Map<String, String>,
        column: Int,
        row: Int
    ) {
        val previous = terminalImagePlacements[imageId]
        val image = terminalImages[imageId]
        terminalImagePlacements[imageId] = StoredImagePlacement(
            imageId = imageId,
            column = column.coerceAtLeast(0),
            row = row.coerceAtLeast(0),
            columns = controls.intValue("c", 1, columns, previous?.columns ?: image?.columns ?: 1),
            rows = controls.intValue("r", 1, rows, previous?.rows ?: image?.rows ?: 1),
            sourceX = controls["x"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            sourceY = controls["y"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            sourceWidth = controls["w"]?.toIntOrNull()?.takeIf { it > 0 },
            sourceHeight = controls["h"]?.toIntOrNull()?.takeIf { it > 0 },
            offsetX = controls["X"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            offsetY = controls["Y"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        )
    }

    private fun evictOldTerminalImages() {
        while (terminalImages.size > MAX_KITTY_IMAGES || terminalImageBytes > MAX_KITTY_TOTAL_BYTES) {
            val oldest = terminalImages.entries.firstOrNull() ?: break
            terminalImages.remove(oldest.key)
            terminalImagePlacements.remove(oldest.key)
            terminalImageBytes -= oldest.value.data.size
        }
    }

    private fun Map<String, String>.intValue(
        key: String,
        minimum: Int,
        maximum: Int,
        fallback: Int = minimum
    ): Int = get(key)?.toIntOrNull()?.coerceIn(minimum, maximum) ?: fallback.coerceIn(minimum, maximum)

    private fun putText(text: String, requestedWidth: Int) {
        if (requestedWidth <= 0) {
            if (!retainScreenContent) return
            val previousColumn = (cursorColumn - 1).coerceAtLeast(0)
            val previous = cells[index(previousColumn, cursorRow)]
            if (previous.width == 0 && previousColumn > 0) {
                cells[index(previousColumn - 1, cursorRow)].text += text
            } else {
                previous.text += text
            }
            return
        }
        val glyphWidth = requestedWidth.coerceIn(1, 2)
        if (wrapPending || (glyphWidth == 2 && cursorColumn == columns - 1)) {
            cursorColumn = 0
            lineFeed()
            wrapPending = false
        }
        if (retainScreenContent) {
            clearGlyphAt(cursorColumn, cursorRow)
            if (glyphWidth == 2) clearGlyphAt(cursorColumn + 1, cursorRow)

            val cell = cells[index(cursorColumn, cursorRow)]
            cell.text = text
            cell.width = glyphWidth
            cell.foreground = foreground
            cell.background = background
            cell.bold = bold
            cell.underline = underline
            cell.inverse = inverse
            if (glyphWidth == 2) {
                cells[index(cursorColumn + 1, cursorRow)] = blankCell().apply { width = 0 }
            }
        }
        cursorColumn += glyphWidth
        if (cursorColumn >= columns) {
            cursorColumn = columns - 1
            wrapPending = true
        }
    }

    private fun clearGlyphAt(column: Int, row: Int) {
        if (column !in 0 until columns) return
        val position = index(column, row)
        when (cells[position].width) {
            0 -> if (column > 0) cells[index(column - 1, row)] = blankCell()
            2 -> if (column + 1 < columns) cells[index(column + 1, row)] = blankCell()
        }
        cells[position] = blankCell()
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else {
            cursorRow = min(rows - 1, cursorRow + 1)
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1) else cursorRow = max(0, cursorRow - 1)
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        if (!retainScreenContent) return
        when (mode) {
            0 -> {
                eraseRange(index(cursorColumn, cursorRow), cells.lastIndex)
            }
            1 -> eraseRange(0, index(cursorColumn, cursorRow))
            2 -> eraseRange(0, cells.lastIndex)
            3 -> {
                eraseRange(0, cells.lastIndex)
                scrollback.clear()
                viewportOffset = 0
            }
        }
    }

    private fun eraseLine(mode: Int) {
        if (!retainScreenContent) return
        val start = index(0, cursorRow)
        val end = index(columns - 1, cursorRow)
        when (mode) {
            0 -> eraseRange(index(cursorColumn, cursorRow), end)
            1 -> eraseRange(start, index(cursorColumn, cursorRow))
            2 -> eraseRange(start, end)
        }
    }

    private fun insertCharacters(count: Int) {
        if (!retainScreenContent) return
        val amount = count.coerceAtMost(columns - cursorColumn)
        val rowStart = index(0, cursorRow)
        for (column in columns - 1 downTo cursorColumn + amount) {
            cells[rowStart + column] = cells[rowStart + column - amount].duplicate()
        }
        eraseRange(rowStart + cursorColumn, rowStart + cursorColumn + amount - 1)
    }

    private fun deleteCharacters(count: Int) {
        if (!retainScreenContent) return
        val amount = count.coerceAtMost(columns - cursorColumn)
        val rowStart = index(0, cursorRow)
        for (column in cursorColumn until columns - amount) {
            cells[rowStart + column] = cells[rowStart + column + amount].duplicate()
        }
        eraseRange(rowStart + columns - amount, rowStart + columns - 1)
    }

    private fun eraseCharacters(count: Int) {
        if (!retainScreenContent) return
        eraseRange(
            index(cursorColumn, cursorRow),
            index(min(columns - 1, cursorColumn + count - 1), cursorRow)
        )
    }

    private fun insertLines(count: Int) {
        if (!retainScreenContent || cursorRow !in scrollTop..scrollBottom) return
        val amount = count.coerceAtMost(scrollBottom - cursorRow + 1)
        for (row in scrollBottom downTo cursorRow + amount) copyRow(row - amount, row)
        for (row in cursorRow until cursorRow + amount) clearRow(row)
    }

    private fun deleteLines(count: Int) {
        if (!retainScreenContent || cursorRow !in scrollTop..scrollBottom) return
        val amount = count.coerceAtMost(scrollBottom - cursorRow + 1)
        for (row in cursorRow..scrollBottom - amount) copyRow(row + amount, row)
        for (row in scrollBottom - amount + 1..scrollBottom) clearRow(row)
    }

    private fun scrollUp(count: Int) {
        if (!retainScreenContent) return
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            // tmux reserves its bottom status row and scrolls only 0..rows-2. The removed top
            // line still belongs in local transcript history whenever the region starts at row 0.
            if (scrollTop == 0) {
                val removedLine = Array(columns) { column ->
                    cells[index(column, 0)].duplicate()
                }
                scrollback.addLast(removedLine)
                if (viewportOffset > 0) viewportOffset++
                while (scrollback.size > MAX_SCROLLBACK_LINES) {
                    scrollback.removeFirst()
                    viewportOffset = viewportOffset.coerceAtMost(scrollback.size)
                }
            }
            for (row in scrollTop until scrollBottom) copyRow(row + 1, row)
            clearRow(scrollBottom)
        }
    }

    private fun scrollDown(count: Int) {
        if (!retainScreenContent) return
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            for (row in scrollBottom downTo scrollTop + 1) copyRow(row - 1, row)
            clearRow(scrollTop)
        }
    }

    private fun copyRow(from: Int, to: Int) {
        for (column in 0 until columns) {
            cells[index(column, to)] = cells[index(column, from)].duplicate()
        }
    }

    private fun clearRow(row: Int) = eraseRange(index(0, row), index(columns - 1, row))

    private fun eraseRange(start: Int, end: Int) {
        if (end < start) return
        for (position in start.coerceAtLeast(0)..end.coerceAtMost(cells.lastIndex)) {
            cells[position] = blankCell()
        }
    }

    private fun applyStyle(parameters: List<Int>) {
        val values = if (parameters.isEmpty()) listOf(0) else parameters
        var cursor = 0
        while (cursor < values.size) {
            when (val code = values[cursor]) {
                0 -> resetStyle()
                1 -> bold = true
                2, 22 -> bold = false
                4 -> underline = true
                24 -> underline = false
                7 -> inverse = true
                27 -> inverse = false
                30, 31, 32, 33, 34, 35, 36, 37 -> foreground = ANSI_COLORS[code - 30]
                39 -> foreground = theme.foregroundColor
                40, 41, 42, 43, 44, 45, 46, 47 -> background = ANSI_COLORS[code - 40]
                49 -> background = theme.backgroundColor
                90, 91, 92, 93, 94, 95, 96, 97 -> foreground = ANSI_COLORS[code - 90 + 8]
                100, 101, 102, 103, 104, 105, 106, 107 -> background = ANSI_COLORS[code - 100 + 8]
                38, 48 -> {
                    val isForeground = code == 38
                    when (values.getOrNull(cursor + 1)) {
                        5 -> {
                            val color = xtermColor(values.getOrNull(cursor + 2) ?: 0)
                            if (isForeground) foreground = color else background = color
                            cursor += 2
                        }
                        2 -> {
                            val red = (values.getOrNull(cursor + 2) ?: 0).coerceIn(0, 255)
                            val green = (values.getOrNull(cursor + 3) ?: 0).coerceIn(0, 255)
                            val blue = (values.getOrNull(cursor + 4) ?: 0).coerceIn(0, 255)
                            val color = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                            if (isForeground) foreground = color else background = color
                            cursor += 4
                        }
                    }
                }
            }
            cursor++
        }
    }

    private fun setModes(parameters: List<Int>, privateMode: Boolean, enabled: Boolean) {
        parameters.forEach { mode ->
            if (privateMode) {
                when (mode) {
                    25 -> cursorVisible = enabled
                    1000, 1002, 1003 -> mouseTracking = enabled
                    1006 -> sgrMouseProtocol = enabled
                    47, 1047, 1049 -> {
                        if (enabled != useAlternate) {
                            if (enabled) {
                                savedColumn = cursorColumn
                                savedRow = cursorRow
                                if (retainScreenContent) alternateCells = freshBuffer(columns, rows)
                                cursorColumn = 0
                                cursorRow = 0
                            } else {
                                cursorColumn = savedColumn.coerceIn(0, columns - 1)
                                cursorRow = savedRow.coerceIn(0, rows - 1)
                            }
                            useAlternate = enabled
                            viewportOffset = 0
                            scrollTop = 0
                            scrollBottom = rows - 1
                        }
                    }
                }
            }
        }
    }

    private fun resetStyle() {
        foreground = theme.foregroundColor
        background = theme.backgroundColor
        bold = false
        underline = false
        inverse = false
    }

    private fun blankCell() = TerminalCell(
        foreground = foreground,
        background = background,
        bold = bold,
        underline = underline,
        inverse = inverse
    )

    private fun index(column: Int, row: Int) = row * columns + column

    private fun defaultCell() = TerminalCell(
        foreground = theme.foregroundColor,
        background = theme.backgroundColor
    )

    private fun freshBuffer(width: Int, height: Int) =
        Array(width * height) { defaultCell() }

    private fun resizedLine(source: Array<TerminalCell>, oldWidth: Int, newWidth: Int): Array<TerminalCell> =
        Array(newWidth) { column ->
            if (column < oldWidth) source[column].duplicate() else defaultCell()
        }

    private fun resizedBuffer(
        source: Array<TerminalCell>,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int
    ): Array<TerminalCell> {
        val result = freshBuffer(newWidth, newHeight)
        for (row in 0 until min(oldHeight, newHeight)) {
            for (column in 0 until min(oldWidth, newWidth)) {
                result[row * newWidth + column] = source[row * oldWidth + column].duplicate()
            }
        }
        return result
    }

    private fun xtermColor(index: Int): Int {
        val safe = index.coerceIn(0, 255)
        if (safe < 16) return ANSI_COLORS[safe]
        if (safe < 232) {
            val value = safe - 16
            val red = value / 36
            val green = (value % 36) / 6
            val blue = value % 6
            fun component(number: Int) = if (number == 0) 0 else 55 + number * 40
            return 0xFF000000.toInt() or
                (component(red) shl 16) or (component(green) shl 8) or component(blue)
        }
        val gray = 8 + (safe - 232) * 10
        return 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
    }

    private fun mapActiveCharset(char: Char): String {
        val lineDrawing = if (activeCharsetSlot == 0) g0LineDrawing else g1LineDrawing
        if (!lineDrawing) return char.toString()
        return DEC_SPECIAL_GRAPHICS[char] ?: char.toString()
    }

    private fun wcWidth(codePoint: Int): Int {
        val type = Character.getType(codePoint)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            codePoint in 0xFE00..0xFE0F
        ) return 0
        return if (
            codePoint in 0x1100..0x115F || codePoint == 0x2329 || codePoint == 0x232A ||
            codePoint in 0x2E80..0xA4CF && codePoint != 0x303F ||
            codePoint in 0xAC00..0xD7A3 || codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE19 || codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 || codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F300..0x1FAFF || codePoint in 0x20000..0x3FFFD
        ) 2 else 1
    }

    private class PendingKittyTransmission(
        val action: String,
        val controls: Map<String, String>,
        val column: Int,
        val row: Int
    ) {
        val base64 = StringBuilder()
        var overflowed = false

        fun append(chunk: String) {
            if (overflowed) return
            if (base64.length + chunk.length > MAX_KITTY_BASE64_CHARS) {
                overflowed = true
                base64.clear()
            } else {
                base64.append(chunk)
            }
        }
    }

    private data class StoredTerminalImage(
        val generation: Long,
        val data: ByteArray,
        val remotePath: String?,
        val mimeType: String?,
        val columns: Int,
        val rows: Int
    )

    private data class StoredImagePlacement(
        val imageId: Long,
        val column: Int,
        val row: Int,
        val columns: Int,
        val rows: Int,
        val sourceX: Int,
        val sourceY: Int,
        val sourceWidth: Int?,
        val sourceHeight: Int?,
        val offsetX: Int,
        val offsetY: Int
    )

    private data class KittyGraphicsState(
        val images: LinkedHashMap<Long, StoredTerminalImage>,
        val placements: LinkedHashMap<Long, StoredImagePlacement>,
        val totalBytes: Int,
        val generation: Long
    )

    private enum class ParserState {
        NORMAL, ESCAPE, CSI, OSC, OSC_ESCAPE, APC, APC_ESCAPE
    }

    companion object {
        private const val MAX_SCROLLBACK_LINES = 2_000
        private const val MAX_KITTY_CHUNK_CHARS = 8 * 1024
        private const val MAX_KITTY_BASE64_CHARS = 24 * 1024 * 1024
        private const val MAX_KITTY_IMAGE_BYTES = 18 * 1024 * 1024
        private const val MAX_KITTY_TOTAL_BYTES = 36 * 1024 * 1024
        private const val MAX_KITTY_IMAGES = 256
        private const val MAX_REMOTE_IMAGE_PATH_CHARS = 4_096
        private val DEC_SPECIAL_GRAPHICS = mapOf(
            '`' to "◆", 'a' to "▒", 'b' to "␉", 'c' to "␌", 'd' to "␍",
            'e' to "␊", 'f' to "°", 'g' to "±", 'h' to "␤", 'i' to "␋",
            'j' to "┘", 'k' to "┐", 'l' to "┌", 'm' to "└", 'n' to "┼",
            'o' to "⎺", 'p' to "⎻", 'q' to "─", 'r' to "⎼", 's' to "⎽",
            't' to "├", 'u' to "┤", 'v' to "┴", 'w' to "┬", 'x' to "│",
            'y' to "≤", 'z' to "≥", '{' to "π", '|' to "≠", '}' to "£",
            '~' to "·"
        )
        private val ANSI_COLORS = intArrayOf(
            0xFF101815.toInt(), 0xFFE06C75.toInt(), 0xFF65DDA5.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56D7D2.toInt(), 0xFFD8E1DC.toInt(),
            0xFF59635E.toInt(), 0xFFFF7B86.toInt(), 0xFF85F0BD.toInt(), 0xFFFFD68A.toInt(),
            0xFF81C7FF.toInt(), 0xFFDC98F2.toInt(), 0xFF75ECE7.toInt(), 0xFFF5FAF7.toInt()
        )
    }
}
