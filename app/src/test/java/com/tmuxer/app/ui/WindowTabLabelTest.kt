package com.tmuxer.app.ui

import com.tmuxer.app.data.TmuxWindow
import com.tmuxer.app.data.windowTitleKey
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowTabLabelTest {
    @Test
    fun tabsUseSameLocalTitleAsDashboard() {
        val window = TmuxWindow(
            sessionName = "mobile", sessionId = "\$1", index = 1, windowId = "@2", name = "editor",
            active = true, paneCount = 1, command = "bash", path = "/home/user",
            activity = false, last = false, serverStartTime = "1720000000"
        )
        assertEquals("窗口 2", windowTabTitle(window, listOf(window), emptyMap()))
        val titles = mapOf(windowTitleKey(window)!! to "编辑代码")
        assertEquals("编辑代码", windowTabTitle(window, listOf(window), titles))
        val otherWindow = window.copy(windowId = "@3")
        assertEquals("编辑代码 · 2", windowTabTitle(
            window, listOf(window, otherWindow),
            titles + (windowTitleKey(otherWindow)!! to "编辑代码")
        ))
    }
}
