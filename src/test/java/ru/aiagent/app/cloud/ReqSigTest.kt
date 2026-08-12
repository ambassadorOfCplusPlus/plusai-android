package ru.aiagent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Подпись запроса (клиентская сторона Android). Пинуем эталонные значения из Go-референса
 * (`server/internal/reqsig`) — совпадение доказывает паритет канонич. строки, HMAC и hex между стеками
 * (иначе сервер отвергнет клиентскую подпись).
 */
class ReqSigTest {
    private val method = "POST"
    private val path = "/v1/wallet/topup"
    private val ts = "1700000000"
    private val nonce = "bm9uY2Ux"
    private val body = "{\"amount_rub\":100}".toByteArray(Charsets.UTF_8)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun sign_globalKey_matchesGoReference() {
        val key = "plusai/reqsig/v1/default-key".toByteArray(Charsets.UTF_8)
        assertEquals(
            "5ac9ca0d6343d3ad699291d37e29863c70e75800f45558335e933527a84594df",
            ReqSig.sign(key, method, path, ts, nonce, body),
        )
    }

    @Test
    fun deriveKey_matchesGoReference() {
        assertEquals(
            "09a5135aa74e62181d199d40badfb59fdfde673617f872daa9cb39d0db4544db",
            hex(ReqSig.deriveKey("acct-token-xyz")),
        )
    }

    @Test
    fun sign_derivedKey_matchesGoReference() {
        val key = ReqSig.deriveKey("acct-token-xyz")
        assertEquals(
            "f8822294b4015cf0d73d5645f9a6e0ae8abb3c82695ffd41bc6bedc5d763921f",
            ReqSig.sign(key, method, path, ts, nonce, body),
        )
    }

    @Test
    fun nonce_isUrlSafeUnpaddedAndRandom() {
        val n = ReqSig.newNonce()
        assertFalse(n.contains('='))
        assertFalse(n.contains('+'))
        assertFalse(n.contains('/'))
        assertNotEquals(ReqSig.newNonce(), n)
    }
}
