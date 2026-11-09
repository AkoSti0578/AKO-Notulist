package nl.arnoldkolkman.akonotulist.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the OpenAI API key encrypted with an AES-256-GCM key that lives in the Android
 * Keystore (hardware-backed where available, never exportable).
 *
 * We deliberately do not use EncryptedSharedPreferences: androidx.security:security-crypto is
 * deprecated. This is the same construction, without the dependency.
 *
 * The key value is never logged, never put in a Bundle/Intent and never written in plain text.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = prefs.contains(PREF_API_KEY)

    fun getApiKey(): String? {
        val stored = prefs.getString(PREF_API_KEY, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    fun setApiKey(value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(PREF_API_KEY).apply()
        } else {
            prefs.edit().putString(PREF_API_KEY, encrypt(trimmed)).apply()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (ivB64, ctB64) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ako_notulist_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREF_API_KEY = "openai_api_key"
    }
}
