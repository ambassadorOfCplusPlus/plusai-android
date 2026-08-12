package ru.aiagent.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование данных-at-rest на аппаратном ключе Android Keystore (AES-256-GCM).
 * Ключ живёт в защищённом железе (TEE/StrongBox), из процесса не извлекается; AES на ARMv8
 * аппаратно ускорен — расшифровка «почти мгновенна». Формат blob: [IV(12) | ciphertext+tag].
 * Используется для локального индекса вложений почты (персональные документы).
 */
object CryptoBox {
    private const val KEY_ALIAS = "plusai_index_aes"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    fun encrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ct = c.doFinal(data)
        return c.iv + ct // iv (12) + ciphertext(+tag)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "битый blob" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv)) }
        return c.doFinal(ct)
    }
}
