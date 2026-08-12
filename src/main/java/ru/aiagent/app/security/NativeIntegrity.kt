package ru.aiagent.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Обёртка над нативной (Rust) проверкой целостности/безопасности — `libplusai_integrity.so`
 * (исходник: native/integrity). Нативные проверки патчить труднее, чем Kotlin-байткод.
 *
 *  • [signatureValid] — подпись ЭТОГО apk совпала с эталоном, зашитым в .so → приложение не репакнули.
 *  • [environment]    — битовая маска признаков среды (отладчик/root/эмулятор/permissive-SELinux).
 *
 * ЧЕСТНО: клиентские проверки поднимают планку, но не абсолют (Frida/hook/патч обходят). Поэтому это
 * СИГНАЛ, а не «кирпич»: репак и отладчик — блокируем чувствительное; root/эмулятор — по умолчанию НЕ
 * блокируем (легитимные кейсы), решает вызывающий код. Если .so не загрузилась (напр. debug без нативки)
 * — деградируем мягко (signatureValid=true), чтобы не ломать разработку.
 */
object NativeIntegrity {
    // Должно совпадать с флагами в native/integrity/src/lib.rs.
    const val DEBUGGER = 1
    const val ROOT = 2
    const val EMULATOR = 4
    const val SELINUX_PERMISSIVE = 8
    const val PTRACE_DENIED = 16

    @Volatile private var loaded = false

    init {
        loaded = runCatching { System.loadLibrary("plusai_integrity") }.isSuccess
    }

    private external fun nativeCheck(): Int
    private external fun nativeVerifySignature(certSha256: ByteArray): Boolean

    /** Признаки окружения битовой маской. 0 — чисто, либо .so не загрузилась. */
    fun environment(): Int = if (loaded) runCatching { nativeCheck() }.getOrDefault(0) else 0

    /** Подпись apk совпадает с эталоном в .so (анти-репак). false — переподписано/репакнуто. */
    fun signatureValid(context: Context): Boolean {
        if (!loaded) return true // нет нативки (debug) — не блокируем разработку
        val sha = ownCertSha256(context) ?: return false
        return runCatching { nativeVerifySignature(sha) }.getOrDefault(false)
    }

    /** SHA-256 сертификата, которым подписан текущий apk (совпадает с keytool/apksigner SHA256), или null. */
    fun ownCertSha256(context: Context): ByteArray? = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return@runCatching null
            si.apkContentsSigners ?: si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return@runCatching null
        if (sigs.isEmpty()) return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
    }.getOrNull()

    fun isDebugger(): Boolean = environment() and DEBUGGER != 0
    fun isRooted(): Boolean = environment() and ROOT != 0
    fun isEmulator(): Boolean = environment() and EMULATOR != 0

    /**
     * Доверять ли среде для ЧУВСТВИТЕЛЬНЫХ операций (спаривание телефон↔ПК, раскрытие секретов).
     *
     * СМЯГЧЕНО: активный отладчик блокирует ВСЕГДА (однозначный красный флаг), а несоответствие подписи
     * (репак) блокирует ТОЛЬКО в строгом режиме. Причина: в release-сборке со встроенной .so, но без
     * вписанного эталонного cert в native/integrity, сверка подписи всегда проваливается и глушила бы
     * спаривание (была форсинг-функция «впиши cert»). По умолчанию — предупреждаем (см. [signatureValid]),
     * не блокируем; владелец включает строгий режим ПОСЛЕ вписывания cert (тумблер в настройках).
     */
    fun trustedForSensitive(context: Context, strict: Boolean = false): Boolean {
        if (isDebugger()) return false                        // активный отладчик — всегда отказ
        if (strict && !signatureValid(context)) return false  // строгий: блокируем репак (нужен вписанный cert)
        return true
    }
}
