package ru.aiagent.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три защиты телефона-хоста связки «управление с ПК», доведённые до паритета с десктопом:
 *  - [TofuPin] — TOFU-пиннинг публичного ключа пира (реле не должно подменить его на MITM);
 *  - [ReplayGuard] — анти-replay по отпечатку шифртекста (реле присваивает seq, ему не доверяем);
 *  - cmd-guard — хост исполняет только `cmd`, а не отражённые ему собственные ответы step/final/err.
 */
class RemoteGuardsTest {

    @Test
    fun `первый ключ пира принимается`() {
        assertTrue(TofuPin.accept(known = null, incoming = "K1"))
    }

    @Test
    fun `тот же ключ пира принимается`() {
        assertTrue(TofuPin.accept(known = "K1", incoming = "K1"))
    }

    @Test
    fun `смена ключа пира отклоняется`() {
        // Подмена pubkey известного пира = попытка MITM со стороны реле — не переderiv-аем ключ.
        assertFalse(TofuPin.accept(known = "K1", incoming = "K2"))
    }

    @Test
    fun `повтор того же шифртекста отклоняется`() {
        val g = ReplayGuard()
        assertFalse(g.seenBefore("payload-A")) // первый раз — не повтор
        assertTrue(g.seenBefore("payload-A"))  // второй раз — повтор
        assertFalse(g.seenBefore("payload-B")) // другой шифртекст — не повтор
    }

    @Test
    fun `старые отпечатки вытесняются по ёмкости`() {
        val g = ReplayGuard(capacity = 2)
        g.seenBefore("a")
        g.seenBefore("b")
        g.seenBefore("c")                       // "a" вытеснен (окно = 2: b, c)
        assertFalse(g.seenBefore("a"))          // снова «не повтор» — окно уехало
    }

    @Test
    fun `cmd-guard отличает команду от ответа`() {
        assertEquals("cmd", Wire.parse(Wire.make("cmd", "ls")).first)
        assertEquals("step", Wire.parse(Wire.make("step", "прогресс")).first)
    }
}
