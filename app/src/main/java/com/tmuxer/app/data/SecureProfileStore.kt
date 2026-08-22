package com.tmuxer.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tmuxer.app.terminal.TerminalTheme
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the complete SSH profile list as one AES-GCM encrypted document. */
class SecureProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<SshProfile> {
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return emptyList()
        return runCatching {
            val envelope = JSONObject(payload)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP))
            )
            val plaintext = cipher.doFinal(
                Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
            )
            decodeProfiles(String(plaintext, StandardCharsets.UTF_8))
        }.getOrElse {
            // A restored preference file cannot be decrypted with a device-local Keystore key.
            preferences.edit().remove(KEY_PAYLOAD).apply()
            emptyList()
        }
    }

    fun save(profiles: List<SshProfile>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(encodeProfiles(profiles).toByteArray(StandardCharsets.UTF_8))
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        preferences.edit().putString(KEY_PAYLOAD, envelope.toString()).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun encodeProfiles(profiles: List<SshProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("username", profile.username)
                    .put("authType", profile.authType.name)
                    .put("password", profile.password)
                    .put("privateKey", profile.privateKey)
                    .put("passphrase", profile.passphrase)
                    .put("terminalTheme", profile.terminalTheme.name)
            )
        }
        return array.toString()
    }

    private fun decodeProfiles(json: String): List<SshProfile> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    SshProfile(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        host = item.getString("host"),
                        port = item.optInt("port", 22),
                        username = item.getString("username"),
                        authType = runCatching {
                            AuthType.valueOf(item.optString("authType", AuthType.PASSWORD.name))
                        }.getOrDefault(AuthType.PASSWORD),
                        password = item.optString("password"),
                        privateKey = item.optString("privateKey"),
                        passphrase = item.optString("passphrase"),
                        terminalTheme = runCatching {
                            TerminalTheme.valueOf(
                                item.optString("terminalTheme", TerminalTheme.DARK.name)
                            )
                        }.getOrDefault(TerminalTheme.DARK)
                    )
                )
            }
        }
    }

    companion object {
        private const val PREFERENCES = "encrypted_ssh_profiles"
        private const val KEY_PAYLOAD = "profiles"
        private const val KEY_ALIAS = "tmuxer.profile.key.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
