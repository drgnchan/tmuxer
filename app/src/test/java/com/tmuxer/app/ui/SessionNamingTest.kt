package com.tmuxer.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNamingTest {
    @Test
    fun usesWorkingDirectoryLeafWhenNameIsBlank() {
        assertEquals(
            "tmuxer",
            resolvePiSessionName("", "~/app-dev/tmuxer/", emptySet())
        )
    }

    @Test
    fun fallsBackToPiForHomeAndRootDirectories() {
        assertEquals("pi", resolvePiSessionName("", "~", emptySet()))
        assertEquals("pi", resolvePiSessionName("", "/", emptySet()))
    }

    @Test
    fun addsFirstAvailableNumericSuffix() {
        assertEquals(
            "tmuxer-4",
            resolvePiSessionName(
                requestedName = "",
                workingDirectory = "/srv/tmuxer",
                existingSessionNames = setOf("tmuxer", "tmuxer-2", "tmuxer-3")
            )
        )
    }

    @Test
    fun sanitizesDirectoryAndCustomNamesForTmux() {
        assertEquals(
            "my-project",
            resolvePiSessionName("", "/srv/my.project", emptySet())
        )
        assertEquals(
            "custom-agent",
            resolvePiSessionName("custom:agent", "/srv/project", emptySet())
        )
    }

    @Test
    fun keepsGeneratedNamesWithinFortyCharacters() {
        val base = "a".repeat(40)
        assertEquals(
            "${"a".repeat(38)}-2",
            resolvePiSessionName(base, "~", setOf(base))
        )
    }
}
