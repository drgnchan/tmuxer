package com.tmuxer.app.data

import com.tmuxer.app.terminal.TerminalTheme
import java.util.UUID

enum class AuthType { PASSWORD, PRIVATE_KEY }

data class SshProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val terminalTheme: TerminalTheme = TerminalTheme.DARK
) {
    val endpoint: String get() = "$username@$host${if (port == 22) "" else ":$port"}"
}

data class QuickLaunchPreset(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val promptTemplate: String,
    val workingDirectory: String = "~",
    val launchWithoutSession: Boolean = false,
    val sessionName: String = "",
    val model: String = "",
    val thinkingEffort: String = ""
)

internal val PI_THINKING_EFFORTS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

data class TmuxWindow(
    val sessionName: String,
    val sessionId: String,
    val index: Int,
    val windowId: String,
    val name: String,
    val active: Boolean,
    val paneCount: Int,
    val command: String,
    val path: String,
    val activity: Boolean,
    val last: Boolean
)

data class ConnectionInfo(
    val profile: SshProfile,
    val hostKeyFingerprint: String,
    val newlyTrustedHost: Boolean
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val profile: SshProfile) : ConnectionState
    data class Connected(val info: ConnectionInfo) : ConnectionState
    data class Failed(val profile: SshProfile, val message: String) : ConnectionState
}

sealed interface AppScreen {
    data object Hosts : AppScreen
    data class ProfileEditor(val profileId: String? = null) : AppScreen
    data class Windows(val profileId: String) : AppScreen
    data object Terminal : AppScreen
}
