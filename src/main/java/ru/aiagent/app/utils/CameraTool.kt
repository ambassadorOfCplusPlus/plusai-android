package ru.aiagent.app.utils

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.withTimeoutOrNull
import ru.aiagent.app.MainActivity
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File

/**
 * camera_capture — сделать фото системной камерой и сохранить его в песочницу. Headless-камера
 * ненадёжна, поэтому — как file_picker: выставляем запрос в [CameraBridge], выводим [MainActivity]
 * вперёд (её onResume запускает TakePicture-launcher с content://-Uri нашего файла), ждём кадр,
 * проверяем, что файл записан. Снимает стороннее приложение камеры → CAMERA-разрешение нам не нужно.
 */
class CameraCaptureTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "camera_capture"
    override val danger = DangerLevel.SAFE // пользователь сам делает снимок
    override val usesFiles = true
    override val schema =
        """сделать фото камерой и сохранить в рабочую папку (пользователь сам делает снимок); """ +
        """args: {"out":"имя файла опц, по умолчанию photo_<ts>.jpg","dir":"папка назначения опц"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val dir = str(argsJson, "dir")?.takeIf { it.isNotBlank() } ?: "."
        val name = str(argsJson, "out")?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
            ?: "photo_${System.currentTimeMillis()}.jpg"
        val rel = if (dir == "." || dir.isBlank()) name else "$dir/$name"
        val file = resolve(rel) ?: return ToolResult.Failure("папка назначения вне песочницы")
        runCatching { file.parentFile?.mkdirs() }

        val deferred = CameraBridge.request(file)
        // Вывести приложение вперёд — иначе камеру не показать.
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("camera", true)
                },
            )
        }

        val outcome = withTimeoutOrNull(120_000L) { runCatching { deferred.await() } }
        if (outcome == null) {
            CameraBridge.cancel("таймаут съёмки")
            return ToolResult.Failure("фото не сделано за 2 минуты — попробуйте ещё раз")
        }
        val success = outcome.getOrElse { return ToolResult.Failure("съёмка: ${it.message?.take(160)}") }
        if (!success) return ToolResult.Failure("пользователь отменил съёмку")
        if (!file.exists() || file.length() == 0L) {
            return ToolResult.Failure("камера вернула успех, но файл пуст — попробуйте ещё раз")
        }
        return ToolResult.Success(
            """{"path":"${escapeJson(rel)}","name":"${escapeJson(name)}","bytes":${file.length()}}""",
        )
    }
}

fun cameraTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(CameraCaptureTool(context, resolve))
