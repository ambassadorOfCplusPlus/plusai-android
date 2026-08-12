package ru.aiagent.app.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.data.ConversationStore
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

private val SPLIT = Regex("[^\\p{L}\\p{Nd}]+") // токенизация запроса (hoisted, чтобы не компилить на каждый вызов)
private val WS = Regex("\\s+")                  // схлопывание пробелов в сниппете

/**
 * search_conversations (идея Hermes «searches its own past conversations») — поиск по СВОИМ прошлым диалогам
 * по ключевым словам: агент вспоминает, что уже обсуждали/решали, не переспрашивая пользователя.
 *
 * §7: содержимое прошлых чатов — личные данные → privateData=true (в облако НЕ уходит, доступно только
 * локальной модели). Хранилище полностью на устройстве (ConversationStore).
 */
class SearchConversationsTool(private val context: Context) : AgentTool {
    override val id = "search_conversations"
    override val danger = DangerLevel.SAFE
    override val privateData = true // §7: тела прошлых диалогов не отдаём облачной модели
    override val schema =
        """искать в СВОИХ прошлых диалогах по словам (вспомнить, что уже обсуждали/решали); """ +
            """args: {"query":"ключевые слова","limit":5}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON")
        val query = o.optString("query").trim()
        if (query.isBlank()) return@withContext ToolResult.Failure("нужен args.query")
        val limit = o.optInt("limit", 5).coerceIn(1, 20)
        val ql = query.lowercase()
        // Грубый стемминг для русской морфологии: сравниваем ОСНОВЫ (первые ~5 символов слова) по подстроке,
        // чтобы «аренды» находило «аренду»/«арендовать». Совпадение — по ВСЕЙ конверсации (не одно сообщение).
        val stems = ql.split(SPLIT).filter { it.length >= 3 }.map { it.take(5) }

        val store = ConversationStore(context)
        // list() уже читает все файлы (для сортировки по updatedAt); take(120) ограничивает второй проход (load).
        val metas = store.list().take(120)
        val hits = JSONArray()
        var found = 0
        for (m in metas) {
            if (found >= limit) break
            val conv = store.load(m.id) ?: continue
            // Конверсация подходит: целая фраза запроса ИЛИ все основы встречаются где-то в её тексте.
            val hasQuery = conv.messages.any { it.text.contains(ql, ignoreCase = true) }
            val hasAllStems = stems.isNotEmpty() && stems.all { st -> conv.messages.any { it.text.contains(st, ignoreCase = true) } }
            if (!hasQuery && !hasAllStems) continue
            // Лучший сниппет — сообщение с наибольшим числом совпадений основ; индекс отслеживаем в цикле
            // (indexOf вернул бы первый структурно-РАВНЫЙ дубль, а не реальное совпадение).
            var bestIdx = -1
            var bestHits = -1
            conv.messages.forEachIndexed { i, msg ->
                val t = msg.text.lowercase()
                val h = (if (t.contains(ql)) 100 else 0) + stems.count { t.contains(it) }
                if (h > bestHits) { bestHits = h; bestIdx = i }
            }
            if (bestIdx < 0) continue
            val match = conv.messages[bestIdx]
            val snippet = match.text.replace(WS, " ").take(240)
            hits.put(
                JSONObject()
                    .put("conversation", m.title.ifBlank { "(без названия)" })
                    .put("when", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(m.updatedAt)))
                    .put("role", if (match.kind == "user") "пользователь" else "ассистент")
                    .put("message_index", bestIdx)
                    .put("snippet", snippet),
            )
            found++
        }
        ToolResult.Success(JSONObject().put("query", query).put("matches", hits).put("found", found).toString())
    }
}
