package com.ekstraktranskrip.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Gemini API key at rest, encrypted with an AES-256-GCM key held by the
 * Android Keystore (non-exportable). The prefs only ever contain IV+ciphertext.
 *
 * The key is never written to logs, never included in exports and never leaves
 * the device except in the request header to Google.
 */
object ApiKeyStore {

    private const val PREFS = "ai_settings"
    private const val PREF_CIPHER = "gemini_key_cipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ekstrak_gemini_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: the user must be able to
                // summarize with the screen off during a long job.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    fun save(context: Context, apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clear(context)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val blob = iv + encrypted
        prefs(context).edit()
            .putString(PREF_CIPHER, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): String? = runCatching {
        val blob = prefs(context).getString(PREF_CIPHER, null)?.let {
            Base64.decode(it, Base64.NO_WRAP)
        } ?: return null
        if (blob.size <= IV_BYTES) return null

        val iv = blob.copyOfRange(0, IV_BYTES)
        val encrypted = blob.copyOfRange(IV_BYTES, blob.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    fun hasKey(context: Context): Boolean = !load(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_CIPHER).apply()
    }

    /** Display-only hint, e.g. "AIza••••••1x9Q". */
    fun masked(context: Context): String? {
        val key = load(context) ?: return null
        if (key.length < 8) return "••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }

    private const val IV_BYTES = 12
}

object AiSettings {

    private const val PREFS = "ai_settings"
    private const val KEY_MODEL = "gemini_model"

    const val DEFAULT_MODEL = "gemini-2.5-flash"

    fun model(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun setModel(context: Context, model: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, model.trim())
            .apply()
    }
}
