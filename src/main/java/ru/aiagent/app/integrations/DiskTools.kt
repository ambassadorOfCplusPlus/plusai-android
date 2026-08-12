package ru.aiagent.app.integrations

import android.content.Context
import ru.aiagent.app.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.toolsdocs.readDocumentText
import java.io.File

/**
 * Яндекс.Диск — ЛОКАЛЬНО по OAuth-токену (§7): приложение ходит в REST Диска напрямую,
 * файлы не проходят через наш сервер. Токен в Keystore. Инструменты помечены privateData
 * (содержимое Диска не уходит облачной модели) и usesFiles (гейтятся тумблером доступа к файлам).
 */

/** Хранилище OAuth-доступа к Диску в Keystore (общая обвязка — [OAuthStore]). */
object DiskAuth : OAuthStore(provider = "yandexdisk", keyAccess = "disk_access_token", keyRefresh = "disk_refresh_token")

private const val DISK_NOT_CONNECTED = "Яндекс Диск не подключён — открой Настройки → Интеграции → Яндекс Диск"
private fun diskField(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** disk_list — список файлов/папок в папке Диска (только имена/размеры). */
class DiskListTool(private val context: Context) : AgentTool {
    override val id = "disk_list"
    override val danger = DangerLevel.IMPORTANT
    override val privateData get() = AppSettings.diskCloudMode(context) == "local" // облаку в hybrid/direct
    override val usesFiles = true
    override val schema = """список файлов на Яндекс.Диске; args: {"path":"disk:/Документы"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val disk = DiskAuth.session(context) ?: return ToolResult.Failure(DISK_NOT_CONNECTED)
        val path = diskField(argsJson, "path") ?: "disk:/"
        val res = disk.callResult { token -> YandexDiskClient.list(token, path) }
        val items = res.getOrElse { return ToolResult.Failure("Диск: ${it.message}") }
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().put("name", it.name).put("path", it.path)
                .put("dir", it.isDir).put("size", it.size))
        }
        return ToolResult.Success(JSONObject().put("path", path).put("items", arr).toString())
    }
}

/** disk_read — скачать файл с Диска и извлечь его текст (pdf/docx/xlsx/txt/csv/md). */
class DiskReadTool(private val context: Context) : AgentTool {
    override val id = "disk_read"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // чтение содержимого личного файла — подтверждение (кроме bypass)
    override val privateData get() = AppSettings.diskCloudMode(context) == "local" // облаку в hybrid/direct
    override val usesFiles = true
    override val schema = """прочитать файл с Яндекс.Диска в текст; args: {"path":"disk:/Документы/отчёт.pdf"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val disk = DiskAuth.session(context) ?: return ToolResult.Failure(DISK_NOT_CONNECTED)
        val path = diskField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path")
        val name = path.substringAfterLast('/').ifBlank { "file" }
        val tmp = File(context.cacheDir, "disk_${System.nanoTime()}_$name")
        try {
            val res = disk.callResult { token -> YandexDiskClient.download(token, path, tmp) }
            res.getOrElse { return ToolResult.Failure("Диск: ${it.message}") }
            val text = runCatching { readDocumentText(context, tmp) }
                .getOrElse { return ToolResult.Failure("не удалось прочитать $name: ${it.message}") }
            // HYBRID (§7): содержимое файла читает ЛОКАЛЬНАЯ модель и отдаёт резюме — сырьё не покидает
            // устройство. Нет локальной модели → честный отказ (не сливаем файл).
            if (AppSettings.diskCloudMode(context) == "hybrid") {
                val instr = "Кратко и точно изложи ключевое содержимое файла пользователя (факты, суть, " +
                    "цифры). Не цитируй лишнего.\n\nФайл: $name\n\n${text.take(8000)}"
                val answer = runCatching { ru.aiagent.app.ChatEngine.agentGenerate(context, instr) }
                    .getOrElse {
                        return ToolResult.Failure("для приватного чтения Диска (режим «через локальную модель») нужна " +
                            "локальная модель — установите/выберите её на вкладке Модели, либо переключите режим Диска на «Напрямую»")
                    }
                return ToolResult.Success(JSONObject().put("path", path).put("summary", answer.trim().take(4000)).toString())
            }
            return ToolResult.Success(JSONObject().put("path", path).put("chars", text.length)
                .put("text", text.take(200_000)).toString())
        } finally {
            tmp.delete()
        }
    }
}

/** disk_upload — загрузить файл из рабочей папки приложения на Диск. */
class DiskUploadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "disk_upload"
    override val danger = DangerLevel.DANGEROUS // запись на Диск — необратимое внешнее действие
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.diskCloudMode(context) == "local" // облаку в hybrid/direct
    override val usesFiles = true
    override val schema =
        """загрузить файл из рабочей папки на Яндекс.Диск; args: {"local":"отчёт.docx","path":"disk:/отчёт.docx"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val disk = DiskAuth.session(context) ?: return ToolResult.Failure(DISK_NOT_CONNECTED)
        val local = diskField(argsJson, "local") ?: return ToolResult.Failure("нужен args.local (файл в рабочей папке)")
        val path = diskField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path (куда на Диск)")
        val file = resolve(local) ?: return ToolResult.Failure("путь вне разрешённой папки")
        if (!file.exists() || !file.isFile) return ToolResult.Failure("файла нет: $local")
        val res = disk.callResult { token -> YandexDiskClient.upload(token, path, file) }
        res.getOrElse { return ToolResult.Failure("Диск: ${it.message}") }
        return ToolResult.Success(JSONObject().put("uploaded", true).put("path", path).toString())
    }
}

/** disk_download — скачать файл с Диска в рабочую папку (песочницу), без извлечения текста (§7: только в песочницу). */
class DiskDownloadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "disk_download"
    override val danger = DangerLevel.IMPORTANT
    override val privateData get() = AppSettings.diskCloudMode(context) == "local" // облаку в hybrid/direct
    override val usesFiles = true
    override val schema =
        """скачать файл с Яндекс.Диска в рабочую папку; args: {"path":"disk:/Документы/отчёт.pdf","dir":"загрузки"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val disk = DiskAuth.session(context) ?: return ToolResult.Failure(DISK_NOT_CONNECTED)
        val path = diskField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path")
        val name = path.substringAfterLast('/').ifBlank { "file" }
        val dir = diskField(argsJson, "dir")?.trim('/')
        val local = if (dir.isNullOrBlank()) name else "$dir/$name"
        val dest = resolve(local) ?: return ToolResult.Failure("путь вне разрешённой папки")
        dest.parentFile?.mkdirs()
        val res = disk.callResult { token -> YandexDiskClient.download(token, path, dest) }
        res.getOrElse { return ToolResult.Failure("Диск: ${it.message?.take(160)}") }
        return ToolResult.Success(JSONObject().put("saved", local).put("bytes", dest.length()).toString())
    }
}

/** disk_delete — удалить файл/папку на Диске (в Корзину). */
class DiskDeleteTool(private val context: Context) : AgentTool {
    override val id = "disk_delete"
    override val danger = DangerLevel.DANGEROUS // удаление в облаке — необратимое внешнее действие
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.diskCloudMode(context) == "local" // облаку в hybrid/direct
    override val usesFiles = true
    override val schema = """удалить файл/папку на Яндекс.Диске (в Корзину); args: {"path":"disk:/старое.txt"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val disk = DiskAuth.session(context) ?: return ToolResult.Failure(DISK_NOT_CONNECTED)
        val path = diskField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path")
        val res = disk.callResult { token -> YandexDiskClient.delete(token, path) }
        res.getOrElse { return ToolResult.Failure("Диск: ${it.message?.take(160)}") }
        return ToolResult.Success(JSONObject().put("deleted", true).put("path", path).toString())
    }
}

/** disk_move — переместить/переименовать файл или папку на Диске. */
class DiskMoveTool(private val context: Context) : AgentTool {
    override val id = "disk_move"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.diskCloudMode(context) == "local" // облаку в hybrid/direct
    override val usesFiles = true
    override val schema =
        """переместить/переименовать на Яндекс.Диске; args: {"from":"disk:/старое.txt","to":"disk:/папка/новое.txt"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val disk = DiskAuth.session(context) ?: return ToolResult.Failure(DISK_NOT_CONNECTED)
        val from = diskField(argsJson, "from") ?: return ToolResult.Failure("нужен args.from")
        val to = diskField(argsJson, "to") ?: return ToolResult.Failure("нужен args.to")
        val res = disk.callResult { token -> YandexDiskClient.move(token, from, to) }
        res.getOrElse { return ToolResult.Failure("Диск: ${it.message?.take(160)}") }
        return ToolResult.Success(JSONObject().put("moved", true).put("from", from).put("to", to).toString())
    }
}

/** disk_create_folder — создать папку на Диске. */
class DiskCreateFolderTool(private val context: Context) : AgentTool {
    override val id = "disk_create_folder"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.diskCloudMode(context) == "local" // облаку в hybrid/direct
    override val usesFiles = true
    override val schema = """создать папку на Яндекс.Диске; args: {"path":"disk:/Новая папка"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val disk = DiskAuth.session(context) ?: return ToolResult.Failure(DISK_NOT_CONNECTED)
        val path = diskField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path")
        val res = disk.callResult { token -> YandexDiskClient.mkdir(token, path) }
        res.getOrElse { return ToolResult.Failure("Диск: ${it.message?.take(160)}") }
        return ToolResult.Success(JSONObject().put("created", true).put("path", path).toString())
    }
}

fun diskTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(
        DiskListTool(context), DiskReadTool(context), DiskUploadTool(context, resolve),
        DiskDownloadTool(context, resolve), DiskDeleteTool(context), DiskMoveTool(context),
        DiskCreateFolderTool(context),
    )
