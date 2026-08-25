package com.tmuxer.app.ui

import com.tmuxer.app.data.TmuxWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionExitSelectionTest {
    @Test
    fun selectsNextTabOutsideExitedSession() {
        val windows = listOf(
            window(sessionId = "\$1", windowId = "@1"),
            window(sessionId = "\$1", windowId = "@2"),
            window(sessionId = "\$2", windowId = "@3"),
            window(sessionId = "\$3", windowId = "@4")
        )

        assertEquals("@3", nextWindowAfterSessionExit(windows, windows.first())?.windowId)
    }

    @Test
    fun selectsPreviousTabWhenThereIsNoTabToTheRight() {
        val windows = listOf(
            window(sessionId = "\$1", windowId = "@1"),
            window(sessionId = "\$2", windowId = "@2"),
            window(sessionId = "\$2", windowId = "@3")
        )

        assertEquals("@1", nextWindowAfterSessionExit(windows, windows.last())?.windowId)
    }

    @Test
    fun returnsNullWhenExitedSessionOwnsEveryTab() {
        val windows = listOf(
            window(sessionId = "\$1", windowId = "@1"),
            window(sessionId = "\$1", windowId = "@2")
        )

        assertNull(nextWindowAfterSessionExit(windows, windows.first()))
    }

    private fun window(sessionId: String, windowId: String) = TmuxWindow(
        sessionName = "session-${sessionId.drop(1)}",
        sessionId = sessionId,
        index = windowId.drop(1).toInt(),
        windowId = windowId,
        name = "shell",
        active = false,
        paneCount = 1,
        command = "bash",
        path = "/home/user",
        activity = false,
        last = false
    )
}
