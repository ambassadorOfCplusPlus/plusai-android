package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.toolsdocs.readDocumentText
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Диск — ЛОКАЛЬНО по OAuth-токену (§7): приложение ходит в REST Drive напрямую,
 * файлы не проходят через наш сервер. Access/refresh в Keystore, брокерятся тем же сервером,
 * что и почта (OAuthBroker, провайдер "gdrive"). Инструменты помечены privateData (содержимое
 * Диска не уходит облачной модели) и usesFiles (гейтятся тумблером доступа к файлам).
 */

/** Хранилище OAuth-доступа к Google Диску в Keystore (общая обвязка — [OAuthStore]). */
object DriveAuth : OAuthStore(provider = "gdrive", keyAccess = "gdrive_access", keyRefresh = "gdrive_refresh")

private const val GDRIVE_NOT_CONNECTED =
    "Google Диск не подключён — подключите в Настройки → Интеграции → Google Диск"

/**
 * Клиент Google Drive REST v3. Access-токен в заголовке «Authorization: Bearer <access>».
 * Все сетевые вызовы — на IO; соединение закрывается в finally; потоки через .use.
 */
private object GDriveApi {
    const val API = "https://www.googleapis.com/drive/v3"
    const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val MAX_DOWNLOAD = 100L shl 20 // 100 МБ — кап на скачиваемый файл
    private const val MAX_UPLOAD = 100L shl 20 // 100 МБ — кап на загрузку

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** JSON/текстовый запрос: возвращает (код, тело). [body]/[contentType] — для POST/DELETE с телом. */
    suspend fun request(
        method: String,
        urlStr: String,
        access: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $access")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10000; readTimeout = 30000
            if (body != null) {
                doOutput = true
                if (contentType != null) setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    /** Скачать содержимое файла в [dest]. Возвращает HTTP-код (для распознавания 401). */
    suspend fun downloadTo(fileId: String, access: String, dest: File): Int = withContext(Dispatchers.IO) {
        val c = (URL("$API/files/${enc(fileId)}?alt=media").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $access")
            connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
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

    /** Multipart-загрузка (метаданные + содержимое). Возвращает (код, тело). */
    suspend fun uploadMultipart(access: String, name: String, src: File): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            if (src.length() > MAX_UPLOAD) error("файл слишком большой (> ${MAX_UPLOAD shr 20} МБ)")
            val boundary = "plusai" + System.nanoTime().toString(16)
            val meta = JSONObject().put("name", name).toString()
            val prefix = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
            val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream()
            bos.write(prefix)
            src.inputStream().use { it.copyTo(bos) }
            bos.write(suffix)
            request(
                "POST", "$UPLOAD/files?uploadType=multipart&fields=id,name",
                access, bos.toByteArray(), "multipart/related; boundary=$boundary",
            )
        }

    fun errorOf(code: Int, body: String): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message")?.ifBlank { null } }
            .getOrNull() ?: "HTTP $code"
}

private fun gdriveField(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** Является ли mime текстовым/документным (для gdrive_read — иначе бинарь в песочницу). */
private fun gdriveReadable(mime: String): Boolean {
    val m = mime.lowercase()
    return m.startsWith("text/") || m.contains("json") || m.contains("xml") || m.contains("csv") ||
        m.contains("pdf") || m.contains("word") || m.contains("officedocument") ||
        m.contains("opendocument") || m.contains("excel") || m.contains("spreadsheet") ||
        m.contains("presentation") || m.contains("rtf")
}

/** gdrive_list — список файлов на Google Диске (id/имя/тип/размер). */
class GDriveListTool(private val context: Context) : AgentTool {
    override val id = "gdrive_list"
    override val danger = DangerLevel.SAFE
    override val privateData = true // содержимое Диска не уходит облачной модели (§7)
    override val schema =
        """список файлов на Google Диске; args: {"query":"по имени (опц)","folder":"id папки (опц)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val drive = DriveAuth.session(context) ?: return ToolResult.Failure(GDRIVE_NOT_CONNECTED)
        val query = gdriveField(argsJson, "query")
        val folder = gdriveField(argsJson, "folder")
        val terms = buildList {
            if (folder != null) add("'${folder.replace("'", "\\'")}' in parents")
            if (query != null) add("name contains '${query.replace("'", "\\'")}'")
            add("trashed = false")
        }
        val q = GDriveApi.enc(terms.joinToString(" and "))
        val url = "${GDriveApi.API}/files?q=$q&pageSize=1000&fields=${GDriveApi.enc("files(id,name,mimeType,size)")}"
        val (code, body) = drive.call({ it.first }) { token -> GDriveApi.request("GET", url, token) }
        if (code !in 200..299) return ToolResult.Failure("Google Диск: ${GDriveApi.errorOf(code, body)}")
        val files = runCatching { JSONObject(body).optJSONArray("files") ?: JSONArray() }.getOrDefault(JSONArray())
        val arr = JSONArray()
        for (i in 0 until files.length()) {
            val o = files.getJSONObject(i)
            arr.put(JSONObject().put("id", o.optString("id")).put("name", o.optString("name"))
                .put("mimeType", o.optString("mimeType")).put("size", o.optLong("size")))
        }
        return ToolResult.Success(JSONObject().put("items", arr).toString())
    }
}

/** gdrive_read — скачать содержимое файла: текст вернуть строкой, бинарь сохранить в песочницу. */
class GDriveReadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "gdrive_read"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // чтение личного файла — подтверждение (кроме bypass)
    override val privateData = true
    override val usesFiles = true
    override val schema = """прочитать файл с Google Диска; args: {"file_id":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val drive = DriveAuth.session(context) ?: return ToolResult.Failure(GDRIVE_NOT_CONNECTED)
        val fileId = gdriveField(argsJson, "file_id") ?: return ToolResult.Failure("нужен args.file_id")

        // Метаданные — узнать имя и mime (чтобы решить: текст или бинарь).
        val metaUrl = "${GDriveApi.API}/files/${GDriveApi.enc(fileId)}?fields=${GDriveApi.enc("id,name,mimeType,size")}"
        val (mc, mb) = drive.call({ it.first }) { token -> GDriveApi.request("GET", metaUrl, token) }
        if (mc !in 200..299) return ToolResult.Failure("Google Диск: ${GDriveApi.errorOf(mc, mb)}")
        val meta = JSONObject(mb)
        val name = meta.optString("name").ifBlank { "file" }
        val mime = meta.optString("mimeType")
        if (mime.startsWith("application/vnd.google-apps"))
            return ToolResult.Failure("это Google-документ (Docs/Sheets/Slides) — прямое скачивание недоступно, экспортируйте его в файл")

        val tmp = File(context.cacheDir, "gdrive_${System.nanoTime()}_$name")
        try {
            val code = drive.call({ it }) { token -> GDriveApi.downloadTo(fileId, token, tmp) }
            if (code !in 200..299) return ToolResult.Failure("Google Диск: скачивание HTTP $code")

            if (gdriveReadable(mime)) {
                val text = runCatching { readDocumentText(context, tmp) }.getOrNull()
                if (text != null) return ToolResult.Success(
                    JSONObject().put("file_id", fileId).put("name", name).put("chars", text.length)
                        .put("text", text.take(200_000)).toString())
            }
            // бинарь (или не распарсился как текст) — сохраняем в песочницу
            val dest = resolve(name) ?: return ToolResult.Failure("путь вне разрешённой папки")
            dest.parentFile?.mkdirs()
            tmp.copyTo(dest, overwrite = true)
            return ToolResult.Success(
                JSONObject().put("file_id", fileId).put("saved", name).put("mimeType", mime)
                    .put("bytes", dest.length()).toString())
        } finally { tmp.delete() }
    }
}

/** gdrive_download — скачать файл с Google Диска в рабочую папку (песочницу). */
class GDriveDownloadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "gdrive_download"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // скачивание личного файла в рабочую папку — подтверждение (§7, как read)
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """скачать файл с Google Диска в рабочую папку; args: {"file_id":"...","out":"имя_файла"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val drive = DriveAuth.session(context) ?: return ToolResult.Failure(GDRIVE_NOT_CONNECTED)
        val fileId = gdriveField(argsJson, "file_id") ?: return ToolResult.Failure("нужен args.file_id")
        val out = gdriveField(argsJson, "out") ?: return ToolResult.Failure("нужен args.out (имя файла в рабочей папке)")
        val dest = resolve(out.trim('/')) ?: return ToolResult.Failure("путь вне разрешённой папки")
        dest.parentFile?.mkdirs()
        val code = drive.call({ it }) { token -> GDriveApi.downloadTo(fileId, token, dest) }
        if (code !in 200..299) return ToolResult.Failure("Google Диск: скачивание HTTP $code")
        return ToolResult.Success(JSONObject().put("saved", out).put("bytes", dest.length()).toString())
    }
}

/** gdrive_upload — загрузить файл из рабочей папки на Google Диск (multipart). */
class GDriveUploadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "gdrive_upload"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // запись во внешнее облако — подтверждение (кроме bypass)
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """загрузить файл из рабочей папки на Google Диск; args: {"path":"файл в рабочей папке","name":"имя на Диске (опц)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val drive = DriveAuth.session(context) ?: return ToolResult.Failure(GDRIVE_NOT_CONNECTED)
        val path = gdriveField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path (файл в рабочей папке)")
        val file = resolve(path) ?: return ToolResult.Failure("путь вне разрешённой папки")
        if (!file.exists() || !file.isFile) return ToolResult.Failure("файла нет: $path")
        val name = gdriveField(argsJson, "name") ?: file.name
        val (code, body) = drive.call({ it.first }) { token -> GDriveApi.uploadMultipart(token, name, file) }
        if (code !in 200..299) return ToolResult.Failure("Google Диск: ${GDriveApi.errorOf(code, body)}")
        val id = runCatching { JSONObject(body).optString("id") }.getOrDefault("")
        return ToolResult.Success(JSONObject().put("uploaded", true).put("id", id).put("name", name).toString())
    }
}

/** gdrive_delete — удалить файл на Google Диске. */
class GDriveDeleteTool(private val context: Context) : AgentTool {
    override val id = "gdrive_delete"
    override val danger = DangerLevel.DANGEROUS // удаление в облаке — необратимое внешнее действие
    override val alwaysConfirm = true
    override val privateData = true
    override val usesFiles = true
    override val schema = """удалить файл на Google Диске; args: {"file_id":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val drive = DriveAuth.session(context) ?: return ToolResult.Failure(GDRIVE_NOT_CONNECTED)
        val fileId = gdriveField(argsJson, "file_id") ?: return ToolResult.Failure("нужен args.file_id")
        val url = "${GDriveApi.API}/files/${GDriveApi.enc(fileId)}"
        val (code, body) = drive.call({ it.first }) { token -> GDriveApi.request("DELETE", url, token) }
        if (code !in 200..299) return ToolResult.Failure("Google Диск: ${GDriveApi.errorOf(code, body)}")
        return ToolResult.Success(JSONObject().put("deleted", true).put("file_id", fileId).toString())
    }
}

fun googleDriveTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(
        GDriveListTool(context), GDriveReadTool(context, resolve), GDriveDownloadTool(context, resolve),
        GDriveUploadTool(context, resolve), GDriveDeleteTool(context),
    )
