package com.tmuxer.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tmuxer.app.terminal.TerminalTheme
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the complete SSH profile list as one AES-GCM encrypted document. */
class SecureProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var payloadUnreadable = false

    // load() runs on the main thread at startup while saves run on a background thread; both
    // read and update payloadUnreadable and the preferences as one step.
    @Synchronized
    fun load(): List<SshProfile> {
        val payload = preferences.getString(KEY_PAYLOAD, null)
        val profiles = payload?.let { decryptOrNull(it, KEY_PAYLOAD) }.orEmpty()
        // Profiles that were unreadable during an earlier session come back once the Keystore
        // recovers; entries saved since then win on id collisions.
        val recovered = preferences.getString(KEY_UNREADABLE_PAYLOAD, null)
            ?.let { decryptOrNull(it, KEY_UNREADABLE_PAYLOAD) }
            ?: return profiles
        val knownIds = profiles.mapTo(HashSet()) { it.id }
        val merged = profiles + recovered.filterNot { it.id in knownIds }
        // Drop the recovered copy only once the merged list is persisted.
        if (merged.size != profiles.size && runCatching { save(merged) }.isFailure) return merged
        preferences.edit().remove(KEY_UNREADABLE_PAYLOAD).apply()
        return merged
    }

    /** Does Keystore IPC and AES work: call it off the main thread. */
    @Synchronized
    fun save(profiles: List<SshProfile>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(encodeProfiles(profiles).toByteArray(StandardCharsets.UTF_8))
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val editor = preferences.edit()
        // Keep a payload that failed to decrypt for a transient reason instead of overwriting it.
        val current = preferences.getString(KEY_PAYLOAD, null)
        if (payloadUnreadable && current != null && !preferences.contains(KEY_UNREADABLE_PAYLOAD)) {
            editor.putString(KEY_UNREADABLE_PAYLOAD, current)
        }
        payloadUnreadable = false
        editor.putString(KEY_PAYLOAD, envelope.toString()).apply()
    }

    /**
     * Returns null when [payload] cannot be decrypted. Only a payload that can never be decrypted
     * (wrong or invalidated key, corrupt data) is deleted; a transient Keystore failure keeps it.
     */
    private fun decryptOrNull(payload: String, key: String): List<SshProfile>? = try {
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
    } catch (error: Exception) {
        if (isPermanentDecryptFailure(error)) {
            preferences.edit().remove(key).apply()
        } else if (key == KEY_PAYLOAD) {
            payloadUnreadable = true
        }
        null
    }

    private fun isPermanentDecryptFailure(error: Exception): Boolean =
        error is AEADBadTagException ||
            error is KeyPermanentlyInvalidatedException ||
            error is UnrecoverableKeyException ||
            error is JSONException ||
            error is IllegalArgumentException

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
        private const val KEY_UNREADABLE_PAYLOAD = "profiles_unreadable"
        private const val KEY_ALIAS = "tmuxer.profile.key.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
