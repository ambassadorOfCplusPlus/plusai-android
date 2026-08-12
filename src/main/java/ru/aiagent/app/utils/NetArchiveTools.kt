package ru.aiagent.app.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * Сетевые/архивные инструменты агента: DNS, WHOIS, одноразовый WebSocket, распаковка архивов.
 * Всё файловое — только в песочнице через [resolve]. IO вынесено на Dispatchers.IO.
 */

private fun jf(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

// ───────────────────────────── dns_lookup ─────────────────────────────

/** dns_lookup — резолв домена во все IP-адреса (A/AAAA). */
class DnsLookupTool : AgentTool {
    override val id = "dns_lookup"
    override val danger = DangerLevel.SAFE
    override val schema = """резолв домена в IP-адреса; args: {"host":"example.com"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val host = jf(argsJson, "host") ?: return@withContext ToolResult.Failure("нужен args.host")
        try {
            val addrs = InetAddress.getAllByName(host)
            val ips = JSONArray()
            for (a in addrs) ips.put(a.hostAddress)
            ToolResult.Success(JSONObject().put("host", host).put("ips", ips).toString())
        } catch (t: Throwable) {
            ToolResult.Failure("dns_lookup: ${t.message?.take(160) ?: "не удалось разрешить $host"}")
        }
    }
}

// ───────────────────────────── whois ─────────────────────────────

private const val WHOIS_LIMIT = 6000

/** Один WHOIS-запрос к серверу:43. Возвращает сырой текст ответа (сервер сам закрывает соединение). */
private fun whoisQuery(server: String, query: String): String =
    Socket().use { sock ->
        sock.connect(InetSocketAddress(server, 43), 10_000)
        sock.soTimeout = 10_000
        sock.getOutputStream().apply { write((query + "\r\n").toByteArray(Charsets.UTF_8)); flush() }
        sock.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

/** whois — WHOIS-запрос к whois.iana.org с переходом по refer: на авторитетный сервер зоны. */
class WhoisTool : AgentTool {
    override val id = "whois"
    override val danger = DangerLevel.SAFE
    override val schema = """WHOIS-запрос по домену; args: {"domain":"example.com"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val domain = jf(argsJson, "domain") ?: return@withContext ToolResult.Failure("нужен args.domain")
        try {
            val first = whoisQuery("whois.iana.org", domain)
            // refer: указывает авторитетный WHOIS-сервер зоны — делаем к нему второй запрос.
            val refer = first.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("refer:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }

            val (server, body) = if (refer != null) {
                val second = runCatching { whoisQuery(refer, domain) }.getOrNull()
                if (!second.isNullOrBlank()) refer to second else "whois.iana.org" to first
            } else {
                "whois.iana.org" to first
            }
            val trimmed = if (body.length > WHOIS_LIMIT) body.take(WHOIS_LIMIT) + "\n… (обрезано)" else body
            ToolResult.Success(
                JSONObject().put("domain", domain).put("server", server).put("result", trimmed).toString(),
            )
        } catch (t: Throwable) {
            ToolResult.Failure("whois: ${t.message?.take(160) ?: "запрос не удался"}")
        }
    }
}

// ───────────────────────────── web_socket ─────────────────────────────

private const val WS_WAIT_MIN = 1000L
private const val WS_WAIT_MAX = 30_000L

/**
 * web_socket — одноразовое WebSocket-взаимодействие: подключиться, отправить [message] при открытии,
 * собрать входящие сообщения до истечения wait_ms ИЛИ до закрытия сокета, затем корректно закрыть.
 *
 * Реализация на OkHttp (okhttp3.WebSocket + WebSocketListener). Гарантия возврата: ожидание идёт через
 * withTimeoutOrNull(wait_ms), поэтому сокет НЕ блокирует навечно — по таймауту, закрытию или ошибке
 * функция всегда возвращается, а сокет и пул диспетчера закрываются в finally.
 *
 * args: {"url":"wss://…","message":"…","wait_ms":5000}. wait_ms клампится в [1000; 30000] мс.
 * Возврат: {"sent":true,"received":[...],"count":N}. Бинарные кадры кодируются в base64.
 */
class WebSocketTool : AgentTool {
    override val id = "web_socket"
    override val danger = DangerLevel.IMPORTANT // сетевое взаимодействие наружу, произвольный обмен
    override val usesFiles = false
    override val schema =
        """одноразовый WebSocket-обмен: отправить message и собрать входящие до wait_ms; """ +
            """args: {"url":"wss://…","message":"…","wait_ms":5000}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("некорректный JSON аргументов")
        val url = obj.optString("url").takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Failure("нужен args.url (ws:// или wss://)")
        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
            return@withContext ToolResult.Failure("url должен начинаться с ws:// или wss://")
        }
        val message = obj.optString("message") // пустое → просто слушаем, ничего не шлём
        val waitMs = obj.optLong("wait_ms", 5000L).coerceIn(WS_WAIT_MIN, WS_WAIT_MAX)

        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
            ?: return@withContext ToolResult.Failure("некорректный WebSocket-URL: $url")

        // Потокобезопасный сбор входящих: колбэки OkHttp приходят из его пула, не из этой корутины.
        val received = Collections.synchronizedList(ArrayList<String>())
        val sent = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        // Сигнал «обмен завершён» (закрытие/ошибка) — чтобы не ждать весь wait_ms, если сокет уже закрыт.
        val done = CompletableDeferred<Unit>()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // readTimeout заведомо больше wait_ms, чтобы OkHttp не рвал соединение раньше нашего окна;
            // ограничение по времени задаёт withTimeoutOrNull ниже.
            .readTimeout(waitMs + 10_000, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS) // ws-ping keepalive, чтобы соединение не «засыпало»
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (message.isNotEmpty() && webSocket.send(message)) sent.set(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received.add("base64:" + bytes.base64())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.compareAndSet(null, t.message?.take(160) ?: "ошибка WebSocket")
                done.complete(Unit)
            }
        }

        val webSocket = client.newWebSocket(request, listener)
        try {
            // Ждём либо завершения обмена (done), либо истечения окна wait_ms — что раньше.
            withTimeoutOrNull(waitMs) { done.await() }
        } finally {
            // Закрываем сокет и в случае таймаута, и ошибки; пул диспетчера гасим, чтобы не текли потоки.
            runCatching { webSocket.close(1000, null) }
            runCatching { webSocket.cancel() }
            client.dispatcher.executorService.shutdown()
            runCatching { client.connectionPool.evictAll() }
        }

        // Ошибка подключения (и ничего не получили) → понятный Failure.
        val err = failure.get()
        if (err != null && received.isEmpty() && !sent.get()) {
            return@withContext ToolResult.Failure("web_socket: $err")
        }

        val arr = JSONArray()
        val snapshot = synchronized(received) { ArrayList(received) }
        for (m in snapshot) arr.put(m)
        ToolResult.Success(
            JSONObject().put("sent", sent.get()).put("received", arr).put("count", snapshot.size).toString(),
        )
    }
}

// ───────────────────────────── extract_archive ─────────────────────────────

private const val MAX_ENTRIES = 20_000
private const val MAX_TOTAL_BYTES = 1_000L * 1024 * 1024 // 1 ГБ распакованного — защита от zip-бомбы
private const val MAX_LONGNAME_BYTES = 65_536 // потолок GNU long-name: без него size из заголовка → OOM

/** Безопасно построить путь распаковки внутри [outDir] (защита от path traversal / tar-slip). */
private fun safeTarget(outDir: File, entryName: String): File? {
    val clean = entryName.replace('\\', '/').trimStart('/')
    if (clean.isEmpty() || clean == ".") return null
    val target = File(outDir, clean)
    val outCanon = outDir.canonicalFile
    val tCanon = target.canonicalFile
    return if (tCanon == outCanon || tCanon.path.startsWith(outCanon.path + File.separator)) target else null
}

/** Прочитать ровно [len] байт (или меньше при EOF). true — если буфер заполнен полностью. */
private fun readFully(input: InputStream, buf: ByteArray, len: Int = buf.size): Boolean {
    var off = 0
    while (off < len) {
        val n = input.read(buf, off, len - off)
        if (n < 0) break
        off += n
    }
    return off == len
}

/** Пропустить (прочитать и отбросить) [n] байт из потока. */
private fun skipExact(input: InputStream, n: Long) {
    var left = n
    val buf = ByteArray(64 * 1024)
    while (left > 0) {
        val want = minOf(left, buf.size.toLong()).toInt()
        val r = input.read(buf, 0, want)
        if (r < 0) break
        left -= r
    }
}

/** Скопировать ровно [n] байт из потока в файл. */
private fun copyExact(input: InputStream, out: java.io.OutputStream, n: Long) {
    var left = n
    val buf = ByteArray(64 * 1024)
    while (left > 0) {
        val want = minOf(left, buf.size.toLong()).toInt()
        val r = input.read(buf, 0, want)
        if (r < 0) break
        out.write(buf, 0, r)
        left -= r
    }
}

/** Обрезать строку из tar-поля по первому NUL-байту (имена файлов могут содержать пробелы). */
private fun trimField(s: String): String {
    val nul = s.indexOf(' ')
    // Только хвостовые пробелы (некоторые tar дополняют поля пробелами) — внутренние НЕ режем.
    return (if (nul >= 0) s.substring(0, nul) else s).trimEnd(' ')
}

/** Разобрать октальное число из поля tar-заголовка (пробелы/нули — разделители). */
private fun readOctal(b: ByteArray, off: Int, len: Int): Long {
    var result = 0L
    var started = false
    var i = off
    val end = off + len
    while (i < end) {
        val c = b[i].toInt() and 0xFF
        when {
            c == 0 || c == ' '.code -> if (started) return result
            c in '0'.code..'7'.code -> { result = result * 8 + (c - '0'.code); started = true }
            else -> return result
        }
        i++
    }
    return result
}

/**
 * Минимальный чистый-Kotlin читатель tar (USTAR + GNU long-name 'L'). Обычные файлы и каталоги;
 * прочие типы (симлинки/устройства) пропускаются. Без зависимостей — commons-compress недоступен
 * в compile-classpath модуля app (он только транзитивный runtime от fastexcel).
 */
private fun extractTar(input: InputStream, outDir: File): List<String> {
    val names = ArrayList<String>()
    val header = ByteArray(512)
    var pendingLongName: String? = null
    var total = 0L
    var entries = 0
    while (true) {
        if (!readFully(input, header)) break
        if (header.all { it.toInt() == 0 }) break // блок нулей — конец архива

        // Лимит на ЧИСЛО записей любого типа (не только файлов) — защита от «миллион записей».
        if (++entries > MAX_ENTRIES) throw IllegalStateException("слишком много записей в архиве")

        var name = trimField(String(header, 0, 100, Charsets.UTF_8))
        val magic = String(header, 257, 5, Charsets.US_ASCII)
        if (magic == "ustar") {
            val prefix = trimField(String(header, 345, 155, Charsets.UTF_8))
            if (prefix.isNotEmpty()) name = "$prefix/$name"
        }
        val size = readOctal(header, 124, 12)
        val type = (header[156].toInt() and 0xFF).toChar()
        val padded = (size + 511) / 512 * 512 // данные выровнены по 512

        if (pendingLongName != null) { name = pendingLongName!!; pendingLongName = null }

        when (type) {
            'L' -> { // GNU longname: следующая запись получит это имя
                // size из заголовка не ограничен → без потолка ByteArray(size) кладёт процесс по OOM.
                // Проверяем ДО аллокации; учитываем усечение Long→Int через coerceIn.
                if (size < 0 || size > MAX_LONGNAME_BYTES) {
                    skipExact(input, padded) // подозрительно длинное имя — пропускаем запись целиком
                } else {
                    val nameLen = size.coerceIn(0L, MAX_LONGNAME_BYTES.toLong()).toInt()
                    val data = ByteArray(nameLen)
                    readFully(input, data, nameLen)
                    skipExact(input, padded - size)
                    pendingLongName = trimField(String(data, Charsets.UTF_8))
                }
            }
            '5' -> { // каталог
                safeTarget(outDir, name)?.mkdirs()
                skipExact(input, padded)
            }
            '0', ' ' -> { // обычный файл (новый '0' и старый NUL-формат)
                total += size
                if (total > MAX_TOTAL_BYTES) throw IllegalStateException("архив распаковывается в >1 ГБ — прервано")
                val target = safeTarget(outDir, name)
                if (target != null) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { copyExact(input, it, size) }
                    skipExact(input, padded - size)
                    names.add(name)
                } else {
                    skipExact(input, padded) // путь вне песочницы — пропускаем
                }
            }
            else -> skipExact(input, padded) // симлинки, устройства и т.п. — не поддерживаем
        }
    }
    return names
}

/** Сигнатура 7z: 37 7A BC AF 27 1C ('7z' + маркер). Позволяет распознать .7z без верного расширения. */
private fun is7z(file: File): Boolean = runCatching {
    file.inputStream().use { input ->
        val sig = ByteArray(6)
        if (!readFully(input, sig, 6)) return@runCatching false
        sig[0] == 0x37.toByte() && sig[1] == 0x7A.toByte() && sig[2] == 0xBC.toByte() &&
            sig[3] == 0xAF.toByte() && sig[4] == 0x27.toByte() && sig[5] == 0x1C.toByte()
    }
}.getOrDefault(false)

/**
 * Читатель .7z через commons-compress ([SevenZFile]). 7z — формат с произвольным доступом (не поток),
 * поэтому читаем из файла. Те же защиты, что и для tar: zip-slip ([safeTarget]) и лимиты
 * ([MAX_ENTRIES] / [MAX_TOTAL_BYTES]). Каталоги создаются, файлы пишутся; лишние типы отсутствуют.
 */
private fun extract7z(file: File, outDir: File): List<String> {
    val names = ArrayList<String>()
    var total = 0L
    var entries = 0
    SevenZFile.builder().setFile(file).get().use { sz ->
        while (true) {
            val entry = sz.nextEntry ?: break
            if (++entries > MAX_ENTRIES) throw IllegalStateException("слишком много записей в архиве")
            if (entry.isDirectory) {
                safeTarget(outDir, entry.name)?.mkdirs()
                continue
            }
            val target = safeTarget(outDir, entry.name) ?: continue // путь вне песочницы — пропускаем
            target.parentFile?.mkdirs()
            val buf = ByteArray(64 * 1024)
            target.outputStream().use { out ->
                while (true) {
                    val r = sz.read(buf) // читает данные ТЕКУЩЕЙ записи
                    if (r < 0) break
                    total += r
                    if (total > MAX_TOTAL_BYTES) throw IllegalStateException("архив распаковывается в >1 ГБ — прервано")
                    out.write(buf, 0, r)
                }
            }
            names.add(entry.name)
        }
    }
    return names
}

/**
 * extract_archive — распаковать архив из песочницы. Поддержка: .tar, .tar.gz/.tgz (GZIP+tar),
 * одиночный .gz, .7z (commons-compress). Формат rar НЕ поддерживается (проприетарный).
 */
class ExtractArchiveTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "extract_archive"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """распаковать архив (.tar, .tar.gz/.tgz, .gz, .7z; rar НЕ поддерживается); """ +
            """args: {"path":"archive.tar.gz","dir":"out"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val path = jf(argsJson, "path") ?: return@withContext ToolResult.Failure("нужен args.path")
        val dirArg = jf(argsJson, "dir") ?: "."
        val src = resolve(path) ?: return@withContext ToolResult.Failure("path вне разрешённой папки")
        if (!src.isFile) return@withContext ToolResult.Failure("файл не найден: $path")
        val outDir = resolve(dirArg) ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        if (!outDir.exists() && !outDir.mkdirs()) return@withContext ToolResult.Failure("не удалось создать папку $dirArg")
        if (!outDir.isDirectory) return@withContext ToolResult.Failure("dir не является папкой")

        val lower = src.name.lowercase()
        try {
            when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> {
                    val names = GZIPInputStream(src.inputStream().buffered()).use { gz -> extractTar(gz, outDir) }
                    ok(names, dirArg)
                }
                lower.endsWith(".tar") -> {
                    val names = src.inputStream().buffered().use { extractTar(it, outDir) }
                    ok(names, dirArg)
                }
                lower.endsWith(".gz") -> {
                    // одиночный gz — распаковываем в файл без расширения .gz
                    val outName = src.name.dropLast(3).ifBlank { "output" }
                    val target = safeTarget(outDir, outName)
                        ?: return@withContext ToolResult.Failure("имя выходного файла вне папки")
                    target.parentFile?.mkdirs()
                    GZIPInputStream(src.inputStream().buffered()).use { gz ->
                        target.outputStream().use { gz.copyTo(it, 64 * 1024) }
                    }
                    ok(listOf(outName), dirArg)
                }
                lower.endsWith(".7z") || is7z(src) -> {
                    val names = extract7z(src, outDir)
                    ok(names, dirArg)
                }
                lower.endsWith(".rar") ->
                    ToolResult.Failure("формат rar не поддерживается (проприетарный)")
                else -> ToolResult.Failure("неизвестный формат архива: ${src.name} (поддержаны .tar/.tar.gz/.tgz/.gz/.7z)")
            }
        } catch (t: Throwable) {
            ToolResult.Failure("extract_archive: ${t.message?.take(200) ?: "ошибка распаковки"}")
        }
    }

    private fun ok(names: List<String>, dir: String): ToolResult {
        val arr = JSONArray()
        for (n in names.take(1000)) arr.put(n)
        return ToolResult.Success(
            JSONObject().put("dir", dir).put("count", names.size).put("files", arr).toString(),
        )
    }
}

// ───────────────────────────── фабрика ─────────────────────────────

fun netArchiveTools(resolve: (String) -> File?): List<AgentTool> = listOf(
    DnsLookupTool(),
    WhoisTool(),
    WebSocketTool(),
    ExtractArchiveTool(resolve),
)
