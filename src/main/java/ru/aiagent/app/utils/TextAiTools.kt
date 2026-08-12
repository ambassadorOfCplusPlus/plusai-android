package ru.aiagent.app.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.ChatEngine
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.jsonField

/**
 * Инструменты обработки текста через МОДЕЛЬ (S-текст). Оба тула опираются на генерацию
 * ЛОКАЛЬНОЙ модели через [ChatEngine.agentGenerate] — тот же простой (не агентный) путь,
 * что используют RagTools/EmailTools/CalendarTools. Работают полностью офлайн; сырьё
 * (произвольный текст пользователя) не уходит в облако — приватность §7 соблюдена.
 *
 * Если локальная модель не установлена/не выбрана, [ChatEngine.agentGenerate] бросает
 * исключение — ловим и возвращаем понятный [ToolResult.Failure] с подсказкой.
 */

/** Убрать возможную утечку спец-токенов чат-шаблона Gemma (<start_of_turn> и т.п.) из ответа модели. */
private fun stripChatTokens(s: String): String = s.replace(Regex("<[^>]{1,20}>"), "").trim()

private const val NO_MODEL =
    "нужна локальная модель — установите/выберите её на вкладке «Модели» (инструмент работает офлайн через модель)"

/**
 * summarize — суммаризация длинного текста моделью (офлайн).
 * args: {"text":"...","max_sentences":5} → {"summary":"..."}
 */
class SummarizeTool(private val context: Context) : AgentTool {
    override val id = "summarize"
    override val danger = DangerLevel.SAFE
    override val schema =
        """кратко суммаризировать длинный текст (через модель, офлайн); args: {"text":"...","max_sentences":5} — max_sentences по умолчанию 5"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val text = jsonField(argsJson, "text")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Failure("нет args.text")
        val n = jsonField(argsJson, "max_sentences")?.trim()?.toIntOrNull()?.coerceIn(1, 30) ?: 5

        val prompt = "Суммаризируй кратко, максимум $n предложений, на языке текста. " +
            "Верни ТОЛЬКО текст резюме, без пояснений и без вводных фраз.\n\nТекст:\n$text"
        val answer = runCatching { ChatEngine.agentGenerate(context, prompt) }
            .getOrElse { return@withContext ToolResult.Failure(NO_MODEL) }

        val summary = stripChatTokens(answer)
        if (summary.isEmpty()) return@withContext ToolResult.Failure("модель вернула пустое резюме")
        ToolResult.Success(JSONObject().put("summary", summary).toString())
    }
}

/**
 * spell_check — проверка орфографии моделью (офлайн).
 * args: {"text":"...","lang":"ru"} → {"corrected":"...","errors":[...]}
 */
class SpellCheckTool(private val context: Context) : AgentTool {
    override val id = "spell_check"
    override val danger = DangerLevel.SAFE
    override val schema =
        """проверить орфографию и вернуть исправленный текст + список ошибок (через модель, офлайн); args: {"text":"...","lang":"ru"} — lang по умолчанию ru"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val text = jsonField(argsJson, "text")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Failure("нет args.text")
        val lang = jsonField(argsJson, "lang")?.takeIf { it.isNotBlank() } ?: "ru"

        val prompt = "Проверь орфографию текста (язык: $lang). Исправь ТОЛЬКО орфографические ошибки, " +
            "не меняя смысл, стиль и пунктуацию. Верни СТРОГО JSON без пояснений в формате: " +
            """{"corrected":"исправленный текст","errors":["было→стало", ...]}. """ +
            "Если ошибок нет — errors пустой массив.\n\nТекст:\n$text"
        val answer = runCatching { ChatEngine.agentGenerate(context, prompt) }
            .getOrElse { return@withContext ToolResult.Failure(NO_MODEL) }

        val cleaned = stripChatTokens(answer)
        // Модель просили вернуть JSON — вычленяем объект из ответа (мог обрасти прозой) и парсим.
        val parsed = extractJsonObject(cleaned)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val out = if (parsed != null && parsed.has("corrected")) {
            JSONObject()
                // isNull-гард обязателен: у Android optString(key, fallback) при JSON null возвращает
                // СТРОКУ "null", а не fallback — модель, ответившая {"corrected":null}, затирала бы весь
                // текст пользователя словом «null».
                .put("corrected", if (parsed.isNull("corrected")) text else parsed.optString("corrected", text))
                .put("errors", parsed.optJSONArray("errors") ?: JSONArray())
        } else {
            // Fallback: модель не выдала валидный JSON — считаем весь ответ исправленным текстом.
            JSONObject().put("corrected", cleaned.ifEmpty { text }).put("errors", JSONArray())
        }
        ToolResult.Success(out.toString())
    }

    /** Вернуть подстроку от первой `{` до последней `}` (грубое вычленение JSON-объекта из ответа модели). */
    private fun extractJsonObject(s: String): String? {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        return if (a in 0 until b) s.substring(a, b + 1) else null
    }
}

/** Фабрика текстовых ИИ-инструментов. */
fun textAiTools(context: Context): List<AgentTool> = listOf(
    SummarizeTool(context),
    SpellCheckTool(context),
)
