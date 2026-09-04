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
    fun rejectsBlankTitle() {
        assertNull(normalizeQuickLaunchPreset(QuickLaunchPreset(title = " ", promptTemplate = "prompt")))
    }
}
