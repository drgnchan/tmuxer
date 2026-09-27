package com.tmuxer.app.data

import android.content.Context

/** Device-local display names; never rename a tmux session/window or a Pi session. */
class WindowTitleStore(context: Context) {
    private val preferences = context.getSharedPreferences("window_titles", Context.MODE_PRIVATE)

    @Synchronized
    fun load(profileId: String): Map<String, String> {
        val prefix = "$profileId/"
        return preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix) && value is String && value.isNotBlank()) {
                key.removePrefix(prefix) to value
            } else null
        }.toMap()
    }

    @Synchronized
    fun save(profileId: String, window: TmuxWindow, title: String): Map<String, String> {
        val key = windowTitleKey(window) ?: return load(profileId)
        val editor = preferences.edit()
        val normalized = title.trim().take(40)
        if (normalized.isEmpty()) editor.remove("$profileId/$key")
        else editor.putString("$profileId/$key", normalized)
        editor.apply()
        return load(profileId)
    }

    @Synchronized
    fun removeProfile(profileId: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("$profileId/") }.forEach(editor::remove)
        editor.apply()
    }
}

/** tmux window IDs are unique within a server lifetime, but can be reused after a server restart. */
fun windowTitleKey(window: TmuxWindow): String? =
    window.serverStartTime.takeIf { it.isNotBlank() }?.let { "$it/${window.windowId}" }

fun displayWindowTitle(window: TmuxWindow, titles: Map<String, String>): String =
    windowTitleKey(window)?.let(titles::get)?.takeIf { it.isNotBlank() }
        ?: "窗口 ${window.windowId.removePrefix("@").ifBlank { window.index.toString() }}"
