package com.tmuxer.app.ssh

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePiModelsTest {
    private val header = "provider  model  context  max-out  thinking  images"

    @Test
    fun parsesSortsAndDeduplicatesAvailableModels() {
        val models = parsePiModelList("""
            $header
            openai-codex  gpt-5.4  1M  128K  yes  yes
            anthropic  claude-sonnet-4-6  200K  64K  yes  yes
            custom  org/model  32.5K  8192  no  no
            openai-codex  gpt-5.4  1M  128K  yes  yes
        """.trimIndent())
        assertEquals(listOf("anthropic", "custom", "openai-codex"), models.map { it.provider })
        assertEquals("custom/org/model", models[1].selection)
        assertEquals("32.5K", models[1].context)
        assertEquals("8192", models[1].maxOutput)
        assertFalse(models[1].thinking)
        assertFalse(models[1].images)
        assertTrue(models[0].thinking)
        assertTrue(models[0].images)
    }

    @Test
    fun ignoresAnsiWarningsAndShellStartupNoise() {
        val models = parsePiModelList("welcome\r\n\u001B[33mWarning: models.json\u001B[0m\r\n" +
            "\u001B[1m$header\u001B[0m\r\nprovider1\tmodel1\t200K\t16K\tyes\tno\r\n" +
            "unrelated plugin notice\n")
        assertEquals("provider1/model1", models.single().selection)
    }

    @Test
    fun recognizesNoCredentialsAndEmptyTable() {
        assertTrue(parsePiModelList("No models available. Use /login to log into a provider via OAuth or API key.").isEmpty())
        assertTrue(parsePiModelList(header).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun unknownOutputIsAnErrorRatherThanAnEmptyCatalog() {
        parsePiModelList("Unknown option: --list-models")
    }

    @Test(expected = IllegalStateException::class)
    fun invalidRowsDoNotBecomeSelectableModels() {
        parsePiModelList("$header\nprovider model invalid 1000 true false")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNullDirectory() {
        buildPiModelListCommand("a\u0000b")
    }

    @Test
    fun commandUsesTaskDirectoryAndSafelyQuotesShellCharacters() {
        val root = Files.createTempDirectory("tmuxer-pi-models").toFile()
        try {
            val bin = root.resolve("bin").apply { mkdir() }
            bin.resolve("pi").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$PWD\" \"\$@\"\n")
                assertTrue(setExecutable(true))
            }
            val project = root.resolve("project's \$(echo unsafe)").apply { mkdir() }
            for (directory in listOf(project.absolutePath, "~/${project.name}", "~", "  ")) {
                val process = ProcessBuilder("/bin/sh", "-c", buildPiModelListCommand(directory))
                    .redirectErrorStream(true).apply {
                        environment()["PATH"] = "$bin:/usr/bin:/bin"
                        environment()["HOME"] = root.absolutePath
                    }.start()
                val output = process.inputStream.bufferedReader().readText().trim().lines()
                assertEquals(0, process.waitFor())
                assertEquals(if (directory.trim().let { it.isEmpty() || it == "~" }) root.absolutePath else project.absolutePath, output.first())
                assertEquals("--list-models", output.last())
                assertEquals(2, output.size)
            }
            val missing = ProcessBuilder("/bin/sh", "-c", buildPiModelListCommand(root.resolve("missing").absolutePath))
                .redirectErrorStream(true).apply { environment()["PATH"] = "$bin:/usr/bin:/bin" }.start()
            assertTrue(missing.inputStream.bufferedReader().readText().contains("__TMUXER_DIRECTORY_MISSING__"))
            assertEquals(2, missing.waitFor())
        } finally {
            root.deleteRecursively()
        }
    }
}
