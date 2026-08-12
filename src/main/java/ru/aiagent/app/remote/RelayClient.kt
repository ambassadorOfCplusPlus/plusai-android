package ru.aiagent.app.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RelayDevice(val id: String, val name: String, val pubkey: String)
data class RelayEnvelope(val from: String, val to: String, val payload: String, val seq: Long = 0)

/** Клиент E2E-релэя (announce / send / poll на сервере, long-poll ~25с). На HttpURLConnection, как Account. */
class RelayClient(private val baseUrl: String, private val token: String) {

    fun announce(id: String, name: String, pubkey: String): List<RelayDevice> {
        val res = post("/v1/relay/announce", JSONObject().put("id", id).put("name", name).put("pubkey", pubkey))
        val arr = res.optJSONArray("peers") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RelayDevice(o.optString("id"), o.optString("name"), o.optString("pubkey"))
        }
    }

    fun send(from: String, to: String, payload: String) {
        post("/v1/relay/send", JSONObject().put("from", from).put("to", to).put("payload", payload))
    }

    /** Забрать сообщения с seq > [since] (курсор догона; 0 — вся удерживаемая история). */
    fun poll(device: String, since: Long): List<RelayEnvelope> {
        val res = get("/v1/relay/poll?device=" + URLEncoder.encode(device, "UTF-8") + "&since=" + since)
        val arr = res.optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RelayEnvelope(o.optString("from"), o.optString("to"), o.optString("payload"), o.optLong("seq"))
        }
    }

    private fun get(path: String): JSONObject {
        val c = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10000
            readTimeout = 35000
        }
        return readJson(c)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10000
            readTimeout = 35000
            doOutput = true
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readJson(c)
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val stream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        c.disconnect()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
