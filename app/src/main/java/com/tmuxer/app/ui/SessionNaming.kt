package com.tmuxer.app.ui

private const val MAX_SESSION_NAME_LENGTH = 40
private val UNSAFE_SESSION_NAME_CHARACTERS = Regex("[^\\p{L}\\p{N}_-]+")

internal fun resolveShellSessionName(
    requestedName: String,
    existingSessionNames: Collection<String>
): String {
    val requested = requestedName.trim()
    if (requested.isNotEmpty()) return requested
    return nextAvailableSessionName("workspace", existingSessionNames)
}

internal fun resolvePiSessionName(
    requestedName: String,
    workingDirectory: String,
    existingSessionNames: Collection<String>
): String {
    val requestedBase = sanitizeSessionName(requestedName)
    val directoryBase = sanitizeSessionName(directoryLeaf(workingDirectory))
    val base = requestedBase.ifEmpty { directoryBase.ifEmpty { "pi" } }
    return nextAvailableSessionName(base, existingSessionNames)
}

private fun directoryLeaf(workingDirectory: String): String {
    val path = workingDirectory.trim().trimEnd('/')
    return when (path) {
        "", "~" -> "pi"
        else -> path.substringAfterLast('/').ifEmpty { "pi" }
    }
}

private fun sanitizeSessionName(value: String): String = value
    .trim()
    .replace(UNSAFE_SESSION_NAME_CHARACTERS, "-")
    .trim('-')
    .take(MAX_SESSION_NAME_LENGTH)
    .trimEnd('-')

private fun nextAvailableSessionName(
    base: String,
    existingSessionNames: Collection<String>
): String {
    val existing = existingSessionNames.toHashSet()
    if (base !in existing) return base

    var number = 2
    while (true) {
        val suffix = "-$number"
        val stem = base.take(MAX_SESSION_NAME_LENGTH - suffix.length).trimEnd('-')
        val candidate = "$stem$suffix"
        if (candidate !in existing) return candidate
        number++
    }
}
