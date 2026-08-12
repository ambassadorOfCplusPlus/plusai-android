package ru.aiagent.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.app.ocr.OcrEngine
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File

/**
 * pdf_ocr — превратить КАРТИНКУ или СКАН-PDF (только изображения, без текстового слоя) в
 * ПОИСКОВЫЙ PDF: под изображение каждой страницы кладётся НЕВИДИМЫЙ текстовый слой (режим
 * отрисовки текста «neither», PDF text rendering mode 3), позиционированный по боксам слов из
 * OCR. Документ выглядит идентично оригиналу, но текст выделяется/ищется/копируется.
 *
 * Переиспользует существующую обвязку:
 *  - OCR: [OcrEngine.newRecognizer] — та же подготовка tessdata (assets→filesDir, rus+eng) и
 *    настройки движка, что и в OcrEngine.extract(); нам нужны БОКСЫ слов (ResultIterator/RIL_WORD),
 *    поэтому используем распознаватель напрямую, а не extract() (тот отдаёт только текст).
 *  - PDFBox: [PdfBoxInit.ensure] из DocMapTools (идемпотентная PDFBoxResourceLoader.init) и тот же
 *    механизм resolve(...) песочницы, что у pdf_merge/pdf_split.
 *
 * Текстовый слой embed-ится ШРИФТОМ LiberationSans (PDType0Font, subset) — он идёт в ассетах
 * pdfbox-android и покрывает кириллицу+латиницу; стандартный Helvetica кириллицу не кодирует.
 *
 * §7: работает ПОЛНОСТЬЮ локально, содержимое документа не покидает устройство → privateData=true,
 * usesFiles=true. IMPORTANT — создаёт файл и читает пользовательский документ.
 */
class PdfOcrTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "pdf_ocr"
    override val danger = DangerLevel.IMPORTANT
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """сделать поисковый PDF из скана/картинки (невидимый текстовый слой поверх изображения, OCR rus+eng); """ +
            """args: {"input":"скан.pdf или фото.jpg","output":"опц имя.pdf","lang":"опц rus+eng"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON")
        val input = resolve(o.optString("input")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!input.exists() || !input.isFile) return@withContext ToolResult.Failure("нет файла: ${input.name}")
        val ext = input.extension.lowercase()
        val isPdf = ext == "pdf"
        if (!isPdf && ext !in OcrEngine.IMAGE_EXTS)
            return@withContext ToolResult.Failure("pdf_ocr: поддерживаются PDF и картинки (${OcrEngine.IMAGE_EXTS.joinToString(",")})")

        val out = resolve(o.optString("output").ifBlank { "${input.nameWithoutExtension}_ocr.pdf" })
            ?: return@withContext ToolResult.Failure("путь вне папки")
        val requested = o.optString("lang").trim().ifBlank { DEFAULT_LANG }

        PdfBoxInit.ensure(context)
        // ВСЁ распознавание — под ОБЩИМ Tesseract-локом: движок не реентерабелен, параллельный OCR
        // (напр. фоновая индексация почты через OcrEngine.extract) на другом потоке = нативный SIGSEGV.
        return@withContext OcrEngine.withLock {
            // Распознаватель с боксами слов — та же подготовка tessdata, что и OcrEngine.extract().
            var usedLang = requested
            val tess = OcrEngine.newRecognizer(context, requested)
                ?: run { usedLang = DEFAULT_LANG; OcrEngine.newRecognizer(context, DEFAULT_LANG) } // откат
                ?: return@withLock ToolResult.Failure("pdf_ocr: не удалось подготовить языковые данные OCR")

            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            val doc = PDDocument()
            var pages = 0
            var chars = 0L
            try {
                // Единый шрифт с кириллицей (subset-embed) на весь документ.
                val font = context.assets.open(FONT_ASSET).use { PDType0Font.load(doc, it) }

                if (isPdf) {
                    pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(pfd)
                    val n = minOf(renderer.pageCount, MAX_PAGES)
                    if (renderer.pageCount == 0) error("во входном PDF нет страниц")
                    for (i in 0 until n) {
                        renderer.openPage(i).use { page ->
                            val ptW = page.width.toFloat()   // PdfRenderer отдаёт размеры в поинтах (1/72")
                            val ptH = page.height.toFloat()
                            val bmpW = (page.width * RENDER_DPI / 72).coerceIn(320, MAX_SIDE)
                            val bmpH = (page.height * RENDER_DPI / 72).coerceIn(320, MAX_SIDE)
                            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(Color.WHITE) // прозрачность скана → белый фон (и под OCR, и под JPEG)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                chars += renderPage(doc, font, bmp, ptW, ptH, tess)
                                pages++
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                } else {
                    val src = decode(input, MAX_SIDE) ?: error("не смог декодировать картинку: ${input.name}")
                    // JPEG не хранит альфу → сводим на непрозрачный белый фон (иначе прозрачность → чёрный).
                    val bmp = flattenOnWhite(src)
                    if (bmp !== src) src.recycle()
                    try {
                        val ptW = bmp.width * 72f / IMAGE_DPI
                        val ptH = bmp.height * 72f / IMAGE_DPI
                        chars += renderPage(doc, font, bmp, ptW, ptH, tess)
                        pages++
                    } finally {
                        bmp.recycle()
                    }
                }

                if (pages == 0) error("нечего распознавать")
                out.parentFile?.mkdirs()
                doc.save(out)
            } catch (t: Throwable) {
                return@withLock ToolResult.Failure("pdf_ocr: ${t.message?.take(200)}")
            } finally {
                runCatching { doc.close() }
                runCatching { renderer?.close() }
                runCatching { pfd?.close() }
                runCatching { tess.recycle() }
            }

            // lang сообщаем ФАКТИЧЕСКИЙ (usedLang): при откате на rus+eng не врём про запрошенный.
            ToolResult.Success(
                """{"path":"${escapeJson(out.name)}","pages":$pages,"chars":$chars,"lang":"${escapeJson(usedLang)}"}""",
            )
        }
    }

    /**
     * Одна страница поискового PDF: изображение во всю страницу + невидимый текстовый слой по боксам
     * слов OCR. Возвращает число символов размещённого текста. Боксы Tesseract — в пикселях [bmp]
     * (начало координат сверху-слева); PDF — снизу-слева, поэтому y инвертируется.
     */
    private fun renderPage(
        doc: PDDocument,
        font: PDType0Font,
        bmp: Bitmap,
        ptW: Float,
        ptH: Float,
        tess: TessBaseAPI,
    ): Long {
        val page = PDPage(PDRectangle(ptW, ptH))
        doc.addPage(page)

        // OCR: сначала прогоняем распознавание, затем идём по словам через ResultIterator.
        tess.setImage(bmp)
        tess.getUTF8Text() // триггерит распознавание (иначе итератор пуст)
        val words = ArrayList<WordBox>()
        val ri = tess.resultIterator
        if (ri != null) {
            try {
                ri.begin()
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                do {
                    val w = ri.getUTF8Text(level)?.trim().orEmpty()
                    if (w.isNotEmpty() && ri.confidence(level) >= MIN_CONF) {
                        words.add(WordBox(w, ri.getBoundingRect(level)))
                    }
                } while (ri.next(level))
            } finally {
                ri.delete()
            }
        }
        tess.clear()

        val scaleX = ptW / bmp.width
        val scaleY = ptH / bmp.height
        var placed = 0L

        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(JPEGFactory.createFromImage(doc, bmp, JPEG_QUALITY), 0f, 0f, ptW, ptH)
            if (words.isNotEmpty()) {
                cs.beginText()
                cs.setRenderingMode(RenderingMode.NEITHER) // mode 3 — невидимый, но выделяемый/ищущийся
                for (wb in words) {
                    val text = encodable(font, wb.text)
                    if (text.isEmpty()) continue
                    val box: Rect = wb.box
                    val fontSize = ((box.bottom - box.top) * scaleY).coerceIn(MIN_FONT, MAX_FONT)
                    val boxW = (box.right - box.left) * scaleX
                    // Растягиваем строку по ширине бокса → область выделения совпадает со словом на скане.
                    val natural = runCatching { font.getStringWidth(text) / 1000f * fontSize }.getOrDefault(0f)
                    val hScale = if (natural > 0f) (boxW / natural).coerceIn(0.05f, 10f) else 1f
                    val x = box.left * scaleX
                    // Базовая линия чуть выше низа бокса (учёт нижних выносных элементов).
                    val y = ptH - box.bottom * scaleY + fontSize * 0.15f
                    runCatching {
                        cs.setFont(font, fontSize)
                        cs.setTextMatrix(Matrix(hScale, 0f, 0f, 1f, x, y))
                        cs.showText(text)
                        placed += text.length
                    }
                }
                cs.endText()
            }
        }
        return placed
    }

    private data class WordBox(val text: String, val box: Rect)

    /** Оставить только символы, которые есть в шрифте (иначе showText/getStringWidth бросят исключение). */
    private fun encodable(font: PDType0Font, s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (runCatching { font.hasGlyph(cp) }.getOrDefault(false)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Непрозрачная копия на белом фоне (если у картинки есть альфа). Иначе — исходный bitmap. */
    private fun flattenOnWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return out
    }

    /** Декод картинки с ограничением стороны (как OcrEngine.decode) — защита от гигантских фото. */
    private fun decode(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    private companion object {
        const val DEFAULT_LANG = "rus+eng"
        const val FONT_ASSET = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
        const val MAX_PAGES = 50        // OCR тяжёлый — потолок страниц на документ
        const val RENDER_DPI = 300      // рендер PDF-страницы под OCR (как OcrEngine)
        const val IMAGE_DPI = 150f      // допущение DPI для картинок → физический размер страницы
        const val MAX_SIDE = 4000       // ограничение стороны bitmap (память)
        const val MIN_CONF = 30f        // порог уверенности слова (0..100) — отсев мусора
        const val MIN_FONT = 1f
        const val MAX_FONT = 300f
        const val JPEG_QUALITY = 0.8f
    }
}

/** Фабрика инструмента pdf_ocr (интеграция — централизованно в реестре). */
fun pdfOcrTool(context: Context, resolve: (String) -> File?): AgentTool = PdfOcrTool(context, resolve)
