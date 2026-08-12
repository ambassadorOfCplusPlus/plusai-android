package ru.aiagent.app.phone

import android.content.Context
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.inference.VisionModel
import java.io.File

/**
 * Зрение (S7-vision, ТЗ §3.11). llama.cpp + mtmd (SmolVLM + mmproj), всё на устройстве.
 * Модель ищется в files/models по имени (vlm/vision/smolvlm) + mmproj-файл.
 * Если модель не установлена — понятное сообщение.
 */
class DescribeImageTool(
    private val appContext: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "describe_image"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true // читает файл изображения с устройства
    override val schema = """описать изображение; args: {"path":"фото.jpg","prompt":"что на фото?"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val path = jsonStr(argsJson, "path") ?: return ToolResult.Failure("нет args.path")
        val file = resolve(path) ?: return ToolResult.Failure("путь вне разрешённой папки")
        if (!file.exists()) return ToolResult.Failure("файла нет: $path")
        val prompt = jsonStr(argsJson, "prompt") ?: "Опиши изображение."

        val modelsDir = File(appContext.filesDir, "models")
        val model = modelsDir.listFiles { f ->
            f.extension == "gguf" && !f.name.startsWith("mmproj", true) &&
                listOf("vlm", "vision", "smolvlm").any { f.name.contains(it, true) }
        }?.firstOrNull()
        val mmproj = modelsDir.listFiles { f ->
            f.name.startsWith("mmproj", true) && f.extension == "gguf"
        }?.firstOrNull()
        if (model == null || mmproj == null) {
            return ToolResult.Failure("vision-модель не установлена (нужны *vlm*.gguf + mmproj-*.gguf)")
        }

        return try {
            // .use — нативный handle освобождается даже если describe бросит.
            val desc = VisionModel.load(model.absolutePath, mmproj.absolutePath).use { vm ->
                vm.describe(file.absolutePath, prompt)
            }
            ToolResult.Success("""{"path":"${esc(path)}","description":"${esc(desc.ifBlank { "(пусто)" })}"}""")
        } catch (t: Throwable) {
            ToolResult.Failure("vision: ${t.message?.take(200)}")
        }
    }

    // Общий escapeJson: экранирует и управляющие символы (\t,\r,\b,\f,<0x20) — ручной 3-replace давал битый JSON.
    private fun esc(s: String) = ru.aiagent.core.agent.tools.escapeJson(s)
}
