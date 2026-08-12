package ru.aiagent.app.utils

import android.content.Context
import com.chaquo.python.Python
import ru.aiagent.app.python.RunPythonTool
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File

/**
 * pdf_tables — извлечение таблиц из PDF в структурированный вид (CSV или JSON).
 *
 * Под капотом — pdfplumber (pure-python, pdfminer.six) во встроенном Chaquopy: открываем PDF,
 * по нужным страницам зовём page.extract_tables(), пишем результат в файл песочницы. Высокоуровневый
 * python-хелпер plusai_pdf.extract_tables делает всю работу и возвращает готовый JSON-статус.
 *
 * usesFiles=true (читает PDF, пишет CSV/JSON), IMPORTANT (создаёт файл), без alwaysConfirm.
 */
class PdfTablesTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "pdf_tables"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """извлечь таблицы из PDF в CSV/JSON (pdfplumber); """ +
            """args: {"path":"doc.pdf","out":"tables.csv (или .json)","pages":"опц. all | 1,2,3 | 1-4"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val path = str(argsJson, "path") ?: return ToolResult.Failure("нет args.path")
        val src = resolve(path) ?: return ToolResult.Failure("путь вне рабочей папки")
        if (!src.isFile) return ToolResult.Failure("файл не найден: $path")

        val outName = str(argsJson, "out")?.takeIf { it.isNotBlank() }
            ?: (File(path).nameWithoutExtension + "_tables.csv")
        val out = resolve(outName) ?: return ToolResult.Failure("путь вывода вне рабочей папки")
        val pages = str(argsJson, "pages")?.takeIf { it.isNotBlank() } ?: "all"

        // Chaquopy — нативный CPython (watchdog его не убьёт извне), но лимит времени защищает агента
        // от зависания вызова; сам разбор PDF конечен. Тот же паттерн, что и в OfficeTools.buildOffice.
        val res = ru.aiagent.app.code.watchdog(60_000, "pdf_tables") {
            RunPythonTool.ensureStarted(context)
            out.parentFile?.mkdirs()
            Python.getInstance().getModule("plusai_pdf")
                .callAttr("extract_tables", src.absolutePath, out.absolutePath, pages)
                .toString()
        }
        return res.fold(
            onSuccess = { status ->
                // Хелпер вернул либо готовый JSON {"tables":N,"rows":M,"out":"…"}, либо "[ошибка] …".
                if (status.startsWith("[ошибка]")) ToolResult.Failure(status)
                else ToolResult.Success(status)
            },
            onFailure = { ToolResult.Failure("pdf_tables: ${it.message?.take(200)}") },
        )
    }
}

/** Фабрика инструментов извлечения таблиц из PDF. */
fun pdfTablesTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(PdfTablesTool(context, resolve))
