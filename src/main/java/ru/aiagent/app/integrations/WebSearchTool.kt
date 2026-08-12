package ru.aiagent.app.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * web_search — поиск в интернете (как у оригинального DeepSeek). Использует DuckDuckGo
 * HTML (без ключей), парсит топ-результаты и отдаёт агенту заголовок+ссылку+сниппет.
 * Публичный веб — не приватные данные, поэтому доступен и облачной модели.
 */
class WebSearchTool(private val context: android.content.Context) : AgentTool {
    override val id = "web_search"
    override val danger = DangerLevel.SAFE
    override val schema = """поиск в интернете; args: {"query":"новости про ...","limit":5}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val query = runCatching { JSONObject(argsJson).optString("query") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.Failure("нужен args.query")
        val limit = runCatching { JSONObject(argsJson).optInt("limit", 5) }.getOrDefault(5).coerceIn(1, 10)

        // 1) Качественный поиск через наш сервер (ключ провайдера на сервере). 2) Если сервер
        // не настроен/недоступен — откат на бесплатный DuckDuckGo.
        serverSearch(query, limit)?.let { return@withContext it }
        runCatching {
            val html = fetch("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
            val results = parse(html, limit)
            if (results.isEmpty()) return@withContext ToolResult.Success("""{"results":[],"note":"ничего не найдено"}""")
            val arr = JSONArray()
            results.forEach { (title, url, snip) ->
                arr.put(JSONObject().put("title", title).put("url", url).put("snippet", snip))
            }
            ToolResult.Success(JSONObject().put("results", arr).toString())
        }.getOrElse { ToolResult.Failure("web_search: ${it.message?.take(120)}") }
    }

    /** Поиск через сервер; null → сервер не настроен/недоступен (нужен откат на DDG). */
    private fun serverSearch(query: String, limit: Int): ToolResult? {
        val sess = ru.aiagent.app.cloud.Account.current(context)
        val base = (sess?.url ?: ru.aiagent.app.cloud.Account.DEFAULT_URL).trimEnd('/')
        // /v1/search требует авторизацию (метринг платного Tavily/Serper на аккаунт). Без токена сервер
        // отвечал 401 → тихий откат на бесплатный DDG, и платный поиск НЕ использовался никогда.
        return runCatching {
            val url = "$base/v1/search?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=$limit"
            val c = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000; readTimeout = 15000
                sess?.token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                val code = c.responseCode
                if (code == 501 || code == 404) return null // не настроен → откат на DDG
                val s = if (code in 200..299) c.inputStream else c.errorStream
                val body = s?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) return null
                // Ответ уже в нужном виде {results:[{title,url,snippet}]} — отдаём как есть.
                if (JSONObject(body).optJSONArray("results") == null) return null
                ToolResult.Success(body)
            } finally { c.disconnect() }
        }.getOrNull()
    }

    private fun fetch(url: String): String {
        val c = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PlusAI")
            setRequestProperty("Accept-Language", "ru,en;q=0.8")
            connectTimeout = 12000; readTimeout = 15000
        }
        try {
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            return s?.bufferedReader()?.use { it.readText() } ?: ""
        } finally { c.disconnect() }
    }

    // DDG HTML: ссылки в <a class="result__a" href="...">title</a>, сниппет в class="result__snippet".
    private fun parse(html: String, limit: Int): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val linkRe = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snips = snipRe.findAll(html).toList()
        for (i in links.indices) {
            if (out.size >= limit) break
            val href = decodeDdg(links[i].groupValues[1])
            val title = strip(links[i].groupValues[2])
            val snippet = snips.getOrNull(i)?.groupValues?.get(1)?.let { strip(it) } ?: ""
            if (title.isNotBlank()) out += Triple(title, href, snippet.take(300))
        }
        return out
    }

    // DDG оборачивает ссылки в редирект //duckduckgo.com/l/?uddg=<реальный url>.
    private fun decodeDdg(href: String): String {
        val m = Regex("""[?&]uddg=([^&]+)""").find(href) ?: return if (href.startsWith("//")) "https:$href" else href
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(href)
    }

    private fun strip(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()
}
