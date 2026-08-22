package com.tmuxer.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxWindowParserTest {
    @Test
    fun parsesPrintableFieldSeparatorUsedByTmux() {
        val line = listOf(
            "mobile", "\$0", "2", "@7", "agent",
            "1", "3", "pi", "/root/project", "1", "0"
        ).joinToString(TMUX_FIELD_SEPARATOR)

        val window = parseTmuxWindow(line)!!
        assertEquals("mobile", window.sessionName)
        assertEquals(2, window.index)
        assertEquals("@7", window.windowId)
        assertEquals("agent", window.name)
        assertEquals(3, window.paneCount)
        assertEquals("pi", window.command)
        assertEquals("/root/project", window.path)
        assertEquals(true, window.active)
        assertEquals(true, window.activity)
    }

    @Test
    fun rejectsSanitizedTabOutputInsteadOfCreatingCorruptWindow() {
        assertNull(parseTmuxWindow("mobile_\$0_0_@0_bash_1_1_bash_/root_0_0"))
    }

    @Test
    fun acceptsOnlyGeneratedTerminalImagePaths() {
        val hash = "0123456789abcdef".repeat(4)
        assertTrue(
            isAllowedTerminalImagePath(
                "/home/raymond",
                "/home/raymond/.cache/tmuxer/images/$hash.png"
            )
        )
        assertTrue(
            isAllowedTerminalImagePath(
                "/home/raymond",
                "/home/raymond/.cache/tmuxer/pi-w7.stream.images/$hash.png"
            )
        )
        assertTrue(
            isAllowedTerminalImagePath(
                "/home/raymond",
                "/home/raymond/.cache/tmuxer/pi-w7.images/$hash.webp"
            )
        )
        assertFalse(
            isAllowedTerminalImagePath(
                "/home/raymond",
                "/home/raymond/.ssh/id_ed25519"
            )
        )
        assertFalse(
            isAllowedTerminalImagePath(
                "/home/raymond",
                "/home/raymond/.cache/tmuxer/pi-w7.stream.images/not-a-hash.png"
            )
        )
    }

    @Test
    fun resumesImageReplayOnlyFromMatchingValidCheckpoint() {
        val checkpoint = ImageStreamCheckpoint("@7", "/tmp/stream:1:2", 900)
        val resumed = planImageReplay("@7", "/tmp/stream:1:2", 1_000, checkpoint)

        assertTrue(resumed.resumed)
        assertEquals(901L, resumed.firstByte)
        assertEquals(100L, resumed.replayBytes)

        val replaced = planImageReplay("@7", "/tmp/stream:1:3", 1_000, checkpoint)
        assertFalse(replaced.resumed)
        assertEquals(1L, replaced.firstByte)
        assertEquals(1_000L, replaced.replayBytes)

        val large = planImageReplay(
            "@7",
            "/tmp/stream:1:2",
            MAX_IMAGE_STREAM_REPLAY_BYTES.toLong() + 123,
            null
        )
        assertFalse(large.resumed)
        assertEquals(124L, large.firstByte)
        assertEquals(MAX_IMAGE_STREAM_REPLAY_BYTES.toLong(), large.replayBytes)
    }
}
