package ru.aiagent.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * On-device OCR (Tesseract, rus+eng, §3.7/§3.9). Извлекает текст из фото и СКАНОВ PDF
 * (страницы рендерятся PdfRenderer → распознаются). Всё локально — файлы не покидают
 * устройство (§7). Языковые данные (assets/tessdata) копируются в filesDir при первом вызове.
 * Синхронный и потокобезопасный (Tesseract не реентерабелен → общий lock).
 */
object OcrEngine {
    private const val LANGS = "rus+eng"
    private const val MAX_PDF_PAGES = 30   // защита от гигантских сканов (время/батарея)
    private const val RENDER_DPI = 300     // рендер страницы PDF под OCR: 300 DPI — «родной» для Tesseract,
                                           // мелкий текст документов распознаётся заметно точнее, чем на 200
    private const val MAX_CHARS = 200_000
    private val lock = Any()

    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "tif", "tiff")

    /**
     * Извлечь текст из файла (image/pdf). [best]=true — распознавать точной моделью tessdata_best
     * (для фоновой индексации, где латентность не важна), с откатом на быстрые данные из assets.
     * Пусто — если не поддержано/не удалось.
     */
    fun extract(context: Context, file: File, best: Boolean = false): String = synchronized(lock) {
        when {
            file.extension.lowercase() == "pdf" -> ocrPdf(context, file, best)
            file.extension.lowercase() in IMAGE_EXTS -> {
                val bmp = decode(file, 2400) ?: return ""
                try { ocrBitmap(context, bmp, best) } finally { bmp.recycle() }
            }
            else -> ""
        }
    }

    /** Папка для точных данных tessdata_best — сюда фоновый воркер кладёт rus/eng.traineddata. */
    fun bestDataDir(context: Context): File = File(File(context.filesDir, "tesseract-best"), "tessdata").apply { mkdirs() }
    fun bestReady(context: Context): Boolean =
        listOf("rus", "eng").all { File(bestDataDir(context), "$it.traineddata").let { f -> f.exists() && f.length() > 0 } }

    private fun ocrPdf(context: Context, file: File, best: Boolean = false): String {
        val tess = newTess(context, best) ?: return ""
        val sb = StringBuilder()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                    for (i in 0 until pages) {
                        if (sb.length >= MAX_CHARS) break
                        renderer.openPage(i).use { page ->
                            val w = (page.width * RENDER_DPI / 72).coerceIn(320, 3000)
                            val h = (page.height * RENDER_DPI / 72).coerceIn(320, 3000)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(Color.WHITE) // прозрачность → белый фон под OCR
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                tess.setImage(bmp)
                                sb.append(tess.getUTF8Text() ?: "").append('\n')
                                tess.clear()
                            } finally {
                                bmp.recycle() // не утечь нативную память даже при исключении OCR
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            tess.recycle()
        }
        return sb.toString().trim().take(MAX_CHARS)
    }

    private fun ocrBitmap(context: Context, bmp: Bitmap, best: Boolean = false): String {
        val tess = newTess(context, best) ?: return ""
        return try {
            tess.setImage(bmp)
            (tess.getUTF8Text() ?: "").trim().take(MAX_CHARS)
        } catch (_: Throwable) {
            ""
        } finally {
            tess.recycle()
        }
    }

    /**
     * Публичная фабрика ГОТОВОГО распознавателя Tesseract (rus+eng по умолчанию) для инструментов,
     * которым нужны БОКСЫ слов, а не только текст (напр. pdf_ocr — текстовый слой поверх скана).
     * Переиспользует ту же подготовку языковых данных (assets→filesDir) и настройки движка
     * (OEM_LSTM_ONLY / PSM_AUTO / preserve_interword_spaces), что и [extract]. Вызывающий ОБЯЗАН
     * вызвать recycle(). null — если языковые данные не удалось подготовить/инициализировать.
     */
    fun newRecognizer(context: Context, langs: String = LANGS): TessBaseAPI? =
        synchronized(lock) { newTess(context, best = false, langs = langs) }

    /**
     * Выполнить [block] под ОБЩИМ Tesseract-локом. Инструменты, получившие распознаватель через
     * [newRecognizer], ОБЯЗАНЫ вести всё распознавание (setImage/getUTF8Text/ResultIterator) внутри
     * этого блока: Tesseract не реентерабелен, и параллельный OCR (напр. фоновая индексация почты
     * через [extract]) на другом потоке = нативный SIGSEGV. Лок реентерабельный (можно звать
     * newRecognizer внутри блока).
     */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    private fun newTess(context: Context, best: Boolean = false, langs: String = LANGS): TessBaseAPI? {
        // best: точные данные, если скачаны; иначе — быстрые из assets.
        val base = (if (best && bestReady(context)) File(context.filesDir, "tesseract-best") else null)
            ?: ensureTessData(context) ?: return null
        val tess = TessBaseAPI()
        // OEM_LSTM_ONLY: нейросетевой движок — лучший для кириллицы (у legacy русский слабее).
        if (!tess.init(base.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY)) { tess.recycle(); return null }
        // Документ с шапкой/абзацами → авто-сегментация страницы; пробелы между словами не схлопываем.
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        runCatching { tess.setVariable("preserve_interword_spaces", "1") }
        return tess
    }

    /** Копирует .traineddata из assets в filesDir/tesseract/tessdata (init ждёт РОДИТЕЛЯ tessdata). */
    private fun ensureTessData(context: Context): File? {
        val base = File(context.filesDir, "tesseract")
        val dir = File(base, "tessdata").apply { mkdirs() }
        for (lang in listOf("rus", "eng")) {
            val out = File(dir, "$lang.traineddata")
            if (out.exists() && out.length() > 0) continue
            // Пишем во ВРЕМЕННЫЙ файл и переименовываем: иначе процесс, убитый посреди копирования,
            // оставит усечённый .traineddata длиной >0 — на след. запуске он считается готовым, Tess.init
            // падает на битых данных, OCR навсегда молча возвращает "" (лечится только сбросом данных).
            val tmp = File(dir, "$lang.traineddata.part")
            val ok = runCatching {
                context.assets.open("tessdata/$lang.traineddata").use { inp ->
                    tmp.outputStream().use { inp.copyTo(it) }
                }
                if (!tmp.renameTo(out)) error("rename failed")
            }.isSuccess
            if (!ok) { tmp.delete(); return null }
        }
        return base
    }

    private fun decode(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }
}
