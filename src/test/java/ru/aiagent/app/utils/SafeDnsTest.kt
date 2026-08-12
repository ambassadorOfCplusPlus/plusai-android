package ru.aiagent.app.utils

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * Анти-SSRF + анти-DNS-rebinding резолвер для OkHttp. Резолвит один раз и проверяет ВСЕ адреса —
 * значит соединение не может уехать на приватный IP между проверкой и подключением (TOCTOU закрыт).
 * Числовые адреса парсятся без сети, поэтому тест герметичный.
 */
class SafeDnsTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun dns(vararg addrs: String) = SafeDns { addrs.map { ip(it) } }

    @Test
    fun `публичный адрес проходит`() {
        val r = dns("93.184.216.34").lookup("example.com")
        assertEquals(listOf(ip("93.184.216.34")), r)
    }

    @Test
    fun `loopback блокируется`() {
        assertThrows(IOException::class.java) { dns("127.0.0.1").lookup("evil.test") }
    }

    @Test
    fun `приватный 10-x блокируется`() {
        assertThrows(IOException::class.java) { dns("10.0.0.5").lookup("evil.test") }
    }

    @Test
    fun `CGNAT 100-64 блокируется`() {
        // isSiteLocalAddress НЕ покрывает carrier-NAT 100.64.0.0/10 — ловится явной проверкой.
        assertThrows(IOException::class.java) { dns("100.64.0.1").lookup("evil.test") }
    }

    @Test
    fun `один приватный из нескольких блокирует весь ответ`() {
        // Rebinding-обманка: смесь публичного и внутреннего — блокируем, раз ЛЮБОЙ адрес внутренний.
        assertThrows(IOException::class.java) { dns("93.184.216.34", "127.0.0.1").lookup("evil.test") }
    }

    @Test
    fun `isBlockedAddress различает публичный и приватный`() {
        assertFalse(isBlockedAddress(ip("93.184.216.34")))
        assertTrue(isBlockedAddress(ip("192.168.1.1")))
        assertTrue(isBlockedAddress(ip("169.254.1.1")))
    }
}
