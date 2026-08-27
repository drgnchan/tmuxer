package com.tmuxer.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPiDirectoryStoreTest {
    @Test
    fun movesSelectedDirectoryToFrontAndRemovesDuplicates() {
        val updated = updateRecentPiDirectories(
            existing = listOf("/srv/one", "/srv/two", "/srv/three"),
            directory = "/srv/two/"
        )

        assertEquals(listOf("/srv/two", "/srv/one", "/srv/three"), updated)
    }

    @Test
    fun trimsPathsAndKeepsOnlyTheConfiguredNumber() {
        val updated = updateRecentPiDirectories(
            existing = listOf("/one", "/two", "/three", "/four"),
            directory = "  ~/projects/  ",
            limit = 3
        )

        assertEquals(listOf("~/projects", "/one", "/two"), updated)
    }

    @Test
    fun limitOfOneKeepsOnlyTheNewSelection() {
        assertEquals(
            listOf("/new"),
            updateRecentPiDirectories(listOf("/one", "/two"), "/new", limit = 1)
        )
    }

    @Test
    fun blankSelectionDoesNotChangeExistingOrder() {
        assertEquals(
            listOf("/one", "/two"),
            updateRecentPiDirectories(listOf("/one", "/two"), "   ")
        )
    }

    @Test
    fun removesOnlyTheSelectedRecentDirectory() {
        assertEquals(
            listOf("/one", "/three"),
            removeRecentPiDirectory(
                existing = listOf("/one", "/two", "/three"),
                directory = "/two/"
            )
        )
    }

    @Test
    fun blankRemovalDoesNotChangeExistingDirectories() {
        assertEquals(
            listOf("/one", "/two"),
            removeRecentPiDirectory(listOf("/one", "/two"), "   ")
        )
    }
}
