package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dropbox — ЛОКАЛЬНО по OAuth-токену (§7): приложение ходит в Dropbox API v2 напрямую,
 * файлы не проходят через наш сервер. Access/refresh в Keystore, брокерятся тем же сервером,
 * что и почта (OAuthBroker, провайдер "dropbox"). Инструменты помечены privateData и usesFiles.
 */

/** Хранилище OAuth-доступа к Dropbox в Keystore (общая обвязка — [OAuthStore]). */
object DropboxAuth : OAuthStore(provider = "dropbox", keyAccess = "dropbox_access", keyRefresh = "dropbox_refresh")

private const val DROPBOX_NOT_CONNECTED =
    "Dropbox не подключён — подключите в Настройки → Интеграции → Dropbox"

/**
 * Клиент Dropbox API v2. RPC-эндпоинты на api.dropboxapi.com (JSON), content на content.dropboxapi.com
 * (файл в теле + аргумент в заголовке Dropbox-API-Arg). Bearer-токен. IO/finally/.use.
 */
private object DropboxApi {
    const val API = "https://api.dropboxapi.com/2"
    const val CONTENT = "https://content.dropboxapi.com/2"
    private const val MAX_DOWNLOAD = 100L shl 20 // 100 МБ
    private const val MAX_UPLOAD = 150L shl 20 // 150 МБ (лимит single-request upload)

    /** Dropbox-API-Arg обязан быть ASCII — экранируем не-ASCII символы как \uXXXX. */
    fun asciiJson(o: JSONObject): String {
        val s = o.toString()
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch.code < 0x80) sb.append(ch) else sb.append("\\u%04x".format(ch.code))
        return sb.toString()
    }

    /** RPC-запрос (JSON тело/ответ). Возвращает (код, тело). */
    suspend fun rpc(path: String, arg: JSONObject, access: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = (URL("$API$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", "Bearer $access")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000; readTimeout = 30000
            }
            try {
                c.outputStream.use { it.write(arg.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                code to (s?.bufferedReader()?.use { it.readText() } ?: "")
            } finally { c.disconnect() }
        }

    /** files/download → стрим в [dest]. Возвращает HTTP-код (для распознавания 401). */
    suspend fun downloadTo(dropboxPath: String, access: String, dest: File): Int =
        withContext(Dispatchers.IO) {
            val c = (URL("$CONTENT/files/download").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $access")
                setRequestProperty("Dropbox-API-Arg", asciiJson(JSONObject().put("path", dropboxPath)))
                connectTimeout = 15000; readTimeout = 60000
            }
            try {
                val code = c.responseCode
                if (code in 200..299) {
                    dest.outputStream().use { out ->
                        c.inputStream.use { inp ->
                            val buf = ByteArray(1 shl 16); var total = 0L
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                total += n
                                if (total > MAX_DOWNLOAD) { dest.delete(); error("файл слишком большой (> ${MAX_DOWNLOAD shr 20} МБ)") }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                code
            } finally { c.disconnect() }
        }

    /** files/upload ← содержимое [src]. Возвращает (код, тело). */
    suspend fun upload(dropboxPath: String, access: String, src: File): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            if (src.length() > MAX_UPLOAD) error("файл слишком большой (> ${MAX_UPLOAD shr 20} МБ)")
            val arg = JSONObject().put("path", dropboxPath).put("mode", "overwrite").put("autorename", false)
            val c = (URL("$CONTENT/files/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", "Bearer $access")
                setRequestProperty("Dropbox-API-Arg", asciiJson(arg))
                setRequestProperty("Content-Type", "application/octet-stream")
                connectTimeout = 15000; readTimeout = 60000
                setFixedLengthStreamingMode(src.length())
            }
            try {
                src.inputStream().use { inp -> c.outputStream.use { inp.copyTo(it) } }
                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                code to (s?.bufferedReader()?.use { it.readText() } ?: "")
            } finally { c.disconnect() }
        }

    fun errorOf(code: Int, body: String): String =
        runCatching { JSONObject(body).optString("error_summary").ifBlank { null } }.getOrNull()
            ?: body.take(160).ifBlank { "HTTP $code" }
}

private fun dropboxField(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** dropbox_list — список файлов/папок в папке Dropbox. */
class DropboxListTool(private val context: Context) : AgentTool {
    override val id = "dropbox_list"
    override val danger = DangerLevel.SAFE
    override val privateData = true // содержимое Dropbox не уходит облачной модели (§7)
    override val schema = """список файлов в папке Dropbox; args: {"folder":"/Документы (опц, корень по умолчанию)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val dropbox = DropboxAuth.session(context) ?: return ToolResult.Failure(DROPBOX_NOT_CONNECTED)
        // корень Dropbox — пустая строка (не "/")
        val raw = dropboxField(argsJson, "folder")?.trimEnd('/') ?: ""
        val folder = if (raw == "" || raw == "/") "" else raw
        val arg = JSONObject().put("path", folder).put("recursive", false)
        val (code, body) = dropbox.call({ it.first }) { token -> DropboxApi.rpc("/files/list_folder", arg, token) }
        if (code !in 200..299) return ToolResult.Failure("Dropbox: ${DropboxApi.errorOf(code, body)}")
        val entries = runCatching { JSONObject(body).optJSONArray("entries") ?: JSONArray() }.getOrDefault(JSONArray())
        val arr = JSONArray()
        for (i in 0 until entries.length()) {
            val o = entries.getJSONObject(i)
            arr.put(JSONObject().put("name", o.optString("name"))
                .put("path", o.optString("path_display"))
                .put("dir", o.optString(".tag") == "folder")
                .put("size", o.optLong("size")))
        }
        return ToolResult.Success(JSONObject().put("folder", folder).put("items", arr).toString())
    }
}

/** dropbox_download — скачать файл из Dropbox в рабочую папку (песочницу). */
class DropboxDownloadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "dropbox_download"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // скачивание личного файла в рабочую папку — подтверждение (§7, как read)
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """скачать файл из Dropbox в рабочую папку; args: {"path":"/путь/в/dropbox","out":"имя_файла"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val dropbox = DropboxAuth.session(context) ?: return ToolResult.Failure(DROPBOX_NOT_CONNECTED)
        val path = dropboxField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path (путь в Dropbox)")
        val out = dropboxField(argsJson, "out") ?: path.substringAfterLast('/').ifBlank { "file" }
        val dest = resolve(out.trim('/')) ?: return ToolResult.Failure("путь вне разрешённой папки")
        dest.parentFile?.mkdirs()
        val code = dropbox.call({ it }) { token -> DropboxApi.downloadTo(path, token, dest) }
        if (code !in 200..299) return ToolResult.Failure("Dropbox: скачивание HTTP $code")
        return ToolResult.Success(JSONObject().put("saved", out).put("bytes", dest.length()).toString())
    }
}

/** dropbox_upload — загрузить файл из рабочей папки в Dropbox. */
class DropboxUploadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "dropbox_upload"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // запись во внешнее облако — подтверждение (кроме bypass)
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """загрузить файл из рабочей папки в Dropbox; args: {"path":"файл в рабочей папке","dropbox_path":"/куда/в/dropbox"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val dropbox = DropboxAuth.session(context) ?: return ToolResult.Failure(DROPBOX_NOT_CONNECTED)
        val path = dropboxField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path (файл в рабочей папке)")
        val file = resolve(path) ?: return ToolResult.Failure("путь вне разрешённой папки")
        if (!file.exists() || !file.isFile) return ToolResult.Failure("файла нет: $path")
        var dbx = dropboxField(argsJson, "dropbox_path") ?: "/${file.name}"
        if (!dbx.startsWith("/")) dbx = "/$dbx"
        val (code, body) = dropbox.call({ it.first }) { token -> DropboxApi.upload(dbx, token, file) }
        if (code !in 200..299) return ToolResult.Failure("Dropbox: ${DropboxApi.errorOf(code, body)}")
        val saved = runCatching { JSONObject(body).optString("path_display").ifBlank { dbx } }.getOrDefault(dbx)
        return ToolResult.Success(JSONObject().put("uploaded", true).put("path", saved).toString())
    }
}

/** dropbox_delete — удалить файл/папку в Dropbox. */
class DropboxDeleteTool(private val context: Context) : AgentTool {
    override val id = "dropbox_delete"
    override val danger = DangerLevel.DANGEROUS // удаление в облаке — необратимое внешнее действие
    override val alwaysConfirm = true
    override val privateData = true
    override val usesFiles = true
    override val schema = """удалить файл/папку в Dropbox; args: {"path":"/путь/в/dropbox"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val dropbox = DropboxAuth.session(context) ?: return ToolResult.Failure(DROPBOX_NOT_CONNECTED)
        val path = dropboxField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path")
        val arg = JSONObject().put("path", path)
        val (code, body) = dropbox.call({ it.first }) { token -> DropboxApi.rpc("/files/delete_v2", arg, token) }
        if (code !in 200..299) return ToolResult.Failure("Dropbox: ${DropboxApi.errorOf(code, body)}")
        return ToolResult.Success(JSONObject().put("deleted", true).put("path", path).toString())
    }
}

fun dropboxTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(
        DropboxListTool(context), DropboxDownloadTool(context, resolve),
        DropboxUploadTool(context, resolve), DropboxDeleteTool(context),
    )
