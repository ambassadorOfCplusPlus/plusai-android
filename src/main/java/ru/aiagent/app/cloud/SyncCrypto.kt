package ru.aiagent.app.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Крипто кросс-девайс синка — БАЙТ-В-БАЙТ как десктоп/CLI (PlusAi.Core.SyncClient), чтобы телефон и ПК
 * читали синк друг друга. Ключ = PBKDF2-HMAC-SHA256(пароль[UTF-8], соль, 210000, 32 байта); соль =
 * SHA-256("plusai/sync/v1/salt:" + логин). Шифр = AES-256-GCM, blob = base64(nonce(12) ‖ ct ‖ tag(16)).
 *
 * PBKDF2 считаем ВРУЧНУЮ по UTF-8-байтам пароля: SecretKeyFactory("PBKDF2WithHmacSHA256") на Android
 * трактует char[]-пароль неоднозначно для не-ASCII (низкий байт вместо UTF-8) — тогда ключ разошёлся бы
 * с C#-клиентом (там пароль — именно UTF-8-байты), и синк между телефоном и ПК не расшифровался бы.
 */
object SyncCrypto {
    private const val ITER = 210_000
    private const val NONCE = 12
    private const val TAG_BITS = 128

    fun deriveKey(passphrase: String, accountLogin: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("plusai/sync/v1/salt:$accountLogin".toByteArray(Charsets.UTF_8))
        return pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, ITER, 32)
    }

    /** Зашифровать текст → base64(nonce ‖ ct ‖ tag). AES-256-GCM. */
    fun encrypt(key: ByteArray, plaintext: String): String {
        val nonce = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ctTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // ct ‖ tag(16) — Java кладёт тег в хвост
        return Base64.getEncoder().encodeToString(nonce + ctTag)
    }

    /** Расшифровать base64-блоб. null — неверный ключ (не тот синк-пароль) или повреждение. */
    fun decrypt(key: ByteArray, blobB64: String): String? = try {
        val blob = Base64.getDecoder().decode(blobB64)
        if (blob.size < NONCE + 16) null else {
            val nonce = blob.copyOfRange(0, NONCE)
            val ctTag = blob.copyOfRange(NONCE, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            String(cipher.doFinal(ctTag), Charsets.UTF_8) // бросит при неверном ключе/подделке
        }
    } catch (_: Exception) {
        null
    }

    // RFC 2898 PBKDF2-HMAC-SHA256 по явным байтам пароля (эквивалент Rfc2898DeriveBytes.Pbkdf2 в C#).
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        var offset = 0
        var block = 1
        while (offset < dkLen) {
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (c in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val len = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, out, offset, len)
            offset += len
            block++
        }
        return out
    }
}
