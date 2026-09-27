package com.tmuxer.app.ui

import com.tmuxer.app.data.TmuxWindow
import com.tmuxer.app.data.displayWindowTitle
import com.tmuxer.app.data.windowTitleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WindowCardLabelTest {
    @Test
    fun ignoresRemoteSessionAndWindowNamesWhenNoLocalTitle() {
        assertEquals("窗口 7", displayWindowTitle(window(), emptyMap()))
    }

    @Test
    fun usesLocalWindowTitle() {
        val window = window()
        assertEquals("我的任务", displayWindowTitle(window, mapOf(windowTitleKey(window)!! to "我的任务")))
    }

    @Test
    fun windowIdentityChangesWhenTmuxServerRestarts() {
        assertNotEquals(windowTitleKey(window()), windowTitleKey(window(startTime = "1720000001")))
    }

    @Test
    fun renameAndWindowIndexChangeDoNotLoseLocalTitle() {
        val window = window()
        assertEquals("我的任务", displayWindowTitle(
            window.copy(sessionName = "新的 tmux 名称", name = "other", index = 10),
            mapOf(windowTitleKey(window)!! to "我的任务")
        ))
    }

    private fun window(startTime: String = "1720000000") = TmuxWindow(
        sessionName = "mobile", sessionId = "\$1", index = 1, windowId = "@7", name = "pi",
        active = true, paneCount = 1, command = "pi", path = "/home/user",
        activity = false, last = false, serverStartTime = startTime
    )
}
