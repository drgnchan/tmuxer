package com.tmuxer.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PiUploadInsertionTest {
    @Test
    fun wrapsFileReferencesInBracketedPaste() {
        assertEquals(
            "\u001B[200~@/home/user/.cache/tmuxer/uploads/one.txt " +
                "@/home/user/.cache/tmuxer/uploads/two.png \u001B[201~",
            buildPiUploadInsertion(
                listOf(
                    "/home/user/.cache/tmuxer/uploads/one.txt",
                    "/home/user/.cache/tmuxer/uploads/two.png"
                )
            )
        )
    }
}
