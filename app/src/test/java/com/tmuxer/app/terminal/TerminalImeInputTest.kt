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

    @Test
    fun wrapsMultilineImeCommitAsBracketedPaste() {
        assertEquals(
            "\u001B[200~first line\nsecond line\u001B[201~",
            terminalImeCommittedInput("first line\r\nsecond line", bracketedPasteMode = true)
        )
    }

    @Test
    fun keepsImeEnterAsAnEnterKey() {
        assertEquals("\r", terminalImeCommittedInput("\n", bracketedPasteMode = true))
    }

    @Test
    fun preservesLegacyMultilineInputWhenBracketedPasteIsDisabled() {
        assertEquals(
            "first line\rsecond line",
            terminalImeCommittedInput("first line\nsecond line", bracketedPasteMode = false)
        )
    }

    @Test
    fun wrapsExplicitClipboardPasteAndNormalizesLineEndings() {
        assertEquals(
            "\u001B[200~first\nsecond\nthird\u001B[201~",
            terminalPasteInput("first\r\nsecond\rthird", bracketedPasteMode = true)
        )
    }
}
