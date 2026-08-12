package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * Telegram ЮЗЕР-КЛИЕНТ для агента — ЛЕГАЛЬНЫЙ клиент от имени пользователя через официальный TDLib
 * (MTProto). Вход СВОИМ номером + SMS/код (+2FA). Транспорт/логика — в [TelegramUserClient]/[TelegramTd];
 * здесь только обёртки-инструменты tg_* (не конфликтуют с bot-тулами telegram_* из [TelegramTools]).
 *
 * Приватность/безопасность:
 *  - все инструменты privateData=true — личная переписка НЕ уходит облачной модели (§7);
 *  - tg_login/tg_verify/tg_password/tg_send — IMPORTANT + alwaysConfirm (подтверждение всегда, кроме bypass):
 *    вход и отправка — действия с внешними последствиями, молча их выполнять нельзя;
 *  - tg_chats/tg_read — SAFE (чтение своих чатов), но тоже privateData;
 *  - чтение всегда проходит через фильтр кодов/чувствительных отправителей ([MessengerFilter]) — коды
 *    входа Госуслуг/банков маскируются, тело у чувствительных отправителей подавляется.
 */

/** Достать строковый аргумент с гардом квирка org.json (optString при JSON null отдаёт "null"). */
private fun tgArg(json: String, name: String): String? =
    runCatching {
        val o = JSONObject(json)
        if (o.isNull(name)) null else o.optString(name).takeIf { it.isNotBlank() }
    }.getOrNull()

private fun TgResult.toToolResult(): ToolResult = when (this) {
    is TgResult.Ok -> ToolResult.Success(data.toString())
    is TgResult.Err -> ToolResult.Failure(message)
}

/** tg_login {phone} — шаг 1 входа: запросить код на СВОЙ номер (setAuthenticationPhoneNumber). */
class TgLoginTool(private val context: Context) : AgentTool {
    override val id = "tg_login"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // вход по номеру — действие с внешним эффектом
    override val privateData = true
    override val schema =
        """войти в Telegram (свой аккаунт, TDLib): запросить код на свой номер (шаг 1/2); args: {"phone":"+79991234567"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val phone = tgArg(argsJson, "phone")
            ?: return@withContext ToolResult.Failure("нужен args.phone (свой номер, напр. +79991234567)")
        TelegramUserClient(context).login(phone).toToolResult()
    }
}

/** tg_verify {code} — шаг 2 входа: код из SMS/Telegram (checkAuthenticationCode). */
class TgVerifyTool(private val context: Context) : AgentTool {
    override val id = "tg_verify"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData = true
    override val schema =
        """подтвердить вход в Telegram кодом (шаг 2/2); если вернётся need_password — нужен tg_password; args: {"code":"12345"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val code = tgArg(argsJson, "code")
            ?: return@withContext ToolResult.Failure("нужен args.code (код из SMS/Telegram)")
        TelegramUserClient(context).verifyCode(code).toToolResult()
    }
}

/** tg_password {password} — облачный пароль (2FA), если после tg_verify пришёл need_password. */
class TgPasswordTool(private val context: Context) : AgentTool {
    override val id = "tg_password"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData = true
    override val schema =
        """ввести облачный пароль (2FA) Telegram, если после tg_verify пришёл need_password; args: {"password":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val password = tgArg(argsJson, "password")
            ?: return@withContext ToolResult.Failure("нужен args.password")
        TelegramUserClient(context).verifyPassword(password).toToolResult()
    }
}

/** tg_chats — список своих чатов (chat_id + название; чувствительные помечены). Чтение — SAFE. */
class TgChatsTool(private val context: Context) : AgentTool {
    override val id = "tg_chats"
    override val danger = DangerLevel.SAFE
    override val privateData = true // список личных чатов — не в облако
    override val schema =
        """список своих чатов Telegram (chat_id + название); args: {"limit":40}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val limit = (tgArg(argsJson, "limit")?.toIntOrNull() ?: 40).coerceIn(1, 100)
        TelegramUserClient(context).chats(limit).toToolResult()
    }
}

/** tg_read {chat_id, limit} — последние сообщения чата (коды/OTP скрыты фильтром). Чтение — SAFE. */
class TgReadTool(private val context: Context) : AgentTool {
    override val id = "tg_read"
    override val danger = DangerLevel.SAFE
    override val privateData = true // личная переписка — не отдаём облачной модели
    override val schema =
        """последние сообщения чата Telegram (коды/OTP скрыты фильтром); args: {"chat_id":"12345","limit":20}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val chatId = tgArg(argsJson, "chat_id")?.toLongOrNull()
            ?: return@withContext ToolResult.Failure("нужен args.chat_id (число)")
        val limit = (tgArg(argsJson, "limit")?.toIntOrNull() ?: 20).coerceIn(1, 100)
        TelegramUserClient(context).read(chatId, limit).toToolResult()
    }
}

/** tg_send {chat_id, text} — отправить сообщение от своего имени (sendMessage). Всегда подтверждаем. */
class TgSendTool(private val context: Context) : AgentTool {
    override val id = "tg_send"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // отправка от имени пользователя — всегда подтверждаем
    override val privateData = true
    override val schema =
        """отправить сообщение в чат Telegram от своего имени; args: {"chat_id":"12345","text":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val chatId = tgArg(argsJson, "chat_id")?.toLongOrNull()
            ?: return@withContext ToolResult.Failure("нужен args.chat_id (число)")
        val text = tgArg(argsJson, "text")
            ?: return@withContext ToolResult.Failure("нужен args.text")
        TelegramUserClient(context).send(chatId, text).toToolResult()
    }
}

/**
 * Все инструменты Telegram юзер-клиента. Вход (tg_login/tg_verify/tg_password) регистрируем всегда —
 * это сам тул-флоу, он обязан быть доступен до наличия сессии. Операции над чатами при отсутствии
 * входа/пака/api-кредов вернут понятную ошибку (см. [TelegramTd.ensureStarted]).
 *
 * ТОЧКА РАСШИРЕНИЯ (пока не включаем): поиск чатов (searchChats), пометка прочитанным
 * (viewMessages), вложения (inputMessagePhoto/Document), редактирование/удаление сообщений.
 */
fun telegramUserTools(context: Context): List<AgentTool> = listOf(
    TgLoginTool(context),
    TgVerifyTool(context),
    TgPasswordTool(context),
    TgChatsTool(context),
    TgReadTool(context),
    TgSendTool(context),
)
