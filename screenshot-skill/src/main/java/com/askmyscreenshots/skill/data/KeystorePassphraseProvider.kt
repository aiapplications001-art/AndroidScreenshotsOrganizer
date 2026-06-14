package com.askmyscreenshots.skill.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystorePassphraseProvider(
    context: Context,
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "ask_my_screenshots_secure_index",
        Context.MODE_PRIVATE,
    )

    fun getOrCreatePassphrase(): ByteArray {
        val storedCipherText = preferences.getString(KEY_CIPHER_TEXT, null)
        val storedIv = preferences.getString(KEY_IV, null)
        if (storedCipherText != null && storedIv != null) {
            return decrypt(
                cipherText = Base64.decode(storedCipherText, Base64.NO_WRAP),
                iv = Base64.decode(storedIv, Base64.NO_WRAP),
            )
        }

        val passphrase = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(passphrase)
        val encrypted = encrypt(passphrase)
        preferences.edit()
            .putString(KEY_CIPHER_TEXT, Base64.encodeToString(encrypted.cipherText, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(encrypted.iv, Base64.NO_WRAP))
            .apply()
        return passphrase
    }

    private fun encrypt(clearText: ByteArray): EncryptedBytes {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedBytes(
            cipherText = cipher.doFinal(clearText),
            iv = cipher.iv,
        )
    }

    private fun decrypt(cipherText: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private data class EncryptedBytes(
        val cipherText: ByteArray,
        val iv: ByteArray,
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ask_my_screenshots_index_key"
        private const val KEY_CIPHER_TEXT = "passphrase_cipher_text"
        private const val KEY_IV = "passphrase_iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 64
    }
}
