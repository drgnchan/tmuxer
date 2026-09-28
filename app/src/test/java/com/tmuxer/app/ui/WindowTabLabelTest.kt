package com.tmuxer.app.ui

import com.tmuxer.app.data.TmuxWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowTabLabelTest {
    @Test
    fun includesSessionNameAndIndex() {
        assertEquals("mobile:1", windowTabLabel(window(name = "pi")))
    }

    @Test
    fun doesNotIncludeWindowName() {
        assertEquals("mobile:1", windowTabLabel(window(name = "editor")))
    }

    private fun window(name: String) = TmuxWindow(
        sessionName = "mobile",
        sessionId = "\$1",
        index = 1,
        windowId = "@2",
        name = name,
        active = true,
        paneCount = 1,
        command = "bash",
        path = "/home/user",
        activity = false,
        last = false
    )
}
