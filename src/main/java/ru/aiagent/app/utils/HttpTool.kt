package ru.aiagent.app.utils

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File
import java.net.URL

// Анти-SSRF клиент — общий для всех сетевых тулов, объявлен в SafeDns.kt (safeHttpClient).
private val safeClient get() = safeHttpClient

private fun normUrl(raw: String): String =
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"

/** http_request — произвольный HTTP-запрос к REST API (GET/POST/PUT/DELETE + заголовки + тело). */
class HttpRequestTool : AgentTool {
    override val id = "http_request"
    override val danger = DangerLevel.IMPORTANT // сетевой запрос наружу — подтверждаем вне bypass
    override val schema =
        """HTTP-запрос к API; args: {"method":"GET|POST|PUT|DELETE","url":"https://…",""" +
            """"headers":{"Authorization":"Bearer …"},"body":"{...}"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ToolResult.Failure("битый JSON args")
        val url = o.optString("url").takeIf { it.isNotBlank() } ?: return ToolResult.Failure("нужен args.url")
        val method = o.optString("method", "GET").uppercase().ifBlank { "GET" }
        val full = normUrl(url)
        if (runCatching { URL(full).host }.getOrNull().isNullOrBlank()) return ToolResult.Failure("плохой url")
        return ru.aiagent.app.code.watchdog(30_000, "http") {
            val builder = Request.Builder().url(full)
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> builder.header(k, h.getString(k)) } }
            val body = o.optString("body")
            // OkHttp: у GET/HEAD тело запрещено; у POST/PUT/PATCH — обязательно (пустое, если модель не дала).
            val reqBody = when {
                body.isNotBlank() && method != "GET" && method != "HEAD" -> body.toRequestBody()
                method in setOf("POST", "PUT", "PATCH") -> "".toRequestBody()
                else -> null
            }
            builder.method(method, reqBody)
            safeClient.newCall(builder.build()).execute().use { resp ->
                // Читаем ОГРАНИЧЕННО (peekBody буферизует ≤N байт), а не .string() целиком — иначе
                // враждебный/огромный ответ кладёт процесс раньше, чем сработает watchdog по времени.
                val text = resp.peekBody(100_000L).string()
                """{"status":${resp.code},"body":"${escapeJson(text)}"}"""
            }
        }.fold(
            onSuccess = { ToolResult.Success(it) },
            onFailure = { ToolResult.Failure("http: ${it.message?.take(200)}") },
        )
    }
}

/** download_file — скачать файл по URL в рабочую папку. */
class DownloadFileTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "download_file"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema = """скачать файл по URL в рабочую папку; args: {"url":"https://…","path":"имя_файла"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ToolResult.Failure("битый JSON args")
        val url = o.optString("url").takeIf { it.isNotBlank() } ?: return ToolResult.Failure("нужен args.url")
        val name = o.optString("path").takeIf { it.isNotBlank() } ?: File(URL(normUrl(url)).path).name.ifBlank { "download.bin" }
        val dest = resolve(name) ?: return ToolResult.Failure("путь вне разрешённой папки")
        val full = normUrl(url)
        if (runCatching { URL(full).host }.getOrNull().isNullOrBlank()) return ToolResult.Failure("плохой url")
        return ru.aiagent.app.code.watchdog(60_000, "download") {
            val tmp = File(dest.parentFile, "${dest.name}.part")
            safeClient.newCall(Request.Builder().url(full).build()).execute().use { resp ->
                try {
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    var total = 0L
                    resp.body!!.byteStream().use { inp -> tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); total += n
                            if (total > 100L * 1024 * 1024) error("файл больше 100 МБ — прервано")
                        }
                    } }
                    if (!tmp.renameTo(dest)) error("не удалось сохранить")
                    """{"saved":"${escapeJson(name)}","bytes":$total}"""
                } catch (e: Exception) { tmp.delete(); throw e }
            }
        }.fold(
            onSuccess = { ToolResult.Success(it) },
            onFailure = { ToolResult.Failure("download: ${it.message?.take(200)}") },
        )
    }
}
