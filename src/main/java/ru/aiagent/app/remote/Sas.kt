package ru.aiagent.app.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * SAS (short authentication string) для внеполосной сверки E2E-ключей при первом спаривании.
 *
 * ЗАЧЕМ. TOFU-пиннинг отклоняет СМЕНУ ключа, но не спасает от MITM В МОМЕНТ первого контакта: реле
 * подставляет свои ключи с самого начала. SAS выводится из хэша ОБОИХ публичных ключей и показывается
 * на двух устройствах — одинаковый код у человека доказывает, что у сторон один набор ключей (реле не
 * подменяло). MITM держит с каждой стороной свой ключ → коды не совпадут.
 *
 * Реализация ДОЛЖНА совпадать байт-в-байт с десктопной `Sas.Code` (кросс-платформенный контракт,
 * закреплён эталонным вектором в тесте): сортировка ключей по байтам DER → SHA-256 → первые 32 бита →
 * шесть десятичных цифр. Base64 — java.util.Base64 (стандартный, как android.util.Base64 NO_WRAP).
 */
object Sas {
    fun code(pubABase64: String, pubBBase64: String): String {
        val a = Base64.getDecoder().decode(pubABase64)
        val b = Base64.getDecoder().decode(pubBBase64)

        // Порядок не важен: сортируем по содержимому, чтобы вход обеих сторон был идентичен.
        val (first, second) = if (compare(a, b) <= 0) a to b else b to a
        val buf = first + second

        val h = MessageDigest.getInstance("SHA-256").digest(buf)
        val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    // ── SAS v2 (защита от грайндинга вредоносным реле) ────────────────────────────────────────────
    //
    // v1 (code выше) считает SAS от одних лишь СТАТИЧНЫХ announce-ключей → вредоносное реле оффлайн
    // перебирает свои подставные ключи до совпадения 6-значных кодов (birthday) и делает MITM. v2 добавляет
    // свежий нонс каждой стороны и раунд commit-before-reveal: реле фиксирует свой нонс ДО того, как увидит
    // честный, а честный каждый раз новый → подбор невозможен, остаётся слепое угадывание 10^-6.
    // Реализация ДОЛЖНА совпадать байт-в-байт с десктопной `Sas` (кросс-платформенный контракт, закреплён
    // эталонным вектором). Дизайн: docs/superpowers/specs/2026-07-30-sas-commitment-hardening-design.md.

    private val COMMIT_PREFIX = "plusai-sas-commit-v1".toByteArray(Charsets.US_ASCII)
    private val SAS_V2_PREFIX = "plusai-sas-v2".toByteArray(Charsets.US_ASCII)
    private val rng = SecureRandom()

    /** Свежий 256-битный нонс на сессию спаривания (CSPRNG). */
    fun newNonce(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    /** Обязательство к своему нонсу: SHA-256("plusai-sas-commit-v1" || nonce), base64. */
    fun commit(nonce: ByteArray): String =
        Base64.getEncoder().encodeToString(sha256(COMMIT_PREFIX + nonce))

    /** Сходится ли раскрытый нонс с ранее принятым commit (constant-time). Нет → подмена, обрыв. */
    fun verifyCommit(commitBase64: String, nonce: ByteArray): Boolean {
        val expected = try {
            Base64.getDecoder().decode(commitBase64)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (expected.size != 32) return false
        return MessageDigest.isEqual(expected, sha256(COMMIT_PREFIX + nonce)) // constant-time (API ≥24)
    }

    /**
     * SAS v2: 6-значный код от ОБОИХ публичных ключей И ОБОИХ нонсов. Пары (ключ, нонс) сортируются по
     * байтам ключа — обе стороны считают одинаково независимо от роли. [nonceA] — нонс владельца
     * [pubABase64], [nonceB] — владельца pubB.
     */
    fun codeV2(pubABase64: String, pubBBase64: String, nonceA: ByteArray, nonceB: ByteArray): String {
        val a = Base64.getDecoder().decode(pubABase64)
        val b = Base64.getDecoder().decode(pubBBase64)
        // Сортируем ПАРЫ (pub,nonce) по байтам pub: association ключ↔нонс сохраняется, роль не важна.
        val buf = if (compare(a, b) <= 0) {
            SAS_V2_PREFIX + a + b + nonceA + nonceB
        } else {
            SAS_V2_PREFIX + b + a + nonceB + nonceA
        }
        val h = sha256(buf)
        val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun compare(x: ByteArray, y: ByteArray): Int {
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val c = (x[i].toInt() and 0xFF).compareTo(y[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
