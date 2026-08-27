package com.tmuxer.app.data

import android.content.Context
import org.json.JSONArray

/** Stores a per-host MRU list of directories used to launch Pi workspaces. */
class RecentPiDirectoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun load(profileId: String): List<String> {
        val payload = preferences.getString(profileId, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(payload)
            buildList {
                for (index in 0 until array.length()) {
                    val path = normalizePiDirectory(array.optString(index)) ?: continue
                    if (path !in this) add(path)
                    if (size == MAX_RECENT_PI_DIRECTORIES) break
                }
            }
        }.getOrElse {
            preferences.edit().remove(profileId).apply()
            emptyList()
        }
    }

    @Synchronized
    fun record(profileId: String, directory: String): List<String> {
        val updated = updateRecentPiDirectories(load(profileId), directory)
        save(profileId, updated)
        return updated
    }

    @Synchronized
    fun remove(profileId: String, directory: String): List<String> {
        val updated = removeRecentPiDirectory(load(profileId), directory)
        save(profileId, updated)
        return updated
    }

    private fun save(profileId: String, directories: List<String>) {
        if (directories.isEmpty()) {
            preferences.edit().remove(profileId).apply()
        } else {
            val array = JSONArray().apply { directories.forEach(::put) }
            preferences.edit().putString(profileId, array.toString()).apply()
        }
    }

    fun removeProfile(profileId: String) {
        preferences.edit().remove(profileId).apply()
    }

    private companion object {
        const val PREFERENCES = "recent_pi_directories"
    }
}

internal const val MAX_RECENT_PI_DIRECTORIES = 8

internal fun updateRecentPiDirectories(
    existing: List<String>,
    directory: String,
    limit: Int = MAX_RECENT_PI_DIRECTORIES
): List<String> {
    require(limit > 0) { "最近目录数量必须大于 0" }
    val selected = normalizePiDirectory(directory) ?: return existing.take(limit)
    return buildList {
        add(selected)
        if (size == limit) return@buildList
        existing.forEach { path ->
            val normalized = normalizePiDirectory(path) ?: return@forEach
            if (normalized !in this) add(normalized)
            if (size == limit) return@buildList
        }
    }
}

internal fun removeRecentPiDirectory(
    existing: List<String>,
    directory: String
): List<String> {
    val removed = normalizePiDirectory(directory) ?: return existing
    return existing.filterNot { normalizePiDirectory(it) == removed }
}

private fun normalizePiDirectory(directory: String): String? {
    val trimmed = directory.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.trimEnd('/').ifEmpty { "/" }
}
