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
 * Telegram для агента — через BOT-ТОКЕН (Bot API, БЕЗ api_id/api_hash и без TDLib). Пользователь
 * создаёт бота у @BotFather в приложении Telegram, копирует токен и вставляет в «Интеграции».
 * Токен лежит в Keystore (EncryptedSharedPreferences). Бот ходит в api.telegram.org напрямую.
 *
 * Ограничение Bot API: бот НЕ видит личную переписку пользователя с другими — только сообщения,
 * присланные САМОМУ боту / в группы-каналы, куда он добавлен. Для доступа к личным чатам нужен
 * user-account (TDLib + api_id) — отдельный тяжёлый путь.
 *
 * Приватность (§7): чтение входящих боту сообщений помечено privateData — не отдаётся облачной
 * модели. Отправка от имени бота — DANGEROUS с подтверждением (внешнее необратимое действие).
 */

/** Хранилище bot-токена Telegram в Keystore (те же EncryptedSharedPreferences, что и ключи облака). */
object TelegramAuth {
    private const val KEY = "telegram_bot_token"

    fun token(context: Context): String? =
        SecureKeys.get(context).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(context: Context, token: String) =
        SecureKeys.get(context).edit().putString(KEY, token.trim()).apply()

    fun clear(context: Context) =
        SecureKeys.get(context).edit().remove(KEY).apply()

    fun isConnected(context: Context): Boolean = token(context) != null
}

private const val TELEGRAM_NOT_CONNECTED =
    "Telegram-бот не подключён — вставьте токен от @BotFather в Интеграциях"

private fun tgField(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** HTTP-хелпер Bot API: токен в ПУТИ (/bot<token>/method), не в заголовке. Таймауты, disconnect в finally. */
private object TelegramHttp {
    fun base(token: String) = "https://api.telegram.org/bot$token"

    /** Возвращает (код, тело). Тело читается и из errorStream для внятных ошибок. */
    fun request(method: String, url: String, body: JSONObject? = null): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 25000
            setRequestProperty("Accept", "application/json")
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

    /** Внятная ошибка из тела Telegram ({"ok":false,"error_code":N,"description":"..."}). */
    fun errorOf(code: Int, body: String): String {
        val desc = runCatching { JSONObject(body).optString("description") }.getOrNull()
        return when {
            code == 401 -> "Telegram: неверный или отозванный bot-токен (401)"
            code == 409 -> "Telegram: конфликт (409) — вероятно, у бота задан webhook; getUpdates недоступен"
            !desc.isNullOrBlank() -> "Telegram ($code): $desc"
            else -> "Telegram: ошибка $code"
        }
    }

    /** chat_id может быть числом или @username канала — кладём корректный тип в JSON. */
    fun putChatId(obj: JSONObject, chatId: String): JSONObject {
        val asLong = chatId.toLongOrNull()
        return if (asLong != null) obj.put("chat_id", asLong) else obj.put("chat_id", chatId)
    }
}

/** telegram_me — проверка подключения: имя/username бота (GET getMe). */
class TelegramMeTool(private val context: Context) : AgentTool {
    override val id = "telegram_me"
    override val danger = DangerLevel.SAFE
    override val schema = "информация о подключённом Telegram-боте (проверка токена); args: {}"

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val token = TelegramAuth.token(context) ?: return@withContext ToolResult.Failure(TELEGRAM_NOT_CONNECTED)
        val (code, body) = runCatching { TelegramHttp.request("GET", "${TelegramHttp.base(token)}/getMe") }
            .getOrElse { return@withContext ToolResult.Failure("telegram_me: ${it.message}") }
        if (code !in 200..299) return@withContext ToolResult.Failure(TelegramHttp.errorOf(code, body))
        val r = runCatching { JSONObject(body).optJSONObject("result") }.getOrNull()
            ?: return@withContext ToolResult.Failure("telegram_me: пустой ответ")
        ToolResult.Success(
            JSONObject()
                .put("id", r.optLong("id"))
                .put("username", r.optString("username"))
                .put("name", r.optString("first_name"))
                .toString(),
        )
    }
}

/**
 * telegram_get_updates — входящие боту сообщения (GET getUpdates). Так узнают chat_id, чтобы потом
 * отвечать. offset — обработать только новые (больше update_id из прошлого раза).
 */
class TelegramGetUpdatesTool(private val context: Context) : AgentTool {
    override val id = "telegram_get_updates"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // чтение входящих — подтверждение (кроме bypass)
    override val privateData = true // сообщения пользователей — не в облако
    override val schema =
        """новые сообщения, присланные Telegram-боту (тут же chat_id для ответа); args: {"offset":"опц update_id","limit":20}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val token = TelegramAuth.token(context) ?: return@withContext ToolResult.Failure(TELEGRAM_NOT_CONNECTED)
        val limit = (tgField(argsJson, "limit")?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val offset = tgField(argsJson, "offset")?.toLongOrNull()
        val url = buildString {
            append("${TelegramHttp.base(token)}/getUpdates?limit=$limit")
            if (offset != null) append("&offset=$offset")
        }
        val (code, body) = runCatching { TelegramHttp.request("GET", url) }
            .getOrElse { return@withContext ToolResult.Failure("telegram_get_updates: ${it.message}") }
        if (code !in 200..299) return@withContext ToolResult.Failure(TelegramHttp.errorOf(code, body))

        val arr = runCatching { JSONObject(body).optJSONArray("result") }.getOrNull() ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            val msg = u.optJSONObject("message") ?: u.optJSONObject("channel_post") ?: continue
            val from = msg.optJSONObject("from")
            val chat = msg.optJSONObject("chat")
            out.put(
                JSONObject()
                    .put("update_id", u.optLong("update_id"))
                    .put("chat_id", chat?.opt("id") ?: JSONObject.NULL)
                    // optString у org.json возвращает "" (не null) для отсутствующего ключа, поэтому
                    // Elvis не сработал бы — нужен takeIf, чтобы фолбэк на username реально включался.
                    .put("chat_title", chat?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: chat?.optString("username") ?: "")
                    .put("from", from?.optString("username")?.takeIf { it.isNotBlank() } ?: from?.optString("first_name") ?: "")
                    .put("text", msg.optString("text"))
                    .put("date", msg.optLong("date")),
            )
        }
        ToolResult.Success(JSONObject().put("updates", out).toString())
    }
}

/** telegram_send — отправить сообщение в чат/канал от имени бота (POST sendMessage). */
class TelegramSendTool(private val context: Context) : AgentTool {
    override val id = "telegram_send"
    override val danger = DangerLevel.DANGEROUS // отправка от имени бота — внешнее необратимое действие
    override val alwaysConfirm = true
    override val schema =
        """отправить сообщение в Telegram от имени бота; args: {"chat_id":"число или @канал","text":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val token = TelegramAuth.token(context) ?: return@withContext ToolResult.Failure(TELEGRAM_NOT_CONNECTED)
        val chatId = tgField(argsJson, "chat_id")
            ?: return@withContext ToolResult.Failure("нужен args.chat_id (число или @канал)")
        val text = tgField(argsJson, "text")
            ?: return@withContext ToolResult.Failure("нужен args.text")
        val payload = TelegramHttp.putChatId(JSONObject().put("text", text), chatId)
        val (code, body) = runCatching { TelegramHttp.request("POST", "${TelegramHttp.base(token)}/sendMessage", payload) }
            .getOrElse { return@withContext ToolResult.Failure("telegram_send: ${it.message}") }
        if (code !in 200..299) return@withContext ToolResult.Failure(TelegramHttp.errorOf(code, body))
        val mid = runCatching { JSONObject(body).optJSONObject("result")?.optLong("message_id") }.getOrNull()
        ToolResult.Success(JSONObject().put("sent", true).put("chat_id", chatId).put("message_id", mid).toString())
    }
}

/** Все Telegram-инструменты (подключаются только если вставлен bot-токен). */
fun telegramTools(context: Context): List<AgentTool> = listOf(
    TelegramMeTool(context),
    TelegramGetUpdatesTool(context),
    TelegramSendTool(context),
)
