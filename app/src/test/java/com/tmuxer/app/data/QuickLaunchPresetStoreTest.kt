package com.tmuxer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickLaunchPresetStoreTest {
    @Test
    fun replacesInputPlaceholder() {
        assertEquals(
            "/skill:doris 查询退款失败",
            buildQuickLaunchPrompt("/skill:doris 查询{{input}}", "退款失败")
        )
    }

    @Test
    fun appendsInputAsSkillArgumentsWhenPlaceholderIsMissing() {
        assertEquals(
            "/skill:doris 退款失败",
            buildQuickLaunchPrompt("/skill:doris", "退款失败")
        )
    }

    @Test
    fun appendsInputAsAParagraphForOrdinaryPrompts() {
        assertEquals(
            "检查项目\n\n重点关注登录流程",
            buildQuickLaunchPrompt("检查项目", "重点关注登录流程")
        )
    }

    @Test
    fun blankInputRemovesPlaceholder() {
        assertEquals(
            "/skill:doris 查询",
            buildQuickLaunchPrompt("/skill:doris 查询 {{input}}", "  ")
        )
    }

    @Test
    fun normalizesPresetFields() {
        val normalized = normalizeQuickLaunchPreset(
            QuickLaunchPreset(
                id = "id",
                title = "  查 Doris  ",
                promptTemplate = "  /skill:doris {{input}}  ",
                workingDirectory = "  ",
                sessionName = "  task  "
            )
        )

        assertEquals("查 Doris", normalized?.title)
        assertEquals("/skill:doris {{input}}", normalized?.promptTemplate)
        assertEquals("~", normalized?.workingDirectory)
        assertEquals("task", normalized?.sessionName)
    }

    @Test
    fun modelAndThinkingDefaultsKeepOldPresetsCompatible() {
        val preset = normalizeQuickLaunchPreset(QuickLaunchPreset(title = "old", promptTemplate = ""))!!
        assertEquals("", preset.model)
        assertEquals("", preset.thinkingEffort)
    }

    @Test
    fun normalizesModelAndValidatesThinkingEffort() {
        val preset = QuickLaunchPreset(title = "task", promptTemplate = "", model = "  provider/model  ")
        for (effort in PI_THINKING_EFFORTS) {
            val normalized = normalizeQuickLaunchPreset(preset.copy(thinkingEffort = " $effort "))!!
            assertEquals("provider/model", normalized.model)
            assertEquals(effort, normalized.thinkingEffort)
        }
        assertEquals("", normalizeQuickLaunchPreset(preset.copy(thinkingEffort = "invalid"))!!.thinkingEffort)
        assertEquals(512, normalizeQuickLaunchPreset(preset.copy(model = "x".repeat(600)))!!.model.length)
        assertEquals("ab", normalizeQuickLaunchPreset(preset.copy(model = "a\u0000b"))!!.model)
    }

    @Test
    fun rejectsBlankTitle() {
        assertNull(normalizeQuickLaunchPreset(QuickLaunchPreset(title = " ", promptTemplate = "prompt")))
    }
}
