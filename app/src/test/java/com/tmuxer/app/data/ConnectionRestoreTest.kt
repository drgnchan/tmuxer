package com.tmuxer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRestoreTest {
    @Test
    fun resolvesByStableWindowIdFirst() {
        val windows = listOf(window("mobile", "\$1", 0, "@8", "bash"))
        val target = TerminalRestoreTarget("old-name", "\$9", 4, "@8", "old-window")

        assertEquals("@8", resolveRestoredWindow(windows, target)?.windowId)
    }

    @Test
    fun fallsBackToSessionNameAndIndexAfterTmuxRestart() {
        val windows = listOf(window("mobile", "\$2", 3, "@21", "editor"))
        val target = TerminalRestoreTarget("mobile", "\$1", 3, "@4", "editor")

        assertEquals("@21", resolveRestoredWindow(windows, target)?.windowId)
    }

    @Test
    fun doesNotRestoreAnUnrelatedWindow() {
        val windows = listOf(window("other", "\$2", 0, "@21", "shell"))
        val target = TerminalRestoreTarget("mobile", "\$1", 3, "@4", "editor")

        assertNull(resolveRestoredWindow(windows, target))
    }

    private fun window(
        sessionName: String,
        sessionId: String,
        index: Int,
        windowId: String,
        name: String
    ) = TmuxWindow(
        sessionName = sessionName,
        sessionId = sessionId,
        index = index,
        windowId = windowId,
        name = name,
        active = true,
        paneCount = 1,
        command = "bash",
        path = "/root",
        activity = false,
        last = false
    )
}
