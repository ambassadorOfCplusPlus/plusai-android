package ru.aiagent.app.integrations

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.code.RuntimePackManager
import ru.aiagent.app.cloud.SecureKeys
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Telegram ЮЗЕР-КЛИЕНТ (не бот) через ОФИЦИАЛЬНЫЙ TDLib (MTProto). Легально: сторонние клиенты
 * разрешены Telegram, вход СВОИМ номером + SMS-кодом (+2FA-пароль), работа со своим же аккаунтом.
 * api_id/api_hash берутся с my.telegram.org (их проставляет владелец в настройках).
 *
 * ОТЛИЧИЕ от [TelegramTools] (bot-токен, Bot API): здесь виден ЛИЧНЫЙ список чатов и переписка
 * пользователя. Инструменты называются tg_* — не конфликтуют с bot-тулами telegram_* .
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TDLib КАК СКАЧИВАЕМОЕ РАСШИРЕНИЕ (пак "tdlib"), НЕ в APK — базовый APK лёгкий (как модели/раннеры).
 * libtdjson.so (+ его зависимости) и тонкая JNI-обёртка грузятся System.load из pack-папки — ровно
 * как whisper/ffmpeg/picoc (dlopen скачанной .so на Android разрешён, execve — нет). Сверка SHA-256
 * пака fail-closed в [RuntimePackManager] закрывает подмену .so (RCE). URL/хэш пака проставит владелец.
 *
 * ВЫБОР БАЙНДИНГОВ — JSON-интерфейс td_json_client, НЕ типизированные org.drinkless.tdlib.Client+TdApi:
 *  - JSON-функции (td_create_client_id/td_send/td_receive/td_execute) — стабильный ABI внутри мажорной
 *    версии TDLib, поэтому пак можно обновлять без пересборки APK; типизированный TdApi.java, наоборот,
 *    жёстко привязан к версии .so (рассинхрон → краш) и весит мегабайты сгенерированного кода в APK;
 *  - работаем строками JSON через org.json — совпадает с остальным кодом интеграций;
 *  - СВОЙ JNI-shim через NDK в ЭТОМ репозитории НЕ собираем: минимальную обёртку (4 функции JSON →
 *    JNI) владелец собирает ОДИН раз при сборке пака (как libpluswhisper.so у whisper-пака). См.
 *    ожидаемые нативные символы в [TdJsonNative].
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * БЕЗОПАСНОСТЬ: номер/код/пароль/сессия НИКОГДА не логируются (в файле нет Log с этими полями).
 * Локальная БД TDLib шифруется (database_encryption_key — генерим и храним в Keystore). Сессия живёт
 * ВНУТРИ зашифрованной БД TDLib (в filesDir/tdlib) — отдельного токена мы не храним.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Нативный мост к libtdjson.so (JSON-интерфейс). Символы экспортирует JNI-обёртка ИЗ ПАКА.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Тонкий Kotlin-фасад над JSON-интерфейсом TDLib. Реальные тела этих external-методов лежат в
 * JNI-обёртке пака (напр. libplustd.so), которая просто прокидывает 4 функции td_json_client.h:
 *   td_create_client_id() / td_send(id,req) / td_receive(timeout) / td_execute(req).
 * Ожидаемые JNI-символы в .so-обёртке:
 *   Java_ru_aiagent_app_integrations_TdJsonNative_createClientId
 *   Java_ru_aiagent_app_integrations_TdJsonNative_send
 *   Java_ru_aiagent_app_integrations_TdJsonNative_receive
 *   Java_ru_aiagent_app_integrations_TdJsonNative_execute
 */
object TdJsonNative {
    @Volatile private var loaded = false
    private val lock = Any()

    fun packDir(context: Context): File = RuntimePackManager.installDir(context, "tdlib")

    /** Пак скачан и распакован (грубая проверка наличия обёртки). */
    fun isPackReady(context: Context): Boolean =
        RuntimePackManager.isInstalled(context, "tdlib") &&
            File(packDir(context), WRAPPER).let { it.exists() && it.length() > 0 }

    private const val WRAPPER = "libplustd.so" // JNI-обёртка вокруг td_json_client (собирает владелец в паке)

    /** Загрузить libtdjson.so + зависимости, затем обёртку. false — пак не скачан/битый. */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (loaded) return true
        val dir = packDir(context)
        val wrapper = File(dir, WRAPPER)
        if (!wrapper.exists() || wrapper.length() == 0L) return false
        return try {
            // Сначала зависимые .so (libtdjson, возможно libc++_shared), потом обёртка — иначе
            // dlopen обёртки не найдёт символы td_* . Порядок: всё кроме обёртки по имени, затем обёртка.
            dir.listFiles { f -> f.isFile && f.name.endsWith(".so") && f.name != WRAPPER }
                ?.sortedBy { it.name }?.forEach { so ->
                    runCatching { if (so.canWrite()) so.setReadOnly() } // будущие Android блокируют System.load писабельного файла
                    runCatching { System.load(so.absolutePath) } // часть либ подтягивается транзитивно — не валимся
                }
            runCatching { if (wrapper.canWrite()) wrapper.setReadOnly() }
            System.load(wrapper.absolutePath)
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun createClientId(): Int
    external fun send(clientId: Int, request: String)
    external fun receive(timeoutSeconds: Double): String?
    external fun execute(request: String): String?
}

// ─────────────────────────────────────────────────────────────────────────────
// Секреты/настройки юзер-клиента Telegram
// ─────────────────────────────────────────────────────────────────────────────

/**
 * api_id/api_hash (с my.telegram.org) и ключ шифрования локальной БД TDLib — в Keystore
 * (EncryptedSharedPreferences, тот же [SecureKeys], что и остальные интеграции). НЕ plaintext.
 * Полного номера/кода/пароля/сессии здесь нет — сессию хранит сам TDLib в своей зашифрованной БД.
 */
object TelegramUserAuth {
    private const val KEY_API_ID = "tg_user_api_id"     // int (String в prefs)
    private const val KEY_API_HASH = "tg_user_api_hash" // String
    private const val KEY_DB_KEY = "tg_user_db_key"     // base64 32 байта — ключ шифрования БД TDLib

    fun apiId(context: Context): Int? =
        SecureKeys.get(context).getString(KEY_API_ID, null)?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    fun apiHash(context: Context): String? =
        SecureKeys.get(context).getString(KEY_API_HASH, null)?.takeIf { it.isNotBlank() }

    /** Проставляется владельцем (экран «Интеграции»/настройки). Без них юзер-клиент не стартует. */
    fun saveApiCredentials(context: Context, apiId: Int, apiHash: String) =
        SecureKeys.get(context).edit()
            .putString(KEY_API_ID, apiId.toString())
            .putString(KEY_API_HASH, apiHash.trim())
            .apply()

    fun hasApiCredentials(context: Context): Boolean = apiId(context) != null && apiHash(context) != null

    /**
     * Ключ шифрования БД TDLib — 32 случайных байта, генерятся один раз и переживают перезапуски
     * (иначе TDLib не расшифрует свою БД и потеряет сессию). Храним в base64 в Keystore.
     */
    fun databaseEncryptionKeyB64(context: Context): String {
        val prefs = SecureKeys.get(context)
        prefs.getString(KEY_DB_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
        prefs.edit().putString(KEY_DB_KEY, b64).apply()
        return b64
    }

    /** Полный сброс интеграции: стереть api-креды и ключ БД. Саму БД TDLib удаляет [TelegramTd.logout]. */
    fun clear(context: Context) =
        SecureKeys.get(context).edit().remove(KEY_API_ID).remove(KEY_API_HASH).remove(KEY_DB_KEY).apply()
}

// ─────────────────────────────────────────────────────────────────────────────
// Долгоживущий TDLib-клиент: один client_id + фоновый цикл td_receive
// ─────────────────────────────────────────────────────────────────────────────

/** Результат операции юзер-клиента: успех с JSON или понятная ошибка (без утечки секретов). */
sealed interface TgResult {
    data class Ok(val data: JSONObject) : TgResult
    data class Err(val message: String) : TgResult
}

/**
 * Синглтон-менеджер одного TDLib-клиента. В отличие от MAX (короткоживущие сокеты), TDLib —
 * ПЕРСИСТЕНТНЫЙ клиент: один client_id и фоновый поток, который вызывает td_receive и разбирает
 * апдейты/ответы. Инструменты tg_* дёргаются по-отдельности, поэтому клиент живёт между вызовами.
 *
 * Корреляция запрос↔ответ — через поле @extra (TDLib возвращает его как есть). Апдейты (в т.ч.
 * updateAuthorizationState) приходят БЕЗ @extra и обрабатываются отдельно.
 */
object TelegramTd {
    @Volatile private var clientId: Int = -1
    @Volatile private var started = false
    @Volatile private var appContext: Context? = null

    /** @type текущего authorization_state (напр. "authorizationStateWaitCode"). */
    @Volatile var authState: String = "unknown"; private set

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val extraSeq = AtomicLong(0)
    private val startLock = Any()

    fun isReady(): Boolean = authState == "authorizationStateReady"

    /**
     * Гарантировать, что нативка загружена, клиент создан и фоновый цикл запущен. Валидирует наличие
     * пака и api-кредов ДО создания клиента (fail-fast с понятным текстом).
     */
    fun ensureStarted(context: Context): TgResult {
        val ctx = context.applicationContext
        if (!TelegramUserAuth.hasApiCredentials(ctx)) {
            return TgResult.Err(NEED_API_CREDENTIALS)
        }
        if (!TdJsonNative.isPackReady(ctx)) {
            return TgResult.Err("Пак «tdlib» не установлен — скачайте расширение TDLib в настройках")
        }
        if (started) return TgResult.Ok(JSONObject().put("started", true))
        synchronized(startLock) {
            if (started) return TgResult.Ok(JSONObject().put("started", true))
            appContext = ctx
            if (!TdJsonNative.ensureLoaded(ctx)) {
                return TgResult.Err("Не удалось загрузить нативный TDLib из пака (битый пак?)")
            }
            // Приглушаем собственный лог TDLib (иначе он сыплет в stdout; секреты туда попасть не должны).
            runCatching { TdJsonNative.execute(JSONObject().put("@type", "setLogVerbosityLevel").put("new_verbosity_level", 1).toString()) }
            clientId = TdJsonNative.createClientId()
            Thread({ receiveLoop() }, "tdlib-recv").apply { isDaemon = true }.start()
            // Первый td_send «будит» доставку апдейтов этому клиенту — просим текущее состояние авторизации.
            TdJsonNative.send(clientId, JSONObject().put("@type", "getAuthorizationState").toString())
            started = true
            return TgResult.Ok(JSONObject().put("started", true))
        }
    }

    /** Фоновый разбор входящего потока TDLib. Тела кадров НЕ логируем. */
    private fun receiveLoop() {
        while (true) {
            val s = runCatching { TdJsonNative.receive(1.0) }.getOrNull()
            if (s.isNullOrEmpty()) continue
            val o = runCatching { JSONObject(s) }.getOrNull() ?: continue
            // Ответ на наш запрос — по @extra.
            val extra = if (o.isNull("@extra")) "" else o.optString("@extra")
            if (extra.isNotEmpty()) {
                extra.toLongOrNull()?.let { pending.remove(it)?.complete(o) }
                continue
            }
            // Апдейт авторизации — двигаем стейт-машину.
            if (o.optString("@type") == "updateAuthorizationState") {
                handleAuthUpdate(o.optJSONObject("authorization_state"))
                // Closed = сессия завершена (logout/сброс). Клиент мёртв — выходим из цикла и помечаем
                // незапущенным, иначе поток крутится вечно на мёртвом clientId, а повторный вход невозможен
                // (ensureStarted видит started=true и не пересоздаёт клиента).
                if (authState == "authorizationStateClosed") { resetState(); return }
            }
        }
    }

    /** Сбросить клиента в незапущенное состояние: следующий ensureStarted поднимет новый client_id. */
    private fun resetState() = synchronized(startLock) {
        started = false
        clientId = -1
        // Разбудить всех ждущих ответа, чтобы tg_* не висели до таймаута.
        pending.values.toList().forEach { it.complete(errObj("канал TDLib закрыт")) }
        pending.clear()
    }

    /**
     * Стейт-машина авторизации. На waitTdlibParameters сами отправляем setTdlibParameters
     * (api_id/api_hash, files dir в filesDir/tdlib, ключ шифрования БД). waitEncryptionKey (старые
     * версии TDLib) обрабатываем через checkDatabaseEncryptionKey. Остальные состояния только
     * фиксируем — их ждут tg_login/tg_verify/tg_password.
     */
    private fun handleAuthUpdate(state: JSONObject?) {
        val type = state?.optString("@type") ?: return
        authState = type
        when (type) {
            "authorizationStateWaitTdlibParameters" -> sendTdlibParameters()
            "authorizationStateWaitEncryptionKey" -> {
                // Старый двухшаговый путь: ключ шифрования отдельным запросом.
                val ctx = appContext ?: return
                TdJsonNative.send(
                    clientId,
                    JSONObject().put("@type", "checkDatabaseEncryptionKey")
                        .put("encryption_key", TelegramUserAuth.databaseEncryptionKeyB64(ctx)).toString(),
                )
            }
        }
    }

    /** setTdlibParameters — плоская форма (TDLib ≥1.8, client_id API). Секреты не логируем. */
    private fun sendTdlibParameters() {
        val ctx = appContext ?: return
        val apiId = TelegramUserAuth.apiId(ctx) ?: return
        val apiHash = TelegramUserAuth.apiHash(ctx) ?: return
        val dbDir = File(ctx.filesDir, "tdlib")
        val filesDir = File(dbDir, "files")
        dbDir.mkdirs(); filesDir.mkdirs()
        val req = JSONObject()
            .put("@type", "setTdlibParameters")
            .put("database_directory", dbDir.absolutePath)
            .put("files_directory", filesDir.absolutePath)
            .put("database_encryption_key", TelegramUserAuth.databaseEncryptionKeyB64(ctx)) // bytes → base64 в JSON
            .put("use_file_database", true)
            .put("use_chat_info_database", true)
            .put("use_message_database", true)
            .put("use_secret_chats", false)
            .put("api_id", apiId)
            .put("api_hash", apiHash)
            .put("system_language_code", "ru")
            .put("device_model", "Android")
            .put("system_version", "Android")
            .put("application_version", "1.0")
        TdJsonNative.send(clientId, req.toString())
    }

    /** Запрос-ответ по @extra с таймаутом. Возвращает объект ответа TDLib (@type ok/error/…). */
    suspend fun request(req: JSONObject, timeoutMs: Long = 25_000): JSONObject {
        if (clientId < 0) return errObj("клиент TDLib не запущен")
        val id = extraSeq.incrementAndGet()
        req.put("@extra", id.toString())
        val def = CompletableDeferred<JSONObject>()
        pending[id] = def
        runCatching { TdJsonNative.send(clientId, req.toString()) }
            .onFailure { pending.remove(id); return errObj("send failed") }
        return withTimeoutOrNull(timeoutMs) { def.await() }
            ?: errObj("таймаут ответа TDLib").also { pending.remove(id) }
    }

    /** Дождаться, пока authState попадёт в [targets] (или "closed"). Возвращает достигнутый стейт или null. */
    suspend fun awaitAuthState(targets: Set<String>, timeoutMs: Long = 30_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = authState
            if (s in targets || s == "authorizationStateClosed") return s
            delay(120)
        }
        return null
    }

    /** logOut в TDLib + удаление локальной БД (сброс сессии). */
    suspend fun logout(context: Context): TgResult {
        if (started && clientId >= 0) runCatching { request(JSONObject().put("@type", "logOut")) }
        File(context.filesDir, "tdlib").deleteRecursively()
        // logOut уводит клиента в Closed асинхронно, но состояние сбрасываем и сами — чтобы повторный
        // tg_login сразу поднял новый клиент, а не упёрся в started=true на мёртвом clientId.
        resetState()
        authState = "unknown"
        return TgResult.Ok(JSONObject().put("logged_out", true))
    }

    /** true, если ответ TDLib — это ошибка (@type=error); её текст (напр. PHONE_CODE_INVALID) безопасно показать. */
    fun errorText(o: JSONObject): String? {
        if (o.optString("@type") == "error") {
            val msg = if (o.isNull("message")) "" else o.optString("message")
            return "TDLib: ${msg.ifBlank { "ошибка" }}"
        }
        if (!o.isNull("_error")) return o.optString("_error")
        return null
    }

    private fun errObj(msg: String) = JSONObject().put("_error", msg)

    const val NEED_API_CREDENTIALS =
        "Не заданы api_id/api_hash Telegram (my.telegram.org) — их проставляет владелец в настройках"
    const val NOT_LOGGED_IN =
        "Telegram-аккаунт не подключён — войдите: tg_login {phone} → tg_verify {code}"
}

// ─────────────────────────────────────────────────────────────────────────────
// Высокоуровневый клиент: auth + операции над своими чатами
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Юзер-клиент Telegram для инструментов. Auth-флоу TDLib:
 *   setTdlibParameters (авто) → setAuthenticationPhoneNumber → checkAuthenticationCode →
 *   (waitPassword?) checkAuthenticationPassword → authorizationStateReady.
 * Чтение сообщений всегда проходит через [MessengerFilter] (маскирование кодов входа/OTP):
 * коды входа Госуслуг/банков маскируются, тело у чувствительных отправителей подавляется.
 */
class TelegramUserClient(private val context: Context) {

    private val ctx = context.applicationContext

    /** Шаг 1 — запросить SMS/код: setAuthenticationPhoneNumber на СВОЙ номер. Номер НЕ логируем. */
    suspend fun login(phone: String): TgResult = withContext(Dispatchers.IO) {
        when (val st = TelegramTd.ensureStarted(ctx)) { is TgResult.Err -> return@withContext st; else -> {} }
        // Дожидаемся, пока стейт-машина дойдёт до запроса номера (или уже дальше).
        val reached = TelegramTd.awaitAuthState(
            setOf(
                "authorizationStateWaitPhoneNumber", "authorizationStateWaitCode",
                "authorizationStateWaitPassword", "authorizationStateReady",
            ),
        ) ?: return@withContext TgResult.Err("TDLib не готов принять номер (проверьте api_id/api_hash и пак)")
        when (reached) {
            "authorizationStateReady" -> return@withContext TgResult.Ok(JSONObject().put("already_logged_in", true))
            "authorizationStateWaitCode" -> return@withContext TgResult.Ok(JSONObject().put("code_sent", true).put("note", "код уже запрошен — введите tg_verify"))
            "authorizationStateWaitPassword" -> return@withContext TgResult.Ok(JSONObject().put("need_password", true))
        }
        val settings = JSONObject().put("@type", "phoneNumberAuthenticationSettings")
        val resp = TelegramTd.request(
            JSONObject().put("@type", "setAuthenticationPhoneNumber")
                .put("phone_number", phone).put("settings", settings),
        )
        TelegramTd.errorText(resp)?.let { return@withContext TgResult.Err("запрос кода не прошёл: $it") }
        TgResult.Ok(JSONObject().put("code_sent", true))
    }

    /** Шаг 2 — код из SMS/Telegram: checkAuthenticationCode. Код НЕ логируем. */
    suspend fun verifyCode(code: String): TgResult = withContext(Dispatchers.IO) {
        when (val st = TelegramTd.ensureStarted(ctx)) { is TgResult.Err -> return@withContext st; else -> {} }
        val resp = TelegramTd.request(JSONObject().put("@type", "checkAuthenticationCode").put("code", code))
        TelegramTd.errorText(resp)?.let { return@withContext TgResult.Err("проверка кода не прошла: $it") }
        // После кода состояние уходит либо в Ready, либо в WaitPassword (2FA).
        when (TelegramTd.awaitAuthState(setOf("authorizationStateReady", "authorizationStateWaitPassword"), 15_000)) {
            "authorizationStateReady" -> TgResult.Ok(JSONObject().put("logged_in", true))
            "authorizationStateWaitPassword" -> TgResult.Ok(JSONObject().put("need_password", true))
            else -> TgResult.Ok(JSONObject().put("accepted", true).put("state", TelegramTd.authState))
        }
    }

    /** Облачный пароль (2FA): checkAuthenticationPassword. Пароль транзиентный — не хранится/не логируется. */
    suspend fun verifyPassword(password: String): TgResult = withContext(Dispatchers.IO) {
        when (val st = TelegramTd.ensureStarted(ctx)) { is TgResult.Err -> return@withContext st; else -> {} }
        val resp = TelegramTd.request(JSONObject().put("@type", "checkAuthenticationPassword").put("password", password))
        TelegramTd.errorText(resp)?.let { return@withContext TgResult.Err("пароль не принят: $it") }
        when (TelegramTd.awaitAuthState(setOf("authorizationStateReady"), 15_000)) {
            "authorizationStateReady" -> TgResult.Ok(JSONObject().put("logged_in", true))
            else -> TgResult.Err("пароль не принят. Повторите вход (tg_login).")
        }
    }

    /** Список своих чатов (id + название; чувствительные помечены — Слой 2 фильтра). */
    suspend fun chats(limit: Int): TgResult = withContext(Dispatchers.IO) {
        when (val st = TelegramTd.ensureStarted(ctx)) { is TgResult.Err -> return@withContext st; else -> {} }
        if (!TelegramTd.isReady()) return@withContext TgResult.Err(TelegramTd.NOT_LOGGED_IN)
        // getChats возвращает лишь УЖЕ загруженные в клиент чаты; на свежей сессии их нет. Сначала loadChats
        // подтягивает список с сервера (ошибка 404 «нет больше чатов» — норма, игнорируем).
        TelegramTd.request(
            JSONObject().put("@type", "loadChats")
                .put("chat_list", JSONObject().put("@type", "chatListMain"))
                .put("limit", limit.coerceIn(1, 100)),
        )
        val listResp = TelegramTd.request(
            JSONObject().put("@type", "getChats")
                .put("chat_list", JSONObject().put("@type", "chatListMain"))
                .put("limit", limit),
        )
        TelegramTd.errorText(listResp)?.let { return@withContext TgResult.Err("список чатов недоступен: $it") }
        val ids = listResp.optJSONArray("chat_ids") ?: JSONArray()
        val out = JSONArray()
        val n = minOf(ids.length(), limit)
        for (i in 0 until n) {
            val chatId = ids.optLong(i)
            val chat = fetchChat(chatId) ?: continue
            val title = MessengerFilter.optStr(chat, "title")
            val sensitive = MessengerFilter.isSensitiveSender(ctx, title, chatFlags(chat))
            out.put(
                JSONObject().put("chat_id", chatId).put("title", title)
                    .put("type", chatTypeName(chat))
                    .apply { if (sensitive) put("sensitive", true) },
            )
        }
        TgResult.Ok(JSONObject().put("chats", out))
    }

    /**
     * Последние сообщения чата (getChatHistory). КАЖДОЕ сообщение проходит через [MessengerFilter]:
     * Слой 1 (маскировка кодов/OTP) всегда; Слой 2 (подавление тела) если чат/отправитель чувствительный.
     */
    suspend fun read(chatId: Long, limit: Int): TgResult = withContext(Dispatchers.IO) {
        when (val st = TelegramTd.ensureStarted(ctx)) { is TgResult.Err -> return@withContext st; else -> {} }
        if (!TelegramTd.isReady()) return@withContext TgResult.Err(TelegramTd.NOT_LOGGED_IN)
        val chat = fetchChat(chatId)
        val title = chat?.let { MessengerFilter.optStr(it, "title") }
        val chatSensitive = MessengerFilter.isSensitiveSender(ctx, title, chat?.let { chatFlags(it) } ?: emptySet())

        // getChatHistory на свежем чате часто отдаёт ПУСТО первым вызовом (TDLib догружает историю с
        // сервера). Ретраим несколько раз с накоплением, пока не наберём хоть что-то или не исчерпаем попытки.
        val raw = JSONArray()
        val want = limit.coerceIn(1, 100)
        var fromId = 0L
        repeat(5) {
            val hist = TelegramTd.request(
                JSONObject().put("@type", "getChatHistory").put("chat_id", chatId)
                    .put("from_message_id", fromId).put("offset", 0)
                    .put("limit", want).put("only_local", false),
            )
            TelegramTd.errorText(hist)?.let { return@withContext TgResult.Err("история чата недоступна: $it") }
            val batch = hist.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until batch.length()) batch.optJSONObject(i)?.let { raw.put(it); fromId = it.optLong("id", fromId) }
            if (raw.length() >= want) return@repeat
        }
        val out = JSONArray()
        val n = minOf(raw.length(), limit)
        for (i in 0 until n) {
            val m = raw.optJSONObject(i) ?: continue
            // Служебный аккаунт Telegram (777000) шлёт коды входа в сам Telegram/сервисы — всегда подавляем
            // тело, даже если чат по имени не попал под паттерны (паритет с десктопом).
            val msgSensitive = chatSensitive || senderUserId(m) == 777000L
            // Нормализуем кадр TDLib в форму, которую понимает MessengerFilter.sanitizeMessage.
            val normalized = JSONObject()
                .put("id", m.opt("id") ?: JSONObject.NULL)
                .put("from", senderLabel(m))
                .put("date", m.opt("date") ?: JSONObject.NULL)
                .put("text", extractText(m))
            out.put(MessengerFilter.sanitizeMessage(ctx, normalized, msgSensitive))
        }
        TgResult.Ok(
            JSONObject().put("chat_id", chatId).put("title", title ?: "")
                .apply { if (chatSensitive) put("chat_sensitive", true) }
                .put("messages", out),
        )
    }

    /** Отправить сообщение в чат от своего имени (sendMessage inputMessageText). Необратимо. */
    suspend fun send(chatId: Long, text: String): TgResult = withContext(Dispatchers.IO) {
        when (val st = TelegramTd.ensureStarted(ctx)) { is TgResult.Err -> return@withContext st; else -> {} }
        if (!TelegramTd.isReady()) return@withContext TgResult.Err(TelegramTd.NOT_LOGGED_IN)
        val content = JSONObject().put("@type", "inputMessageText")
            .put("text", JSONObject().put("@type", "formattedText").put("text", text))
        val resp = TelegramTd.request(
            JSONObject().put("@type", "sendMessage").put("chat_id", chatId).put("input_message_content", content),
        )
        TelegramTd.errorText(resp)?.let { return@withContext TgResult.Err("отправка не удалась: $it") }
        // Ответ — объект message (черновик отправки); id может смениться на серверный после доставки.
        val msgId = resp.opt("id") ?: JSONObject.NULL
        TgResult.Ok(JSONObject().put("sent", true).put("chat_id", chatId).put("message_id", msgId))
    }

    // ── помощники разбора кадров TDLib ──

    private suspend fun fetchChat(chatId: Long): JSONObject? {
        val resp = TelegramTd.request(JSONObject().put("@type", "getChat").put("chat_id", chatId))
        if (TelegramTd.errorText(resp) != null) return null
        return resp.takeIf { it.optString("@type") == "chat" }
    }

    /** Тип чата в человекочитаемом виде + признак канала/бота для Слоя 2. */
    private fun chatTypeName(chat: JSONObject): String =
        chat.optJSONObject("type")?.optString("@type")?.removePrefix("chatType") ?: ""

    private fun chatFlags(chat: JSONObject): Set<String> {
        val s = mutableSetOf<String>()
        val t = chat.optJSONObject("type")
        val tt = t?.optString("@type") ?: ""
        // Канал (супергруппа с is_channel) приравниваем к «официальным» для Слоя 2 (как у MAX).
        if (tt == "chatTypeSupergroup" && t?.optBoolean("is_channel") == true) s += "official"
        return s
    }

    private fun senderLabel(m: JSONObject): String {
        val sid = m.optJSONObject("sender_id") ?: return ""
        return when (sid.optString("@type")) {
            "messageSenderUser" -> "user:" + sid.optLong("user_id")
            "messageSenderChat" -> "chat:" + sid.optLong("chat_id")
            else -> ""
        }
    }

    /** user_id отправителя (или -1). 777000 — служебный аккаунт Telegram (коды входа). */
    private fun senderUserId(m: JSONObject): Long {
        val sid = m.optJSONObject("sender_id") ?: return -1L
        return if (sid.optString("@type") == "messageSenderUser") sid.optLong("user_id", -1L) else -1L
    }

    /** Текст сообщения из content. Для не-текстовых типов — плейсхолдер [тип]; caption если есть. */
    private fun extractText(m: JSONObject): String {
        val content = m.optJSONObject("content") ?: return ""
        val ctype = content.optString("@type")
        content.optJSONObject("text")?.let { if (!it.isNull("text")) return it.optString("text") }
        content.optJSONObject("caption")?.let { if (!it.isNull("text") && it.optString("text").isNotBlank()) return it.optString("text") }
        return "[${ctype.removePrefix("message")}]"
    }
}
