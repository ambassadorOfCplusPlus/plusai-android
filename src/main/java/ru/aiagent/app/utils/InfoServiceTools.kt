package ru.aiagent.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.jsonField
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Информационные инструменты агента (§3.4): Википедия, погода, курсы валют, новости.
 * Все — публичные HTTP-API БЕЗ ключей. Только чтение внешних данных, ничего личного не уходит →
 * класс SAFE. IO вынесено на Dispatchers.IO, таймауты ~10с, ошибки → ToolResult.Failure.
 */

private const val UA = "PlusAI-Agent/1.0 (Android; info-tools)"
private const val HTTP_TIMEOUT = 10_000

/** Простой HTTP GET публичного API: возвращает тело ответа (текст). Бросает при не-2xx/сетевой ошибке. */
private fun httpGet(url: String): String {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = HTTP_TIMEOUT
        readTimeout = HTTP_TIMEOUT
        instanceFollowRedirects = true // публичные API часто редиректят (напр. Google News RSS)
        setRequestProperty("User-Agent", UA) // Wikipedia API требует User-Agent
        setRequestProperty("Accept", "*/*")
    }
    try {
        val code = c.responseCode
        val stream = if (code in 200..399) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return text
    } finally {
        c.disconnect()
    }
}

/** URL-кодирование параметра запроса. */
private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Убрать HTML-теги (сниппеты Википедии, заголовки RSS). */
private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

/** Раскодировать базовые HTML-сущности (для RSS-заголовков Google News). */
private fun unescapeHtml(s: String): String = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")

// ───────────────────────────── wikipedia_search ─────────────────────────────

/** wikipedia_search — поиск по Википедии + краткая сводка (intro-extract) верхней статьи. */
class WikipediaSearchTool : AgentTool {
    override val id = "wikipedia_search"
    override val danger = DangerLevel.SAFE
    override val schema =
        """поиск и краткая сводка по Википедии; args: {"query":"...","lang":"ru"} (lang по умолчанию ru)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val query = jsonField(argsJson, "query")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Failure("нужен args.query")
        val lang = jsonField(argsJson, "lang")?.takeIf { it.isNotBlank() }?.lowercase() ?: "ru"
        try {
            val searchUrl = "https://$lang.wikipedia.org/w/api.php?action=query&list=search" +
                "&format=json&srlimit=5&srsearch=${enc(query)}"
            val hits = JSONObject(httpGet(searchUrl))
                .optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            if (hits.length() == 0) return@withContext ToolResult.Failure("ничего не найдено по «$query»")

            val results = JSONArray()
            for (i in 0 until hits.length()) {
                val h = hits.getJSONObject(i)
                results.put(
                    JSONObject()
                        .put("title", h.optString("title"))
                        .put("snippet", stripTags(h.optString("snippet"))),
                )
            }
            // Сводка (intro) верхней статьи — action=query&prop=extracts&exintro&explaintext.
            val topTitle = hits.getJSONObject(0).optString("title")
            val summary = runCatching {
                val exUrl = "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts" +
                    "&exintro&explaintext&format=json&redirects=1&titles=${enc(topTitle)}"
                val pages = JSONObject(httpGet(exUrl)).optJSONObject("query")?.optJSONObject("pages")
                pages?.keys()?.asSequence()?.mapNotNull { pages.optJSONObject(it)?.optString("extract") }
                    ?.firstOrNull { it.isNotBlank() }?.take(1200)
            }.getOrNull().orEmpty()

            ToolResult.Success(
                JSONObject()
                    .put("query", query).put("lang", lang)
                    .put("top_title", topTitle).put("summary", summary)
                    .put("results", results)
                    .toString(),
            )
        } catch (t: Throwable) {
            ToolResult.Failure("wikipedia_search: ${t.message?.take(160) ?: "запрос не удался"}")
        }
    }
}

// ───────────────────────────── weather_get ─────────────────────────────

/** Расшифровка WMO weather_code (Open-Meteo) в русскую строку. */
private fun weatherDesc(code: Int): String = when (code) {
    0 -> "ясно"
    1, 2 -> "переменная облачность"
    3 -> "пасмурно"
    45, 48 -> "туман"
    51, 53, 55 -> "морось"
    56, 57 -> "ледяная морось"
    61, 63, 65 -> "дождь"
    66, 67 -> "ледяной дождь"
    71, 73, 75 -> "снег"
    77 -> "снежные зёрна"
    80, 81, 82 -> "ливень"
    85, 86 -> "снегопад"
    95 -> "гроза"
    96, 99 -> "гроза с градом"
    else -> "код $code"
}

/** weather_get — текущая погода по координатам или названию города (Open-Meteo, без ключа). */
class WeatherGetTool : AgentTool {
    override val id = "weather_get"
    override val danger = DangerLevel.SAFE
    override val schema =
        """текущая погода; args: {"lat":..,"lon":..} ИЛИ {"city":"Москва"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON args")
        try {
            var lat = if (args.has("lat")) args.optDouble("lat") else Double.NaN
            var lon = if (args.has("lon")) args.optDouble("lon") else Double.NaN
            var place = ""
            if (lat.isNaN() || lon.isNaN()) {
                val city = jsonField(argsJson, "city")?.takeIf { it.isNotBlank() }
                    ?: return@withContext ToolResult.Failure("нужны args.lat+lon или args.city")
                // Геокодинг названия → координаты.
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name=${enc(city)}"
                val g = JSONObject(httpGet(geoUrl)).optJSONArray("results")?.optJSONObject(0)
                    ?: return@withContext ToolResult.Failure("город «$city» не найден")
                lat = g.optDouble("latitude"); lon = g.optDouble("longitude")
                place = listOfNotNull(
                    g.optString("name").takeIf { it.isNotBlank() },
                    g.optString("country").takeIf { it.isNotBlank() },
                ).joinToString(", ")
            }
            if (lat.isNaN() || lon.isNaN()) return@withContext ToolResult.Failure("некорректные координаты")

            val wUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,wind_speed_10m"
            val cur = JSONObject(httpGet(wUrl)).optJSONObject("current")
                ?: return@withContext ToolResult.Failure("нет данных о погоде")
            val code = cur.optInt("weather_code", -1)
            ToolResult.Success(
                JSONObject()
                    .put("place", place).put("lat", lat).put("lon", lon)
                    .put("temperature_c", cur.optDouble("temperature_2m"))
                    .put("wind_speed_ms", cur.optDouble("wind_speed_10m"))
                    .put("weather_code", code).put("condition", weatherDesc(code))
                    .toString(),
            )
        } catch (t: Throwable) {
            ToolResult.Failure("weather_get: ${t.message?.take(160) ?: "запрос не удался"}")
        }
    }
}

// ───────────────────────────── currency_convert ─────────────────────────────

/** currency_convert — конвертация валют по реальному курсу (open.er-api.com, без ключа). */
class CurrencyConvertTool : AgentTool {
    override val id = "currency_convert"
    override val danger = DangerLevel.SAFE
    override val schema =
        """конвертация валют по актуальному курсу; args: {"from":"USD","to":"RUB","amount":100} (amount по умолчанию 1)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON args")
        val from = args.optString("from").takeIf { it.isNotBlank() }?.uppercase()
            ?: return@withContext ToolResult.Failure("нужен args.from (напр. USD)")
        val to = args.optString("to").takeIf { it.isNotBlank() }?.uppercase()
            ?: return@withContext ToolResult.Failure("нужен args.to (напр. RUB)")
        val amount = if (args.has("amount")) args.optDouble("amount", 1.0) else 1.0
        try {
            val resp = JSONObject(httpGet("https://open.er-api.com/v6/latest/${enc(from)}"))
            if (resp.optString("result") != "success") {
                return@withContext ToolResult.Failure("валюта «$from» не поддерживается или сервис недоступен")
            }
            val rates = resp.optJSONObject("rates")
                ?: return@withContext ToolResult.Failure("нет данных о курсах")
            if (!rates.has(to)) return@withContext ToolResult.Failure("валюта «$to» не найдена в курсах")
            val rate = rates.optDouble(to)
            val converted = amount * rate
            ToolResult.Success(
                JSONObject()
                    .put("from", from).put("to", to)
                    .put("amount", amount).put("rate", rate)
                    .put("result", converted)
                    .put("updated", resp.optString("time_last_update_utc"))
                    .toString(),
            )
        } catch (t: Throwable) {
            ToolResult.Failure("currency_convert: ${t.message?.take(160) ?: "запрос не удался"}")
        }
    }
}

// ───────────────────────────── news_search ─────────────────────────────

private val NEWS_ITEM_RE = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
private val NEWS_TITLE_RE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
private val NEWS_LINK_RE = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
private val NEWS_PUB_RE = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)

/** Развернуть CDATA-обёртку, если есть. */
private fun unwrapCData(s: String): String =
    Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1) ?: s

/** news_search — новости по теме через Google News RSS (без ключа), парсинг простым regex. */
class NewsSearchTool : AgentTool {
    override val id = "news_search"
    override val danger = DangerLevel.SAFE
    override val schema =
        """поиск новостей по теме (Google News RSS); args: {"query":"...","lang":"ru"} (lang по умолчанию ru)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val query = jsonField(argsJson, "query")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Failure("нужен args.query")
        val lang = jsonField(argsJson, "lang")?.takeIf { it.isNotBlank() }?.lowercase() ?: "ru"
        try {
            val rssUrl = "https://news.google.com/rss/search?q=${enc(query)}&hl=$lang"
            val xml = httpGet(rssUrl)
            val items = JSONArray()
            for (m in NEWS_ITEM_RE.findAll(xml)) {
                if (items.length() >= 10) break
                val block = m.groupValues[1]
                val title = NEWS_TITLE_RE.find(block)?.groupValues?.get(1)
                    ?.let { unescapeHtml(stripTags(unwrapCData(it))) }?.trim().orEmpty()
                val link = NEWS_LINK_RE.find(block)?.groupValues?.get(1)?.let(::unwrapCData)?.trim().orEmpty()
                val pub = NEWS_PUB_RE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                if (title.isBlank()) continue
                items.put(JSONObject().put("title", title).put("link", link).put("published", pub))
            }
            if (items.length() == 0) return@withContext ToolResult.Failure("новостей по «$query» не найдено")
            ToolResult.Success(
                JSONObject().put("query", query).put("lang", lang)
                    .put("count", items.length()).put("items", items).toString(),
            )
        } catch (t: Throwable) {
            ToolResult.Failure("news_search: ${t.message?.take(160) ?: "запрос не удался"}")
        }
    }
}

// ───────────────────────────── фабрика ─────────────────────────────

/** Фабрика информационных инструментов (все SAFE, без ключей). */
fun infoServiceTools(): List<AgentTool> = listOf(
    WikipediaSearchTool(),
    WeatherGetTool(),
    CurrencyConvertTool(),
    NewsSearchTool(),
)
