package ru.aiagent.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.aiagent.app.MainActivity
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File

/**
 * file_picker — попросить пользователя выбрать файл системным диалогом (SAF) и скопировать его в
 * песочницу. Headless невозможен (диалог показывает только foreground-Activity), поэтому — как NFC:
 * выставляем запрос в [FilePickerBridge], выводим [MainActivity] вперёд (её onResume запускает
 * OpenDocument-launcher), ждём выбора, копируем содержимое Uri в рабочую папку. SAF не требует разрешений.
 */
class FilePickerTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "file_picker"
    override val danger = DangerLevel.SAFE // пользователь сам выбирает, что отдать
    override val usesFiles = true
    override val schema =
        """попросить пользователя выбрать файл (системный диалог) и скопировать в рабочую папку; """ +
        """args: {"dir":"папка назначения опц","types":"MIME-фильтр опц, напр. image/* или application/pdf"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val dir = str(argsJson, "dir") ?: "."
        val types = str(argsJson, "types")?.takeIf { it.isNotBlank() }?.let { arrayOf(it) } ?: arrayOf("*/*")

        val deferred = FilePickerBridge.request(types)
        // Вывести приложение вперёд — иначе SAF-диалог не показать.
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("file_picker", true)
                },
            )
        }

        val outcome = withTimeoutOrNull(120_000L) { runCatching { deferred.await() } }
        if (outcome == null) {
            FilePickerBridge.cancel("таймаут выбора файла")
            return ToolResult.Failure("файл не выбран за 2 минуты — попробуйте ещё раз")
        }
        val uri = outcome.getOrElse { return ToolResult.Failure("выбор файла: ${it.message?.take(160)}") }
            ?: return ToolResult.Failure("пользователь отменил выбор файла")

        return withContext(Dispatchers.IO) { copyIntoSandbox(uri, dir) }
    }

    private fun copyIntoSandbox(uri: Uri, dir: String): ToolResult {
        val name = displayName(uri) ?: "file_${System.currentTimeMillis()}"
        val rel = if (dir == "." || dir.isBlank()) name else "$dir/$name"
        val dst = resolve(rel) ?: return ToolResult.Failure("папка назначения вне песочницы")
        return try {
            dst.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return ToolResult.Failure("не удалось открыть выбранный файл")
                dst.outputStream().use { input.copyTo(it) }
            }
            ToolResult.Success("""{"path":"${escapeJson(rel)}","name":"${escapeJson(name)}","bytes":${dst.length()}}""")
        } catch (t: Throwable) {
            ToolResult.Failure("копирование файла: ${t.message?.take(160)}")
        }
    }

    /** Человекочитаемое имя выбранного документа (из ContentResolver), либо null. */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringAfterLast('/') else null
        }
    }.getOrNull()
}

fun filePickerTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(FilePickerTool(context, resolve))
