package ru.aiagent.app.utils

import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import ru.aiagent.core.agent.tools.jsonField

/**
 * format_json — проверить и красиво отформатировать JSON (или сжать). Без Python: нативный парсер
 * org.json. Возвращает валидность и результат — база для работы с API/конфигами. (YAML/XML — через run_python.)
 */
class FormatDataTool : AgentTool {
    override val id = "format_json"
    override val danger = DangerLevel.SAFE
    override val schema =
        """проверить и отформатировать JSON; args: {"data":"{...или [...]}","mode":"pretty|minify"} (mode по умолчанию pretty)"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val data = jsonField(argsJson, "data") ?: return ToolResult.Failure("нет args.data")
        val minify = jsonField(argsJson, "mode")?.equals("minify", true) == true
        val trimmed = data.trim()
        // JSON — это объект или массив: пробуем оба, сообщаем понятную ошибку валидации (как Success —
        // результат проверки, а не сбой инструмента).
        val formatted = runCatching {
            if (trimmed.startsWith("[")) {
                val a = JSONArray(trimmed); if (minify) a.toString() else a.toString(2)
            } else {
                val o = JSONObject(trimmed); if (minify) o.toString() else o.toString(2)
            }
        }.getOrElse {
            return ToolResult.Success("""{"valid":false,"error":"${escapeJson(it.message ?: "невалидный JSON")}"}""")
        }
        return ToolResult.Success("""{"valid":true,"formatted":"${escapeJson(formatted)}"}""")
    }
}
