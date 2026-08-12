package ru.aiagent.app.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import ru.aiagent.core.agent.tools.jsonField

/**
 * Исполнение Python-кода в песочнице (S7, ТЗ §3.7–§3.8). Chaquopy: встроенный CPython
 * с pandas/matplotlib. Код гоняется через встроенный runner (stdout захватывается),
 * рабочая директория — переданный workspace. IMPORTANT: создаёт/меняет файлы.
 *
 * ПРИМЕЧАНИЕ (пересмотр Q4): Python встроен в APK (RuStore без dynamic delivery Play).
 * Настоящие «загружаемые паки» остаются для C++/Java.
 */
class RunPythonTool(
    private val appContext: Context,
    private val workspacePath: String,
) : AgentTool {
    override val id = "run_python"
    // Исполняет произвольный код без реальной песочницы (Chaquopy не изолирует ФС/сеть).
    // IMPORTANT (остаётся доступен средним локалкам вроде Gemma E2B), но alwaysConfirm —
    // подтверждение ВСЕГДА, кроме bypass, даже в auto: код не исполняется молча.
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema =
        """выполнить python-код (доступны pandas, matplotlib); args: {"code":"print(2+2)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val code = jsonCode(argsJson) ?: return ToolResult.Failure("нет args.code")
        // Watchdog: бесконечный цикл в коде не должен вешать агента (лимит 30с).
        val res = ru.aiagent.app.code.watchdog(30_000, "python") {
            ensureStarted(appContext)
            Python.getInstance().getModule("plusai_runner").callAttr("run", code, workspacePath).toString()
        }
        // Раннер уже возвращает валидный JSON {"stdout":...,"images":[...]} (json.dumps).
        return res.fold(
            onSuccess = { ToolResult.Success(it) },
            onFailure = { ToolResult.Failure("python: ${it.message?.take(300)}") },
        )
    }

    companion object {
        // @Synchronized: run_python и OfficeTools.buildOffice бегут на своих watchdog-потоках; без лока
        // одновременный старт даёт двойной Python.start → IllegalStateException при инициализации Chaquopy.
        @Synchronized
        fun ensureStarted(context: Context) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        }
    }
}

/** Извлечь поле code (может быть многострочным) — единый извлекатель [jsonField]. */
private fun jsonCode(json: String): String? = jsonField(json, "code")

private fun esc(s: String) = escapeJson(s)
