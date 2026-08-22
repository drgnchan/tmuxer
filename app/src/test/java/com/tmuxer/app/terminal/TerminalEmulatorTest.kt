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

    private fun row(snapshot: TerminalSnapshot, row: Int): String =
        (0 until snapshot.columns).joinToString("") {
            snapshot.cells[row * snapshot.columns + it].text
        }
}
