package ru.aiagent.app.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.inference.ChatMessage
import ru.aiagent.core.inference.ChatRole
import java.net.HttpURLConnection
import java.net.URL

/**
 * Клиент ОТВЯЗАННОЙ серверной генерации (досыл на телефон). Генерация идёт на сервере независимо от
 * соединения телефона: POST /v1/chat/generate создаёт сессию, GET /v1/chat/generate/{id} — долгий опрос
 * по байтовому курсору. Телефон переживает переподключение и ЗАКРЫТИЕ приложения: по session_id (сохранён
 * в диалоге) дозаберёт накопленное и финал при возврате.
 *
 * Работает только через прокси владельца (есть URL+токен). Возвращает поток кусочков content (delta),
 * тот же, что прямой стрим — вызывающий код не меняется.
 */
object ServerGen {

    /** Доступна ли серверная сессия (включён прокси владельца и есть токен).
     *  «Свой API» имеет приоритет: серверный досыл через владельца не применим к чужому endpoint'у, иначе
     *  запрос ушёл бы на сервер владельца (кошелёк + данные туда) мимо выбранного пользователем endpoint'а. */
    fun available(context: Context): Boolean {
        if (CloudEngine.customEnabled(context)) return false
        val (enabled, _, token) = CloudEngine.proxyConfig(context)
        return enabled && token.isNotBlank()
    }

    /** Старт серверной генерации. Возвращает session_id (для опроса и сохранения в диалоге). */
    suspend fun start(context: Context, model: String, history: List<ChatMessage>): String {
        val (_, url, token) = CloudEngine.proxyConfig(context)
        val messages = JSONArray()
        for (m in history) {
            messages.put(
                JSONObject()
                    .put("role", if (m.role == ChatRole.USER) "user" else "assistant")
                    .put("content", m.text),
            )
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
        val resp = post("${url.trimEnd('/')}/v1/chat/generate", token, body.toString())
        if (resp.first !in 200..299) {
            throw RuntimeException(errorOf(resp.first, resp.second))
        }
        val id = JSONObject(resp.second).optString("session_id")
        if (id.isBlank()) throw RuntimeException("сервер не вернул session_id")
        return id
    }

    /**
     * Долгий опрос сессии: поток кусочков content по мере генерации. Переживает разрыв — на следующем
     * опросе продолжает с курсора. Завершается, когда сервер отдал done; кидает, если сервер вернул error.
     */
    fun poll(context: Context, sessionId: String): Flow<String> = flow {
        val (_, url, token) = CloudEngine.proxyConfig(context)
        var cursor = 0
        var idle = 0    // подряд ответов без прогресса (сервер long-poll'ит по 25с; ~8 = «зависла»)
        var blips = 0   // подряд сетевых сбоев (короткий разрыв — ретраим, не роняем сессию)
        while (true) {
            val resp = try {
                get("${url.trimEnd('/')}/v1/chat/generate/$sessionId?cursor=$cursor", token)
            } catch (e: Exception) {
                // Короткий разрыв сети во время активного опроса: ретраим, чтобы не осиротить серверную
                // генерацию (она продолжается на сервере). После нескольких неудач — пробрасываем.
                if (++blips > 5) throw e
                kotlinx.coroutines.delay(2000)
                continue
            }
            blips = 0
            if (resp.first == 404) throw RuntimeException("сессия истекла на сервере")
            if (resp.first !in 200..299) throw RuntimeException(errorOf(resp.first, resp.second))
            val o = JSONObject(resp.second)
            val newCursor = o.optInt("cursor", cursor)
            val data = o.optString("data")
            // Эмитим ТОЛЬКО при реальном продвижении курсора — иначе повтор тех же байт задваивал бы текст.
            if (data.isNotEmpty() && newCursor > cursor) {
                for (piece in parseSseContent(data)) emit(piece)
                cursor = newCursor
                idle = 0
            } else if (!o.optBoolean("done")) {
                // Порог с запасом: сервер сам обрывает зависший апстрим по своему таймауту (~15 мин) и
                // помечает сессию done/error РАНЬШЕ, чем сюда. 40×25с ≈ 16 мин — только бэкстоп на случай,
                // если сервер вообще не отвечает done (медленный time-to-first-token не роняем ложно).
                if (++idle >= 40) throw RuntimeException("сервер не отдаёт данные — сессия, похоже, зависла")
            }
            // isNull перед optString: на Android org.json.optString для JSON-null возвращает строку "null"
            // (не ""), поэтому поле error:null иначе бросило бы RuntimeException("null") даже на чистом ответе.
            if (!o.isNull("error") && o.optString("error").isNotBlank()) throw RuntimeException(o.optString("error"))
            if (o.optBoolean("done")) break
        }
    }.flowOn(Dispatchers.IO)

    /** Прервать серверную генерацию (телефон нажал «Стоп»): сервер отменит апстрим и спишет частичный
     *  ответ вместо полного. Тихо (best-effort) — если сессии уже нет, не важно. */
    suspend fun abort(context: Context, sessionId: String) {
        val (_, url, token) = CloudEngine.proxyConfig(context)
        if (url.isBlank() || sessionId.isBlank()) return
        runCatching { delete("${url.trimEnd('/')}/v1/chat/generate/$sessionId", token) }
    }

    /** Извлекает content-дельты из сырых SSE-строк ответа сервера (тот же формат, что прямой стрим). */
    private fun parseSseContent(raw: String): List<String> {
        val out = ArrayList<String>()
        for (line in raw.split('\n')) {
            val t = line.trim()
            if (!t.startsWith("data:")) continue
            val payload = t.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val delta = runCatching {
                JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
            }.getOrNull() ?: continue
            // isNull-гард: у роле-/finish-дельт content приходит JSON-null, а optString вернул бы строку
            // "null" (Android-квирк) → в ответ протёк бы литерал «null» посреди текста.
            val content = if (delta.isNull("content")) "" else delta.optString("content")
            if (content.isNotEmpty()) out.add(content)
        }
        return out
    }

    private fun errorOf(code: Int, body: String): String =
        runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "сервер вернул $code"

    private fun post(url: String, token: String, body: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 35000 // > серверного long-poll (25с)
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun delete(url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }
}
