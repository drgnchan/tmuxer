package com.tmuxer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores Pi quick-launch presets separately for each SSH profile. */
class QuickLaunchPresetStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun load(profileId: String): List<QuickLaunchPreset> {
        val payload = preferences.getString(profileId, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(payload)
            buildList<QuickLaunchPreset> {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val preset = normalizeQuickLaunchPreset(
                        QuickLaunchPreset(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            promptTemplate = item.optString("promptTemplate"),
                            workingDirectory = item.optString("workingDirectory", "~"),
                            launchWithoutSession = item.optBoolean("launchWithoutSession"),
                            sessionName = item.optString("sessionName"),
                            model = item.optString("model"),
                            thinkingEffort = item.optString("thinkingEffort")
                        )
                    ) ?: continue
                    if (none { it.id == preset.id }) add(preset)
                    if (size == MAX_QUICK_LAUNCH_PRESETS) break
                }
            }
        }.getOrElse {
            preferences.edit().remove(profileId).apply()
            emptyList()
        }
    }

    @Synchronized
    fun upsert(profileId: String, preset: QuickLaunchPreset): List<QuickLaunchPreset> {
        val normalized = requireNotNull(normalizeQuickLaunchPreset(preset)) { "快捷任务名称不能为空" }
        val updated = buildList {
            add(normalized)
            load(profileId).forEach {
                if (it.id != normalized.id && size < MAX_QUICK_LAUNCH_PRESETS) add(it)
            }
        }
        save(profileId, updated)
        return updated
    }

    @Synchronized
    fun remove(profileId: String, presetId: String): List<QuickLaunchPreset> {
        val updated = load(profileId).filterNot { it.id == presetId }
        save(profileId, updated)
        return updated
    }

    fun removeProfile(profileId: String) {
        preferences.edit().remove(profileId).apply()
    }

    private fun save(profileId: String, presets: List<QuickLaunchPreset>) {
        if (presets.isEmpty()) {
            preferences.edit().remove(profileId).apply()
            return
        }
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("title", preset.title)
                    .put("promptTemplate", preset.promptTemplate)
                    .put("workingDirectory", preset.workingDirectory)
                    .put("launchWithoutSession", preset.launchWithoutSession)
                    .put("sessionName", preset.sessionName)
                    .put("model", preset.model)
                    .put("thinkingEffort", preset.thinkingEffort)
            )
        }
        preferences.edit().putString(profileId, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "pi_quick_launch_presets"
    }
}

internal const val MAX_QUICK_LAUNCH_PRESETS = 12
internal const val QUICK_LAUNCH_INPUT_PLACEHOLDER = "{{input}}"

internal fun normalizeQuickLaunchPreset(preset: QuickLaunchPreset): QuickLaunchPreset? {
    val title = preset.title.trim().take(40)
    if (title.isEmpty()) return null
    val id = preset.id.trim().ifEmpty { java.util.UUID.randomUUID().toString() }
    return preset.copy(
        id = id,
        title = title,
        promptTemplate = preset.promptTemplate.trim().take(16_384),
        workingDirectory = preset.workingDirectory.trim().ifEmpty { "~" }.take(512),
        sessionName = preset.sessionName.trim().take(40),
        model = preset.model.trim().replace("\u0000", "").take(512),
        thinkingEffort = preset.thinkingEffort.trim().takeIf { it in PI_THINKING_EFFORTS }.orEmpty()
    )
}

internal fun buildQuickLaunchPrompt(promptTemplate: String, input: String): String {
    val template = promptTemplate.trim()
    val argument = input.trim()
    return when {
        QUICK_LAUNCH_INPUT_PLACEHOLDER in template ->
            template.replace(QUICK_LAUNCH_INPUT_PLACEHOLDER, argument).trim()
        template.isEmpty() -> argument
        argument.isEmpty() -> template
        template.startsWith("/") -> "$template $argument"
        else -> "$template\n\n$argument"
    }
}
