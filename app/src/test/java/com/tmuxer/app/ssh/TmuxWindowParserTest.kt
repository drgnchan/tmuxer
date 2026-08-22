package com.tmuxer.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
