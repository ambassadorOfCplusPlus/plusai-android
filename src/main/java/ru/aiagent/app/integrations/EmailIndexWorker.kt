package ru.aiagent.app.integrations

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.aiagent.app.AppSettings
import ru.aiagent.app.ocr.OcrEngine
import ru.aiagent.app.rag.RagEngine
import ru.aiagent.toolsdocs.readDocumentText
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Фоновая индексация вложений почты (по согласию пользователя). Инкрементально по UID:
 * скачивает документ/скан-вложения новых писем, OCR-ит в ХОРОШЕМ качестве (tessdata_best),
 * эмбеддит и кладёт в зашифрованный локальный индекс ([EmailIndexStore]). Затем — тиринг
 * (сжатие/удаление старого). Ограничения запуска — Wi-Fi + не разряжен (см. [EmailIndexer]).
 */
class EmailIndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!AppSettings.emailIndexEnabled(ctx)) return Result.success()
        val acc = MailAuth.account(ctx) ?: return Result.success()
        // Без установленного эмбеддера RagEngine.embed вернёт ПУСТОЙ вектор. Если бы мы всё равно писали
        // строку в индекс, письмо считалось бы обработанным (watermark двигается), а cosineSim по пустому
        // вектору = -1 → оно навсегда невидимо в поиске и НЕ переиндексируется, когда модель поставят.
        // Поэтому без модели просто ждём (retry позже) — индекс не «отравляем».
        if (RagEngine.embeddingModelFile(ctx) == null) return Result.retry()
        ensureBestData(ctx) // докачать точные OCR-данные (best-effort)

        return EmailIndexStore(ctx).use { store ->
            val last = store.lastUid()
            val mails = MailClient.fetchRecent(acc.host, acc.email, acc.secret, acc.oauth, SCAN).getOrNull()
                ?: return@use Result.retry()

            // Только НОВЫЕ письма, СТАРЫЕ первыми. last_uid двигаем лишь за ПОЛНОСТЬЮ обработанные —
            // иначе при исчерпании бюджета/транзиентном сбое письмо было бы навсегда пропущено.
            val fresh = mails.filter { it.uid > last }.sortedBy { it.uid }
            var budget = MAX_PER_RUN
            var newLast = last
            run {
                for (m in fresh) {
                    var complete = true
                    for (name in m.attachments) {
                        if (!isExtractable(name) || store.has(m.uid, name)) continue
                        if (budget <= 0) { complete = false; break }          // бюджет кончился → не за m
                        budget--
                        val bytes = MailClient.fetchAttachment(acc.host, acc.email, acc.secret, acc.oauth, m.uid, name)
                            .getOrNull()
                        if (bytes == null) { complete = false; break }         // транзиентный сбой → повтор в след. раз
                        val text = extractBest(ctx, name, bytes)
                        if (text.isNotBlank()) {                               // пустой скан — не транзиент, просто пропускаем
                            val vec = RagEngine.embed(ctx, text.take(2000))   // эмбеддим «шапку» документа
                            // Пустой вектор (эмбеддер отвалился в ходе прогона) НЕ пишем и НЕ завершаем
                            // письмо — иначе оно осталось бы в индексе неискомым навсегда.
                            if (vec.isEmpty()) { complete = false; break }
                            store.add(m.uid, name, m.subject, m.from, vec, text)
                        }
                    }
                    if (!complete) return@run                                 // стоп: за незавершённое письмо не двигаем last
                    newLast = m.uid
                }
            }
            store.setLastUid(newLast)
            store.tier()
            Result.success()
        }
    }

    private fun extractBest(ctx: Context, name: String, bytes: ByteArray): String {
        val ext = safeExt(name) // имя вложения подконтрольно отправителю → чистим (path-traversal)
        val tmp = File(ctx.cacheDir, "idx_${System.nanoTime()}.$ext")
        return try {
            tmp.writeBytes(bytes)
            when {
                ext in OcrEngine.IMAGE_EXTS -> OcrEngine.extract(ctx, tmp, best = true)
                ext == "pdf" -> {
                    val t = runCatching { readDocumentText(ctx, tmp) }.getOrDefault("")
                    if (t.length < 20) OcrEngine.extract(ctx, tmp, best = true) else t
                }
                else -> runCatching { readDocumentText(ctx, tmp) }.getOrDefault("")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun ensureBestData(ctx: Context) {
        if (OcrEngine.bestReady(ctx)) return
        val dir = OcrEngine.bestDataDir(ctx)
        for (lang in listOf("rus", "eng")) {
            val out = File(dir, "$lang.traineddata")
            if (out.exists() && out.length() > 0) continue
            // Скачиваем во ВРЕМЕННЫЙ файл и переименовываем при успехе — иначе оборванная загрузка
            // оставит частичный .traineddata (bestReady=true → OCR на битых данных / нативный крэш).
            val tmp = File(dir, "$lang.traineddata.part")
            runCatching {
                val c = (URL("$BEST_BASE/$lang.traineddata").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 120000
                }
                try {
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    val len = c.contentLengthLong
                    if (len > 0 && tmp.length() != len) error("недокачано: ${tmp.length()}/$len")
                } finally {
                    c.disconnect()
                }
                // Целостность (supply-chain): traineddata грузится нативным Tesseract — подменённый/битый
                // файл = нативный крэш или мусорный OCR. Сверяем sha256 c пиннутым (тег 4.1.0, неизменяем).
                val expected = SHA256[lang] ?: error("нет эталонного sha256 для $lang")
                val actual = sha256Hex(tmp)
                if (!actual.equals(expected, ignoreCase = true)) error("sha256 не совпал ($lang): $actual")
                if (!tmp.renameTo(out)) error("rename")
            }.onFailure { tmp.delete(); out.delete() }
        }
    }

    /** Безопасное расширение из имени вложения (имя подконтрольно отправителю). */
    private fun safeExt(name: String): String =
        name.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.lowercase().take(5).ifBlank { "bin" }

    private fun isExtractable(name: String): Boolean {
        val ext = safeExt(name)
        return ext in setOf("pdf", "docx", "xlsx", "txt", "csv", "md") || ext in OcrEngine.IMAGE_EXTS
    }

    /** sha256 скачанного файла (hex, lowercase). */
    private fun sha256Hex(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SCAN = 60         // сколько недавних писем смотрим за проход
        const val MAX_PER_RUN = 20  // сколько вложений OCR-им за проход (батарея/время)
        // Пиннутый НЕИЗМЕНЯЕМЫЙ тег (не raw/main): контент фиксирован → sha256 стабилен.
        const val BEST_BASE = "https://github.com/tesseract-ocr/tessdata_best/raw/4.1.0"
        // Эталонные sha256 tessdata_best@4.1.0 (посчитаны при внедрении). Обновлять вместе с BEST_BASE.
        val SHA256 = mapOf(
            "eng" to "8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba",
            "rus" to "b617eb6830ffabaaa795dd87ea7fd251adfe9cf0efe05eb9a2e8128b7728d6b6",
        )
    }
}

/** Управление фоновым индексатором почты. */
object EmailIndexer {
    private const val WORK = "email_index"

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<EmailIndexWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi: трафик/приватность
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
}
