package ru.aiagent.app.utils

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import ru.aiagent.app.MainActivity
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * Реальные NFC-инструменты (не заглушка) через foreground reader mode.
 *
 * Headless-чтения NFC на Android нет: теги приходят только активности на переднем плане.
 * Поэтому инструмент выставляет запрос в [NfcBridge], выводит [MainActivity] вперёд (она в
 * onResume включает reader mode) и ждёт поднесения метки. Реально работает при наличии NFC-чипа
 * в телефоне, включённого NFC и физической метки.
 */

private const val TIMEOUT_MS = 30_000L

/** nfc_read — прочитать NFC-метку (UID + NDEF text/URI). SAFE: только чтение поднесённой метки. */
class NfcReadTool(private val context: Context) : AgentTool {
    override val id = "nfc_read"
    override val danger = DangerLevel.SAFE
    override val schema =
        """прочитать NFC-метку — выводит приложение вперёд, включает режим чтения, ждёт поднесения метки (до 30с); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        nfcPrecheck(context)?.let { return it }
        val deferred = NfcBridge.requestRead()
        bringActivityToFront(context)
        return awaitTag(deferred)
    }
}

/** nfc_write — записать text-record на NFC-метку. IMPORTANT: изменяет содержимое метки. */
class NfcWriteTool(private val context: Context) : AgentTool {
    override val id = "nfc_write"
    override val danger = DangerLevel.IMPORTANT
    override val schema =
        """записать текст на NFC-метку (NDEF text-record) — выводит приложение вперёд, ждёт поднесения метки (до 30с); args: {"text":"строка для записи"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val text = str(argsJson, "text")
            ?: return ToolResult.Failure("нет args.text")
        if (text.isEmpty()) return ToolResult.Failure("пустой args.text — нечего писать")
        nfcPrecheck(context)?.let { return it }
        val deferred = NfcBridge.requestWrite(text)
        bringActivityToFront(context)
        return awaitTag(deferred)
    }
}

/** Проверка наличия и включённости NFC — понятный Failure вместо тихого зависания. */
private fun nfcPrecheck(context: Context): ToolResult? {
    val adapter = NfcAdapter.getDefaultAdapter(context)
        ?: return ToolResult.Failure("в этом устройстве нет NFC-чипа — операция недоступна")
    if (!adapter.isEnabled) {
        return ToolResult.Failure("NFC выключен — включите его в настройках и повторите")
    }
    return null
}

/** Выводит MainActivity на передний план, чтобы сработал foreground reader mode. */
private fun bringActivityToFront(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra("nfc", true)
    }
    context.startActivity(intent)
}

/** Ждёт метку с таймаутом; при истечении отменяет pending и даёт понятный Failure. */
private suspend fun awaitTag(deferred: Deferred<String>): ToolResult {
    val outcome = withTimeoutOrNull(TIMEOUT_MS) {
        runCatching { deferred.await() }
    }
    if (outcome == null) {
        NfcBridge.cancel("таймаут ожидания метки")
        return ToolResult.Failure("метка не поднесена за 30с — попробуйте ещё раз")
    }
    return outcome.fold(
        onSuccess = { ToolResult.Success(it) },
        onFailure = { ToolResult.Failure("NFC: ${it.message?.take(200)}") },
    )
}

/** Фабрика NFC-инструментов. */
fun nfcTools(context: Context): List<AgentTool> = listOf(
    NfcReadTool(context),
    NfcWriteTool(context),
)
