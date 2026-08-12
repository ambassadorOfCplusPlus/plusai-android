package ru.aiagent.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAS для сверки E2E-ключей — ДОЛЖЕН давать тот же код, что десктопный `Sas.Code` (иначе телефон и ПК
 * увидят разные цифры и спаривание не сойдётся). Эталонный вектор совпадает с C#-реализацией.
 */
class SasTest {
    private val a = "AQIDBA==" // байты 1,2,3,4
    private val b = "CQgHBg==" // байты 9,8,7,6
    private val m = "BQUFBQ==" // байты 5,5,5,5 — «ключ атакующего»

    @Test
    fun `совпадает с эталоном десктопа`() {
        // C#: Sas.Code("AQIDBA==","CQgHBg==") == "940789". Кросс-платформенный контракт.
        assertEquals("940789", Sas.code(a, b))
    }

    @Test
    fun `симметричен по порядку`() {
        assertEquals(Sas.code(a, b), Sas.code(b, a))
    }

    @Test
    fun `ровно шесть цифр`() {
        val c = Sas.code(a, b)
        assertEquals(6, c.length)
        assertTrue(c.all { it.isDigit() })
    }

    @Test
    fun `MITM видит разные коды на сторонах`() {
        assertNotEquals(Sas.code(a, m), Sas.code(m, b))
    }

    // ── SAS v2 (commit/reveal) — те же входы и эталоны, что в C# SasV2Tests ────────────────────────
    private val pubBv2 = "BQYHCA==" // байты 5,6,7,8
    private val nonceA = ByteArray(32) { 0x11.toByte() }
    private val nonceB = ByteArray(32) { 0x22.toByte() }

    @Test
    fun `commit v2 совпадает с эталоном`() {
        assertEquals("ZEXtlOzDdZepWId31YbJKOIu8MnRuM94y+NwBBFqKQI=", Sas.commit(nonceA))
    }

    @Test
    fun `codeV2 совпадает с эталоном десктопа`() {
        assertEquals("416464", Sas.codeV2(a, pubBv2, nonceA, nonceB)) // C#: Sas.CodeV2(...) == "416464"
    }

    @Test
    fun `codeV2 не зависит от роли`() {
        assertEquals(Sas.codeV2(a, pubBv2, nonceA, nonceB), Sas.codeV2(pubBv2, a, nonceB, nonceA))
    }

    @Test
    fun `verifyCommit принимает верный нонс и отвергает подмену и мусор`() {
        assertTrue(Sas.verifyCommit(Sas.commit(nonceA), nonceA))
        assertFalse(Sas.verifyCommit(Sas.commit(nonceA), nonceB))
        assertFalse(Sas.verifyCommit("не base64 !!!", nonceA))
    }

    @Test
    fun `codeV2 зависит от обоих нонсов`() {
        val byA = HashSet<String>()
        val byB = HashSet<String>()
        for (i in 0 until 8) {
            byA.add(Sas.codeV2(a, pubBv2, nonceA.copyOf().also { it[0] = i.toByte() }, nonceB))
            byB.add(Sas.codeV2(a, pubBv2, nonceA, nonceB.copyOf().also { it[0] = i.toByte() }))
        }
        assertTrue(byA.size > 1)
        assertTrue(byB.size > 1)
    }

    @Test
    fun `newNonce 32 байта и случаен`() {
        val n = Sas.newNonce()
        assertEquals(32, n.size)
        assertFalse(Sas.newNonce().contentEquals(n))
    }
}
