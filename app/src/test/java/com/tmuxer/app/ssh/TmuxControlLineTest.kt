package com.tmuxer.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxControlLineTest {
    @Test
    fun windowAndSessionTopologyNotificationsRequestARefresh() {
        // Lines captured from a tmux 3.7c control client attached to session $0 while another
        // client created, renamed, selected and killed windows and sessions.
        listOf(
            "%window-add @2",
            "%window-close @2",
            "%window-renamed @2 renamed-a",
            "%window-renamed @0 bash",
            "%window-pane-changed @0 %5",
            "%unlinked-window-add @3",
            "%unlinked-window-close @4",
            "%unlinked-window-renamed @3 renamed-b",
            "%session-renamed \$1 bb",
            "%session-renamed \$0 新标题",
            "%sessions-changed",
            "%session-window-changed \$1 @1"
        ).forEach { line ->
            assertEquals(line, TmuxControlLine.TOPOLOGY_CHANGED, classifyTmuxControlLine(line))
        }
    }

    @Test
    fun ignoresNotificationsThatDoNotChangeTheWindowList() {
        listOf(
            "%layout-change @0 c197,80x24,0,0[80x12,0,0,0,80x11,0,13,5] c197,80x24,0,0 *",
            "%output %0 hello",
            "%extended-output %0 12 : hello",
            "%client-session-changed /dev/pts/3 \$1 b",
            "%client-detached /dev/pts/3",
            "%pane-mode-changed %0",
            "%paste-buffer-changed buffer0",
            "%message hello",
            "%continue %0",
            "%pause %0",
            "%window-added-by-a-future-tmux @1",
            "%session-renamedx",
            "window-add @2",
            ""
        ).forEach { line ->
            assertEquals(line, TmuxControlLine.OTHER, classifyTmuxControlLine(line))
        }
    }

    @Test
    fun recognizesReplyBlocksAttachAndExit() {
        assertEquals(TmuxControlLine.BLOCK_BEGIN, classifyTmuxControlLine("%begin 1790674197 290 0"))
        assertEquals(TmuxControlLine.BLOCK_END, classifyTmuxControlLine("%end 1790674197 290 0"))
        assertEquals(TmuxControlLine.BLOCK_END, classifyTmuxControlLine("%error 1790674197 290 0"))
        assertEquals(TmuxControlLine.ATTACHED, classifyTmuxControlLine("%session-changed \$0 a"))
        assertEquals(TmuxControlLine.EXIT, classifyTmuxControlLine("%exit"))
        assertEquals(TmuxControlLine.EXIT, classifyTmuxControlLine("%exit detached"))
    }

    @Test
    fun toleratesCarriageReturnsAndBareNames() {
        assertEquals(TmuxControlLine.TOPOLOGY_CHANGED, classifyTmuxControlLine("%sessions-changed\r"))
        assertEquals(TmuxControlLine.TOPOLOGY_CHANGED, classifyTmuxControlLine("%session-renamed"))
        assertEquals(TmuxControlLine.EXIT, classifyTmuxControlLine("%exit\r"))
    }
}
