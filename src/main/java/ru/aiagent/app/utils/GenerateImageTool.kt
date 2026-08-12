package ru.aiagent.app.utils

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.AppSettings
import ru.aiagent.app.cloud.CloudEngine
import ru.aiagent.app.cloud.CloudFunctionAgent
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import okhttp3.Request
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * generate_image — генерация картинки по тексту через image-модели RouterAI (мультимодальные
 * image+text отдают картинку прямо в /v1/chat/completions). Идёт ЧЕРЕЗ прокси владельца
 * ([CloudFunctionAgent.endpoint] = base+Bearer кошелька) — ключей не нужно, тратится тот же кошелёк
 * (с наценкой/метрингом §3.13.1), поэтому функция ПЛАТНАЯ: по умолчанию выключена, включается
 * пользователем в настройках вместе с выбором модели ([AppSettings.imageGenEnabled]/[imageModel]).
 *
 * DangerLevel.IMPORTANT + alwaysConfirm — тратит деньги, подтверждаем в любом режиме кроме bypass.
 */
class GenerateImageTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "generate_image"
    override val danger = DangerLevel.IMPORTANT // тратит деньги (облачный вызов)
    override val alwaysConfirm = true           // никогда не генерируем молча (кроме bypass)
    override val usesFiles = true               // пишет файл картинки в песочницу
    override val schema =
        """сгенерировать картинку по тексту (image-модель, платно, тот же кошелёк); """ +
        """args: {"prompt":"что нарисовать","out":"имя файла опц, по умолч. image.png",""" +
        """"model":"id image-модели опц — иначе из настроек"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        if (!AppSettings.imageGenEnabled(context)) {
            return@withContext ToolResult.Failure(
                "генерация картинок выключена — включите её в настройках и выберите модель",
            )
        }
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON args")
        val prompt = o.optString("prompt").takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Failure("нужен args.prompt (описание картинки)")
        val model = o.optString("model").takeIf { it.isNotBlank() } ?: AppSettings.imageModel(context)
        val name = o.optString("out").takeIf { it.isNotBlank() }?.substringAfterLast('/') ?: "image.png"
        val dest = resolve(name) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        runCatching { dest.parentFile?.mkdirs() }

        // endpoint()==null и когда включён «Свой API» (proxyEndpoint уступает ему приоритет): генерация
        // картинок идёт только через облако владельца (нужен кошелёк), а не через чужой чат-endpoint.
        val (base, token) = CloudFunctionAgent.endpoint(context)
            ?: return@withContext ToolResult.Failure(
                if (CloudEngine.customEnabled(context)) "генерация картинок недоступна в режиме «Свой API» — она работает только через облако владельца"
                else "нужен вход в аккаунт (облачный прокси)",
            )
        val url = "${base.trimEnd('/')}/v1/chat/completions"
        // Bearer = токен кошелька: по plaintext http не шлём (только https / локальный прокси).
        if (!(url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) {
            return@withContext ToolResult.Failure("небезопасный URL прокси (нужен https)")
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("modalities", JSONArray().put("image").put("text"))
            .put("stream", false)
            .toString()

        val text = runCatching { post(url, token, body) }
            .getOrElse { return@withContext ToolResult.Failure("генерация: ${it.message?.take(200)}") }

        val src = extractImageUrl(text)
            ?: return@withContext ToolResult.Failure("модель не вернула картинку (ответ без image)")

        val bytes = runCatching { fetchBytes(src) }
            .getOrElse { return@withContext ToolResult.Failure("сохранение картинки: ${it.message?.take(160)}") }
        if (bytes.isEmpty()) return@withContext ToolResult.Failure("картинка пустая")

        runCatching { dest.writeBytes(bytes) }
            .getOrElse { return@withContext ToolResult.Failure("не удалось записать файл: ${it.message?.take(120)}") }

        ToolResult.Success(
            """{"path":"${escapeJson(name)}","model":"${escapeJson(model)}","bytes":${bytes.size}}""",
        )
    }

    private fun post(url: String, token: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 20_000
            readTimeout = 120_000 // генерация картинки долгая
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${resp.take(160)}")
            return resp
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Достаёт из ответа /v1/chat/completions строку-источник картинки (data:image/...;base64,... либо
     * http(s)-URL). Возможные места (разные провайдеры): choices[].message.images[].image_url.url;
     * message.content как data-URL/URL строкой; message.content как массив частей (image_url.url);
     * b64_json (в message или choices[]). Возвращает первую найденную строку или null.
     */
    private fun extractImageUrl(responseText: String): String? {
        val root = runCatching { JSONObject(responseText) }.getOrNull() ?: return null
        val choices = root.optJSONArray("choices") ?: return null
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            val msg = choice.optJSONObject("message") ?: continue
            // 1) message.images[] → image_url.url
            msg.optJSONArray("images")?.let { imgs ->
                for (j in 0 until imgs.length()) {
                    val im = imgs.optJSONObject(j) ?: continue
                    val u = im.optJSONObject("image_url")?.optString("url")?.takeIf { it.isNotBlank() }
                        ?: im.optString("url").takeIf { it.isNotBlank() }
                    if (u != null) return u
                }
            }
            // 2) message.content — строка (data:.../http) или массив частей
            when (val content = msg.opt("content")) {
                is String -> extractFromString(content)?.let { return it }
                is JSONArray -> {
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        val u = part.optJSONObject("image_url")?.optString("url")?.takeIf { it.isNotBlank() }
                            ?: part.optString("image_url").takeIf { it.isNotBlank() }
                        if (u != null) return u
                    }
                }
            }
            // 3) b64_json (нестандартно для chat, но встречается)
            msg.optString("b64_json").takeIf { it.isNotBlank() }?.let { return "data:image/png;base64,$it" }
            choice.optString("b64_json").takeIf { it.isNotBlank() }?.let { return "data:image/png;base64,$it" }
        }
        return null
    }

    private fun extractFromString(s: String): String? {
        val t = s.trim()
        if (t.startsWith("data:image")) return t
        Regex("data:image/[\\w.+-]+;base64,[A-Za-z0-9+/=]+").find(t)?.let { return it.value }
        Regex("https?://\\S+\\.(?:png|jpe?g|webp|gif)", RegexOption.IGNORE_CASE).find(t)?.let { return it.value }
        return null
    }

    /** Источник → байты: data:base64 декодируем; http(s) — скачиваем. */
    private fun fetchBytes(src: String): ByteArray {
        if (src.startsWith("data:")) {
            val b64 = src.substringAfter(",", "")
            if (b64.isBlank()) throw RuntimeException("пустой data-URL")
            return Base64.decode(b64, Base64.DEFAULT)
        }
        // URL картинки приходит из ответа облачной модели (недоверенный) — качаем через общий анти-SSRF
        // клиент (safeHttpClient/SafeDns), а НЕ напрямую: иначе prompt-injection увёл бы запрос на
        // внутренний адрес (169.254.169.254 / 192.168.x.x) в обход защиты остальных сетевых тулов.
        safeHttpClient.newCall(Request.Builder().url(src).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("пустой ответ")
            return body.byteStream().use { inp ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); total += n
                    if (total > 50L * 1024 * 1024) throw RuntimeException("картинка больше 50 МБ")
                }
                out.toByteArray()
            }
        }
    }
}

/** Фабрика инструмента генерации картинок (регистрируется как остальные utils-тулы). */
fun generateImageTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(GenerateImageTool(context, resolve))
