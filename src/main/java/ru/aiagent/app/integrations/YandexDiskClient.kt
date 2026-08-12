package ru.aiagent.app.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Клиент Яндекс.Диска (REST cloud-api.yandex.net, §3.10). Ходит НАПРЯМУЮ с устройства
 * по OAuth-токену (заголовок «OAuth <token>») — файлы Диска не проходят через наш сервер (§7).
 * Токен получаем тем же брокером, что и почту (см. OAuthBroker, провайдер "yandexdisk").
 */
object YandexDiskClient {
    private const val API = "https://cloud-api.yandex.net/v1/disk"
    private const val MAX_LIST = 2000 // кап на суммарный список папки (защита от гигантской папки)
    private const val MAX_DOWNLOAD = 100L shl 20 // 100 МБ — кап на скачиваемый с Диска файл

    data class Item(val name: String, val path: String, val isDir: Boolean, val size: Long, val mime: String)

    // URLEncoder кодирует пробел как '+', а в значении query-параметра пути Диска нужен %20
    // (иначе «disk:/Мои документы» ломается). Правим на percent-encoding.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Список ресурсов в папке [path] (по умолчанию корень «disk:/»). Пагинация по offset: без неё папка
     *  >[pageSize] элементов молча обрезалась, и агент мог решить, что файла нет. Кап [MAX_LIST] на всякий. */
    suspend fun list(token: String, path: String, pageSize: Int = 100): Result<List<Item>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val out = ArrayList<Item>()
                var offset = 0
                while (out.size < MAX_LIST) {
                    val (code, body) = req("GET", "$API/resources?path=${enc(path)}&limit=$pageSize&offset=$offset&sort=name", token)
                    if (code !in 200..299) error(errorOf(code, body))
                    val embedded = JSONObject(body).optJSONObject("_embedded") ?: break
                    val items = embedded.optJSONArray("items") ?: break
                    if (items.length() == 0) break
                    for (i in 0 until items.length()) {
                        val o = items.getJSONObject(i)
                        out.add(Item(
                            o.optString("name"), o.optString("path"),
                            o.optString("type") == "dir", o.optLong("size"), o.optString("mime_type"),
                        ))
                    }
                    offset += items.length()
                    val total = embedded.optInt("total", -1)
                    if ((total in 0..offset) || items.length() < pageSize) break // все получены / последняя страница
                }
                out
            }
        }

    /** Скачать файл [path] в [dest] (temp/cache приложения). */
    suspend fun download(token: String, path: String, dest: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = req("GET", "$API/resources/download?path=${enc(path)}", token)
                if (code !in 200..299) error(errorOf(code, body))
                val href = JSONObject(body).getString("href")
                val c = (URL(href).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
                }
                try {
                    if (c.responseCode !in 200..299) error("скачивание HTTP ${c.responseCode}")
                    // Кап на размер (как у HttpTool.DownloadFileTool/почты): гигантский файл с Диска иначе
                    // забил бы хранилище приложения. Стрим с обрывом на превышении.
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
                } finally { c.disconnect() }
                dest
            }
        }

    /** Загрузить локальный [src] на Диск по пути [path] (с перезаписью). */
    suspend fun upload(token: String, path: String, src: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = req("GET", "$API/resources/upload?path=${enc(path)}&overwrite=true", token)
                if (code !in 200..299) error(errorOf(code, body))
                val href = JSONObject(body).getString("href")
                val c = (URL(href).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; doOutput = true
                    connectTimeout = 15000; readTimeout = 60000
                    setFixedLengthStreamingMode(src.length())
                }
                try {
                    src.inputStream().use { inp -> c.outputStream.use { inp.copyTo(it) } }
                    if (c.responseCode !in 200..299) error("загрузка HTTP ${c.responseCode}")
                } finally { c.disconnect() }
            }
        }

    /** Удалить файл/папку [path] на Диске (в Корзину: permanently=false). */
    suspend fun delete(token: String, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = req("DELETE", "$API/resources?path=${enc(path)}&permanently=false", token)
                if (code !in 200..299) error(errorOf(code, body)) // 204 сразу / 202 асинхронно (папка) — оба ок
            }
        }

    /** Переместить/переименовать [from] → [to] на Диске (с перезаписью цели). */
    suspend fun move(token: String, from: String, to: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = req("POST", "$API/resources/move?from=${enc(from)}&path=${enc(to)}&overwrite=true", token)
                if (code !in 200..299) error(errorOf(code, body)) // 201 сразу / 202 асинхронно
            }
        }

    /** Создать папку [path] на Диске. */
    suspend fun mkdir(token: String, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = req("PUT", "$API/resources?path=${enc(path)}", token)
                if (code !in 200..299) error(errorOf(code, body)) // 201 создано
            }
        }

    private fun req(method: String, url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "OAuth $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10000; readTimeout = 20000
        }
        try {
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            return code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    private fun errorOf(code: Int, body: String): String =
        runCatching { JSONObject(body).optString("message").ifBlank { "HTTP $code" } }.getOrDefault("HTTP $code")
}
