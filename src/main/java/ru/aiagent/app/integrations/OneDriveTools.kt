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
import java.net.URLEncoder

/**
 * OneDrive — ЛОКАЛЬНО по OAuth-токену (§7): приложение ходит в MS Graph v1.0 напрямую,
 * файлы не проходят через наш сервер. Access/refresh в Keystore, брокерятся тем же сервером,
 * что и почта (OAuthBroker, провайдер "onedrive"). Инструменты помечены privateData и usesFiles.
 */

/** Хранилище OAuth-доступа к OneDrive в Keystore (общая обвязка — [OAuthStore]). */
object OneDriveAuth : OAuthStore(provider = "onedrive", keyAccess = "onedrive_access", keyRefresh = "onedrive_refresh")

private const val ONEDRIVE_NOT_CONNECTED =
    "OneDrive не подключён — подключите в Настройки → Интеграции → OneDrive"

/**
 * Клиент MS Graph v1.0. Все эндпоинты на graph.microsoft.com (JSON или content).
 * Bearer-токен. IO/finally/.use. Адресация по id (/me/drive/items/{id}) либо по пути
 * (/me/drive/root:/{path}:/...).
 */
private object OneDriveApi {
    const val API = "https://graph.microsoft.com/v1.0"
    private const val MAX_DOWNLOAD = 100L shl 20 // 100 МБ
    private const val MAX_UPLOAD = 60L shl 20 // 60 МБ (лимит простого PUT-upload на /content)
    private const val MAX_READ = 1L shl 20 // 1 МБ на чтение текстового файла в ответ

    /** URL-кодирование одного сегмента (пробелы как %20, не +). */
    private fun encSeg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Кодирование пути с сохранением разделителей '/'. */
    fun encPath(path: String): String =
        path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encSeg(it) }

    fun childrenUrl(path: String): String {
        val p = encPath(path)
        return if (p.isEmpty()) "$API/me/drive/root/children" else "$API/me/drive/root:/$p:/children"
    }

    fun itemContentByIdUrl(id: String): String = "$API/me/drive/items/${encSeg(id)}/content"
    fun contentByPathUrl(path: String): String = "$API/me/drive/root:/${encPath(path)}:/content"
    fun itemByIdUrl(id: String): String = "$API/me/drive/items/${encSeg(id)}"

    /** GET JSON. Возвращает (код, тело). */
    suspend fun getJson(url: String, access: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $access")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10000; readTimeout = 30000
            }
            try {
                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                code to (s?.bufferedReader()?.use { it.readText() } ?: "")
            } finally { c.disconnect() }
        }

    /** GET content → стрим в [dest]. Возвращает HTTP-код (для распознавания 401). */
    suspend fun downloadTo(url: String, access: String, dest: File): Int =
        withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $access")
                instanceFollowRedirects = true
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

    /** GET content как текст (для чтения небольшого файла в ответ). Возвращает (код, тело/текст). */
    suspend fun readText(url: String, access: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $access")
                instanceFollowRedirects = true
                connectTimeout = 15000; readTimeout = 60000
            }
            try {
                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                if (code in 200..299) {
                    val sb = StringBuilder()
                    val buf = ByteArray(1 shl 16); var total = 0L
                    s?.use { inp ->
                        val reader = inp.bufferedReader()
                        val cbuf = CharArray(1 shl 15)
                        while (true) {
                            val n = reader.read(cbuf); if (n < 0) break
                            total += n
                            if (total > MAX_READ) error("файл слишком большой для чтения (> ${MAX_READ shr 20} МБ) — используйте onedrive_download")
                            sb.append(cbuf, 0, n)
                        }
                    }
                    code to sb.toString()
                } else {
                    code to (s?.bufferedReader()?.use { it.readText() } ?: "")
                }
            } finally { c.disconnect() }
        }

    /** PUT content ← содержимое [src]. Возвращает (код, тело). */
    suspend fun upload(url: String, access: String, src: File): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            if (src.length() > MAX_UPLOAD) error("файл слишком большой (> ${MAX_UPLOAD shr 20} МБ)")
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"; doOutput = true
                setRequestProperty("Authorization", "Bearer $access")
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

    /** DELETE элемента. Возвращает (код, тело). */
    suspend fun delete(url: String, access: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Authorization", "Bearer $access")
                connectTimeout = 10000; readTimeout = 30000
            }
            try {
                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                code to (s?.bufferedReader()?.use { it.readText() } ?: "")
            } finally { c.disconnect() }
        }

    fun errorOf(code: Int, body: String): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message")?.ifBlank { null } }.getOrNull()
            ?: body.take(160).ifBlank { "HTTP $code" }
}

private fun oneDriveField(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()


/** onedrive_list — список файлов/папок в папке OneDrive. */
class OneDriveListTool(private val context: Context) : AgentTool {
    override val id = "onedrive_list"
    override val danger = DangerLevel.SAFE
    override val privateData = true // содержимое OneDrive не уходит облачной модели (§7)
    override val schema = """список файлов в папке OneDrive; args: {"path":"/Документы (опц, корень по умолчанию)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val onedrive = OneDriveAuth.session(context) ?: return ToolResult.Failure(ONEDRIVE_NOT_CONNECTED)
        val path = oneDriveField(argsJson, "path") ?: ""
        val url = OneDriveApi.childrenUrl(path)
        val (code, body) = onedrive.call({ it.first }) { token -> OneDriveApi.getJson(url, token) }
        if (code !in 200..299) return ToolResult.Failure("OneDrive: ${OneDriveApi.errorOf(code, body)}")
        val entries = runCatching { JSONObject(body).optJSONArray("value") ?: JSONArray() }.getOrDefault(JSONArray())
        val arr = JSONArray()
        for (i in 0 until entries.length()) {
            val o = entries.getJSONObject(i)
            arr.put(JSONObject().put("id", o.optString("id"))
                .put("name", o.optString("name"))
                .put("dir", o.has("folder"))
                .put("size", o.optLong("size")))
        }
        return ToolResult.Success(JSONObject().put("path", path.trim('/')).put("items", arr).toString())
    }
}

/** onedrive_read — прочитать небольшой текстовый файл из OneDrive (по id или пути). */
class OneDriveReadTool(private val context: Context) : AgentTool {
    override val id = "onedrive_read"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // §7: чтение ЛИЧНОГО файла — подтверждение в любом режиме кроме bypass.
    // Без него IMPORTANT в режиме AUTO проходит как ALLOW (DangerPolicy.decide) и содержимое приватного
    // файла утекало бы в транскрипт без спроса. Родственные gdrive_read/disk_read/onedrive_download — с флагом.
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """прочитать небольшой текстовый файл из OneDrive; args: {"id":"id элемента"} ИЛИ {"path":"/путь/в/onedrive"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val onedrive = OneDriveAuth.session(context) ?: return ToolResult.Failure(ONEDRIVE_NOT_CONNECTED)
        val idArg = oneDriveField(argsJson, "id")
        val pathArg = oneDriveField(argsJson, "path")
        val url = when {
            idArg != null -> OneDriveApi.itemContentByIdUrl(idArg)
            pathArg != null -> OneDriveApi.contentByPathUrl(pathArg)
            else -> return ToolResult.Failure("нужен args.id или args.path")
        }
        val (code, body) = onedrive.call({ it.first }) { token -> OneDriveApi.readText(url, token) }
        if (code !in 200..299) return ToolResult.Failure("OneDrive: ${OneDriveApi.errorOf(code, body)}")
        return ToolResult.Success(JSONObject().put("content", body).toString())
    }
}

/** onedrive_download — скачать файл из OneDrive в рабочую папку (песочницу). */
class OneDriveDownloadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "onedrive_download"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // скачивание личного файла в рабочую папку — подтверждение (§7, как read)
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """скачать файл из OneDrive в рабочую папку; args: {"id":"id элемента" ИЛИ "path":"/путь/в/onedrive","out":"имя_файла"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val onedrive = OneDriveAuth.session(context) ?: return ToolResult.Failure(ONEDRIVE_NOT_CONNECTED)
        val idArg = oneDriveField(argsJson, "id")
        val pathArg = oneDriveField(argsJson, "path")
        val url = when {
            idArg != null -> OneDriveApi.itemContentByIdUrl(idArg)
            pathArg != null -> OneDriveApi.contentByPathUrl(pathArg)
            else -> return ToolResult.Failure("нужен args.id или args.path")
        }
        val defName = pathArg?.substringAfterLast('/')?.ifBlank { null } ?: "file"
        val out = oneDriveField(argsJson, "out") ?: defName
        val dest = resolve(out.trim('/')) ?: return ToolResult.Failure("путь вне разрешённой папки")
        dest.parentFile?.mkdirs()
        val code = onedrive.call({ it }) { token -> OneDriveApi.downloadTo(url, token, dest) }
        if (code !in 200..299) return ToolResult.Failure("OneDrive: скачивание HTTP $code")
        return ToolResult.Success(JSONObject().put("saved", out).put("bytes", dest.length()).toString())
    }
}

/** onedrive_upload — загрузить файл из рабочей папки в OneDrive. */
class OneDriveUploadTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "onedrive_upload"
    override val danger = DangerLevel.DANGEROUS // запись во внешнее облако — необратимое внешнее действие
    override val alwaysConfirm = true
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """загрузить файл из рабочей папки в OneDrive; args: {"path":"файл в рабочей папке","onedrive_path":"/куда/в/onedrive"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val onedrive = OneDriveAuth.session(context) ?: return ToolResult.Failure(ONEDRIVE_NOT_CONNECTED)
        val path = oneDriveField(argsJson, "path") ?: return ToolResult.Failure("нужен args.path (файл в рабочей папке)")
        val file = resolve(path) ?: return ToolResult.Failure("путь вне разрешённой папки")
        if (!file.exists() || !file.isFile) return ToolResult.Failure("файла нет: $path")
        var dst = oneDriveField(argsJson, "onedrive_path") ?: "/${file.name}"
        if (!dst.startsWith("/")) dst = "/$dst"
        val url = OneDriveApi.contentByPathUrl(dst)
        val (code, body) = onedrive.call({ it.first }) { token -> OneDriveApi.upload(url, token, file) }
        if (code !in 200..299) return ToolResult.Failure("OneDrive: ${OneDriveApi.errorOf(code, body)}")
        val saved = runCatching { JSONObject(body).optString("name").ifBlank { dst } }.getOrDefault(dst)
        return ToolResult.Success(JSONObject().put("uploaded", true).put("name", saved).toString())
    }
}

/** onedrive_delete — удалить файл/папку в OneDrive (по id). */
class OneDriveDeleteTool(private val context: Context) : AgentTool {
    override val id = "onedrive_delete"
    override val danger = DangerLevel.DANGEROUS // удаление в облаке — необратимое внешнее действие
    override val alwaysConfirm = true
    override val privateData = true
    override val usesFiles = true
    override val schema = """удалить файл/папку в OneDrive; args: {"id":"id элемента"} ИЛИ {"path":"/путь/в/onedrive"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val onedrive = OneDriveAuth.session(context) ?: return ToolResult.Failure(ONEDRIVE_NOT_CONNECTED)
        val idArg = oneDriveField(argsJson, "id")
        val pathArg = oneDriveField(argsJson, "path")
        val url = when {
            idArg != null -> OneDriveApi.itemByIdUrl(idArg)
            // Адресация САМОГО элемента (не связи) — root:/{path} БЕЗ хвостового двоеточия. С ним
            // (root:/{path}:) Graph отвечает 400: после ':' обязан идти сегмент связи (/content и т.п.).
            pathArg != null -> "${OneDriveApi.API}/me/drive/root:/${OneDriveApi.encPath(pathArg)}"
            else -> return ToolResult.Failure("нужен args.id или args.path")
        }
        val (code, body) = onedrive.call({ it.first }) { token -> OneDriveApi.delete(url, token) }
        if (code !in 200..299) return ToolResult.Failure("OneDrive: ${OneDriveApi.errorOf(code, body)}")
        return ToolResult.Success(JSONObject().put("deleted", true).put("target", idArg ?: pathArg).toString())
    }
}

fun oneDriveTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(
        OneDriveListTool(context), OneDriveReadTool(context),
        OneDriveDownloadTool(context, resolve), OneDriveUploadTool(context, resolve),
        OneDriveDeleteTool(context),
    )
