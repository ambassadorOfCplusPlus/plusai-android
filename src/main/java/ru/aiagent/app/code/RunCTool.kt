package ru.aiagent.app.code

import android.content.Context
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson

/**
 * run_c — интерпретировать C-код на устройстве (picoc, пак `c`). Появляется только когда
 * пак установлен. printf → вывод. Для «ИИ набросал и проверил» — оптимизаций нет, это ок.
 */
class RunCTool(private val context: Context) : AgentTool {
    override val id = "run_c"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema =
        """выполнить C-код (интерпретатор); printf для вывода; args: {"code":"#include <stdio.h>\nint main(){printf(\"%d\\n\",2+2);return 0;}"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!CInterp.ensureLoaded(context)) {
            return ToolResult.Failure("пак C не установлен — Настройки → Расширения → «Компилятор C»")
        }
        val code = strArg(argsJson, "code") ?: return ToolResult.Failure("нет args.code")
        return watchdog(15_000, "c") { CInterp.run(code) }.fold(
            onSuccess = { ToolResult.Success("""{"stdout":"${escapeJson(it.ifBlank { "(выполнено, вывода нет)" }.take(8000))}"}""") },
            onFailure = { ToolResult.Failure("c: ${it.message?.take(300)}") },
        )
    }
}
