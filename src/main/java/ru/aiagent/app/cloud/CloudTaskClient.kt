package ru.aiagent.app.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Клиент ОБЛАЧНЫХ ЗАДАЧ (Фаза 3): создать задачу (запрос + файлы + модель), опросить статус/результат.
 * Задача выполняется на сервере владельца под аккаунтом пользователя (изолированно), списывается
 * комиссия облачной задачи (20% + фикс 5₽, скрыто). Все запросы несут Bearer-токен аккаунта.
 */
object CloudTaskClient {

    /** Реплика диалога задачи. role: user|assistant|plan|question. */
    data class Msg(val role: String, val text: String, val ts: Long)

    data class Task(
        val id: String,
        val status: String,        // running|needs_input|done|error
        val answer: String,
        val error: String,
        val steps: Int,
        val chargeKop: Long,
        val outputs: List<String>,
        val prompt: String,
        val model: String,
        val created: Long,         // unix-время создания (для сортировки «новые сверху»)
        val messages: List<Msg>,   // диалог: задача + план + уточнения + ответы
    )

    /** Файл для загрузки в задачу: имя + байты. */
    data class Upload(val name: String, val bytes: ByteArray)

    private const val SLOW_LINK_MS = 120L // ping выше → «медленная связь» → сжимаем файлы перед отправкой

    // gzip-сжатие байтов (для оценки целесообразности и отправки при медленной связи).
    private fun gzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    // Пинг к серверу (мс) через лёгкий /healthz. Не смогли — считаем связь плохой (сжимаем).
    private fun pingMs(context: Context): Long = runCatching {
        val url = java.net.URL(base(context) + "/healthz")
        val t0 = System.nanoTime()
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 3000; readTimeout = 3000
        }
        try { c.responseCode; (System.nanoTime() - t0) / 1_000_000 } finally { c.disconnect() }
    }.getOrDefault(Long.MAX_VALUE)

    private fun base(context: Context) = (Account.current(context)?.url ?: Account.DEFAULT_URL).trimEnd('/')
    private fun token(context: Context): String? = Account.current(context)?.token?.takeIf { it.isNotBlank() }

    /** Создать задачу. Возвращает созданную (status=running). */
    suspend fun create(context: Context, prompt: String, model: String, files: List<Upload>): Result<Task> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tok = token(context) ?: error("войдите в аккаунт Plus AI")
                // Сжимать ли файлы — решаем по ПИНГУ к серверу: на медленной связи gzip окупается (меньше
                // трафика/быстрее заливка), на быстрой — только зря греем CPU. И не сжимаем то, что уже сжато.
                val slowLink = pingMs(context) > SLOW_LINK_MS
                val arr = JSONArray()
                for (f in files) {
                    var payload = f.bytes
                    var gz = false
                    if (slowLink) {
                        val packed = gzip(f.bytes)
                        if (packed.size.toLong() < f.bytes.size.toLong() * 9 / 10) { payload = packed; gz = true } // выигрыш ≥10%
                    }
                    arr.put(
                        JSONObject()
                            .put("name", f.name)
                            .put("content_base64", android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
                            .apply { if (gz) put("content_gzip", true) },
                    )
                }
                val body = JSONObject().put("prompt", prompt).put("model", model).put("files", arr)
                val (code, resp) = req(context, "POST", "/v1/cloud-task", tok, body.toString())
                if (code !in 200..299) error(errorOf(resp))
                parse(JSONObject(resp))
            }
        }

    /** Статус/результат задачи по id. */
    suspend fun get(context: Context, id: String): Result<Task> = withContext(Dispatchers.IO) {
        runCatching {
            val tok = token(context) ?: error("войдите в аккаунт Plus AI")
            val (code, resp) = req(context, "GET", "/v1/cloud-task/$id", tok, null)
            if (code !in 200..299) error(errorOf(resp))
            parse(JSONObject(resp))
        }
    }

    /** Отправить сообщение в задачу (уточнение / ответ на вопрос / доспрос во время работы). */
    suspend fun postMessage(context: Context, id: String, text: String): Result<Task> = withContext(Dispatchers.IO) {
        runCatching {
            val tok = token(context) ?: error("войдите в аккаунт Plus AI")
            val body = JSONObject().put("text", text)
            val (code, resp) = req(context, "POST", "/v1/cloud-task/$id/message", tok, body.toString())
            if (code !in 200..299) error(errorOf(resp))
            parse(JSONObject(resp))
        }
    }

    /** Список задач пользователя (новые сверху). */
    suspend fun list(context: Context): Result<List<Task>> = withContext(Dispatchers.IO) {
        runCatching {
            val tok = token(context) ?: error("войдите в аккаунт Plus AI")
            val (code, resp) = req(context, "GET", "/v1/cloud-tasks", tok, null)
            if (code !in 200..299) error(errorOf(resp))
            val tasks = JSONObject(resp).optJSONArray("tasks") ?: JSONArray()
            (0 until tasks.length()).mapNotNull { i -> tasks.optJSONObject(i)?.let { parse(it) } }
                .sortedByDescending { it.created } // по времени создания (id сервера рандомный — сортировка по нему бессмысленна)
        }
    }

    private fun parse(o: JSONObject): Task {
        val outs = o.optJSONArray("outputs")
        val msgs = o.optJSONArray("messages")
        return Task(
            id = o.optString("id"),
            status = o.optString("status"),
            answer = o.optString("answer"),
            error = o.optString("error"),
            steps = o.optInt("steps"),
            chargeKop = o.optLong("charge_kop"),
            outputs = if (outs == null) emptyList() else (0 until outs.length()).map { outs.optString(it) },
            prompt = o.optString("prompt"),
            model = o.optString("model"),
            created = o.optLong("created"),
            messages = if (msgs == null) emptyList() else (0 until msgs.length()).mapNotNull { i ->
                msgs.optJSONObject(i)?.let { Msg(it.optString("role"), it.optString("text"), it.optLong("ts")) }
            },
        )
    }

    private fun req(context: Context, method: String, path: String, token: String, body: String?): Pair<Int, String> {
        val fullUrl = base(context) + path
        // Bearer = ключ кошелька: не по plaintext http (как Account.call — защита не дошла до этой копии;
        // ошибочно сохранённый http-URL прокси утёк бы токен). Localhost — для локального теста сервера.
        require(fullUrl.startsWith("https://") || fullUrl.startsWith("http://127.0.0.1") || fullUrl.startsWith("http://localhost")) {
            "небезопасный URL для токена: только https"
        }
        val c = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 60000 // задача выполняется в фоне; ручки быстрые, но с запасом на загрузку файлов
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            return code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun errorOf(body: String): String =
        runCatching { JSONObject(body).optString("error").ifBlank { "ошибка сервера" } }.getOrDefault("ошибка сети")
}
