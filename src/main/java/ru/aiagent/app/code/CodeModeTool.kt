package ru.aiagent.app.code

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson

/**
 * code-mode (`execute`) на Android — паритет с ПК. Модель пишет ОДИН JS-скрипт с tool(name, args)/log(x)
 * вместо десятков отдельных вызовов (меньше раундов). Исполняется в песочнице Rhino ([JsEngine.runOrchestration]:
 * Java-мост закрыт ClassShutter, есть CPU-дедлайн). БЕЗОПАСНОСТЬ: каждый tool() идёт через [gatedInvoke] —
 * тот же [ru.aiagent.core.agent.AgentLoopSupport.executeGated], что и обе петли (gate/подтверждение/danger),
 * так что оркестрация из скрипта НЕ обход защиты. Сам execute DANGEROUS + alwaysConfirm (только сильной
 * облачной модели; слабым не выдаётся); из [availableIds] исключены execute, code-раннеры и субагенты.
 */
class CodeModeTool(
    private val availableIds: Set<String>,
    private val gatedInvoke: suspend (String, String) -> ToolResult,
) : AgentTool {
    override val id = "execute"
    override val danger = DangerLevel.DANGEROUS
    override val alwaysConfirm = true
    override val schema =
        """запустить ОДИН JS-скрипт, оркестрирующий инструменты (меньше раундов, удобно для циклов/условий): """ +
            """внутри tool(name, argsObject) -> строка-результат (можно JSON.parse) и log(x) для вывода; """ +
            """инструменты те же (кроме самого execute и раннеров кода); каждый вызов проходит подтверждения; """ +
            """args: {"code":"const it=JSON.parse(tool('list_dir',{path:'.'})); log('файлов: '+it.length)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val code = runCatching { JSONObject(argsJson).optString("code") }.getOrNull()
        if (code.isNullOrBlank()) return ToolResult.Failure("нет args.code")
        var calls = 0
        return watchdog(45_000, "code-mode") {
            JsEngine.runOrchestration(code) { name, a ->
                if (++calls > 100) throw RuntimeException("превышен лимит вызовов инструментов (100)")
                if (name !in availableIds) throw RuntimeException("инструмент «$name» недоступен из code-mode")
                // Синхронный мост Rhino → suspend-исполнитель: барьеры (gate/подтверждение) отрабатывают штатно.
                when (val r = runBlocking { gatedInvoke(name, if (a.isBlank()) "{}" else a) }) {
                    is ToolResult.Success -> r.outputJson
                    is ToolResult.Failure -> "ошибка: ${r.message}"
                    is ToolResult.NeedsConfirmation -> "требуется подтверждение: ${r.prompt}"
                }
            }
        }.fold(
            onSuccess = { ToolResult.Success("""{"output":"${escapeJson(it)}"}""") },
            onFailure = { ToolResult.Failure("code-mode: ${it.message?.take(300)}") },
        )
    }
}
