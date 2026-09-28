package com.localchatbot.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

actual fun createSecretCipher(): SecretCipher? = KeystoreCipher

/**
 * AES-256-GCM con una clave del Android Keystore: se genera dentro del Keystore (en
 * hardware cuando el dispositivo lo tiene) y nunca sale de él, así que ni un backup de
 * SharedPreferences ni un dispositivo rooteado que copie el XML se llevan las keys en claro.
 */
private object KeystoreCipher : SecretCipher {
    private const val ALIAS = "localchatbot_settings_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        // El Keystore elige el IV (no deja fijarlo al cifrar), así que se lee del Cipher.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.encodeToByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }
}
