package com.tmuxer.app.data

import android.content.Context

/** Non-secret navigation state used to restore an interrupted SSH/tmux connection. */
data class ConnectionRestoreState(
    val profileId: String,
    val terminalTarget: TerminalRestoreTarget? = null
)

data class TerminalRestoreTarget(
    val sessionName: String,
    val sessionId: String,
    val windowIndex: Int,
    val windowId: String,
    val windowName: String
) {
    fun placeholder() = TmuxWindow(
        sessionName = sessionName,
        sessionId = sessionId,
        index = windowIndex,
        windowId = windowId,
        name = windowName,
        active = true,
        paneCount = 1,
        command = "",
        path = "",
        activity = false,
        last = false
    )
}

class ConnectionRestoreStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): ConnectionRestoreState? {
        val profileId = preferences.getString(KEY_PROFILE_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null
        if (!preferences.getBoolean(KEY_TERMINAL, false)) {
            return ConnectionRestoreState(profileId)
        }
        val sessionName = preferences.getString(KEY_SESSION_NAME, null) ?: return ConnectionRestoreState(profileId)
        val sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return ConnectionRestoreState(profileId)
        val windowId = preferences.getString(KEY_WINDOW_ID, null) ?: return ConnectionRestoreState(profileId)
        val windowName = preferences.getString(KEY_WINDOW_NAME, null).orEmpty().ifEmpty { "终端" }
        val windowIndex = preferences.getInt(KEY_WINDOW_INDEX, -1)
        if (windowIndex < 0) return ConnectionRestoreState(profileId)
        return ConnectionRestoreState(
            profileId,
            TerminalRestoreTarget(sessionName, sessionId, windowIndex, windowId, windowName)
        )
    }

    fun saveDashboard(profileId: String) {
        preferences.edit()
            .putString(KEY_PROFILE_ID, profileId)
            .putBoolean(KEY_TERMINAL, false)
            .removeTerminalTarget()
            .apply()
    }

    fun saveTerminal(profileId: String, window: TmuxWindow) {
        preferences.edit()
            .putString(KEY_PROFILE_ID, profileId)
            .putBoolean(KEY_TERMINAL, true)
            .putString(KEY_SESSION_NAME, window.sessionName)
            .putString(KEY_SESSION_ID, window.sessionId)
            .putInt(KEY_WINDOW_INDEX, window.index)
            .putString(KEY_WINDOW_ID, window.windowId)
            .putString(KEY_WINDOW_NAME, window.name)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun android.content.SharedPreferences.Editor.removeTerminalTarget() =
        remove(KEY_SESSION_NAME)
            .remove(KEY_SESSION_ID)
            .remove(KEY_WINDOW_INDEX)
            .remove(KEY_WINDOW_ID)
            .remove(KEY_WINDOW_NAME)

    private companion object {
        const val PREFERENCES = "connection_restore"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_TERMINAL = "terminal"
        const val KEY_SESSION_NAME = "session_name"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_WINDOW_INDEX = "window_index"
        const val KEY_WINDOW_ID = "window_id"
        const val KEY_WINDOW_NAME = "window_name"
    }
}

internal fun resolveRestoredWindow(
    windows: List<TmuxWindow>,
    target: TerminalRestoreTarget
): TmuxWindow? = windows.firstOrNull { it.windowId == target.windowId } ?: windows.firstOrNull {
    it.sessionName == target.sessionName && it.index == target.windowIndex
} ?: windows.firstOrNull {
    it.sessionName == target.sessionName && it.name == target.windowName
}
