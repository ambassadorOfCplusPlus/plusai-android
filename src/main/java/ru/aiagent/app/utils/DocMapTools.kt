package ru.aiagent.app.utils

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.code.FfmpegNative
import ru.aiagent.app.code.RuntimePackManager
import ru.aiagent.app.code.watchdog
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Инструменты работы с документами и картами (§3.7, §3.11): манипуляции с PDF, маршрут по картам,
 * онлайн-перевод и нормализация громкости аудио.
 *
 * PDF (pdf_merge/pdf_split/pdf_delete_pages).
 * Реализованы на PDFBox-android (com.tom-roush:pdfbox-android, подключён в app/build.gradle.kts).
 * PDFBox работает со страницами как с объектами PDF-модели: текстовый и векторный слой сохраняются
 * БЕЗ ПОТЕРЬ (никакой растеризации в картинку). Страницы переносятся deep-copy через importPage,
 * поэтому исходный документ можно спокойно закрыть. Все PDDocument закрываются через .use.
 * PDFBoxResourceLoader.init(context) вызывается один раз (идемпотентно) перед работой — грузит
 * шрифты/ресурсы из assets.
 */

// Потолок страниц на один PDF (защита от PDF-бомбы). Как в :tools-docs.
private const val MAX_PDF_PAGES = 1000

/**
 * Ленивая идемпотентная инициализация PDFBox-android. init грузит ресурсы (шрифты) из assets и должна
 * быть вызвана до первой работы с PDDocument; повторные вызовы безвредны, но флаг экономит их.
 */
internal object PdfBoxInit {
    @Volatile private var done = false
    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            PDFBoxResourceLoader.init(context.applicationContext)
            done = true
        }
    }
}

// User-Agent обязателен для Nominatim и желателен для остальных публичных эндпоинтов.
private const val UA = "PlusAI-Agent/1.0 (Android; +https://aiagent.ru)"

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/**
 * GET с User-Agent, таймаутами и обязательным disconnect. Возвращает тело или бросает исключение
 * (HTTP != 2xx → IOException). Потоки закрываются через .use.
 */
private fun httpGet(rawUrl: String, readTimeoutMs: Int = 20_000): String {
    val c = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", UA)
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = c.responseCode
        val stream = if (code in 200..399) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return body
    } finally {
        c.disconnect()
    }
}

/**
 * pdf_merge — склеить несколько PDF в один (по порядку страниц каждого входа). Страницы переносятся
 * через importPage (deep-copy, без потери текста/векторов). IMPORTANT (создаёт файл).
 */
class PdfMergeTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "pdf_merge"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """склеить несколько PDF в один; args: {"inputs":["a.pdf","b.pdf"],"out":"merged.pdf"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val inArr = o.optJSONArray("inputs")
        val inputs = (0 until (inArr?.length() ?: 0)).mapNotNull { inArr?.optString(it) }.filter { it.isNotBlank() }
        if (inputs.size < 2) return@withContext ToolResult.Failure("нужно минимум 2 файла в args.inputs")
        val out = resolve(o.optString("out").ifBlank { "merged.pdf" }) ?: return@withContext ToolResult.Failure("путь вне папки")

        PdfBoxInit.ensure(context)
        var pages = 0
        val result = runCatching {
            PDDocument().use { merged ->
                for (name in inputs) {
                    val f = resolve(name) ?: error("путь вне папки: $name")
                    if (!f.exists() || !f.isFile) error("нет файла: $name")
                    PDDocument.load(f).use { src ->
                        for (page in src.pages) {
                            if (pages >= MAX_PDF_PAGES) error("слишком много страниц (> $MAX_PDF_PAGES)")
                            merged.importPage(page) // deep-copy: исходник можно закрыть
                            pages++
                        }
                    }
                }
                if (pages == 0) error("во входных PDF нет страниц")
                out.parentFile?.mkdirs()
                merged.save(out)
            }
        }
        result.onFailure { return@withContext ToolResult.Failure("pdf_merge: ${it.message}") }
        ToolResult.Success("""{"path":"${escapeJson(out.name)}","pages":$pages,"inputs":${inputs.size}}""")
    }
}

/**
 * pdf_split — разбить PDF на отдельные файлы по одной странице каждый: <out_prefix>_1.pdf, _2.pdf, …
 * Каждая страница переносится importPage (без растеризации). IMPORTANT (создаёт файлы).
 */
class PdfSplitTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "pdf_split"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """разбить PDF постранично на отдельные файлы; args: {"input":"doc.pdf","out_prefix":"page"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val input = resolve(o.optString("input")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!input.exists() || !input.isFile) return@withContext ToolResult.Failure("нет файла: ${input.name}")
        val prefix = o.optString("out_prefix").ifBlank { input.nameWithoutExtension }

        PdfBoxInit.ensure(context)
        val outNames = ArrayList<String>()
        val result = runCatching {
            PDDocument.load(input).use { src ->
                val n = src.numberOfPages
                if (n == 0) error("во входном PDF нет страниц")
                // Не режем молча: как pdf_merge/pdf_delete_pages — честный отказ при превышении лимита.
                if (n > MAX_PDF_PAGES) error("документ слишком большой ($n страниц, лимит $MAX_PDF_PAGES)")
                for (i in 0 until n) {
                    PDDocument().use { one ->
                        one.importPage(src.getPage(i))
                        val outF = resolve("${prefix}_${i + 1}.pdf") ?: error("путь вне папки")
                        outF.parentFile?.mkdirs()
                        one.save(outF)
                        outNames.add(outF.name)
                    }
                }
            }
        }
        result.onFailure { return@withContext ToolResult.Failure("pdf_split: ${it.message}") }
        val filesJson = outNames.joinToString(",") { "\"${escapeJson(it)}\"" }
        ToolResult.Success("""{"count":${outNames.size},"files":[$filesJson]}""")
    }
}

/** Разобрать спецификацию страниц вида "2,4-6" в множество 1-based номеров. Битые куски игнорируются. */
private fun parsePageSpec(spec: String): Set<Int> {
    val set = LinkedHashSet<Int>()
    for (part in spec.split(",")) {
        val p = part.trim()
        if (p.isEmpty()) continue
        val dash = p.indexOf('-')
        if (dash > 0) {
            val lo = p.substring(0, dash).trim().toIntOrNull()
            val hi = p.substring(dash + 1).trim().toIntOrNull()
            if (lo != null && hi != null && lo <= hi) for (x in lo..hi) if (x >= 1) set.add(x)
        } else {
            p.toIntOrNull()?.takeIf { it >= 1 }?.let { set.add(it) }
        }
    }
    return set
}

/**
 * pdf_delete_pages — удалить страницы (спецификация "2,4-6", нумерация с 1) из PDF, сохранив остальные.
 * IMPORTANT (создаёт файл).
 */
class PdfDeletePagesTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "pdf_delete_pages"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """удалить страницы из PDF; args: {"input":"doc.pdf","pages":"2,4-6","out":"cleaned.pdf"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val input = resolve(o.optString("input")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!input.exists() || !input.isFile) return@withContext ToolResult.Failure("нет файла: ${input.name}")
        val del = parsePageSpec(o.optString("pages"))
        if (del.isEmpty()) return@withContext ToolResult.Failure("нужен args.pages, напр. \"2,4-6\"")
        val out = resolve(o.optString("out").ifBlank { "${input.nameWithoutExtension}_cleaned.pdf" })
            ?: return@withContext ToolResult.Failure("путь вне папки")

        PdfBoxInit.ensure(context)
        var remaining = 0
        val result = runCatching {
            PDDocument.load(input).use { doc ->
                if (doc.numberOfPages > MAX_PDF_PAGES) error("слишком большой PDF (> $MAX_PDF_PAGES страниц)")
                // del — 1-based; переводим в 0-based индексы и удаляем С КОНЦА, чтобы индексы не сползали.
                val idxs = del.map { it - 1 }.filter { it in 0 until doc.numberOfPages }.sortedDescending()
                for (idx in idxs) doc.removePage(idx)
                remaining = doc.numberOfPages
                if (remaining == 0) error("после удаления не осталось страниц")
                out.parentFile?.mkdirs()
                doc.save(out)
            }
        }
        result.onFailure { return@withContext ToolResult.Failure("pdf_delete_pages: ${it.message}") }
        ToolResult.Success("""{"path":"${escapeJson(out.name)}","pages":$remaining,"deleted":${del.size}}""")
    }
}

/**
 * maps_route — маршрут и расстояние между двумя точками. БЕЗ ключей: геокодинг адресов через
 * Nominatim (OpenStreetMap), маршрут — через публичный OSRM (профиль driving). Точки можно задавать
 * адресом или прямыми координатами "lat,lon". SAFE (только чтение открытых карт-данных).
 */
class MapsRouteTool : AgentTool {
    override val id = "maps_route"
    override val danger = DangerLevel.SAFE
    override val schema =
        """маршрут и расстояние между точками (авто, OSRM+Nominatim, без ключей); """ +
            """args: {"from":"адрес или lat,lon","to":"адрес или lat,lon"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val fromQ = o.optString("from").ifBlank { return@withContext ToolResult.Failure("нужен args.from") }
        val toQ = o.optString("to").ifBlank { return@withContext ToolResult.Failure("нужен args.to") }

        val a = geocode(fromQ) ?: return@withContext ToolResult.Failure("не нашёл точку: $fromQ")
        val b = geocode(toQ) ?: return@withContext ToolResult.Failure("не нашёл точку: $toQ")

        // OSRM ждёт координаты в порядке lon,lat.
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${a.second},${a.first};${b.second},${b.first}?overview=false"
        val body = runCatching { httpGet(url) }
            .getOrElse { return@withContext ToolResult.Failure("маршрут недоступен: ${it.message?.take(100)}") }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return@withContext ToolResult.Failure("не разобрал ответ маршрутизатора")
        if (root.optString("code") != "Ok")
            return@withContext ToolResult.Failure("маршрут не построен (OSRM: ${root.optString("code", "?")})")
        val route = root.optJSONArray("routes")?.optJSONObject(0)
            ?: return@withContext ToolResult.Failure("маршрут пуст")
        val distKm = route.optDouble("distance", 0.0) / 1000.0
        val durMin = Math.round(route.optDouble("duration", 0.0) / 60.0)

        ToolResult.Success(
            JSONObject()
                .put("from", JSONObject().put("lat", a.first).put("lon", a.second))
                .put("to", JSONObject().put("lat", b.first).put("lon", b.second))
                .put("distance_km", String.format(Locale.US, "%.2f", distKm).toDouble())
                .put("duration_min", durMin)
                .put("duration_text", durText(durMin))
                .toString(),
        )
    }

    /** Адрес → (lat, lon). Прямые координаты "lat,lon" распознаются без сети. null, если не найдено. */
    private fun geocode(q: String): Pair<Double, Double>? {
        Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""").find(q)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        val url = "https://nominatim.openstreetmap.org/search?q=${enc(q)}&format=json&limit=1"
        val body = runCatching { httpGet(url) }.getOrNull() ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val hit = arr.optJSONObject(0) ?: return null
        val lat = hit.optString("lat").toDoubleOrNull() ?: return null
        val lon = hit.optString("lon").toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun durText(totalMin: Long): String {
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h ч $m мин" else "$m мин"
    }
}

/**
 * translate_online — качественный ОНЛАЙН-перевод текста. БЕЗ ключей: неофициальный, но рабочий
 * публичный эндпоинт Google Translate (translate_a/single). from:auto — язык определяется сервером.
 * Fallback: если эндпоинт недоступен/не разобран — честный отказ с советом использовать офлайн-
 * инструмент translate (ML Kit на устройстве). SAFE.
 */
class TranslateOnlineTool : AgentTool {
    override val id = "translate_online"
    override val danger = DangerLevel.SAFE
    override val schema =
        """онлайн-перевод текста (качественный, без ключей); args: {"text":"...","to":"en","from":"auto"} — """ +
            """from по умолчанию auto; коды языков ISO (ru, en, de, …)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val text = o.optString("text").ifBlank { return@withContext ToolResult.Failure("нужен args.text") }
        val to = o.optString("to").ifBlank { return@withContext ToolResult.Failure("нужен args.to (код языка, напр. en)") }
        val from = o.optString("from").ifBlank { "auto" }

        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=${enc(from)}&tl=${enc(to)}&dt=t&q=${enc(text)}"
        val body = runCatching { httpGet(url) }.getOrElse {
            return@withContext ToolResult.Failure(
                "онлайн-перевод недоступен (${it.message?.take(80)}) — попробуй офлайн-инструмент translate",
            )
        }
        // Ответ: [[[ "перевод", "оригинал", ... ], ...], null, "<исходный язык>", ...]
        val root = runCatching { JSONArray(body) }.getOrElse {
            return@withContext ToolResult.Failure("не разобрал ответ перевода — попробуй офлайн translate")
        }
        val sentences = root.optJSONArray(0)
        if (sentences == null || sentences.length() == 0)
            return@withContext ToolResult.Failure("пустой перевод — попробуй офлайн translate")
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
            sentences.optJSONArray(i)?.optString(0)?.let { sb.append(it) }
        }
        val translated = sb.toString()
        if (translated.isBlank())
            return@withContext ToolResult.Failure("пустой перевод — попробуй офлайн translate")
        val detected = root.optString(2, from).ifBlank { from }
        ToolResult.Success(
            JSONObject().put("from", detected).put("to", to).put("text", translated).toString(),
        )
    }
}

/**
 * audio_normalize — нормализация громкости аудио через фильтр loudnorm нативного FFmpeg из
 * СКАЧИВАЕМОГО пака `ffmpeg` (не бандл в APK). Пак при первом использовании докачивается автоматически
 * (как transcribe_audio/whisper) со сверкой SHA-256; если источника пака нет — честный отказ.
 * Формат вывода — по расширению out. IMPORTANT (создаёт файл).
 */
class AudioNormalizeTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "audio_normalize"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """нормализовать громкость аудио (FFmpeg loudnorm); args: {"input":"in.mp3","out":"norm.mp3"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val inF = resolve(o.optString("input")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!inF.exists() || !inF.isFile) return@withContext ToolResult.Failure("нет файла: ${inF.name}")
        val out = resolve(
            o.optString("out").ifBlank { "${inF.nameWithoutExtension}_norm.${inF.extension.ifBlank { "mp3" }}" },
        ) ?: return@withContext ToolResult.Failure("путь вне папки")

        // Пак FFmpeg: авто-докачка при первом использовании (как whisper в transcribe_audio).
        if (!RuntimePackManager.isInstalled(context, "ffmpeg")) {
            val pack = RuntimePackManager.known.firstOrNull { it.id == "ffmpeg" }
                ?: return@withContext ToolResult.Failure("нужен пак FFmpeg — Настройки → модули кода")
            RuntimePackManager.install(context, pack).getOrElse {
                return@withContext ToolResult.Failure("не удалось скачать пак FFmpeg: ${it.message?.take(120)}")
            }
        }
        if (!FfmpegNative.ensureLoaded(context)) {
            return@withContext ToolResult.Failure("нужен пак FFmpeg — Настройки → модули кода")
        }
        out.parentFile?.mkdirs()

        // Как RunFfmpegTool: гоняем на watchdog-потоке с таймаутом (перекодирование может зависнуть).
        // Формат вывода ffmpeg определяет по расширению out (не передаём -f, чтобы не путать m4a/ipod).
        watchdog(120_000, "loudnorm") {
            val rc = FfmpegNative.run(
                arrayOf("-y", "-i", inF.absolutePath, "-af", "loudnorm", out.absolutePath),
            )
            rc to FfmpegNative.lastOutput()
        }.fold(
            onSuccess = { (rc, log) ->
                if (rc != 0 || !out.exists() || out.length() == 0L) {
                    ToolResult.Failure("ffmpeg не смог нормализовать (код $rc): ${log.take(300)}")
                } else {
                    ToolResult.Success(
                        """{"path":"${escapeJson(out.name)}","bytes":${out.length()},""" +
                            """"output":"${escapeJson(log.take(2000))}"}""",
                    )
                }
            },
            onFailure = { ToolResult.Failure("audio_normalize: ${it.message?.take(200)}") },
        )
    }
}

/**
 * Инструменты работы с документами и картами: манипуляции с PDF (merge/split/delete),
 * маршрут по картам, онлайн-перевод и нормализация громкости аудио.
 */
fun docMapTools(context: Context, resolve: (String) -> File?): List<AgentTool> = listOf(
    PdfMergeTool(context, resolve),
    PdfSplitTool(context, resolve),
    PdfDeletePagesTool(context, resolve),
    MapsRouteTool(),
    TranslateOnlineTool(),
    AudioNormalizeTool(context, resolve),
)
