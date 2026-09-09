package com.tmuxer.app.ssh

/** Public metadata only; credentials never leave the remote Pi process. */
data class RemotePiModel(
    val provider: String,
    val id: String,
    val context: String,
    val maxOutput: String,
    val thinking: Boolean,
    val images: Boolean
) {
    val selection: String get() = "$provider/$id"
}

internal fun buildPiModelListCommand(workingDirectory: String): String {
    val directory = workingDirectory.trim().ifEmpty { "~" }
    require(directory.length <= 512 && '\u0000' !in directory) { "工作目录无效" }
    val quotedDirectory = "'" + directory.replace("'", "'\"'\"'") + "'"
    return buildPiExecutableCheckCommand() +
        "START_DIR=$quotedDirectory; " +
        "if [ \"\$START_DIR\" = '~' ]; then START_DIR=\"\$HOME\"; " +
        "elif [ \"\${START_DIR#\\~/}\" != \"\$START_DIR\" ]; then START_DIR=\"\$HOME/\${START_DIR#\\~/}\"; fi; " +
        "cd -- \"\$START_DIR\" 2>/dev/null || { printf '__TMUXER_DIRECTORY_MISSING__\\n'; exit 2; }; " +
        "exec env NO_COLOR=1 FORCE_COLOR=0 TERM=dumb PI_SKIP_VERSION_CHECK=1 " +
        "PATH=\"\$(dirname \"\$PI_BIN\"):\$PATH\" \"\$PI_BIN\" --list-models"
}

/** Pi exposes a text table, not JSON. Validate its header and rows rather than parsing warnings as models. */
internal fun parsePiModelList(output: String): List<RemotePiModel> {
    val plain = output
        .replace(Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"), "")
        .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
    val lines = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val header = listOf("provider", "model", "context", "max-out", "thinking", "images")
    val start = lines.indexOfFirst { it.split(Regex("\\s+")) == header }
    if (start < 0) {
        if (lines.any { it.startsWith("No models available.") }) return emptyList()
        throw IllegalStateException("无法识别 Pi 模型列表，请更新远程 Pi 或手动输入模型")
    }
    val tokenCount = Regex("\\d+(?:\\.\\d+)?[KM]?")
    val models = lines.drop(start + 1).mapNotNull { line ->
        val cells = line.split(Regex("\\s+"))
        if (cells.size != 6 || !tokenCount.matches(cells[2]) || !tokenCount.matches(cells[3]) ||
            cells[4] !in listOf("yes", "no") || cells[5] !in listOf("yes", "no")) return@mapNotNull null
        RemotePiModel(cells[0], cells[1], cells[2], cells[3], cells[4] == "yes", cells[5] == "yes")
    }.distinctBy { it.selection }.sortedWith(compareBy({ it.provider }, { it.id }))
    if (models.isEmpty() && lines.size > start + 1) {
        throw IllegalStateException("无法识别 Pi 模型列表，请更新远程 Pi 或手动输入模型")
    }
    return models
}
