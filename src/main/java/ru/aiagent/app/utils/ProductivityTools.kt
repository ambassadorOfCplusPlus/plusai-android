package ru.aiagent.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.regex.Pattern

/**
 * Инструменты продуктивности (§3.4): персистентные ЗАДАЧИ/TODO с дедлайнами, чтение произвольной
 * RSS/Atom-ленты по URL, генерация штрихкодов (не только QR), замена по регулярке и выгрузка
 * долговременной памяти ИИ в файл.
 *
 * ЗАДАЧИ и память хранятся ЛОКАЛЬНО (§7, filesDir), наружу не уходят. RSS — только чтение
 * публичной ленты по указанному URL. Все инструменты SAFE.
 */

// ═══════════════════════════════ ЗАДАЧИ / TODO ═══════════════════════════════

/**
 * Персистентный список дел с дедлайнами — свой лёгкий JSON-стор в filesDir/tasks.json.
 * В отличие от разовых cron-напоминаний, задачи живут, пока их не закроют/удалят.
 * Каждая задача: {id,title,due,priority,done,created}. Всё под lock (read-modify-write не атомарен).
 */
object TaskStore {
    private const val MAX_TASKS = 1000 // кап, чтобы файл не раздувался
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "tasks.json")

    /**
     * Прочитать список задач как JSONArray. Файла нет → пусто (это норма).
     * НО: если файл есть и НЕПУСТ, но прочитать/распарсить не удалось — бросаем, чтобы последующая
     * мутация НЕ затёрла существующие задачи пустым списком (потеря данных). Пустой файл → пусто.
     */
    private fun readLocked(ctx: Context): JSONArray {
        val f = file(ctx)
        if (!f.exists()) return JSONArray()
        val text = f.readText() // IOException пробрасываем: файл есть, но не читается — не молчим
        if (text.isBlank()) return JSONArray()
        return runCatching { JSONArray(text) }.getOrElse {
            throw java.io.IOException("файл задач повреждён (${f.name}) — мутация отменена, чтобы не потерять данные")
        }
    }

    // Атомарная запись tmp+rename: краш посреди writeText не оставит обрезанный JSON.
    // Ошибку записи (диск полон и т.п.) НЕ глотаем — пробрасываем, чтобы инструмент вернул Failure,
    // а не ложный успех при несохранённых данных.
    private fun writeLocked(ctx: Context, arr: JSONArray) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "tasks.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
    }

    /** Случайный короткий id (12 hex-символов). */
    private fun genId(): String {
        val rnd = java.security.SecureRandom()
        val b = ByteArray(6).also { rnd.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    fun add(ctx: Context, title: String, due: String, priority: String): JSONObject = synchronized(lock) {
        val arr = readLocked(ctx)
        val task = JSONObject()
            .put("id", genId())
            .put("title", title.trim())
            .put("due", due.trim())
            .put("priority", priority.trim())
            .put("done", false)
            .put("created", isoNow())
        arr.put(task)
        // Кап: держим только последние MAX_TASKS (старьё вытесняем с начала).
        val capped = if (arr.length() > MAX_TASKS) {
            JSONArray().apply { for (i in arr.length() - MAX_TASKS until arr.length()) put(arr.get(i)) }
        } else {
            arr
        }
        writeLocked(ctx, capped)
        task
    }

    fun list(ctx: Context, filter: String): JSONArray = synchronized(lock) {
        val arr = readLocked(ctx)
        val now = Instant.now()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val done = t.optBoolean("done", false)
            // Сравниваем как моменты времени, а не строки: иначе date-only «2026-07-17» ложно
            // считалось бы просроченным против «2026-07-17T14:00». Непарсящийся due → не просрочен.
            val overdue = !done && dueDeadline(t.optString("due"))?.let { now.isAfter(it) } == true
            val keep = when (filter) {
                "open" -> !done
                "done" -> done
                "overdue" -> overdue
                else -> true // all
            }
            if (keep) out.put(t)
        }
        out
    }

    /** Пометить задачу выполненной. true — нашли и обновили. */
    fun done(ctx: Context, id: String): Boolean = synchronized(lock) {
        val arr = readLocked(ctx)
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == id) {
                t.put("done", true)
                writeLocked(ctx, arr)
                return@synchronized true
            }
        }
        false
    }

    /** Удалить задачу. true — нашли и удалили. */
    fun delete(ctx: Context, id: String): Boolean = synchronized(lock) {
        val arr = readLocked(ctx)
        val kept = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == id) { removed = true; continue }
            kept.put(t)
        }
        if (removed) writeLocked(ctx, kept)
        removed
    }

    private fun isoNow(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    /**
     * Момент дедлайна как [Instant] (в системной таймзоне), или null если строку не распознали
     * (тогда задача не считается просроченной — лучше пропустить, чем упасть/наврать).
     *  - ISO со смещением/зоной (…+03:00 / …Z) → берётся как есть;
     *  - дата+время без зоны (YYYY-MM-DDTHH:mm[:ss]) → системная зона;
     *  - только дата (YYYY-MM-DD) → КОНЕЦ суток, иначе «сегодня» ложно просрочилось бы утром.
     */
    private fun dueDeadline(due: String): Instant? {
        val s = due.trim()
        if (s.isBlank()) return null
        val zone = ZoneId.systemDefault()
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return Instant.parse(s) }
        runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant() }
        runCatching { return LocalDate.parse(s).atTime(LocalTime.MAX).atZone(zone).toInstant() }
        return null
    }
}

/** task_add — добавить задачу в список дел (с дедлайном и приоритетом). SAFE. */
class TaskAddTool(private val context: Context) : AgentTool {
    override val id = "task_add"
    override val danger = DangerLevel.SAFE
    override val schema =
        """добавить задачу в список дел (персистентный TODO с дедлайнами); """ +
            """args: {"title":"позвонить в банк","due":"2026-07-20T10:00:00 — опц ISO","priority":"high|normal|low — опц"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@withContext ToolResult.Failure("нужен args.title")
        val task = runCatching { TaskStore.add(context, title, o.optString("due"), o.optString("priority")) }
            .getOrElse { return@withContext ToolResult.Failure("task_add: ${it.message?.take(160) ?: "не удалось сохранить"}") }
        ToolResult.Success("""{"added":true,"id":"${escapeJson(task.optString("id"))}"}""")
    }
}

/** task_list — показать задачи с фильтром (all|open|done|overdue). SAFE. */
class TaskListTool(private val context: Context) : AgentTool {
    override val id = "task_list"
    override val danger = DangerLevel.SAFE
    override val schema =
        """показать список дел; args: {"filter":"all|open|done|overdue"} (по умолчанию open)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val filter = o.optString("filter").ifBlank { "open" }.lowercase()
        val tasks = runCatching { TaskStore.list(context, filter) }
            .getOrElse { return@withContext ToolResult.Failure("task_list: ${it.message?.take(160) ?: "не удалось прочитать задачи"}") }
        ToolResult.Success(JSONObject().put("filter", filter).put("count", tasks.length()).put("tasks", tasks).toString())
    }
}

/** task_done — пометить задачу выполненной. SAFE. */
class TaskDoneTool(private val context: Context) : AgentTool {
    override val id = "task_done"
    override val danger = DangerLevel.SAFE
    override val schema = """пометить задачу выполненной; args: {"id":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val taskId = o.optString("id").takeIf { it.isNotBlank() } ?: return@withContext ToolResult.Failure("нужен args.id")
        runCatching { TaskStore.done(context, taskId) }
            .fold(
                onSuccess = { found ->
                    if (found) ToolResult.Success("""{"done":true,"id":"${escapeJson(taskId)}"}""")
                    else ToolResult.Failure("задача не найдена: $taskId")
                },
                onFailure = { ToolResult.Failure("task_done: ${it.message?.take(160) ?: "не удалось сохранить"}") },
            )
    }
}

/** task_delete — удалить задачу из списка. SAFE. */
class TaskDeleteTool(private val context: Context) : AgentTool {
    override val id = "task_delete"
    override val danger = DangerLevel.SAFE
    override val schema = """удалить задачу из списка дел; args: {"id":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val taskId = o.optString("id").takeIf { it.isNotBlank() } ?: return@withContext ToolResult.Failure("нужен args.id")
        runCatching { TaskStore.delete(context, taskId) }
            .fold(
                onSuccess = { found ->
                    if (found) ToolResult.Success("""{"deleted":true,"id":"${escapeJson(taskId)}"}""")
                    else ToolResult.Failure("задача не найдена: $taskId")
                },
                onFailure = { ToolResult.Failure("task_delete: ${it.message?.take(160) ?: "не удалось сохранить"}") },
            )
    }
}

// ═══════════════════════════════ RSS / Atom ═══════════════════════════════

private const val RSS_UA = "PlusAI-Agent/1.0 (Android; rss-reader)"
private const val RSS_TIMEOUT = 10_000
private const val RSS_MAX_BYTES = 4 * 1024 * 1024 // потолок скачивания ленты (защита от гигантского ответа)

private val RSS_ITEM_RE = Regex("<item[\\s>].*?</item>|<item>.*?</item>", RegexOption.DOT_MATCHES_ALL)
private val ATOM_ENTRY_RE = Regex("<entry[\\s>].*?</entry>|<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
private val RSS_TITLE_RE = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
private val RSS_LINK_RE = Regex("<link[^>]*>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
private val ATOM_LINK_HREF_RE = Regex("""<link[^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
private val RSS_DATE_RE = Regex("<(?:pubDate|published|updated)[^>]*>(.*?)</(?:pubDate|published|updated)>", RegexOption.DOT_MATCHES_ALL)
private val CDATA_RE = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)

private fun rssUnwrapCData(s: String): String = CDATA_RE.find(s)?.groupValues?.get(1) ?: s
private fun rssStripTags(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()
private fun rssUnescapeHtml(s: String): String = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")

private fun rssClean(s: String): String = rssUnescapeHtml(rssStripTags(rssUnwrapCData(s))).trim()

/** rss_read — прочитать произвольную RSS/Atom-ленту по URL (в отличие от news_search — это общий RSS). SAFE. */
class RssReadTool : AgentTool {
    override val id = "rss_read"
    override val danger = DangerLevel.SAFE
    override val schema =
        """прочитать RSS/Atom-ленту по URL (общий RSS, не поиск новостей); """ +
            """args: {"url":"https://…/feed.xml","limit":10}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val url = o.optString("url").trim().takeIf { it.isNotBlank() } ?: return@withContext ToolResult.Failure("нужен args.url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ToolResult.Failure("url должен быть http(s)")
        val limit = o.optInt("limit", 10).coerceIn(1, 100)
        try {
            val xml = rssHttpGet(url)
            // RSS: <item>. Atom: <entry>. Берём тот, где что-то есть.
            val itemMatches = RSS_ITEM_RE.findAll(xml).toList()
            val entryMatches = if (itemMatches.isEmpty()) ATOM_ENTRY_RE.findAll(xml).toList() else emptyList()
            val blocks = if (itemMatches.isNotEmpty()) itemMatches else entryMatches
            val items = JSONArray()
            for (m in blocks) {
                if (items.length() >= limit) break
                val block = m.value
                val title = RSS_TITLE_RE.find(block)?.groupValues?.get(1)?.let(::rssClean).orEmpty()
                // Atom <link href="..."/> имеет приоритет; иначе RSS <link>...</link>.
                val link = ATOM_LINK_HREF_RE.find(block)?.groupValues?.get(1)?.trim()
                    ?: RSS_LINK_RE.find(block)?.groupValues?.get(1)?.let(::rssClean).orEmpty()
                val date = RSS_DATE_RE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                if (title.isBlank() && link.isBlank()) continue
                items.put(JSONObject().put("title", title).put("link", link).put("published", date))
            }
            if (items.length() == 0) return@withContext ToolResult.Failure("в ленте не найдено записей (item/entry)")
            ToolResult.Success(JSONObject().put("url", url).put("count", items.length()).put("items", items).toString())
        } catch (t: Throwable) {
            ToolResult.Failure("rss_read: ${t.message?.take(160) ?: "запрос не удался"}")
        }
    }

    private fun rssHttpGet(url: String): String {
        // ЧЕРЕЗ safeHttpClient (SafeDns): проверяет РЕАЛЬНЫЙ IP на каждом хопе (в т.ч. после редиректа),
        // блокируя SSRF на внутренние/loopback/metadata-адреса. Раньше сырой HttpURLConnection с ручным
        // cross-protocol-редиректом ходил мимо защиты (audit: url от модели, danger=SAFE → без подтверждения).
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", RSS_UA)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .build()
        safeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("пустой ответ")
            // Кап объёма: не тянем гигантскую ленту в память. Читаем БАЙТЫ (декодируем по кодировке ленты).
            val buf = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk); if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > RSS_MAX_BYTES) break
                }
            }
            val bytes = buf.toByteArray()
            return String(bytes, rssCharset(resp.header("Content-Type"), bytes))
        }
    }
}

/**
 * Кодировка ленты: сперва HTTP Content-Type charset, затем XML-декларация
 * <?xml … encoding="…"?> из первых байтов; иначе UTF-8. Так кириллица в windows-1251 не крякается.
 */
private fun rssCharset(contentType: String?, bytes: ByteArray): java.nio.charset.Charset {
    contentType?.let { ct ->
        Regex("""charset\s*=\s*"?([\w-]+)""", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.let { name ->
            runCatching { return java.nio.charset.Charset.forName(name) }
        }
    }
    // Префикс декодируем как ISO-8859-1 (байт-в-символ) — этого хватает, чтобы вычитать декларацию.
    val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
    Regex("""<\?xml[^>]*encoding\s*=\s*["']([\w-]+)["']""", RegexOption.IGNORE_CASE)
        .find(head)?.groupValues?.get(1)?.let { name ->
            runCatching { return java.nio.charset.Charset.forName(name) }
        }
    return Charsets.UTF_8
}

// ═══════════════════════════════ ШТРИХКОД ═══════════════════════════════

/** barcode_generate — штрихкод (code128|ean13|ean8|upca|code39), PNG рисуется в песочницу и в ответ. SAFE. */
class BarcodeGenerateTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "barcode_generate"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """сгенерировать штрихкод (картинка в ответ); args: {"data":"12345","format":"code128|ean13|ean8|upca|code39",""" +
            """"out":"barcode.png"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.Default) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return@withContext ToolResult.Failure("битый JSON")
        val data = o.optString("data").ifBlank { return@withContext ToolResult.Failure("нужен args.data") }
        val fmtName = o.optString("format", "code128").lowercase()
        val format = when (fmtName) {
            "code128", "code_128" -> BarcodeFormat.CODE_128
            "ean13", "ean_13" -> BarcodeFormat.EAN_13
            "ean8", "ean_8" -> BarcodeFormat.EAN_8
            "upca", "upc_a", "upc-a" -> BarcodeFormat.UPC_A
            "code39", "code_39" -> BarcodeFormat.CODE_39
            else -> return@withContext ToolResult.Failure("неизвестный format=$fmtName (code128|ean13|ean8|upca|code39)")
        }
        val file = resolve(o.optString("out").ifBlank { "barcode.png" }) ?: return@withContext ToolResult.Failure("путь вне папки")
        runCatching {
            val w = 640
            val h = 240
            // MultiFormatWriter кидает при несоответствии данных формату (напр. ean13 требует 12–13 цифр).
            val matrix = MultiFormatWriter().encode(data, format, w, h)
            val mw = matrix.width
            val mh = matrix.height
            val bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            // Собираем пиксели в IntArray и заливаем одним setPixels — вместо ~153k JNI-вызовов setPixel.
            val pixels = IntArray(mw * mh)
            for (y in 0 until mh) for (x in 0 until mw)
                pixels[y * mw + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            bmp.setPixels(pixels, 0, mw, 0, 0, mw, mh)
            file.parentFile?.mkdirs()
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure {
            return@withContext ToolResult.Failure("barcode: ${it.message?.take(160) ?: "не удалось (проверь формат/данные)"}")
        }
        ToolResult.Success("""{"images":["${escapeJson(file.absolutePath)}"],"path":"${escapeJson(file.name)}","format":"$fmtName"}""")
    }
}

// ═══════════════════════════════ REGEX REPLACE ═══════════════════════════════

private const val RR_TIMEOUT_MS = 3000L
private const val RR_MAX_TEXT = 100_000

/** regex_replace — замена по регулярному выражению (java.util.regex, с таймаутом от ReDoS). SAFE. */
class RegexReplaceTool : AgentTool {
    override val id = "regex_replace"
    override val danger = DangerLevel.SAFE
    override val schema =
        """замена по регулярному выражению; args: {"text":"a12b34","pattern":"\\d+","replacement":"#","flags":"imsux — опц"}; """ +
            """в replacement символы $ и \ экранируются буквально — обратные ссылки на группы ($1, \1) НЕ работают"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ToolResult.Failure("битый JSON")
        val text = o.optString("text")
        if (!o.has("text")) return ToolResult.Failure("нужен args.text")
        val pattern = o.optString("pattern").takeIf { it.isNotBlank() } ?: return ToolResult.Failure("нужен args.pattern")
        val replacement = o.optString("replacement")
        // Ограничение длины входа снижает риск/стоимость катастрофического бэктрекинга (как в regex_test).
        if (text.length > RR_MAX_TEXT) return ToolResult.Failure("текст слишком велик для regex_replace (> $RR_MAX_TEXT символов)")

        val flagsStr = o.optString("flags")
        var flags = 0
        if (flagsStr.contains('i')) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (flagsStr.contains('m')) flags = flags or Pattern.MULTILINE
        if (flagsStr.contains('s')) flags = flags or Pattern.DOTALL
        if (flagsStr.contains('x')) flags = flags or Pattern.COMMENTS
        if (flagsStr.contains('u')) flags = flags or Pattern.UNICODE_CHARACTER_CLASS
        val p = runCatching { Pattern.compile(pattern, flags) }
            .getOrElse { return ToolResult.Failure("невалидное регулярное выражение: ${it.message}") }

        // java-regex не прерывается кооперативно (Thread.interrupt не останавливает matcher на String),
        // поэтому считаем в потоке-демоне и ограничиваем время через CompletableDeferred (как regex_test).
        val deferred = CompletableDeferred<Pair<String, Int>>()
        Thread {
            try {
                val matcher = p.matcher(text)
                var count = 0
                val sb = StringBuffer()
                while (matcher.find()) {
                    count++
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
                }
                matcher.appendTail(sb)
                deferred.complete(sb.toString() to count)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }.apply { isDaemon = true; name = "regex_replace" }.start()

        val out = try {
            withTimeoutOrNull(RR_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            return ToolResult.Failure("regex_replace: ${t.message?.take(160)}")
        } ?: return ToolResult.Failure("слишком сложный паттерн или слишком долгое сопоставление (таймаут $RR_TIMEOUT_MS мс)")

        return ToolResult.Success("""{"result":"${escapeJson(out.first)}","replaced":${out.second}}""")
    }
}

// ═══════════════════════════════ ЭКСПОРТ ПАМЯТИ ═══════════════════════════════

/** export_memory — выгрузить долговременную память ИИ + рабочие заметки/план сессии в текстовый файл. SAFE. */
class ExportMemoryTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "export_memory"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """выгрузить долговременную память ИИ и заметки/план сессии в текстовый файл; """ +
            """args: {"out":"memory_export.txt"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val file = resolve(o.optString("out").ifBlank { "memory_export.txt" }) ?: return@withContext ToolResult.Failure("путь вне папки")

        val memory = MemoryStore.read(context)               // долговременная память (memory.md)
        val sessionNotes = SessionStore.recall(context)      // рабочая память сессии (session-memory.md)
        val plan = SessionStore.getPlan(context)             // план задачи (session-plan.md)

        val sb = StringBuilder()
        sb.append("# Экспорт памяти ИИ\n")
        sb.append("Дата: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append("\n\n")
        sb.append("## Долговременная память\n")
        sb.append(memory.ifBlank { "(пусто)" }).append("\n\n")
        sb.append("## Рабочие заметки сессии\n")
        sb.append(sessionNotes.ifBlank { "(пусто)" }).append("\n\n")
        sb.append("## План текущей задачи\n")
        sb.append(plan.ifBlank { "(пусто)" }).append("\n")

        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
        }.onFailure { return@withContext ToolResult.Failure("export_memory: ${it.message}") }

        ToolResult.Success("""{"path":"${escapeJson(file.name)}","bytes":${sb.toString().toByteArray().size}}""")
    }
}

// ═══════════════════════════════ ФАБРИКА ═══════════════════════════════

/**
 * Инструменты продуктивности: задачи/TODO, чтение RSS/Atom, штрихкоды, замена по регулярке,
 * экспорт памяти. Все SAFE. [resolve] — резолвер путей песочницы (barcode/export пишут файлы).
 */
fun productivityTools(context: Context, resolve: (String) -> File?): List<AgentTool> = listOf(
    TaskAddTool(context),
    TaskListTool(context),
    TaskDoneTool(context),
    TaskDeleteTool(context),
    RssReadTool(),
    BarcodeGenerateTool(resolve),
    RegexReplaceTool(),
    ExportMemoryTool(context, resolve),
)
