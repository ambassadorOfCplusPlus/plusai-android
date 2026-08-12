package ru.aiagent.app.utils

import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.net.HttpURLConnection
import java.net.URL

/**
 * Создать артефакт — HTML/JS-страницу, доступную по публичной ссылке.
 * Отправляет на сервер, получает URL.
 */
class CreateArtifactTool : AgentTool {
    override val id = "create_artifact"
    override val danger = DangerLevel.SAFE
    override val schema =
        """создать HTML/JS-страницу (артефакт), доступную по ссылке; args: {"title":"название","html":"<h1>Привет</h1>"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val obj = JSONObject(argsJson)
        val title = obj.optString("title", "Артефакт")
        val html = obj.optString("html", "")
        if (html.isBlank()) return ToolResult.Failure("нужен html-код страницы")

        val baseUrl = "https://api.plus-ai.ru"
        return try {
            val body = JSONObject().put("title", title).put("html", html).toString()
            val conn = URL("$baseUrl/v1/artifacts").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.outputStream.use { it.write(body.toByteArray()) }
            val resp = conn.inputStream.bufferedReader().readText()
            val result = JSONObject(resp)
            val id = result.optString("id")
            val url = result.optString("url", "/a/$id")
            conn.disconnect()
            ToolResult.Success("Артефакт создан: $baseUrl$url")
        } catch (e: Exception) {
            ToolResult.Failure("не удалось создать артефакт: ${e.message}")
        }
    }
}
