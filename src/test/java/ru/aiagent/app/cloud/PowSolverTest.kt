package ru.aiagent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * PoW-солвер (клиентская сторона Android). Проверяем, что найденный counter действительно даёт
 * SHA-256(nonce || counter_be8) с ≥ bits ведущих нулевых бит — байт-в-байт как серверный
 * `server/internal/pow` (иначе сервер отвергнет решение как «мало работы»).
 */
class PowSolverTest {

    // Токен в серверном формате b64url(nonce).exp.bits.b64url(sig). Подпись солверу не важна.
    private fun makeToken(nonce: ByteArray, bits: Int): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return "${b64.encodeToString(nonce)}.9999999999.$bits.${b64.encodeToString(ByteArray(16))}"
    }

    private fun leadingZeroBits(h: ByteArray): Int {
        var n = 0
        for (b in h) {
            val v = b.toInt() and 0xFF
            if (v == 0) { n += 8; continue }
            var mask = 0x80
            while (mask != 0) {
                if (v and mask != 0) return n
                n++; mask = mask ushr 1
            }
            return n
        }
        return n
    }

    @Test
    fun solve_matchesGoReferenceCounter() {
        // Эталон из Go-референса (server/internal/pow.Solve) для nonce[i]=i*7+3. Клиент ищет первый
        // подходящий counter с нуля тем же предикатом → обязан найти ровно этот же (паритет байт-в-байт).
        val expected = mapOf(8 to 16L, 12 to 3801L, 16 to 35568L)
        for ((bits, want) in expected) {
            val nonce = ByteArray(16) { (it * 7 + 3).toByte() }
            val token = makeToken(nonce, bits)

            val solved = PowSolver.solve(token)
            assertTrue("bits=$bits: решение не найдено", solved != null)

            val counter = solved!!.substring(token.length + 1).toULong().toLong()
            assertEquals("bits=$bits паритет с Go", want, counter)

            val buf = ByteArray(24)
            System.arraycopy(nonce, 0, buf, 0, 16)
            var v = counter
            for (i in 7 downTo 0) { buf[16 + i] = (v and 0xFF).toByte(); v = v ushr 8 }
            val hash = MessageDigest.getInstance("SHA-256").digest(buf)
            assertTrue("counter $counter: нулей ${leadingZeroBits(hash)} < $bits", leadingZeroBits(hash) >= bits)
        }
    }

    @Test
    fun solve_rejectsMalformedToken() {
        assertNull(PowSolver.solve("not-a-token"))
        assertNull(PowSolver.solve("a.b.c"))         // мало сегментов
        assertNull(PowSolver.solve("!!!.9.8.!!!"))   // битый base64
    }

    @Test
    fun solve_returnsNullBeyondLimit() {
        // 64 нулевых бит недостижимо за крошечный лимит.
        assertNull(PowSolver.solve(makeToken(ByteArray(16), 64), maxIterations = 32))
    }

    @Test
    fun solve_headerFormatIsTokenColonCounter() {
        val token = makeToken(ByteArray(16) { it.toByte() }, 4)
        val solved = PowSolver.solve(token)!!
        assertEquals(token, solved.substringBeforeLast(':'))
        assertTrue(solved.substringAfterLast(':').toLong() >= 0)
    }
}
