package com.tmuxer.app.ui

import com.tmuxer.app.data.TmuxWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowCardLabelTest {
    @Test
    fun titlePrefersSessionName() {
        assertEquals(
            "按账号表批量创建 sandbox",
            windowCardTitle(window(sessionName = "按账号表批量创建 sandbox", name = "pi"))
        )
    }

    @Test
    fun titleFallsBackToWindowName() {
        assertEquals("editor", windowCardTitle(window(sessionName = "  ", name = "editor")))
    }

    @Test
    fun titleFallsBackToPlaceholderWhenUnnamed() {
        assertEquals("未命名会话", windowCardTitle(window(sessionName = "", name = "  ")))
    }

    @Test
    fun labelHidesDefaultWindowNameMatchingCommand() {
        assertNull(windowCardWindowLabel(window(name = "pi")))
    }

    @Test
    fun labelHidesWindowNameMatchingTitle() {
        assertNull(windowCardWindowLabel(window(name = "mobile")))
    }

    @Test
    fun labelShowsDistinctWindowName() {
        assertEquals("editor", windowCardWindowLabel(window(name = "editor", command = "nvim")))
    }

    private fun window(
        sessionName: String = "mobile",
        name: String,
        command: String = "pi"
    ) = TmuxWindow(
        sessionName = sessionName,
        sessionId = "\$1",
        index = 0,
        windowId = "@0",
        name = name,
        active = true,
        paneCount = 1,
        command = command,
        path = "/home/raymond/pi-workdir/weex",
        activity = false,
        last = false
    )
}
