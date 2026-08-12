package ru.aiagent.app.utils

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Координатор реального NFC в foreground reader mode.
 *
 * Headless-NFC на Android невозможен: система отдаёт теги только активности, которая на
 * переднем плане и включила reader mode. Поэтому схема такая:
 *
 *   инструмент (nfc_read / nfc_write) → [requestRead]/[requestWrite] выставляет pending-запрос
 *      → инструмент выводит MainActivity на передний план → Activity.onResume видит [hasPending]
 *      и включает enableReaderMode → пользователь подносит метку → колбэк reader mode зовёт
 *      [onTag] (на binder-потоке, IO по NDEF безопасно) → читаем/пишем NDEF и complete-им deferred
 *      → инструмент получает результат (или таймаут).
 *
 * Одновременно поддерживается ровно один pending-запрос (последовательный доступ к чипу).
 */
object NfcBridge {

    private sealed interface Pending {
        val deferred: CompletableDeferred<String>

        data class Read(override val deferred: CompletableDeferred<String>) : Pending
        data class Write(val text: String, override val deferred: CompletableDeferred<String>) : Pending
    }

    @Volatile
    private var pending: Pending? = null

    /** Есть ли активный запрос — MainActivity.onResume смотрит сюда, включать ли reader mode. */
    fun hasPending(): Boolean = pending != null

    /** Запросить чтение метки. Возвращает deferred, который завершится JSON-описанием тега. */
    @Synchronized
    fun requestRead(): Deferred<String> {
        cancel("вытеснен новым запросом NFC")
        val d = CompletableDeferred<String>()
        pending = Pending.Read(d)
        return d
    }

    /** Запросить запись text-record на метку. Deferred завершится JSON-результатом записи. */
    @Synchronized
    fun requestWrite(text: String): Deferred<String> {
        cancel("вытеснен новым запросом NFC")
        val d = CompletableDeferred<String>()
        pending = Pending.Write(text, d)
        return d
    }

    /** Отменить текущий запрос (таймаут инструмента / уход приложения в фон). */
    @Synchronized
    fun cancel(reason: String) {
        val p = pending ?: return
        pending = null
        if (!p.deferred.isCompleted) p.deferred.completeExceptionally(NfcCancelled(reason))
    }

    /**
     * Вызывается из MainActivity колбэком reader mode при поднесении метки. Читает/пишет NDEF
     * и завершает pending-deferred. Выполняется на потоке NFC (не main) — блокирующее IO ок.
     */
    fun onTag(tag: Tag) {
        val p = synchronized(this) { pending.also { pending = null } } ?: return
        try {
            val result = when (p) {
                is Pending.Read -> readTag(tag)
                is Pending.Write -> writeTag(tag, p.text)
            }
            p.deferred.complete(result)
        } catch (t: Throwable) {
            if (!p.deferred.isCompleted) p.deferred.completeExceptionally(t)
        }
    }

    class NfcCancelled(message: String) : Exception(message)

    // ──────────────────────────── ЧТЕНИЕ ────────────────────────────

    private fun readTag(tag: Tag): String {
        val uid = uidHex(tag)
        val techs = tag.techList.joinToString(",") { it.substringAfterLast('.') }
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            // Метка без NDEF (напр. голый MIFARE Classic) — отдаём хотя бы UID и типы.
            return """{"uid":"${esc(uid)}","tech":"${esc(techs)}","ndef":false,"text":"","records":[]}"""
        }
        ndef.connect()
        try {
            val msg: NdefMessage? = ndef.cachedNdefMessage ?: runCatching { ndef.ndefMessage }.getOrNull()
            val records = msg?.records ?: emptyArray()
            val texts = ArrayList<String>()
            val recJson = StringBuilder()
            for (r in records) {
                val text = decodeText(r)
                val uri = runCatching { r.toUri()?.toString() }.getOrNull()
                if (text != null) texts.add(text)
                else if (!uri.isNullOrBlank()) texts.add(uri)
                if (recJson.isNotEmpty()) recJson.append(",")
                recJson.append(
                    """{"tnf":${r.tnf},"type":"${esc(String(r.type, Charsets.US_ASCII))}",""" +
                        """"text":"${esc(text ?: uri ?: "")}"}""",
                )
            }
            val joined = texts.joinToString("\n")
            return """{"uid":"${esc(uid)}","tech":"${esc(techs)}","ndef":true,""" +
                """"writable":${ndef.isWritable},"maxSize":${ndef.maxSize},""" +
                """"text":"${esc(joined)}","records":[$recJson]}"""
        } finally {
            runCatching { ndef.close() }
        }
    }

    /** Декодирует NDEF Well-Known Text record по спецификации NFC Forum (иначе null). */
    private fun decodeText(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
        val payload = record.payload
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt() and 0xFF
        val langLen = status and 0x3F
        val utf16 = (status and 0x80) != 0
        val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
        val start = 1 + langLen
        if (start > payload.size) return ""
        return String(payload, start, payload.size - start, charset)
    }

    // ──────────────────────────── ЗАПИСЬ ────────────────────────────

    private fun writeTag(tag: Tag, text: String): String {
        val message = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", text)))
        val size = message.toByteArray().size
        val uid = uidHex(tag)

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) throw IllegalStateException("метка только для чтения")
                if (ndef.maxSize in 1 until size) {
                    throw IllegalStateException("не помещается: нужно $size Б, на метке ${ndef.maxSize} Б")
                }
                ndef.writeNdefMessage(message)
            } finally {
                runCatching { ndef.close() }
            }
            return """{"uid":"${esc(uid)}","written":true,"formatted":false,"bytes":$size,"text":"${esc(text)}"}"""
        }

        // Пустая, ещё не форматированная под NDEF метка — форматируем и пишем за один заход.
        val formatable = NdefFormatable.get(tag)
            ?: throw IllegalStateException("метка не поддерживает NDEF — записать нельзя")
        formatable.connect()
        try {
            formatable.format(message)
        } finally {
            runCatching { formatable.close() }
        }
        return """{"uid":"${esc(uid)}","written":true,"formatted":true,"bytes":$size,"text":"${esc(text)}"}"""
    }

    private fun uidHex(tag: Tag): String =
        tag.id?.joinToString("") { "%02X".format(it) } ?: ""
}
