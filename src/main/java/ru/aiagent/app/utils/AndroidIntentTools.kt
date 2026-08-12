package ru.aiagent.app.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File

/**
 * Инструменты запуска Android-интентов и шаринга файлов из песочницы.
 * Дают агенту доступ к системным действиям (звонок, карты, произвольное приложение)
 * и к системному меню «Поделиться» для отдачи файла наружу через FileProvider.
 */

/**
 * intent_send — запустить произвольный Android Intent.
 *
 * DANGEROUS: интент может позвонить, открыть платёж, запустить любое приложение —
 * поэтому подтверждение обязательно во всех режимах, кроме bypass.
 */
class IntentSendTool(private val context: Context) : AgentTool {
    override val id = "intent_send"
    override val danger = DangerLevel.DANGEROUS
    override val alwaysConfirm = true
    override val schema =
        """запустить Android Intent (звонок, карты, любое приложение); args: {"action":"android.intent.action.VIEW|DIAL|SENDTO|...","data":"tel:+7...|geo:..|https://..","type":"опц mime","extras":{"опц":"строки"}}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val action = str(argsJson, "action") ?: return ToolResult.Failure("нет args.action")
        val data = str(argsJson, "data")
        val type = str(argsJson, "type")
        return try {
            val intent = Intent(action)
            val uri = data?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            when {
                uri != null && !type.isNullOrBlank() -> intent.setDataAndType(uri, type)
                uri != null -> intent.setData(uri)
                !type.isNullOrBlank() -> intent.setType(type)
            }
            // extras — строковые поля объекта кладём как putExtra(String).
            runCatching {
                val obj = JSONObject(argsJson).optJSONObject("extras")
                if (obj != null) {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        intent.putExtra(k, obj.optString(k))
                    }
                }
            }
            // Запуск из non-Activity контекста требует NEW_TASK.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ok("launched" to action)
        } catch (e: ActivityNotFoundException) {
            ToolResult.Failure("нет приложения для action=$action: ${e.message}")
        } catch (t: Throwable) {
            ToolResult.Failure("intent_send: ${t.message}")
        }
    }
}

/**
 * share_file — поделиться файлом из песочницы через системное меню «Поделиться».
 *
 * Файл отдаётся наружу как content:// Uri через FileProvider (реальный доступ к файлу
 * получателю выдаётся временным grant'ом), поэтому usesFiles=true и уровень IMPORTANT.
 */
class ShareFileTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "share_file"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """поделиться файлом из песочницы (системное меню «Поделиться»); args: {"path":"file.pdf","text":"опц подпись"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val path = str(argsJson, "path") ?: return ToolResult.Failure("нет args.path")
        val file = resolve(path) ?: return ToolResult.Failure("путь вне песочницы: $path")
        if (!file.exists()) return ToolResult.Failure("файл не найден: $path")
        val text = str(argsJson, "text")
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!text.isNullOrBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
            val chooser = Intent.createChooser(send, "Поделиться")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            ok("shared" to file.name)
        } catch (e: ActivityNotFoundException) {
            ToolResult.Failure("нет приложения для «Поделиться»: ${e.message}")
        } catch (t: Throwable) {
            ToolResult.Failure("share_file: ${t.message}")
        }
    }
}

/** Фабрика инструментов интентов/шаринга. */
fun androidIntentTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(
        IntentSendTool(context),
        ShareFileTool(context, resolve),
    )
