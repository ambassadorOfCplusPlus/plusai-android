package ru.aiagent.app.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маскирование OTP/кодов в сообщениях (§7): цена ошибки — код подтверждения уходит в облачную модель.
 * Особый случай — алфа-цифровой код рядом с маркером (раньше протекал).
 */
class MessengerFilterTest {
    @Test
    fun `голый цифровой код маскируется`() {
        val (out, masked) = MessengerFilter.redactCodes("483920")
        assertTrue(masked)
        assertFalse(out.contains("483920"))
    }

    @Test
    fun `девятизначный OTP рядом с маркером маскируется`() {
        val (out, masked) = MessengerFilter.redactCodes("Ваш код: 123456789")
        assertTrue(masked)
        assertFalse(out.contains("123456789"))
    }

    @Test
    fun `алфа-цифровой код рядом с маркером маскируется`() {
        // Раньше чисто-цифровой паттерн его пропускал → код протекал в транскрипт.
        val (out, masked) = MessengerFilter.redactCodes("код: A1B2C3")
        assertTrue(masked)
        assertFalse(out.contains("A1B2C3"))
    }

    @Test
    fun `обычный текст без маркера не трогаем`() {
        val (out, masked) = MessengerFilter.redactCodes("привет, как дела")
        assertFalse(masked)
        assertEquals("привет, как дела", out)
    }
}
