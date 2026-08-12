package ru.aiagent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Крипто синка ОБЯЗАНО совпадать байт-в-байт с C#/CLI (PlusAi.Core.SyncClient), иначе телефон и ПК не
 * прочитают синк друг друга. Пиннед-векторы ключа посчитаны независимым эталоном (Python pbkdf2_hmac) и
 * продублированы в C#-тесте (SyncClientTests) — так все три реализации сверены на одном значении.
 */
class SyncCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun deriveKey_кроссплатформенные_векторы() {
        assertEquals(
            "bfefbd43ef3b3ee8578f4839ced0d6af60b612008d9b2cecb3069ea1baba9216",
            hex(SyncCrypto.deriveKey("s3cret", "alice")),
        )
        assertEquals(
            "8939c10a502403010179636d3716874a51a3637acde1f60c91f137d6783027b3",
            hex(SyncCrypto.deriveKey("парольь", "юзер")), // не-ASCII: проверка UTF-8-совместимости
        )
    }

    @Test
    fun encryptDecrypt_round_trip() {
        val key = SyncCrypto.deriveKey("pass", "u")
        val msg = "Диалог: привет 👋\nвторая строка"
        val blob = SyncCrypto.encrypt(key, msg)
        assertNotEquals(blob, SyncCrypto.encrypt(key, msg)) // nonce случаен → блоб каждый раз другой
        assertEquals(msg, SyncCrypto.decrypt(key, blob))
    }

    @Test
    fun decrypt_неверный_пароль_возвращает_null() {
        val blob = SyncCrypto.encrypt(SyncCrypto.deriveKey("right", "u"), "секрет")
        assertNull(SyncCrypto.decrypt(SyncCrypto.deriveKey("wrong", "u"), blob))
        assertNull(SyncCrypto.decrypt(SyncCrypto.deriveKey("right", "u"), "не-base64!!"))
    }
}
