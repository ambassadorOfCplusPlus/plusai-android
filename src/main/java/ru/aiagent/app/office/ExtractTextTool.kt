package ru.aiagent.app.office

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.toolsdocs.readDocumentText
import java.io.File

/**
 * extract_text — универсальное извлечение текста из файла: документ, ФОТО или СКАН.
 * Картинки и PDF без текстового слоя распознаются OCR (Tesseract, локально). Для обычных
 * текст-документов работает быстрым путём (PDFBox/парсер). SAFE (только чтение).
 */
class ExtractTextTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "extract_text"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """извлечь текст из файла — фото, скан или документ (OCR для картинок и сканов); args: {"path":"скан.jpg"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val path = runCatching { JSONObject(argsJson).optString("path") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("нужен args.path")
        val file = resolve(path) ?: return ToolResult.Failure("путь вне разрешённой папки")
        if (!file.exists() || !file.isFile) return ToolResult.Failure("файла нет: $path")
        val text = withContext(Dispatchers.Default) {
            runCatching { readDocumentText(context, file) }.getOrDefault("")
        }
        if (text.isBlank()) return ToolResult.Failure("текст не извлечён (пустой скан или неподдержанный формат)")
        return ToolResult.Success(
            JSONObject().put("path", path).put("chars", text.length).put("text", text.take(200_000)).toString(),
        )
    }
}
