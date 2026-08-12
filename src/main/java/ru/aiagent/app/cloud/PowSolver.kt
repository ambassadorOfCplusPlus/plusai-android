package ru.aiagent.app.cloud

import java.security.MessageDigest
import java.util.Base64

/**
 * Клиентский решатель анти-абуз PoW сервера («хеш-ловушка»). Челендж-токен:
 * `b64url(nonce).exp.bits.b64url(sig)`. Ищем counter (u64), у которого
 * SHA-256(nonce || counter_be8) содержит ≥ bits ведущих нулевых бит — порт
 * `server/internal/pow` байт-в-байт (стандартный SHA-256, поэтому переносимо и тривиально).
 * Обычному клиенту это доли секунды, массовому боту — дорого.
 */
object PowSolver {

    /** Решить челендж. Возвращает `"token:counter"` для заголовка X-PlusAI-PoW, или null. */
    fun solve(token: String, maxIterations: Long = 1L shl 26): String? {
        val parts = token.split(".")
        if (parts.size != 4) return null
        val nonce = try {
            // RawURLEncoding с сервера (без padding); java.util.Base64 URL-декодер это принимает.
            Base64.getUrlDecoder().decode(parts[0])
        } catch (e: IllegalArgumentException) {
            return null
        }
        val bits = parts[2].toIntOrNull() ?: return null
        if (bits < 0 || bits > 255) return null // bits — uint8 у сервера

        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(nonce.size + 8)
        System.arraycopy(nonce, 0, buf, 0, nonce.size)
        var c = 0L
        while (c < maxIterations) {
            // counter в последние 8 байт, big-endian — как binary.BigEndian.PutUint64 на сервере.
            var v = c
            for (i in 7 downTo 0) {
                buf[nonce.size + i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            md.reset()
            if (leadingZeroBits(md.digest(buf)) >= bits) return "$token:${c.toULong()}"
            c++
        }
        return null
    }

    private fun leadingZeroBits(h: ByteArray): Int {
        var n = 0
        for (b in h) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                n += 8
                continue
            }
            var mask = 0x80
            while (mask != 0) {
                if (v and mask != 0) return n
                n++
                mask = mask ushr 1
            }
            return n
        }
        return n
    }
}
