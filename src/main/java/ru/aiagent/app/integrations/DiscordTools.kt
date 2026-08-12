package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.cloud.SecureKeys
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discord для агента — через BOT-ТОКЕН (без OAuth). Пользователь создаёт бота на
 * Discord Developer Portal, копирует Bot Token и вставляет его в «Интеграции». Токен
 * лежит в Keystore (EncryptedSharedPreferences), как и остальные секреты. Бот ходит в
 * Discord REST API v10 напрямую с телефона — через наш сервер ничего не проходит.
 *
 * Приватность (§7): чтение чужих сообщений помечено privateData — сырьё не отдаётся
 * облачной модели. Отправка сообщений от имени бота — DANGEROUS с обязательным
 * подтверждением (внешнее необратимое действие).
 */

/** Хранилище bot-токена Discord в Keystore (те же EncryptedSharedPreferences, что и ключи облака). */
object DiscordAuth {
    private const val KEY = "discord_bot_token"

    fun token(context: Context): String? =
        SecureKeys.get(context).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(context: Context, token: String) {
        SecureKeys.get(context).edit().putString(KEY, token.trim()).apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun clear(context: Context) {
        SecureKeys.get(context).edit().remove(KEY).apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun isConnected(context: Context): Boolean = token(context) != null
}

private const val DISCORD_API = "https://discord.com/api/v10"
private const val DISCORD_NOT_CONNECTED =
    "Discord-бот не подключён — вставьте токен в Интеграциях"

private fun discordField(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** HTTP-хелпер: заголовок Authorization: Bot <token>, таймауты, disconnect в finally. */
private object DiscordHttp {
    /** Возвращает пару (код ответа, тело). Тело читается и из errorStream для внятных ошибок. */
    fun request(method: String, url: String, token: String, body: JSONObject? = null): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bot $token")
            setRequestProperty("Accept", "application/json")
            // Discord требует User-Agent у API-клиентов.
            setRequestProperty("User-Agent", "PlusAI (https://aiagent.ru, 1.0)")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            return code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    /** Внятное сообщение об ошибке Discord из тела ответа. */
    fun errorOf(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
        return when {
            code == 401 -> "Discord: неверный или отозванный bot-токен (401)"
            code == 403 -> "Discord: у бота нет доступа (403). Пригласите бота на сервер с нужными правами."
            !msg.isNullOrBlank() -> "Discord ($code): $msg"
            else -> "Discord: ошибка $code"
        }
    }
}

/**
 * discord_list_channels — если задан guild_id: каналы сервера (GET /guilds/{id}/channels);
 * иначе список серверов, куда добавлен бот (GET /users/@me/guilds).
 */
class DiscordListChannelsTool(private val context: Context) : AgentTool {
    override val id = "discord_list_channels"
    override val danger = DangerLevel.SAFE
    override val privateData = true // список серверов/каналов пользователя — не в облако
    override val schema =
        """каналы сервера Discord (если задан guild_id) или список серверов бота (если нет); args: {"guild_id":"опц"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val token = DiscordAuth.token(context) ?: return@withContext ToolResult.Failure(DISCORD_NOT_CONNECTED)
        val guildId = discordField(argsJson, "guild_id")
        val url = if (guildId != null) "$DISCORD_API/guilds/$guildId/channels" else "$DISCORD_API/users/@me/guilds"
        val (code, body) = runCatching { DiscordHttp.request("GET", url, token) }
            .getOrElse { return@withContext ToolResult.Failure("discord_list_channels: ${it.message}") }
        if (code !in 200..299) return@withContext ToolResult.Failure(DiscordHttp.errorOf(code, body))

        val arr = runCatching { JSONArray(body) }.getOrElse { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.put(
                JSONObject()
                    .put("id", o.optString("id"))
                    .put("name", o.optString("name"))
                    .apply { if (guildId != null) put("type", o.optInt("type")) },
            )
        }
        val key = if (guildId != null) "channels" else "guilds"
        ToolResult.Success(JSONObject().put(key, out).toString())
    }
}

/** discord_read_messages — последние сообщения канала (GET /channels/{id}/messages?limit=N). */
class DiscordReadMessagesTool(private val context: Context) : AgentTool {
    override val id = "discord_read_messages"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // чтение чужих сообщений — подтверждение (кроме bypass)
    override val privateData = true // чужие сообщения — не отдаём облачной модели
    override val schema =
        """последние сообщения канала Discord; args: {"channel_id":"...","limit":20}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val token = DiscordAuth.token(context) ?: return@withContext ToolResult.Failure(DISCORD_NOT_CONNECTED)
        val channelId = discordField(argsJson, "channel_id")
            ?: return@withContext ToolResult.Failure("нужен args.channel_id")
        val limit = (discordField(argsJson, "limit")?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val url = "$DISCORD_API/channels/$channelId/messages?limit=$limit"
        val (code, body) = runCatching { DiscordHttp.request("GET", url, token) }
            .getOrElse { return@withContext ToolResult.Failure("discord_read_messages: ${it.message}") }
        if (code !in 200..299) return@withContext ToolResult.Failure(DiscordHttp.errorOf(code, body))

        val arr = runCatching { JSONArray(body) }.getOrElse { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val author = o.optJSONObject("author")
            out.put(
                JSONObject()
                    .put("id", o.optString("id"))
                    .put("author", author?.optString("username") ?: "?")
                    .put("content", o.optString("content"))
                    .put("timestamp", o.optString("timestamp")),
            )
        }
        ToolResult.Success(JSONObject().put("channel_id", channelId).put("messages", out).toString())
    }
}

/** discord_send — отправить сообщение в канал от имени бота (POST /channels/{id}/messages). */
class DiscordSendTool(private val context: Context) : AgentTool {
    override val id = "discord_send"
    override val danger = DangerLevel.DANGEROUS // отправка от имени бота — внешнее необратимое действие
    override val alwaysConfirm = true
    override val schema =
        """отправить сообщение в канал Discord от имени бота; args: {"channel_id":"...","text":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val token = DiscordAuth.token(context) ?: return@withContext ToolResult.Failure(DISCORD_NOT_CONNECTED)
        val channelId = discordField(argsJson, "channel_id")
            ?: return@withContext ToolResult.Failure("нужен args.channel_id")
        val text = discordField(argsJson, "text")
            ?: return@withContext ToolResult.Failure("нужен args.text")
        val url = "$DISCORD_API/channels/$channelId/messages"
        val (code, body) = runCatching { DiscordHttp.request("POST", url, token, JSONObject().put("content", text)) }
            .getOrElse { return@withContext ToolResult.Failure("discord_send: ${it.message}") }
        if (code !in 200..299) return@withContext ToolResult.Failure(DiscordHttp.errorOf(code, body))
        val id = runCatching { JSONObject(body).optString("id") }.getOrNull()
        ToolResult.Success(JSONObject().put("sent", true).put("channel_id", channelId).put("message_id", id).toString())
    }
}

/** Все Discord-инструменты (подключаются только если вставлен bot-токен). */
fun discordTools(context: Context): List<AgentTool> = listOf(
    DiscordListChannelsTool(context),
    DiscordReadMessagesTool(context),
    DiscordSendTool(context),
)
