package ru.aiagent.app.utils

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import com.caverock.androidsvg.SVG
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun savep(bmp: Bitmap, file: File) {
    file.parentFile?.mkdirs()
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** clipboard — прочитать/записать системный буфер обмена. */
class ClipboardTool(private val context: Context) : AgentTool {
    override val id = "clipboard"
    override val danger = DangerLevel.SAFE
    override val schema = """буфер обмена; args: {"action":"read|write","text":"для write"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.Main) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (o.optString("action", "read").lowercase()) {
            "write" -> {
                val text = o.optString("text")
                cm.setPrimaryClip(ClipData.newPlainText("plusai", text))
                ToolResult.Success("""{"written":true,"chars":${text.length}}""")
            }
            else -> {
                // Android 10+ (API 29): читать системный буфер обмена может ТОЛЬКО приложение в фокусе
                // (или текущий IME). В фоне getPrimaryClip вернёт null или устаревшее/чужое значение —
                // поэтому честно объясняем ограничение, а не отдаём не то, что записали.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isAppInForeground()) {
                    return@withContext ToolResult.Failure(
                        "Android 10+ не даёт читать буфер обмена, когда приложение не на переднем плане " +
                            "(доступ только у приложения в фокусе). Открой приложение поверх и повтори — " +
                            "или вставь нужный текст в чат вручную.",
                    )
                }
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                ToolResult.Success("""{"text":"${escapeJson(text)}"}""")
            }
        }
    }

    /** Есть ли у нашего процесса видимая/сфокусированная Activity (проксирует «приложение в фокусе»). */
    private fun isAppInForeground(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val myPid = android.os.Process.myPid()
        return runCatching { am.runningAppProcesses }.getOrNull()?.any {
            it.pid == myPid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
    }
}

/** zip_files — упаковать файлы рабочей папки в zip-архив. */
class ZipFilesTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "zip_files"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema = """упаковать файлы в zip; args: {"paths":["a.txt","dir"],"out":"archive.zip"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val out = resolve(o.optString("out").ifBlank { "archive.zip" }) ?: return@withContext ToolResult.Failure("путь вне папки")
        val paths = (0 until (o.optJSONArray("paths")?.length() ?: 0)).mapNotNull { o.optJSONArray("paths")?.optString(it) }
        if (paths.isEmpty()) return@withContext ToolResult.Failure("нужен args.paths")
        var n = 0
        runCatching {
            ZipOutputStream(out.outputStream()).use { zos ->
                for (p in paths) {
                    val f = resolve(p) ?: continue
                    if (!f.exists()) continue
                    f.walkTopDown().filter { it.isFile }.forEach { file ->
                        zos.putNextEntry(ZipEntry(file.relativeTo(f.parentFile ?: f).path))
                        file.inputStream().use { it.copyTo(zos) }; zos.closeEntry(); n++
                    }
                }
            }
        }.onFailure { return@withContext ToolResult.Failure("zip: ${it.message}") }
        ToolResult.Success("""{"created":"${escapeJson(out.name)}","files":$n}""")
    }
}

private const val MAX_UNZIP_TOTAL_BYTES = 200L * 1024 * 1024 // потолок распакованного объёма (анти-zip-бомба)
private const val MAX_UNZIP_ENTRIES = 10_000                 // потолок числа файлов в архиве

/** unzip_file — распаковать zip в папку (с защитой от zip-slip и zip-бомбы). */
class UnzipFileTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "unzip_file"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema = """распаковать zip; args: {"path":"archive.zip","dest":"папка"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val zip = resolve(o.optString("path")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!zip.exists()) return@withContext ToolResult.Failure("нет файла: ${zip.name}")
        val dest = resolve(o.optString("dest").ifBlank { zip.nameWithoutExtension }) ?: return@withContext ToolResult.Failure("dest вне папки")
        dest.mkdirs()
        val destCanon = dest.canonicalPath
        var n = 0
        var entries = 0 // считаем ВСЕ записи (в т.ч. директории) — иначе zip из миллионов папок обходил лимит
        var totalBytes = 0L
        runCatching {
            ZipInputStream(zip.inputStream()).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    if (++entries > MAX_UNZIP_ENTRIES) error("слишком много записей в архиве (> $MAX_UNZIP_ENTRIES)")
                    val target = File(dest, e.name)
                    // zip-slip: запись не должна вылезти за пределы dest.
                    if (target.canonicalPath.startsWith(destCanon + File.separator)) {
                        if (e.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            // Лимит РАСПАКОВАННОГО объёма (счётчик по мере записи): иначе zip-бомба на
                            // десятки КБ забивает весь диск (в auto/bypass — без подтверждения). copyTo не
                            // знает про распаковку, поэтому копируем вручную с подсчётом.
                            target.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val r = zis.read(buf); if (r < 0) break
                                    totalBytes += r
                                    if (totalBytes > MAX_UNZIP_TOTAL_BYTES) error("распаковка превышает лимит ${MAX_UNZIP_TOTAL_BYTES / (1024 * 1024)} МБ (zip-бомба?)")
                                    out.write(buf, 0, r)
                                }
                            }
                            n++
                        }
                    }
                    zis.closeEntry(); e = zis.nextEntry
                }
            }
        }.onFailure { return@withContext ToolResult.Failure("unzip: ${it.message}") }
        ToolResult.Success("""{"extracted_to":"${escapeJson(dest.name)}","files":$n}""")
    }
}

/** generate_qr — QR-код по тексту, PNG рисуется прямо в ответе. */
class GenerateQrTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "generate_qr"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """сгенерировать QR-код (картинка в ответ); args: {"text":"https://…","path":"qr.png"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.Default) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val text = o.optString("text").ifBlank { return@withContext ToolResult.Failure("нужен args.text") }
        val file = resolve(o.optString("path").ifBlank { "qr.png" }) ?: return@withContext ToolResult.Failure("путь вне папки")
        runCatching {
            val size = 640
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            savep(bmp, file)
        }.onFailure { return@withContext ToolResult.Failure("qr: ${it.message}") }
        ToolResult.Success("""{"images":["${escapeJson(file.absolutePath)}"],"path":"${escapeJson(file.name)}"}""")
    }
}

/** scan_qr — распознать QR/штрихкод с изображения. */
class ScanQrTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "scan_qr"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """распознать QR/штрихкод с картинки; args: {"path":"фото.jpg"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.Default) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val file = resolve(o.optString("path")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!file.exists()) return@withContext ToolResult.Failure("нет файла")
        // Даунсемплим при декоде: полноразмерное фото 4000×3000 → битмап+IntArray по ~48 МБ = OOM.
        // Для чтения QR/штрихкода хватает ≤1600px по стороне.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@withContext ToolResult.Failure("не изображение")
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext ToolResult.Failure("не изображение")
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val src = RGBLuminanceSource(bmp.width, bmp.height, pixels)
        val res = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(src))) }.getOrNull()
            ?: return@withContext ToolResult.Failure("код не найден на изображении")
        ToolResult.Success("""{"text":"${escapeJson(res.text)}","format":"${res.barcodeFormat}"}""")
    }
}

/** create_chart — простая диаграмма (bar/line/pie) по данным; PNG рисуется в ответ. */
class CreateChartTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "create_chart"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """диаграмма в ответ (bar|line|pie); args: {"type":"bar","title":"Продажи",""" +
            """"labels":["Янв","Фев"],"values":[10,25],"path":"chart.png"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.Default) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val values = (0 until (o.optJSONArray("values")?.length() ?: 0)).map { o.optJSONArray("values")!!.optDouble(it) }
        if (values.isEmpty()) return@withContext ToolResult.Failure("нужен args.values")
        val labels = (0 until (o.optJSONArray("labels")?.length() ?: 0)).map { o.optJSONArray("labels")!!.optString(it) }
        val type = o.optString("type", "bar").lowercase()
        val title = o.optString("title")
        val file = resolve(o.optString("path").ifBlank { "chart.png" }) ?: return@withContext ToolResult.Failure("путь вне папки")
        runCatching { savep(drawChart(type, title, labels, values), file) }
            .onFailure { return@withContext ToolResult.Failure("chart: ${it.message}") }
        ToolResult.Success("""{"images":["${escapeJson(file.absolutePath)}"],"path":"${escapeJson(file.name)}"}""")
    }

    private val palette = intArrayOf(0xFFD97757.toInt(), 0xFF6FBF87.toInt(), 0xFF5B8DEF.toInt(),
        0xFFE0A860.toInt(), 0xFF9B6FBF.toInt(), 0xFF4FB0C6.toInt())

    private fun drawChart(type: String, title: String, labels: List<String>, values: List<Double>): Bitmap {
        val w = 900; val h = 560; val pad = 60f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(0xFF1B1A18.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFECEAE4.toInt(); p.textSize = 30f
        if (title.isNotBlank()) cv.drawText(title, pad, 44f, p)
        val top = if (title.isNotBlank()) 70f else 30f
        val maxV = (values.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
        when (type) {
            "pie" -> {
                val total = values.sum().coerceAtLeast(1e-9)
                val rect = RectF(w / 2f - 180, top + 20, w / 2f + 180, top + 380)
                var start = -90f
                values.forEachIndexed { i, v ->
                    p.color = palette[i % palette.size]
                    val sweep = (v / total * 360).toFloat()
                    cv.drawArc(rect, start, sweep, true, p); start += sweep
                }
                p.color = 0xFFECEAE4.toInt(); p.textSize = 26f
                labels.forEachIndexed { i, l ->
                    p.color = palette[i % palette.size]; cv.drawRect(pad, top + 420 + i * 34f, pad + 24, top + 444 + i * 34f, p)
                    p.color = 0xFFECEAE4.toInt(); cv.drawText("$l — ${fmt(values.getOrElse(i){0.0})}", pad + 34, top + 442 + i * 34f, p)
                }
            }
            "line" -> {
                val n = values.size; val gx = (w - 2 * pad) / (n - 1).coerceAtLeast(1)
                val gy = (h - top - 80); val base = h - 60f
                p.strokeWidth = 4f; p.color = palette[0]
                for (i in 0 until n - 1) {
                    val x1 = pad + i * gx; val y1 = base - (values[i] / maxV * gy).toFloat()
                    val x2 = pad + (i + 1) * gx; val y2 = base - (values[i + 1] / maxV * gy).toFloat()
                    cv.drawLine(x1, y1, x2, y2, p)
                }
                drawLabels(cv, p, labels, pad, gx, base)
            }
            else -> { // bar
                val n = values.size; val bw = (w - 2 * pad) / n * 0.6f; val step = (w - 2 * pad) / n
                val base = h - 60f; val gy = (h - top - 80)
                values.forEachIndexed { i, v ->
                    p.color = palette[i % palette.size]
                    val x = pad + i * step + (step - bw) / 2
                    val bh = (v / maxV * gy).toFloat()
                    cv.drawRect(x, base - bh, x + bw, base, p)
                }
                drawLabels(cv, p, labels, pad + 6f, step, base) // под колонками (левый край области бара)
            }
        }
        return bmp
    }

    private fun drawLabels(cv: Canvas, p: Paint, labels: List<String>, x0: Float, step: Float, base: Float) {
        p.color = 0xFF9C968C.toInt(); p.textSize = 24f
        labels.forEachIndexed { i, l -> cv.drawText(l, x0 + i * step, base + 34, p) }
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

/** render_svg — векторный рисунок из SVG-текста, растеризуется в PNG и рисуется в ответ. */
class RenderSvgTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "render_svg"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """векторный рисунок из SVG в ответ; args: {"svg":"<svg …>…</svg>","path":"draw.png","width":600}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.Default) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val svgText = o.optString("svg").ifBlank { return@withContext ToolResult.Failure("нужен args.svg") }
        val file = resolve(o.optString("path").ifBlank { "drawing.png" }) ?: return@withContext ToolResult.Failure("путь вне папки")
        val width = o.optInt("width", 640).coerceIn(64, 2048)
        runCatching {
            val svg = SVG.getFromString(svgText)
            val docAr = svg.documentAspectRatio.takeIf { it > 0f } ?: 1f
            val height = (width / docAr).toInt().coerceIn(64, 2048)
            svg.setDocumentWidth(width.toFloat()); svg.setDocumentHeight(height.toFloat())
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bmp))
            savep(bmp, file)
        }.onFailure { return@withContext ToolResult.Failure("svg: ${it.message?.take(160)}") }
        ToolResult.Success("""{"images":["${escapeJson(file.absolutePath)}"],"path":"${escapeJson(file.name)}"}""")
    }
}

/**
 * Декод картинки с даунсемплом (до [maxSide] по большей стороне) — защита от OOM на
 * полноразмерных фото. Возвращает null, если файл не изображение.
 */
private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Разбить строку на строки по ширине [maxWidth] (перенос по словам, длинные — по символам). */
private fun wrapLine(paint: Paint, text: String, maxWidth: Float): List<String> {
    if (text.isEmpty()) return listOf("")
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var count = paint.breakText(text, start, text.length, true, maxWidth, null)
        if (count <= 0) count = 1
        var end = start + count
        // По возможности переносим по последнему пробелу внутри влезающего куска (не рвём слово).
        if (end < text.length) {
            val lastSpace = text.lastIndexOf(' ', end - 1)
            if (lastSpace > start) end = lastSpace + 1
        }
        out.add(text.substring(start, end).trimEnd())
        start = end
    }
    return out
}

/**
 * create_pdf — собрать PDF из текста (построчно, с переносом и разбивкой на страницы А4)
 * и/или картинок песочницы (каждая — на отдельной странице, вписана по размеру).
 * Через нативный android.graphics.pdf.PdfDocument: без внешних зависимостей и, в отличие
 * от стандартных шрифтов PDFBox (WinAnsi), корректно рисует кириллицу системным шрифтом.
 * IMPORTANT (создаёт файл).
 */
class CreatePdfTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "create_pdf"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """создать PDF из текста и/или картинок; args: {"text":"…","images":["a.png","b.png"],"out":"doc.pdf"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val out = resolve(o.optString("out").ifBlank { "doc.pdf" }) ?: return@withContext ToolResult.Failure("путь вне папки")
        val text = o.optString("text")
        val images = (0 until (o.optJSONArray("images")?.length() ?: 0)).mapNotNull { o.optJSONArray("images")?.optString(it) }
        if (text.isBlank() && images.isEmpty()) return@withContext ToolResult.Failure("нужен args.text или args.images")

        val pageW = 595; val pageH = 842; val margin = 40 // A4 @72dpi, поля 40pt
        val contentW = (pageW - 2 * margin).toFloat()
        val doc = PdfDocument()
        var pages = 0
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }

        val result = runCatching {
            if (text.isNotBlank()) {
                val lineH = paint.fontSpacing
                val top = margin + paint.textSize
                val bottom = pageH - margin
                var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, ++pages).create())
                var y = top
                for (para in text.split("\n")) {
                    for (line in wrapLine(paint, para, contentW)) {
                        if (y > bottom) {
                            doc.finishPage(page)
                            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, ++pages).create())
                            y = top
                        }
                        page.canvas.drawText(line, margin.toFloat(), y, paint)
                        y += lineH
                    }
                }
                doc.finishPage(page)
            }
            val avW = (pageW - 2 * margin).toFloat(); val avH = (pageH - 2 * margin).toFloat()
            for (p in images) {
                val f = resolve(p) ?: continue
                if (!f.exists()) continue
                val bmp = decodeSampled(f, 2000) ?: continue
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, ++pages).create())
                val sc = minOf(avW / bmp.width, avH / bmp.height) // вписать целиком, сохраняя пропорции
                val dw = bmp.width * sc; val dh = bmp.height * sc
                val left = margin + (avW - dw) / 2; val topI = margin + (avH - dh) / 2
                page.canvas.drawBitmap(bmp, null, RectF(left, topI, left + dw, topI + dh), null)
                doc.finishPage(page)
                bmp.recycle()
            }
            if (pages == 0) error("нечего писать (нет текста и не удалось прочитать картинки)")
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
        }
        doc.close()
        result.onFailure { return@withContext ToolResult.Failure("pdf: ${it.message}") }
        ToolResult.Success("""{"path":"${escapeJson(out.name)}","pages":$pages}""")
    }
}

/**
 * edit_image — редактировать картинку песочницы: crop|resize|rotate|convert.
 * Через android.graphics; формат вывода — из args.format или по расширению out (png|jpeg|webp).
 * IMPORTANT (создаёт/перезаписывает файл).
 */
class EditImageTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "edit_image"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """редактировать картинку (crop|resize|rotate|convert); args: {"path":"in.jpg","out":"out.png",""" +
            """"op":"crop","x":0,"y":0,"w":200,"h":150,"width":800,"height":600,"degrees":90,"format":"png|jpeg|webp","quality":90}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val inFile = resolve(o.optString("path")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!inFile.exists()) return@withContext ToolResult.Failure("нет файла: ${inFile.name}")
        val op = o.optString("op").lowercase().ifBlank { "convert" }

        // Полноразмерный декод: координаты crop заданы в пикселях исходника, даунсемпл сдвинул бы их.
        // runCatching ловит и OutOfMemoryError (Throwable) → отдаём Failure, а не падаем.
        val edited = runCatching {
            val src = BitmapFactory.decodeFile(inFile.absolutePath) ?: error("не изображение")
            when (op) {
                "crop" -> {
                    val x = o.optInt("x").coerceIn(0, src.width - 1)
                    val y = o.optInt("y").coerceIn(0, src.height - 1)
                    val w = o.optInt("w", src.width - x).coerceIn(1, src.width - x)
                    val h = o.optInt("h", src.height - y).coerceIn(1, src.height - y)
                    Bitmap.createBitmap(src, x, y, w, h)
                }
                "resize" -> {
                    var w = o.optInt("width", 0); var h = o.optInt("height", 0)
                    if (w <= 0 && h <= 0) error("resize: нужен width или height")
                    if (w <= 0) w = (src.width.toFloat() * h / src.height).toInt()
                    if (h <= 0) h = (src.height.toFloat() * w / src.width).toInt()
                    Bitmap.createScaledBitmap(src, w.coerceIn(1, 10000), h.coerceIn(1, 10000), true)
                }
                "rotate" -> {
                    val deg = o.optDouble("degrees", 90.0).toFloat()
                    Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)
                }
                "convert" -> src
                else -> error("неизвестная op: $op (crop|resize|rotate|convert)")
            }
        }.getOrElse { return@withContext ToolResult.Failure("edit_image: ${it.message}") }

        val fmtArg = o.optString("format").lowercase()
        val out = resolve(
            o.optString("out").ifBlank {
                val ext = if (fmtArg == "jpeg") "jpg" else fmtArg.ifBlank { "png" }
                "${inFile.nameWithoutExtension}_edited.$ext"
            },
        ) ?: return@withContext ToolResult.Failure("out вне папки")
        val fmt = when (fmtArg.ifBlank { out.extension.lowercase() }) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
        val quality = o.optInt("quality", 90).coerceIn(1, 100)
        runCatching {
            out.parentFile?.mkdirs()
            out.outputStream().use { edited.compress(fmt, quality, it) }
        }.onFailure { return@withContext ToolResult.Failure("edit_image: ${it.message}") }
        ToolResult.Success(
            """{"images":["${escapeJson(out.absolutePath)}"],"path":"${escapeJson(out.name)}",""" +
                """"width":${edited.width},"height":${edited.height}}""",
        )
    }
}

/** Медиа-инструменты работы с файлами: PDF из текста/картинок и редактор изображений. */
fun mediaTools(resolve: (String) -> File?): List<AgentTool> =
    listOf(CreatePdfTool(resolve), EditImageTool(resolve))
