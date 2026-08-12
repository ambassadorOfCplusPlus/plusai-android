package ru.aiagent.app.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись отдельного запроса к нашему API (defense-in-depth поверх TLS+Bearer). Порт
 * `server/internal/reqsig` байт-в-байт: канонич. строка `method\npath\nts\nnonce\nhex(sha256(body))`,
 * подпись = `hex(HMAC-SHA256(key, canonical))`. Для аутентифицированных запросов ключ выводится из
 * токена аккаунта (deriveKey) — там ценность реальна (целостность/анти-реплей денежных операций на
 * недоверенном хопе). Для неаутентифицированных — общий ключ-порог (честная граница в ADR).
 */
object ReqSig {
    private const val DERIVE_LABEL = "plusai-reqsig-v1"
    private val rng = SecureRandom()
    private const val HEX = "0123456789abcdef"

    /** Ключ подписи из секрета аккаунта (Bearer-токена): HMAC-SHA256(token, label). */
    fun deriveKey(accountToken: String): ByteArray =
        hmac(accountToken.toByteArray(Charsets.UTF_8), DERIVE_LABEL.toByteArray(Charsets.UTF_8))

    /** Каноническая подписываемая строка (порядок и разделители фиксированы). */
    fun canonical(method: String, path: String, ts: String, nonce: String, body: ByteArray): String {
        val bh = MessageDigest.getInstance("SHA-256").digest(body)
        return "$method\n$path\n$ts\n$nonce\n${hex(bh)}"
    }

    /** hex(HMAC-SHA256(key, canonical(...))) — значение заголовка X-PlusAI-Sig. */
    fun sign(key: ByteArray, method: String, path: String, ts: String, nonce: String, body: ByteArray): String =
        hex(hmac(key, canonical(method, path, ts, nonce, body).toByteArray(Charsets.UTF_8)))

    /** Случайный nonce (16 байт → base64url без padding). */
    fun newNonce(): String {
        val b = ByteArray(16)
        rng.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }
}
