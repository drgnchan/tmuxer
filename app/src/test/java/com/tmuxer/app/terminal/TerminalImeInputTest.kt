package com.tmuxer.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeInputTest {
    @Test
    fun appendsOnlyNewComposingSuffix() {
        assertEquals(
            TerminalImeReplacement(deleteCodePoints = 0, insertText = "l"),
            terminalImeReplacement("he", "hel")
        )
    }

    @Test
    fun shorteningCompositionProducesBackspace() {
        assertEquals(
            TerminalImeReplacement(deleteCodePoints = 1, insertText = ""),
            terminalImeReplacement("hel", "he")
        )
    }

    @Test
    fun replacesChangedSuffix() {
        assertEquals(
            TerminalImeReplacement(deleteCodePoints = 2, insertText = "p"),
            terminalImeReplacement("hello", "help")
        )
    }

    @Test
    fun countsSupplementaryCharactersAsSingleTerminalCharacters() {
        assertEquals(
            TerminalImeReplacement(deleteCodePoints = 1, insertText = "们"),
            terminalImeReplacement("你🙂", "你们")
        )
    }
}
