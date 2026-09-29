package com.tmuxer.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    @Test
    fun writesAndMovesCursor() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("hello\r\nworld\u001B[2;10H!".toByteArray())

        val snapshot = terminal.snapshot()
        assertEquals("hello", row(snapshot, 0).take(5))
        assertEquals("world    !", row(snapshot, 1).take(10))
        assertEquals(10, snapshot.cursorColumn)
        assertEquals(1, snapshot.cursorRow)
    }

    @Test
    fun keepsOsc8TerminalImageLinksOnTheirCells() {
        val terminal = TerminalEmulator(30, 5)
        val remotePath = "/home/test/.cache/tmuxer/pi-w7.images/" +
            "0123456789abcdef".repeat(4) + ".png"
        val encodedPath = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(remotePath.toByteArray())
        val hyperlink = "tmuxer-image://$encodedPath"

        terminal.feed(
            ("A\u001B[s\u001B]8;;$hyperlink\u001B\\图片\u001B]8;;\u001B\\\u001B[u").toByteArray()
        )

        val snapshot = terminal.snapshot()
        assertEquals(1, snapshot.cursorColumn)
        assertEquals(hyperlink, snapshot.cells[1].hyperlink)
        assertEquals(hyperlink, snapshot.cells[2].hyperlink)
        assertEquals(remotePath, terminalImagePathFromHyperlink(snapshot.cells[1].hyperlink))
        assertEquals(null, snapshot.cells[0].hyperlink)
    }

    @Test
    fun keepsOsc8WebLinksAndAcceptsOnlyHttpSchemes() {
        val terminal = TerminalEmulator(30, 5)
        val url = "https://example.com/docs?q=tmuxer"

        terminal.feed("\u001B]8;;$url\u001B\\文档\u001B]8;;\u001B\\".toByteArray())

        val hyperlink = terminal.snapshot().cells[0].hyperlink
        assertEquals(url, hyperlink)
        assertEquals(url, terminalWebUrlFromHyperlink(hyperlink))
        assertEquals("http://example.com", terminalWebUrlFromHyperlink("http://example.com"))
        assertEquals(null, terminalWebUrlFromHyperlink("file:///etc/passwd"))
        assertEquals(null, terminalWebUrlFromHyperlink("javascript:alert(1)"))
    }

    @Test
    fun copiesLongOsc52TextToAndroidClipboardSink() {
        val copied = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5).apply {
            onClipboardCopy = copied::add
        }
        val text = "Pi 原始回复\n" + "long line ".repeat(300)
        val payload = java.util.Base64.getEncoder().encodeToString(text.toByteArray())

        terminal.feed("\u001B]52;c;$payload\u0007".toByteArray())

        assertEquals(listOf(text), copied)
    }

    @Test
    fun acceptsOsc52StringTerminatorSplitAcrossPackets() {
        val copied = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5).apply {
            onClipboardCopy = copied::add
        }
        val text = "line one\nline two"
        val payload = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        val sequence = "\u001B]52;c;$payload\u001B\\".toByteArray()

        terminal.feed(sequence.copyOfRange(0, sequence.size - 1))
        assertTrue(copied.isEmpty())
        terminal.feed(sequence.copyOfRange(sequence.size - 1, sequence.size))

        assertEquals(listOf(text), copied)
    }

    @Test
    fun reportsOneRowImageLinkGeometryToPi() {
        val replies = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5, reply = replies::add)

        terminal.feed("\u001B[16t".toByteArray())

        assertEquals(listOf(TERMINAL_IMAGE_LINK_CELL_SIZE_RESPONSE), replies)
    }

    @Test
    fun decodesUtf8SplitAcrossNetworkPackets() {
        val terminal = TerminalEmulator(20, 5)
        val bytes = "你".toByteArray(Charsets.UTF_8)
        terminal.feed(bytes.copyOfRange(0, 2))
        terminal.feed(bytes.copyOfRange(2, 3))

        val cell = terminal.snapshot().cells[0]
        assertEquals("你", cell.text)
        assertEquals(2, cell.width)
    }

    @Test
    fun matchesWcwidthForEmojiAndFormatCharacters() {
        val terminal = TerminalEmulator(20, 5)
        // ✅ and ⭐ are East Asian Wide; U+200D (ZWJ) and U+200B are zero-width format characters.
        terminal.feed("\u2705\u2B50a\u200Bb\u200D".toByteArray())

        val snapshot = terminal.snapshot()
        assertEquals(2, snapshot.cells[0].width)
        assertEquals(2, snapshot.cells[2].width)
        assertEquals("a\u200B", snapshot.cells[4].text)
        assertEquals("b\u200D", snapshot.cells[5].text)
        assertEquals(6, snapshot.cursorColumn)
    }

    @Test
    fun attachesCombiningMarkToLastColumnWhenWrapIsPending() {
        val terminal = TerminalEmulator(3, 2)
        terminal.feed("abe\u0301".toByteArray())

        val snapshot = terminal.snapshot()
        assertEquals("b", snapshot.cells[1].text)
        assertEquals("e\u0301", snapshot.cells[2].text)
    }

    @Test
    fun reusesSnapshotStorageUntilGridDimensionsChange() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("A".toByteArray())
        val first = terminal.snapshot()

        terminal.feed("B".toByteArray())
        val reused = terminal.snapshot(first)
        assertSame(first, reused)
        assertEquals("AB", row(reused, 0).take(2))

        terminal.resize(20, 6)
        assertNotSame(first, terminal.snapshot(first))
    }

    @Test
    fun rendersDecSpecialGraphicsUsedByPiBorders() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("\u001B(0lqqk\u001B(B q".toByteArray())

        assertEquals("┌──┐ q", row(terminal.snapshot(), 0).take(6))
    }

    @Test
    fun supportsAlternateScreenAndRestoresMainScreen() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("main".toByteArray())
        terminal.feed("\u001B[?1049halt".toByteArray())
        assertEquals("alt", row(terminal.snapshot(), 0).take(3))

        terminal.feed("\u001B[?1049l".toByteArray())
        assertEquals("main", row(terminal.snapshot(), 0).take(4))
    }

    @Test
    fun ignoresPrivateKittyKeyboardRequestsInsteadOfRestoringCursor() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("A\u001B[s\u001B[5C\u001B[>1uX".toByteArray())

        assertEquals("A     X", row(terminal.snapshot(), 0).take(7))
    }

    @Test
    fun doesNotInjectDeviceAttributesIntoRemoteInput() {
        val replies = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5, replies::add)
        terminal.feed("\u001B[c".toByteArray())

        assertEquals(emptyList<String>(), replies)
    }

    @Test
    fun convertsTouchScrollToSgrMouseWheelForFullscreenApps() {
        val replies = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5, replies::add)
        assertFalse(terminal.isMouseTrackingActive())
        terminal.feed("\u001B[?1000h\u001B[?1006h".toByteArray())
        assertTrue(terminal.isMouseTrackingActive())
        terminal.scroll(rowsDown = 2, column = 4, row = 3)

        assertEquals("\u001B[<65;4;3M\u001B[<65;4;3M", replies.single())
        terminal.feed("\u001B[?1000l".toByteArray())
        assertFalse(terminal.isMouseTrackingActive())
    }

    @Test
    fun sendsPrimaryPressAndReleaseForAllSupportedMouseTrackingModes() {
        for (mode in listOf(1000, 1002, 1003)) {
            val replies = mutableListOf<String>()
            val terminal = TerminalEmulator(20, 5, replies::add)
            terminal.feed("\u001B[?${mode}h\u001B[?1006h".toByteArray())
            terminal.click(column = 4, row = 3)
            assertEquals(listOf("\u001B[<0;4;3M\u001B[<0;4;3m"), replies)
        }
    }

    @Test
    fun ignoresClicksWithoutMouseTrackingOrAfterItIsDisabled() {
        val replies = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5, replies::add)
        terminal.click(4, 3)
        terminal.feed("\u001B[?1000h\u001B[?1000l".toByteArray())
        terminal.click(4, 3)
        assertTrue(replies.isEmpty())
    }

    @Test
    fun clampsMouseClickCoordinatesToScreen() {
        val replies = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5, replies::add)
        terminal.feed("\u001B[?1000h\u001B[?1006h".toByteArray())
        terminal.click(-2, 99)
        assertEquals("\u001B[<0;1;5M\u001B[<0;1;5m", replies.single())
    }

    @Test
    fun supportsLegacyMouseClickProtocol() {
        val replies = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5, replies::add)
        terminal.feed("\u001B[?1000h".toByteArray())
        terminal.click(4, 3)
        assertEquals("\u001B[M $#\u001B[M#$#", replies.single())
    }

    @Test
    fun tracksBracketedPasteModeRequestedByPi() {
        val terminal = TerminalEmulator(20, 5)
        assertFalse(terminal.isBracketedPasteModeEnabled())

        terminal.feed("\u001B[?2004h".toByteArray())
        assertTrue(terminal.isBracketedPasteModeEnabled())

        terminal.feed("\u001B[?2004l".toByteArray())
        assertFalse(terminal.isBracketedPasteModeEnabled())
    }

    @Test
    fun scrollsTmuxAlternateScreenLocallyWithoutChangingShellHistory() {
        val replies = mutableListOf<String>()
        val terminal = TerminalEmulator(20, 5, replies::add)
        terminal.feed("\u001B[?1049h\u001B[1;4r".toByteArray())
        terminal.feed((1..8).joinToString("") { "$it\r\n" }.toByteArray())

        terminal.scroll(rowsDown = -2, column = 1, row = 1)

        assertEquals(emptyList<String>(), replies)
        assertEquals("4", row(terminal.snapshot(), 0).trim())
    }

    @Test
    fun switchesDefaultPaletteWithoutOverwritingExplicitAnsiColors() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("D\u001B[38;2;1;2;3mX".toByteArray())

        terminal.setTheme(TerminalTheme.LIGHT)

        val snapshot = terminal.snapshot()
        assertEquals(TERMINAL_LIGHT_FOREGROUND, snapshot.cells[0].foreground)
        assertEquals(TERMINAL_LIGHT_BACKGROUND, snapshot.cells[0].background)
        assertEquals(0xFF010203.toInt(), snapshot.cells[1].foreground)
        assertEquals(TERMINAL_LIGHT_BACKGROUND, snapshot.cells[1].background)
        assertEquals(TerminalTheme.LIGHT, terminal.theme)
    }

    @Test
    fun appliesAnsiColorAndErase() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("\u001B[31mR\u001B[0mN".toByteArray())
        val colored = terminal.snapshot()
        assertNotEquals(colored.cells[0].foreground, colored.cells[1].foreground)

        terminal.feed("\r\u001B[2K".toByteArray())
        assertEquals("                    ", row(terminal.snapshot(), 0))
    }

    @Test
    fun copiesSelectedTerminalTextWithoutWideCellDuplicates() {
        val terminal = TerminalEmulator(10, 3)
        terminal.feed("hello\r\n中文 ok".toByteArray())
        val snapshot = terminal.snapshot()

        assertEquals("hello", terminalSelectionText(snapshot, 0, 4))
        assertEquals("hello\n中文 ok", terminalSelectionText(snapshot, 0, 16))
        assertEquals("中文 ok", terminalSelectionText(snapshot, 16, 10))
    }

    @Test
    fun graphicsTrackerPreservesCursorPlacementWithoutRetainingScreenCells() {
        val visible = TerminalEmulator(20, 5)
        val tracker = TerminalEmulator(20, 5, retainScreenContent = false)
        val stream = (
            "12345678901234567890X" +
                "\u001B[2K" +
                "\u001B_Ga=T,f=100,c=4,r=2,i=9;aW1hZ2U=\u001B\\"
            ).toByteArray()

        visible.feed(stream)
        tracker.feed(stream)

        val visibleImage = visible.snapshot().images.single()
        val trackedSnapshot = tracker.snapshot()
        val trackedImage = trackedSnapshot.images.single()
        assertEquals(visibleImage.column, trackedImage.column)
        assertEquals(visibleImage.row, trackedImage.row)
        assertEquals(visibleImage.columns, trackedImage.columns)
        assertEquals(visibleImage.rows, trackedImage.rows)
        assertEquals("image", trackedImage.encodedData.toString(Charsets.UTF_8))
        assertTrue(trackedSnapshot.cells.all { it.text == " " })
    }

    @Test
    fun publishesOnlyFinalImageStateAfterHistoricalReplay() {
        val visible = TerminalEmulator(20, 8)
        var visibleChanges = 0
        visible.onChanged = { visibleChanges++ }
        val replay = TerminalEmulator(20, 8)
        replay.feed(
            ("\u001B_Ga=T,f=100,c=2,r=1,i=1;b2xk\u001B\\" +
                "\u001B_Ga=d,d=I,i=1,q=2\u001B\\" +
                "\u001B_Ga=T,f=100,c=3,r=2,i=2;Y3VycmVudA==\u001B\\").toByteArray()
        )

        assertTrue(visible.snapshot().images.isEmpty())
        assertEquals(0, visibleChanges)

        visible.replaceKittyGraphicsStateFrom(replay)

        val image = visible.snapshot().images.single()
        assertEquals(2L, image.imageId)
        assertEquals("current", image.encodedData.toString(Charsets.UTF_8))
        assertEquals(1, visibleChanges)

        replay.mirrorKittyGraphicsTo(visible)
        replay.feed(
            ("\u001B_Ga=d,d=I,i=2,q=2\u001B\\" +
                "\u001B_Ga=T,f=100,c=2,r=2,i=3;bGl2ZQ==\u001B\\").toByteArray()
        )
        val liveImage = visible.snapshot().images.single()
        assertEquals(3L, liveImage.imageId)
        assertEquals("live", liveImage.encodedData.toString(Charsets.UTF_8))
        assertEquals(3, visibleChanges)

        val restoredAgain = TerminalEmulator(20, 8)
        restoredAgain.replaceKittyGraphicsStateFrom(replay)
        val checkpointedLiveImage = restoredAgain.snapshot().images.single()
        assertEquals(3L, checkpointedLiveImage.imageId)
        assertEquals("live", checkpointedLiveImage.encodedData.toString(Charsets.UTF_8))
    }

    @Test
    fun keepsRemoteKittyImageAsLightweightReference() {
        val terminal = TerminalEmulator(20, 8)
        val remotePath = "/home/test/.cache/tmuxer/pi-w7.stream.images/" +
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.png"
        val payload = java.util.Base64.getEncoder().encodeToString(remotePath.toByteArray())

        terminal.feed(
            "\u001B_Ga=T,t=f,tmuxer=1,M=image/png,c=10,r=4,i=7;$payload\u001B\\".toByteArray()
        )

        val image = terminal.snapshot().images.single()
        assertEquals(7L, image.imageId)
        assertTrue(image.encodedData.isEmpty())
        assertEquals(remotePath, image.remotePath)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun assemblesAndPlacesChunkedKittyImages() {
        val terminal = TerminalEmulator(20, 8)
        terminal.feed(
            ("\u001B[3;5H" +
                "\u001B_Ga=T,f=100,c=4,r=3,i=4294967294,C=1,m=1;aW1h\u001B\\" +
                "\u001B_Gm=0;Z2U=\u001B\\").toByteArray()
        )

        val image = terminal.snapshot().images.single()
        assertEquals(4294967294L, image.imageId)
        assertEquals("image", image.encodedData.toString(Charsets.UTF_8))
        assertEquals(4, image.column)
        assertEquals(2, image.row)
        assertEquals(4, image.columns)
        assertEquals(3, image.rows)

        terminal.feed("\u001B_Ga=d,d=a,q=2\u001B\\".toByteArray())
        assertTrue(terminal.snapshot().images.isEmpty())

        terminal.feed("\u001B[2;2H\u001B_Ga=p,q=2,i=4294967294,c=2,r=1\u001B\\".toByteArray())
        val moved = terminal.snapshot().images.single()
        assertEquals(1, moved.column)
        assertEquals(1, moved.row)
        assertEquals(2, moved.columns)
        assertEquals(1, moved.rows)

        terminal.feed("\u001B_Ga=d,d=I,i=4294967294,q=2\u001B\\".toByteArray())
        assertTrue(terminal.snapshot().images.isEmpty())
    }

    @Test
    fun scrollRegionKeepsStatusRowAndMovesTopLineToHistory() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed("\u001B[4;1HSTATUS\u001B[1;3rone\r\ntwo\r\nthree\r\nfour".toByteArray())

        var snapshot = terminal.snapshot()
        assertEquals(listOf("two", "three", "four", "STATUS"), (0 until 4).map { row(snapshot, it).trimEnd() })

        // Screen cells handed to history must not alias cells that are still being written.
        terminal.feed("\u001B[1;1HXX\r\n\u001B[3;1Hfive\r\n".toByteArray())
        terminal.scroll(-2, 1, 1)
        snapshot = terminal.snapshot()
        assertEquals("one", row(snapshot, 0).trimEnd())
        assertEquals("XXo", row(snapshot, 1).trimEnd())
    }

    @Test
    fun scrollDownInsertsBlankLineAtRegionTop() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed("a\r\nb\r\nc\r\nd\u001B[1;3r\u001B[T".toByteArray())

        val snapshot = terminal.snapshot()
        assertEquals(listOf("", "a", "b", "d"), (0 until 4).map { row(snapshot, it).trimEnd() })
    }

    @Test
    fun parsesSgrParametersAndIgnoresPrivateModifyOtherKeys() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed("\u001B[>4;1mx\u001B[1;4;38;2;1;2;3my\u001B[0mz".toByteArray())

        val cells = terminal.snapshot().cells
        assertFalse(cells[0].bold)
        assertFalse(cells[0].underline)
        assertTrue(cells[1].bold)
        assertTrue(cells[1].underline)
        assertEquals(0xFF010203.toInt(), cells[1].foreground)
        assertFalse(cells[2].bold)
    }

    @Test
    fun insertsAndDeletesCharactersWithinRow() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed("abcdef\u001B[1;2H\u001B[2P".toByteArray())
        assertEquals("adef", row(terminal.snapshot(), 0).trimEnd())

        terminal.feed("\u001B[1;2H\u001B[2@XY".toByteArray())
        assertEquals("aXYdef", row(terminal.snapshot(), 0).trimEnd())
    }

    @Test
    fun keepsStyledHistoryAfterScrollingAndAcrossColumnResizes() {
        val terminal = TerminalEmulator(20, 5)
        val link = "https://example.com/a"
        terminal.feed(
            ("\u001B[1;4;31mred\u001B[0m \u001B[44;7m中\u001B[0m e\u0301 " +
                "\u001B]8;;$link\u001B\\L\u001B]8;;\u001B\\\u001B[42m  \u001B[0m\r\n").toByteArray()
        )
        terminal.feed((1..10).joinToString("") { "line$it\r\n" }.toByteArray())

        fun assertFirstHistoryLine(columns: Int) {
            terminal.scroll(-1000, 1, 1)
            val snapshot = terminal.snapshot()
            assertEquals(columns, snapshot.columns)
            val cells = snapshot.cells
            assertEquals("red", (0..2).joinToString("") { cells[it].text })
            assertTrue(cells[0].bold)
            assertTrue(cells[0].underline)
            assertEquals(0xFFE06C75.toInt(), cells[0].foreground)
            assertFalse(cells[3].bold)
            assertEquals(TERMINAL_DEFAULT_FOREGROUND, cells[3].foreground)
            assertEquals("中", cells[4].text)
            assertEquals(2, cells[4].width)
            assertTrue(cells[4].inverse)
            assertEquals(0xFF61AFEF.toInt(), cells[4].background)
            assertEquals(0, cells[5].width)
            assertEquals("e\u0301", cells[7].text)
            assertEquals(link, cells[9].hyperlink)
            assertEquals(null, cells[8].hyperlink)
            // Trailing blanks with a non-default background survive trimming.
            assertEquals(0xFF65DDA5.toInt(), cells[10].background)
            assertEquals(0xFF65DDA5.toInt(), cells[11].background)
            for (column in 12 until columns) {
                assertEquals(" ", cells[column].text)
                assertEquals(1, cells[column].width)
                assertEquals(TERMINAL_DEFAULT_BACKGROUND, cells[column].background)
            }
            assertEquals("line1", row(snapshot, 1).trimEnd())
            assertFalse(snapshot.cursorVisible)
            terminal.scroll(1000, 1, 1)
        }

        assertFirstHistoryLine(20)
        terminal.resize(40, 5)
        assertFirstHistoryLine(40)
        // Narrowing and widening again must not lose history text beyond the narrow width.
        terminal.feed("\u001B[1;1H".toByteArray())
        terminal.resize(20, 5)
        terminal.resize(30, 5)
        assertFirstHistoryLine(30)
    }

    @Test
    fun truncatesHistoryLinesWhenNarrowerThanWrittenWidth() {
        val terminal = TerminalEmulator(30, 5)
        terminal.feed(("abcdefghijklmnopqrstuvwxyz0123\r\n" + "\r\n".repeat(5)).toByteArray())
        terminal.resize(20, 5)
        terminal.scroll(-1000, 1, 1)

        assertEquals("abcdefghijklmnopqrst", row(terminal.snapshot(), 0))

        // Widening again restores the full line; history was never rewritten.
        terminal.resize(40, 5)
        assertEquals("abcdefghijklmnopqrstuvwxyz0123", row(terminal.snapshot(), 0).trimEnd())
    }

    @Test
    fun evictsOldestHistoryAndClearsItOnEraseSavedLines() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed((0 until 2_010).joinToString("") { "n$it\r\n" }.toByteArray())
        terminal.scroll(-10_000, 1, 1)
        // 2,010 lines scrolled plus the cursor line: history keeps the newest 2,000 (n6..n2005).
        assertEquals("n6", row(terminal.snapshot(), 0).trimEnd())

        terminal.scroll(10_000, 1, 1)
        terminal.feed("\u001B[3J".toByteArray())
        terminal.scroll(-10, 1, 1)
        assertTrue(terminal.snapshot().cursorVisible)
    }

    @Test
    fun keepsViewportAnchoredWhileNewLinesArrive() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed((0 until 20).joinToString("") { "n$it\r\n" }.toByteArray())
        terminal.scroll(-3, 1, 1)
        val before = row(terminal.snapshot(), 0)
        terminal.feed("more\r\nlines\r\n".toByteArray())
        assertEquals(before, row(terminal.snapshot(), 0))
    }

    @Test
    fun remapsDefaultColorsInPackedHistoryOnThemeChange() {
        val terminal = TerminalEmulator(20, 5)
        terminal.feed("D\u001B[38;2;1;2;3mX\u001B[0m\u001B[41mB\u001B[0m\r\n".toByteArray())
        terminal.feed("plain\r\n".repeat(6).toByteArray())

        terminal.setTheme(TerminalTheme.LIGHT)
        terminal.scroll(-1000, 1, 1)
        var cells = terminal.snapshot().cells
        assertEquals(TERMINAL_LIGHT_FOREGROUND, cells[0].foreground)
        assertEquals(TERMINAL_LIGHT_BACKGROUND, cells[0].background)
        assertEquals(0xFF010203.toInt(), cells[1].foreground)
        assertEquals(TERMINAL_LIGHT_BACKGROUND, cells[1].background)
        assertEquals(TERMINAL_LIGHT_FOREGROUND, cells[2].foreground)
        assertEquals(0xFFE06C75.toInt(), cells[2].background)
        assertEquals(TERMINAL_LIGHT_BACKGROUND, cells[19].background)
        assertEquals(TERMINAL_LIGHT_FOREGROUND, cells[20].foreground)

        terminal.setTheme(TerminalTheme.DARK)
        cells = terminal.snapshot().cells
        assertEquals(TERMINAL_DEFAULT_FOREGROUND, cells[0].foreground)
        assertEquals(TERMINAL_DEFAULT_BACKGROUND, cells[1].background)
        assertEquals(0xFF010203.toInt(), cells[1].foreground)
    }

    @Test
    fun swallowsDcsPayloadsUntilStringTerminator() {
        val terminal = TerminalEmulator(40, 5)
        terminal.feed(
            ("a\u001BPtmux;\u001B\u001B]52;c;aGk=\u0007\u001B\\b" +
                "\u001BP+q544e\u001B\\c" +
                "\u001BPq#0;2;0;0;0#0~~@@vv\r\n-\u001B\\d").toByteArray()
        )
        assertEquals("abcd", row(terminal.snapshot(), 0).trimEnd())
        assertEquals(0, terminal.snapshot().cursorRow)

        // Split across packets, and aborted by CAN / SUB.
        terminal.feed("\u001BPq junk".toByteArray())
        terminal.feed(" more junk\u001B".toByteArray())
        terminal.feed("\\e\u001BPjunk\u0018f\u001BPjunk\u001Ag".toByteArray())
        assertEquals("abcdefg", row(terminal.snapshot(), 0).trimEnd())
    }

    @Test
    fun executesC0ControlsInsideCsiAndAbortsOnEscOrCan() {
        val terminal = TerminalEmulator(20, 5)
        // CSI 3 <CR> C: the CR executes, then CSI 3 C moves right from column 0.
        terminal.feed("abcdef\u001B[3\rCX".toByteArray())
        assertEquals("abcXef", row(terminal.snapshot(), 0).trimEnd())

        // ESC inside CSI abandons it and starts a new sequence; CAN cancels it outright.
        terminal.feed("\r\u001B[12\u001B[2CY\u001B[5\u0018Z".toByteArray())
        assertEquals("abYZef", row(terminal.snapshot(), 0).trimEnd())

        // Backspace inside CSI executes without polluting the parameters.
        terminal.feed("\u001B[1;\b4H*".toByteArray())
        assertEquals("abY*ef", row(terminal.snapshot(), 0).trimEnd())
    }

    @Test
    fun faintDoesNotClearBoldButNormalIntensityDoes() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed("\u001B[1ma\u001B[2mb\u001B[22mc".toByteArray())

        val cells = terminal.snapshot().cells
        assertTrue(cells[0].bold)
        assertTrue(cells[1].bold)
        assertFalse(cells[2].bold)
    }

    @Test
    fun decscAndDecrcSaveAndRestoreAttributesAndCharsets() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed(
            ("\u001B[1;4;7;31;42m\u001B(0\u001B[2;3H\u001B7" +
                "\u001B[0m\u001B(B\u001B[4;10Hz\u001B8q").toByteArray()
        )

        val snapshot = terminal.snapshot()
        val restored = snapshot.cells[1 * 20 + 2]
        assertEquals("─", restored.text)
        assertTrue(restored.bold)
        assertTrue(restored.underline)
        assertTrue(restored.inverse)
        assertEquals(0xFFE06C75.toInt(), restored.foreground)
        assertEquals(0xFF65DDA5.toInt(), restored.background)
        val plain = snapshot.cells[3 * 20 + 9]
        assertEquals("z", plain.text)
        assertFalse(plain.bold)
        assertEquals(TERMINAL_DEFAULT_FOREGROUND, plain.foreground)

        // CSI s / u still restore only the cursor position.
        terminal.feed("\u001B[0m\u001B(B\u001B[1;1H\u001B[s\u001B[1;31m\u001B[3;3H\u001B[uw".toByteArray())
        val cell = terminal.snapshot().cells[0]
        assertEquals("w", cell.text)
        assertTrue(cell.bold)
        assertEquals(0xFFE06C75.toInt(), cell.foreground)
    }

    @Test
    fun capsCombiningMarksPerCell() {
        val terminal = TerminalEmulator(20, 4)
        terminal.feed(("e" + "\u0301".repeat(10_000) + "x").toByteArray())

        val cells = terminal.snapshot().cells
        assertTrue(cells[0].text.length <= 32)
        assertTrue(cells[0].text.startsWith("e\u0301"))
        assertEquals("x", cells[1].text)
    }

    private fun row(snapshot: TerminalSnapshot, row: Int): String =
        (0 until snapshot.columns).joinToString("") {
            snapshot.cells[row * snapshot.columns + it].text
        }
}
