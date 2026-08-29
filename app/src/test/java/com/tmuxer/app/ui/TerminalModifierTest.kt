package com.tmuxer.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalModifierTest {
    @Test
    fun shiftModifiesTheFirstTypedCharacter() {
        assertEquals(
            "Hello",
            applyTerminalInputModifiers("hello", TerminalModifiers(shift = true))
        )
        assertEquals(
            "?",
            applyTerminalInputModifiers("/", TerminalModifiers(shift = true))
        )
    }

    @Test
    fun altPrefixesTypedInputWithEscape() {
        assertEquals(
            "\u001Bf",
            applyTerminalInputModifiers("f", TerminalModifiers(alt = true))
        )
    }

    @Test
    fun modifiersCanBeCombinedForTypedInput() {
        assertEquals(
            "\u001B\u0001bc",
            applyTerminalInputModifiers(
                "abc",
                TerminalModifiers(control = true, shift = true, alt = true)
            )
        )
    }

    @Test
    fun shiftTabUsesTheConventionalBackTabSequence() {
        assertEquals(
            "\u001B[Z",
            applyTerminalSpecialKeyModifiers("\t", TerminalModifiers(shift = true))
        )
    }

    @Test
    fun navigationKeysIncludeActiveXtermModifiers() {
        assertEquals(
            "\u001B[1;3D",
            applyTerminalSpecialKeyModifiers("\u001B[D", TerminalModifiers(alt = true))
        )
        assertEquals(
            "\u001B[5;6~",
            applyTerminalSpecialKeyModifiers(
                "\u001B[5~",
                TerminalModifiers(control = true, shift = true)
            )
        )
    }
}
