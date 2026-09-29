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
internal const val TERMINAL_IMAGE_LINK_CELL_SIZE_RESPONSE = "\u001B[6;100;1t"
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
    var inverse: Boolean = false,
    var hyperlink: String? = null
) {
    fun duplicate() = copy()

    fun copyFrom(source: TerminalCell) {
        text = source.text
        width = source.width
        foreground = source.foreground
        background = source.background
        bold = source.bold
        underline = source.underline
        inverse = source.inverse
        hyperlink = source.hyperlink
    }

    /** Resets this cell in place; the emulator mutates cells instead of allocating new ones. */
    fun blank(foreground: Int, background: Int, bold: Boolean, underline: Boolean, inverse: Boolean) {
        text = " "
        width = 1
        this.foreground = foreground
        this.background = background
        this.bold = bold
        this.underline = underline
        this.inverse = inverse
        hyperlink = null
    }
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
    private var bracketedPasteMode = false

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
    private var activeHyperlink: String? = null

    private var parserState = ParserState.NORMAL
    private val sequence = StringBuilder()
    // CSI parameters parsed in place from [sequence]; avoids per-sequence strings and lists.
    private val csiParameters = IntArray(MAX_CSI_PARAMETERS)
    private var csiParameterCount = 0
    private var oscSequenceOverflow = false
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

    @Volatile
    var onClipboardCopy: ((String) -> Unit)? = null

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
            bracketedPasteMode = false
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
            oscSequenceOverflow = false
            charsetSequencePending = false
            pendingCharsetSlot = 0
            g0LineDrawing = false
            g1LineDrawing = false
            activeCharsetSlot = 0
            pendingUtf8 = EMPTY_BYTE_ARRAY
            pendingHighSurrogate = null
            activeHyperlink = null
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

        fun copyCell(source: TerminalCell, target: TerminalCell) = target.copyFrom(source)

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

    /** Send a complete primary-button click only to a mouse-aware live screen. */
    fun click(column: Int, row: Int) {
        val response = synchronized(this) {
            if (!mouseTracking || viewportOffset != 0) return
            val x = column.coerceIn(1, columns)
            val y = row.coerceIn(1, rows)
            if (sgrMouseProtocol) {
                "\u001B[<0;$x;${y}M\u001B[<0;$x;${y}m"
            } else {
                // Legacy X10 coordinates cannot represent cells beyond 223.
                val coordinates = "${(32 + x.coerceAtMost(223)).toChar()}${(32 + y.coerceAtMost(223)).toChar()}"
                "\u001B[M $coordinates\u001B[M#$coordinates"
            }
        }
        reply(response)
    }

    @Synchronized
    fun isBracketedPasteModeEnabled(): Boolean = bracketedPasteMode

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
                    '\u0007' -> finishOsc()
                    '\u001B' -> parserState = ParserState.OSC_ESCAPE
                    else -> {
                        if (sequence.length < MAX_OSC_SEQUENCE_CHARS) sequence.append(char)
                        else oscSequenceOverflow = true
                    }
                }
            }
            ParserState.OSC_ESCAPE -> {
                if (char == '\\') {
                    finishOsc()
                } else {
                    parserState = ParserState.OSC
                }
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
                oscSequenceOverflow = false
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
            applyCsi(char)
            sequence.clear()
            parserState = ParserState.NORMAL
        } else if (sequence.length < 256) {
            sequence.append(char)
        }
    }

    /**
     * Parses [sequence] into [csiParameters]: leading `?`, `>` and `!` markers are skipped, other
     * non-parameter characters ignored, colon sub-parameters dropped, and overflowing or empty
     * values read as 0.
     */
    private fun parseCsiParameters() {
        csiParameterCount = 0
        var start = 0
        while (start < sequence.length && sequence[start].let { it == '?' || it == '>' || it == '!' }) {
            start++
        }
        var current = 0
        var overflow = false
        var inSubParameter = false
        var sawParameter = false
        for (position in start until sequence.length) {
            val char = sequence[position]
            when {
                char in '0'..'9' -> {
                    sawParameter = true
                    if (!inSubParameter && !overflow) {
                        val digit = char - '0'
                        if (current > (Int.MAX_VALUE - digit) / 10) overflow = true
                        else current = current * 10 + digit
                    }
                }
                char == ':' -> {
                    sawParameter = true
                    inSubParameter = true
                }
                char == ';' -> {
                    sawParameter = true
                    if (csiParameterCount < csiParameters.size) {
                        csiParameters[csiParameterCount++] = if (overflow) 0 else current
                    }
                    current = 0
                    overflow = false
                    inSubParameter = false
                }
            }
        }
        if (sawParameter && csiParameterCount < csiParameters.size) {
            csiParameters[csiParameterCount++] = if (overflow) 0 else current
        }
    }

    private fun parameterOrNull(index: Int): Int? =
        if (index < csiParameterCount) csiParameters[index] else null

    private fun applyCsi(command: Char) {
        val prefix = sequence.firstOrNull()
        val privateMode = prefix == '?'
        parseCsiParameters()
        fun value(index: Int, fallback: Int = 1): Int =
            parameterOrNull(index)?.takeIf { it != 0 } ?: fallback

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
            'J' -> eraseDisplay(parameterOrNull(0) ?: 0)
            'K' -> eraseLine(parameterOrNull(0) ?: 0)
            '@' -> insertCharacters(value(0))
            'P' -> deleteCharacters(value(0))
            'X' -> eraseCharacters(value(0))
            'L' -> insertLines(value(0))
            'M' -> deleteLines(value(0))
            'S' -> scrollUp(value(0))
            'T' -> scrollDown(value(0))
            // CSI > Ps m (xterm modifyOtherKeys) and similar private forms are not SGR.
            'm' -> if (prefix == null || prefix !in charArrayOf('>', '<', '=', '?')) applyStyle()
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
                if (prefix == null || prefix !in charArrayOf('?', '>', '<', '=')) {
                    cursorColumn = savedColumn.coerceIn(0, columns - 1)
                    cursorRow = savedRow.coerceIn(0, rows - 1)
                }
            }
            'h', 'l' -> setModes(privateMode, command == 'h')
            'n' -> when (parameterOrNull(0)) {
                5 -> reply("\u001B[0n")
                6 -> reply("\u001B[${cursorRow + 1};${cursorColumn + 1}R")
            }
            't' -> if (parameterOrNull(0) == 16) {
                // tmuxer renders images as one-row links. Reporting a deliberately tall cell makes
                // Pi reserve one row for Kitty output instead of its normal 20-30 image rows.
                reply(TERMINAL_IMAGE_LINK_CELL_SIZE_RESPONSE)
            }
            // Do not answer DA here. tmux can emit a late DA probe after attaching; on a few
            // servers that reply is forwarded to the pane and appears as literal "?1;2c" input.
        }
        if (command !in charArrayOf('m', 'h', 'l')) wrapPending = false
    }

    private fun finishOsc() {
        if (!oscSequenceOverflow) processOsc(sequence.toString())
        sequence.clear()
        oscSequenceOverflow = false
        parserState = ParserState.NORMAL
    }

    private fun processOsc(raw: String) {
        val parts = raw.split(';', limit = 3)
        if (parts.size != 3) return
        when (parts[0]) {
            "8" -> activeHyperlink = parts[2].takeIf { it.isNotEmpty() }
            "52" -> decodeOsc52Clipboard(parts[1], parts[2])?.let { onClipboardCopy?.invoke(it) }
        }
    }

    private fun decodeOsc52Clipboard(selection: String, payload: String): String? {
        if (selection.isEmpty() || selection.any { it !in "cpsq01234567" }) return null
        if (payload == "?" || payload.length > MAX_OSC52_BASE64_CHARS) return null
        val decoded = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        if (decoded.size > MAX_OSC52_CLIPBOARD_BYTES) return null
        return decoded.toString(Charsets.UTF_8)
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
            // With a wrap pending the cursor still sits on the last glyph written.
            val previousColumn = if (wrapPending) cursorColumn else (cursorColumn - 1).coerceAtLeast(0)
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
            cell.hyperlink = activeHyperlink
            if (glyphWidth == 2) {
                cells[index(cursorColumn + 1, cursorRow)].apply {
                    blankInPlace(this)
                    width = 0
                    hyperlink = activeHyperlink
                }
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
            0 -> if (column > 0) blankInPlace(cells[index(column - 1, row)])
            2 -> if (column + 1 < columns) blankInPlace(cells[index(column + 1, row)])
        }
        blankInPlace(cells[position])
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
            cells[rowStart + column].copyFrom(cells[rowStart + column - amount])
        }
        eraseRange(rowStart + cursorColumn, rowStart + cursorColumn + amount - 1)
    }

    private fun deleteCharacters(count: Int) {
        if (!retainScreenContent) return
        val amount = count.coerceAtMost(columns - cursorColumn)
        val rowStart = index(0, cursorRow)
        for (column in cursorColumn until columns - amount) {
            cells[rowStart + column].copyFrom(cells[rowStart + column + amount])
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
        val buffer = cells
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            val topStart = index(0, scrollTop)
            // tmux reserves its bottom status row and scrolls only 0..rows-2. The removed top
            // line still belongs in local transcript history whenever the region starts at row 0.
            val recycled: Array<TerminalCell>
            if (scrollTop == 0) {
                // Hand the top row's cells to history as-is; the rows below move by reference.
                scrollback.addLast(buffer.copyOfRange(topStart, topStart + columns))
                if (viewportOffset > 0) viewportOffset++
                var evicted: Array<TerminalCell>? = null
                while (scrollback.size > MAX_SCROLLBACK_LINES) {
                    evicted = scrollback.removeFirst()
                    viewportOffset = viewportOffset.coerceAtMost(scrollback.size)
                }
                recycled = evicted?.takeIf { it.size == columns } ?: Array(columns) { defaultCell() }
            } else {
                recycled = buffer.copyOfRange(topStart, topStart + columns)
            }
            System.arraycopy(buffer, topStart + columns, buffer, topStart, (scrollBottom - scrollTop) * columns)
            val bottomStart = index(0, scrollBottom)
            for (column in 0 until columns) {
                buffer[bottomStart + column] = recycled[column].also(::blankInPlace)
            }
        }
    }

    private fun scrollDown(count: Int) {
        if (!retainScreenContent) return
        val buffer = cells
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            val topStart = index(0, scrollTop)
            val bottomStart = index(0, scrollBottom)
            val recycled = buffer.copyOfRange(bottomStart, bottomStart + columns)
            System.arraycopy(buffer, topStart, buffer, topStart + columns, (scrollBottom - scrollTop) * columns)
            for (column in 0 until columns) {
                buffer[topStart + column] = recycled[column].also(::blankInPlace)
            }
        }
    }

    private fun copyRow(from: Int, to: Int) {
        for (column in 0 until columns) {
            cells[index(column, to)].copyFrom(cells[index(column, from)])
        }
    }

    private fun clearRow(row: Int) = eraseRange(index(0, row), index(columns - 1, row))

    private fun eraseRange(start: Int, end: Int) {
        if (end < start) return
        val buffer = cells
        for (position in start.coerceAtLeast(0)..end.coerceAtMost(buffer.lastIndex)) {
            blankInPlace(buffer[position])
        }
    }

    private fun applyStyle() {
        if (csiParameterCount == 0) csiParameters[csiParameterCount++] = 0
        var cursor = 0
        while (cursor < csiParameterCount) {
            when (val code = csiParameters[cursor]) {
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
                    when (parameterOrNull(cursor + 1)) {
                        5 -> {
                            val color = xtermColor(parameterOrNull(cursor + 2) ?: 0)
                            if (isForeground) foreground = color else background = color
                            cursor += 2
                        }
                        2 -> {
                            val red = (parameterOrNull(cursor + 2) ?: 0).coerceIn(0, 255)
                            val green = (parameterOrNull(cursor + 3) ?: 0).coerceIn(0, 255)
                            val blue = (parameterOrNull(cursor + 4) ?: 0).coerceIn(0, 255)
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

    private fun setModes(privateMode: Boolean, enabled: Boolean) {
        for (parameterIndex in 0 until csiParameterCount) {
            val mode = csiParameters[parameterIndex]
            if (privateMode) {
                when (mode) {
                    25 -> cursorVisible = enabled
                    1000, 1002, 1003 -> mouseTracking = enabled
                    1006 -> sgrMouseProtocol = enabled
                    2004 -> bracketedPasteMode = enabled
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

    private fun blankInPlace(cell: TerminalCell) =
        cell.blank(foreground, background, bold, underline, inverse)

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
        if (lineDrawing) DEC_SPECIAL_GRAPHICS[char]?.let { return it }
        return if (char.code < ASCII_STRINGS.size) ASCII_STRINGS[char.code] else char.toString()
    }

    private fun wcWidth(codePoint: Int): Int {
        val type = Character.getType(codePoint)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            (type == Character.FORMAT.toInt() && codePoint != 0x00AD) ||
            codePoint in 0x1160..0x11FF || codePoint in 0xD7B0..0xD7FF ||
            codePoint in 0xFE00..0xFE0F
        ) return 0
        return if (isWideCodePoint(codePoint)) 2 else 1
    }

    private fun isWideCodePoint(codePoint: Int): Boolean {
        if (codePoint < WIDE_RANGES[0]) return false
        var low = 0
        var high = WIDE_RANGES.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < WIDE_RANGES[mid * 2] -> high = mid - 1
                codePoint > WIDE_RANGES[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
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
        private const val MAX_CSI_PARAMETERS = 256
        private val ASCII_STRINGS = Array(128) { it.toChar().toString() }
        private const val MAX_OSC_SEQUENCE_CHARS = 128 * 1024
        private const val MAX_OSC52_BASE64_CHARS = 100_000
        private const val MAX_OSC52_CLIPBOARD_BYTES = 75_000
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
        // East Asian Width W/F ranges (inclusive start/end pairs) from Unicode 16.0, matching
        // glibc wcwidth() so the cursor stays aligned with tmux.
        private val WIDE_RANGES = intArrayOf(
            0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC, 0x23F0, 0x23F0,
            0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, 0x2630, 0x2637, 0x2648, 0x2653,
            0x267F, 0x267F, 0x268A, 0x268F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB,
            0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA,
            0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705,
            0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755,
            0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C,
            0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x2E80, 0x2E99, 0x2E9B, 0x2EF3, 0x2F00, 0x2FD5,
            0x2FF0, 0x303E, 0x3041, 0x3096, 0x3099, 0x30FF, 0x3105, 0x312F, 0x3131, 0x318E,
            0x3190, 0x31E5, 0x31EF, 0x321E, 0x3220, 0x3247, 0x3250, 0xA48C, 0xA490, 0xA4C6,
            0xA960, 0xA97C, 0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE52,
            0xFE54, 0xFE66, 0xFE68, 0xFE6B, 0xFF01, 0xFF60, 0xFFE0, 0xFFE6, 0x16FE0, 0x16FE4,
            0x16FF0, 0x16FF1, 0x17000, 0x187F7, 0x18800, 0x18CD5, 0x18CFF, 0x18D08, 0x1AFF0, 0x1AFF3,
            0x1AFF5, 0x1AFFB, 0x1AFFD, 0x1AFFE, 0x1B000, 0x1B122, 0x1B132, 0x1B132, 0x1B150, 0x1B152,
            0x1B155, 0x1B155, 0x1B164, 0x1B167, 0x1B170, 0x1B2FB, 0x1D300, 0x1D356, 0x1D360, 0x1D376,
            0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F200, 0x1F202,
            0x1F210, 0x1F23B, 0x1F240, 0x1F248, 0x1F250, 0x1F251, 0x1F260, 0x1F265, 0x1F300, 0x1F320,
            0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3,
            0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440, 0x1F442, 0x1F4FC,
            0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596,
            0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2,
            0x1F6D5, 0x1F6D7, 0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB,
            0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C,
            0x1FA80, 0x1FA89, 0x1FA8F, 0x1FAC6, 0x1FACE, 0x1FADC, 0x1FADF, 0x1FAE9, 0x1FAF0, 0x1FAF8,
            0x20000, 0x2FFFD, 0x30000, 0x3FFFD
        )
        private val ANSI_COLORS = intArrayOf(
            0xFF101815.toInt(), 0xFFE06C75.toInt(), 0xFF65DDA5.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56D7D2.toInt(), 0xFFD8E1DC.toInt(),
            0xFF59635E.toInt(), 0xFFFF7B86.toInt(), 0xFF85F0BD.toInt(), 0xFFFFD68A.toInt(),
            0xFF81C7FF.toInt(), 0xFFDC98F2.toInt(), 0xFF75ECE7.toInt(), 0xFFF5FAF7.toInt()
        )
    }
}
