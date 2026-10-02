package com.weshah.router.openwrt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores router credentials securely using Android Keystore + AES-256-GCM encryption.
 * Credentials are encrypted before writing to SharedPreferences.
 * The encryption key never leaves the hardware-backed keystore.
 *
 * This is used for router passwords/tokens only.
 * Never store credentials in plain text.
 */
@Singleton
class RouterCredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "weshah_router_cred_key"
        private const val PREFS_NAME = "weshah_secure_prefs"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun storeCredential(keyAlias: String, password: String) {
        try {
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            val encoded = Base64.encodeToString(combined, Base64.DEFAULT)
            prefs.edit().putString(keyAlias, encoded).apply()
        } catch (e: Exception) {
            Timber.e(e, "Failed to store credential for $keyAlias")
            throw SecurityException("Cannot store credential securely", e)
        }
    }

    fun getCredential(keyAlias: String): String? {
        return try {
            val encoded = prefs.getString(keyAlias, null) ?: return null
            val combined = Base64.decode(encoded, Base64.DEFAULT)
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve credential for $keyAlias")
            null
        }
    }

    fun deleteCredential(keyAlias: String) {
        prefs.edit().remove(keyAlias).apply()
    }

    fun hasCredential(keyAlias: String): Boolean = prefs.contains(keyAlias)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return keyGenerator.generateKey()
    }
}
