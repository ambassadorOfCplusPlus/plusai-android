package ru.aiagent.app.integrations.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.cloud.SecureKeys
import ru.aiagent.app.utils.SafeDns
import java.util.concurrent.TimeUnit

/**
 * MCP (Model Context Protocol) — клиент к УДАЛЁННЫМ серверам-инструментам (Streamable HTTP, JSON-RPC 2.0).
 * Пользователь подключает свой MCP-сервер (URL + опц. токен) → его инструменты становятся доступны агенту,
 * как «Свой API», но для ИНСТРУМЕНТОВ. Локальные stdio-серверы (Node/Python-процессы) на телефоне не поднять —
 * только удалённые по HTTP (их уже много: GitHub, Cloudflare, SaaS); stdio — задел на Фазу 2 (десктоп).
 *
 * Безопасность: MCP-сервер — СТОРОННИЙ код на чужом хосте. Инструменты гейтятся как расширения-паки
 * (danger≥IMPORTANT, alwaysConfirm, privateData — см. [McpTool]). Токен уходит Bearer'ом → только по https
 * (http лишь для localhost). Транспорт: single request/response POST; ответ может прийти как application/json
 * ЛИБО как SSE (text/event-stream) — разбираем оба.
 */

/** Подключённый MCP-сервер: имя (namespace для id тулов), URL, опц. Bearer-токен. */
data class McpServer(val name: String, val url: String, val token: String)

/** Описание одного инструмента MCP-сервера (из tools/list). schema — сырая JSON-Schema аргументов. */
data class McpToolDesc(val name: String, val description: String, val schema: String)

/** Хранилище конфигурации MCP-серверов (URL+токен секретны → Keystore, как ключи облака/интеграций). */
object McpAuth {
    private const val KEY = "mcp_servers" // JSON-массив [{name,url,token}]

    fun servers(context: Context): List<McpServer> {
        val raw = SecureKeys.get(context).getString(KEY, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpServer(name, url, o.optString("token"))
        }
    }

    private fun write(context: Context, list: List<McpServer>) {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("name", s.name).put("url", s.url).put("token", s.token))
        SecureKeys.get(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** Добавить/обновить сервер по имени (перезапись при совпадении имени). */
    fun save(context: Context, s: McpServer) {
        val list = servers(context).filterNot { it.name.equals(s.name, ignoreCase = true) } + s
        write(context, list)
    }

    fun remove(context: Context, name: String) {
        write(context, servers(context).filterNot { it.name.equals(name, ignoreCase = true) })
        McpCache.clear(context, name)
    }

    fun hasServers(context: Context): Boolean = servers(context).isNotEmpty()
}

/** Кэш обнаруженных инструментов на сервер (НЕ секрет: имена/схемы). Читается синхронно при сборке набора
 *  тулов (per-message), чтобы не ходить в сеть на каждом сообщении — сеть только на invoke. */
object McpCache {
    private fun prefs(context: Context) = context.getSharedPreferences("plusai_mcp_cache", Context.MODE_PRIVATE)

    private fun atKey(server: String) = "${server}__at"

    fun save(context: Context, server: String, tools: List<McpToolDesc>) {
        val arr = JSONArray()
        for (t in tools) arr.put(JSONObject().put("name", t.name).put("description", t.description).put("schema", t.schema))
        prefs(context).edit().putString(server, arr.toString()).putLong(atKey(server), System.currentTimeMillis()).apply()
    }

    fun tools(context: Context, server: String): List<McpToolDesc> {
        val raw = prefs(context).getString(server, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            McpToolDesc(o.optString("name"), o.optString("description"), o.optString("schema", "{}"))
        }
    }

    /** Возраст кэша в мс (Long.MAX_VALUE, если кэша нет) — для авто-обновления списка тулов. */
    fun ageMs(context: Context, server: String): Long {
        val at = prefs(context).getLong(atKey(server), 0L)
        return if (at == 0L) Long.MAX_VALUE else System.currentTimeMillis() - at
    }

    fun clear(context: Context, server: String) = prefs(context).edit().remove(server).remove(atKey(server)).apply()
}

/** JSON-RPC 2.0 клиент MCP по Streamable HTTP. Инициализация → tools/list / tools/call. */
object McpClient {
    private data class Rpc(val code: Int, val body: String, val session: String?)

    // Резолв ОДИН раз через SafeDns (анти-rebind/SSRF: резолвит, проверяет ВСЕ адреса, коннектит на них же —
    // без второго резолва ОС, поэтому TOCTOU публичное-имя→приватный-IP закрыт; покрывает и ::1, и CGNAT
    // 100.64/10 через isBlockedAddress/isExtraPrivate). Редиректы НЕ следуем: MCP — POST на одобренный адрес,
    // 3xx на внутренний хост увёл бы Authorization; parse() ждёт JSON/SSE, а не Location.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(SafeDns())
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Явный localhost/127.0.0.1 (isSafeUrl разрешает http ТОЛЬКО для них) — свой клиент без SafeDns, иначе
    // петля резалась бы как «внутренний адрес». Публичное имя, резолвящееся в петлю (rebind), сюда НЕ попадёт:
    // оно идёт через `http` с SafeDns и блокируется. Локальный MCP — осознанный выбор пользователя на своём хосте.
    private val localHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase().trim('.').removePrefix("[").removeSuffix("]")
        return h == "localhost" || h.endsWith(".localhost") || h == "127.0.0.1" || h == "::1" ||
            h == "0:0:0:0:0:0:0:1"
    }

    /** Токен Bearer уходит только по https; http — лишь для ТОЧНОГО localhost/127.0.0.1. Хост парсим (а не
     *  startsWith): иначе `http://localhost.evil.com`/`http://localhost@evil.com` прошли бы и слили токен. */
    fun isSafeUrl(url: String): Boolean {
        val t = url.trim()
        val https = t.lowercase().startsWith("https://")
        if (!https && !t.lowercase().startsWith("http://")) return false
        val host = runCatching { java.net.URL(t).host }.getOrNull()?.lowercase()?.trim('.') ?: return false
        if (!https) return host == "localhost" || host == "127.0.0.1"
        // https раньше принимался БЕЗ разбора хоста вовсе — и запрос с Bearer'ом уходил на внутренние
        // адреса (https://169.254.169.254, https://192.168.0.1, https://[::1]). Схема шифрует канал, но
        // не делает внутреннюю сеть устройства безопасной целью, поэтому приватные диапазоны режем.
        return !isPrivateHost(host)
    }

    /** Петля/линк-локал/приватные диапазоны/.internal — цели, которых у внешнего MCP-сервера быть не может. */
    internal fun isPrivateHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".internal") || h.endsWith(".local")) return true
        // ULA-префиксы fc/fd проверяем ТОЛЬКО у IPv6-литералов (в хосте есть ':'): без этого условия
        // startsWith("fc")/("fd") резал обычные домены — fcbank.com, fdny.gov и любой другой на fc*/fd*.
        val isV6 = h.contains(':')
        // IPv6-петля бывает и в развёрнутой форме (InetAddress.getByName("::1").hostAddress == "0:0:0:0:0:0:0:1").
        if (h == "::1" || h == "0:0:0:0:0:0:0:1" ||
            (isV6 && (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")))) return true
        val o = h.split('.')
        if (o.size == 4 && o.all { it.toIntOrNull() in 0..255 }) {
            val (a, b) = (o[0].toInt() to o[1].toInt())
            return a == 127 || a == 10 || a == 0 ||
                (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // CGNAT 100.64.0.0/10 (carrier-NAT/hotspot) — как в SafeDns.isExtraPrivate
        }
        return false
    }

    /** optString с гардом от Android-квирка (JSON null → строка "null"): вернёт "" для null/отсутствия. */
    private fun s(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key)

    private fun initParams() = JSONObject()
        .put("protocolVersion", "2025-06-18")
        .put("capabilities", JSONObject())
        .put("clientInfo", JSONObject().put("name", "PlusAI").put("version", "1"))

    /** Один JSON-RPC POST. method+params+id (id=null → уведомление без ответа). Возвращает (код, тело, session). */
    private fun rpc(server: McpServer, session: String?, method: String, params: JSONObject?, id: Int?): Rpc {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (id != null) body.put("id", id)
        if (params != null) body.put("params", params)
        // Анти-rebind/SSRF теперь в резолвере (SafeDns, резолв-один-раз) — см. `http`. Явный localhost идёт
        // через localHttp. Никакого ручного pre-check с повторным резолвом (он и оставлял окно TOCTOU).
        val host = runCatching { java.net.URL(server.url).host }.getOrNull().orEmpty()
        val client = if (isLoopbackHost(host)) localHttp else http
        val req = Request.Builder()
            .url(server.url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (server.token.isNotBlank()) header("Authorization", "Bearer ${server.token}")
                if (session != null) header("Mcp-Session-Id", session)
            }
            .build()
        client.newCall(req).execute().use { resp ->
            val newSession = resp.header("Mcp-Session-Id") ?: session
            val text = resp.body?.string() ?: ""
            return Rpc(resp.code, text, newSession)
        }
    }

    /**
     * Достаёт JSON-RPC ответ С НУЖНЫМ id из тела. Сначала как цельный JSON (application/json), иначе из SSE.
     * id проверяем ВСЕГДА (и на цельном пути тоже): иначе прокси-мультиплексор/эхо initialize на POST tools/call
     * подсунул бы чужой ответ как результат этого запроса. SSE: событие = подряд идущие data:-строки,
     * склеенные '\n' (по спеке), до пустой строки — многострочный JSON иначе не распарсился бы.
     */
    private fun parse(text: String, id: Int): JSONObject? {
        runCatching { JSONObject(text) }.getOrNull()?.let { if (it.optInt("id", -1) == id) return it }
        val buf = StringBuilder()
        fun flush(): JSONObject? {
            if (buf.isEmpty()) return null
            val doc = buf.toString(); buf.setLength(0)
            return runCatching { JSONObject(doc) }.getOrNull()?.takeIf { it.optInt("id", -1) == id }
        }
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("data:") -> {
                    if (buf.isNotEmpty()) buf.append('\n')
                    // Спека SSE: после "data:" срезаем ОДИН ведущий пробел (не trim — важные пробелы в JSON редки, но корректно).
                    buf.append(line.removePrefix("data:").removePrefix(" "))
                }
                line.isEmpty() -> flush()?.let { return it }
            }
        }
        return flush()
    }

    /** initialize → notifications/initialized. Возвращает session-id для последующих вызовов. */
    private fun handshake(server: McpServer): String? {
        val init = rpc(server, null, "initialize", initParams(), 1)
        if (init.code !in 200..299) error("initialize: HTTP ${init.code}")
        // JSON-RPC отдаёт ошибку в ТЕЛЕ с кодом 200 (например «unsupported protocolVersion»). Проверяя
        // только HTTP-код, мы считали рукопожатие успешным, кэшировали заведомо негодную сессию на 5 минут
        // и теряли настоящую причину — дальше сыпались невнятные ошибки разбора на tools/list.
        runCatching { JSONObject(init.body) }.getOrNull()?.let { o ->
            if (!o.isNull("error")) {
                val e = o.optJSONObject("error")
                error("initialize: ${e?.let { s(it, "message") }?.ifBlank { null } ?: "ошибка сервера"}")
            }
        }
        val session = init.session
        rpc(server, session, "notifications/initialized", null, null) // уведомление, ответ игнорируем
        return session
    }

    // Кэш сессий по URL сервера: переиспользуем в пределах TTL, чтобы НЕ хендшейкать (initialize+initialized)
    // на КАЖДЫЙ вызов тула (было 3 round-trip на вызов; стало 1 после первого).
    private data class Sess(val id: String?, val at: Long)
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Sess>()
    private const val SESSION_TTL_MS = 5 * 60 * 1000L

    private fun session(server: McpServer, force: Boolean): String? {
        if (!force) {
            sessions[server.url]?.let { if (System.currentTimeMillis() - it.at < SESSION_TTL_MS) return it.id }
        }
        val s = handshake(server)
        sessions[server.url] = Sess(s, System.currentTimeMillis())
        return s
    }

    /** Обнаружить инструменты сервера (initialize + tools/list). */
    suspend fun discover(server: McpServer): Result<List<McpToolDesc>> = withContext(Dispatchers.IO) {
        runCatching {
            require(isSafeUrl(server.url)) { "URL должен быть https:// (http:// — только localhost)" }
            val session = session(server, force = true) // свежая сессия (и обновляем кэш для последующих вызовов)
            val resp = rpc(server, session, "tools/list", JSONObject(), 2)
            val obj = parse(resp.body, 2) ?: error("tools/list: пустой ответ (HTTP ${resp.code})")
            obj.optJSONObject("error")?.let { error("MCP: ${s(it, "message")}") }
            val arr = obj.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = s(t, "name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                McpToolDesc(name, s(t, "description"), t.optJSONObject("inputSchema")?.toString() ?: "{}")
            }
        }
    }

    /** Вызвать инструмент (initialize + tools/call). Возвращает текст результата. */
    suspend fun call(server: McpServer, toolName: String, argsJson: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(isSafeUrl(server.url)) { "небезопасный URL MCP (нужен https)" }
            val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
            val params = JSONObject().put("name", toolName).put("arguments", args)
            // Переиспользуем кэш-сессию; при 4xx (сессия истекла/невалидна) — один ре-хендшейк со свежей.
            var resp = rpc(server, session(server, force = false), "tools/call", params, 3)
            if (resp.code == 400 || resp.code == 401 || resp.code == 404) {
                resp = rpc(server, session(server, force = true), "tools/call", params, 3)
            }
            val obj = parse(resp.body, 3) ?: error("tools/call: пустой ответ (HTTP ${resp.code})")
            obj.optJSONObject("error")?.let { error("MCP: ${s(it, "message")}") }
            val result = obj.optJSONObject("result") ?: error("tools/call: нет result")
            // content — массив блоков {type:"text",text} (плюс возможные image/resource — отдаём как есть).
            val content = result.optJSONArray("content")
            val sb = StringBuilder()
            if (content != null) {
                for (i in 0 until content.length()) {
                    val cc = content.optJSONObject(i) ?: continue
                    if (s(cc, "type") == "text") sb.append(s(cc, "text")) else sb.append(cc.toString())
                }
            }
            val out = sb.toString().ifBlank { result.toString() }
            if (result.optBoolean("isError")) error(out.take(300)) // сервер пометил ошибку в результате
            out
        }
    }
}
