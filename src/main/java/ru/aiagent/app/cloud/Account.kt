package ru.aiagent.app.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.cloud.CloudProvider
import java.net.HttpURLConnection
import java.net.URL

/**
 * Аккаунт пользователя на сервере владельца (логин/пароль/почта). Токен, полученный
 * при входе/регистрации, — это Bearer прокси-кошелька: после успеха мы сразу включаем
 * маршрут OwnerProxy на этот аккаунт. Пароль на устройстве не хранится (только токен).
 */
object Account {

    const val DEFAULT_URL = "https://api.plus-ai.ru"

    data class Session(val login: String, val email: String, val token: String, val url: String)

    /** Текущая сессия, если пользователь вошёл. */
    fun current(context: Context): Session? {
        val s = SecureKeys.get(context)
        val token = s.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val login = s.getString(KEY_LOGIN, "") ?: ""
        val email = s.getString(KEY_EMAIL, "") ?: ""
        val url = s.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        return Session(login, email, token, url)
    }

    fun isLoggedIn(context: Context): Boolean = current(context) != null

    // ── Кросс-девайс синк (E2E) ──────────────────────────────────────────────────────────────────
    // Содержимое шифруется на устройстве (SyncCrypto, ключ от синк-пароля + логина); сервер видит только
    // шифроблоб (§7). Пароль хранится в SecureKeys, как токен. Совместимо с десктоп/CLI (тот же формат).

    /** Синк-пароль (пусто — не настроен). */
    fun syncPassphrase(context: Context): String =
        SecureKeys.get(context).getString(KEY_SYNC_PASS, "") ?: ""

    fun setSyncPassphrase(context: Context, value: String) {
        SecureKeys.get(context).edit().putString(KEY_SYNC_PASS, value).apply()
    }

    // ── Автосинк: ключ E2E от ПАРОЛЯ АККАУНТА ────────────────────────────────────────────────────────
    // Пароль хранится в SecureKeys (Keystore), как токен. Из него выводится ключ синка (SyncCrypto.
    // deriveKey(пароль, login)) — тот же ключ получается на любом устройстве под тем же аккаунтом,
    // поэтому отдельный синк-пароль настраивать не нужно. При смене пароля ключ меняется, старые
    // блобы нечитаемы → синк сбрасывается (POST /v1/sync/reset) и начинается заново.

    private const val KEY_ACCOUNT_PASS = "acct_password"

    /** Пароль аккаунта (для ключа автосинка). Пусто — не сохранён (не входили с паролем). */
    fun accountPassword(context: Context): String =
        SecureKeys.get(context).getString(KEY_ACCOUNT_PASS, "") ?: ""

    fun setAccountPassword(context: Context, value: String) {
        SecureKeys.get(context).edit().putString(KEY_ACCOUNT_PASS, value).apply()
    }

    /** Сбросить ВЕСЬ синк аккаунта на сервере (смена пароля: старый ключ больше не подходит). */
    suspend fun syncReset(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(Exception("нужен вход в аккаунт"))
        runCatching {
            val (code, body) = post("${sess.url.trimEnd('/')}/v1/sync/reset", JSONObject(), sess.token)
            if (code !in 200..299) error(errMsg(body, code))
            SecureKeys.get(context).edit().remove(KEY_SYNC_PASS).remove("sync_cursor").apply()
            Unit
        }
    }

    /** Выгрузить один элемент синка. text=null → tombstone (удаление). */
    suspend fun syncPush(context: Context, key: ByteArray, id: String, text: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            val sess = current(context) ?: return@withContext Result.failure(Exception("нужен вход в аккаунт"))
            val item = JSONObject().put("id", id).put("deleted", text == null)
                .put("data", if (text == null) "" else SyncCrypto.encrypt(key, text))
            val body = JSONObject().put("items", JSONArray().put(item))
            val (code, resp) = post("${sess.url.trimEnd('/')}/v1/sync/push", body, sess.token)
            if (code in 200..299) Result.success(Unit) else Result.failure(Exception(errMsg(resp, code)))
        }

    /** Забрать элемент синка по id. success(null) — на сервере нет; success(text) — расшифровано.
     *  failure — сеть/авторизация ИЛИ есть, но не расшифровалось (не тот синк-пароль). */
    suspend fun syncPull(context: Context, key: ByteArray, id: String): Result<String?> =
        withContext(Dispatchers.IO) {
            val sess = current(context) ?: return@withContext Result.failure(Exception("нужен вход в аккаунт"))
            var since = 0L
            var found: JSONObject? = null
            var done = false
            var guard = 0
            while (!done && guard++ < 1000) {
                val (code, resp) = get("${sess.url.trimEnd('/')}/v1/sync/pull?since=$since&limit=500", sess.token)
                if (code !in 200..299) return@withContext Result.failure(Exception(errMsg(resp, code)))
                val o = JSONObject(resp)
                val arr = o.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.getJSONObject(i)
                    if (it.optString("id") == id) found = it
                }
                since = o.optLong("cursor", since)
                done = o.optBoolean("done", true)
            }
            val f = found ?: return@withContext Result.success(null)
            if (f.optBoolean("deleted", false)) return@withContext Result.success(null)
            val data = f.optString("data", "")
            if (data.isBlank()) return@withContext Result.success(null)
            val text = SyncCrypto.decrypt(key, data)
                ?: return@withContext Result.failure(Exception("не расшифровалось — тот ли синк-пароль и логин?"))
            Result.success(text)
        }

    private fun errMsg(resp: String, code: Int): String =
        runCatching { JSONObject(resp).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

    suspend fun register(context: Context, url: String, login: String, password: String, email: String): Result<Session> {
        val res = call(context, url, "/v1/auth/register", JSONObject().put("login", login).put("password", password).put("email", email))
        if (res.isSuccess) rememberPassword(context, login, password)
        return res
    }

    suspend fun login(context: Context, url: String, login: String, password: String): Result<Session> {
        val res = call(context, url, "/v1/auth/login", JSONObject().put("login", login).put("password", password))
        if (res.isSuccess) rememberPassword(context, login, password)
        return res
    }

    /** Сброс пароля 6-значным кодом из письма (сервер ротирует токен). */
    suspend fun resetByEmail(context: Context, url: String, login: String, code: String, newPassword: String): Result<Session> {
        val res = call(context, url, "/v1/auth/email/reset", JSONObject().put("login", login).put("code", code).put("password", newPassword))
        if (res.isSuccess) {
            rememberPassword(context, login, newPassword)
            // Ключ синка сменился → старые блобы не расшифровать: стираем синк и пушим заново.
            syncReset(context)
            ru.aiagent.app.cloud.AutoSync.schedulePush(context)
        }
        return res
    }

    /** Запомнить пароль аккаунта в Keystore (для ключа автосинка). Отдельная фраза не нужна. */
    private fun rememberPassword(context: Context, login: String, password: String) {
        if (login.isBlank() || password.isBlank()) return
        // Совместимость со старым синком: если пользователь уже задавал ОТДЕЛЬНЫЙ синк-пароль, его не
        // трогаем — иначе ключ сменится и старая история перестанет расшифровываться.
        if (syncPassphrase(context).isNotEmpty() && syncPassphrase(context) != password) return
        setSyncPassphrase(context, password)
        setAccountPassword(context, password)
        SecureKeys.get(context).edit().remove("sync_cursor").apply() // начинаем с нуля — вдруг пароль менялся
    }

    /** Попросить сервер отправить код сброса на привязанную почту. */
    suspend fun requestEmailRecover(context: Context, url: String, login: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = url.trim().ifBlank { DEFAULT_URL }.trimEnd('/')
            val (code, body) = post("$base/v1/auth/email/recover", JSONObject().put("login", login), null)
            if (code !in 200..299) error(errorOf(code, body))
            Unit
        }
    }

    /** Прислать код подтверждения почты (на свою почту; нужен вход). */
    suspend fun requestEmailVerify(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post("${sess.url.trimEnd('/')}/v1/auth/email/request", JSONObject(), sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            Unit
        }
    }

    /** Подтвердить почту кодом из письма. */
    suspend fun verifyEmail(context: Context, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (c, body) = post("${sess.url.trimEnd('/')}/v1/auth/email/verify", JSONObject().put("code", code), sess.token)
            if (c !in 200..299) error(errorOf(c, body))
            Unit
        }
    }

    /** Сменить пароль (нужен текущий токен). Ключ синка меняется → синк сбрасывается и пушится заново. */
    suspend fun changePassword(context: Context, old: String, next: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post("${sess.url.trimEnd('/')}/v1/auth/password", JSONObject().put("old", old).put("new", next), sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            rememberPassword(context, sess.login, next)
            syncReset(context)
            AutoSync.schedulePush(context)
            Unit
        }
    }

    /** Полное состояние кошелька/подписки (GET /v1/wallet/balance). */
    data class BalanceInfo(
        val balanceRub: Double,
        val admin: Boolean,
        val subActive: Boolean,
        val subLeftRub: Double,
        val subLimitRub: Double,
        val subExpires: String,
        val emailVerified: Boolean,
        val mailEnabled: Boolean,
        val betaMode: Boolean,
        val spent5hRub: Double,
        val cap5hRub: Double,
        val spentWeekRub: Double,
        val capWeekRub: Double,
    )

    /** Баланс одного апстрима (для админ-панели: балансы каждого API отдельно). */
    data class ProviderBal(val name: String, val balance: Double, val currency: String, val note: String, val error: String)

    /** Балансы всех провайдеров отдельно (GET /v1/admin/provider-balances, только админ). */
    suspend fun providerBalances(context: Context): Result<List<ProviderBal>> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = get("${sess.url.trimEnd('/')}/v1/admin/provider-balances", sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            val arr = JSONObject(body).optJSONArray("providers") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ProviderBal(
                    name = o.optString("name"),
                    balance = o.optDouble("balance", 0.0),
                    currency = o.optString("currency"),
                    note = if (o.isNull("note")) "" else o.optString("note"),
                    error = if (o.isNull("error")) "" else o.optString("error"),
                )
            }
        }
    }

    /** Ручной выбор провайдера админом ("auto" = самый дешёвый) + список доступных. */
    data class PreferProvider(val current: String, val available: List<String>)

    private fun parsePrefer(body: String): PreferProvider {
        val j = JSONObject(body)
        val arr = j.optJSONArray("available") ?: org.json.JSONArray()
        return PreferProvider(j.optString("current"), (0 until arr.length()).map { arr.optString(it) })
    }

    suspend fun preferProvider(context: Context): Result<PreferProvider> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = get("${sess.url.trimEnd('/')}/v1/admin/prefer-provider", sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            parsePrefer(body)
        }
    }

    suspend fun setPreferProvider(context: Context, provider: String): Result<PreferProvider> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post("${sess.url.trimEnd('/')}/v1/admin/prefer-provider", JSONObject().put("provider", provider), sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            parsePrefer(body)
        }
    }

    suspend fun balance(context: Context): Result<BalanceInfo> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = get("${sess.url.trimEnd('/')}/v1/wallet/balance", sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            val j = JSONObject(body)
            BalanceInfo(
                balanceRub = j.optDouble("balance_rub", 0.0),
                admin = j.optBoolean("admin", false),
                subActive = j.optBoolean("sub_active", false),
                subLeftRub = j.optDouble("sub_left_rub", 0.0),
                subLimitRub = j.optDouble("sub_limit_rub", 0.0),
                subExpires = j.optString("sub_expires", ""),
                emailVerified = j.optBoolean("email_verified", false),
                mailEnabled = j.optBoolean("mail_enabled", false),
                spent5hRub = j.optDouble("spent_5h_rub", 0.0),
                cap5hRub = j.optDouble("cap_5h_rub", 0.0),
                spentWeekRub = j.optDouble("spent_week_rub", 0.0),
                capWeekRub = j.optDouble("cap_week_rub", 0.0),
                betaMode = j.optBoolean("beta_mode", false),
            )
        }
    }

    /** Ставки с сервера (ТЗ: ставки — конфиг): ползунок поддержки + тарифы BYOK-Pro. */
    data class SupportRates(
        val min: Double, val max: Double, val def: Double,
        val byokTier1Rub: Int, val byokTier2Rub: Int,
    )

    suspend fun rates(context: Context): SupportRates = withContext(Dispatchers.IO) {
        val url = current(context)?.url ?: DEFAULT_URL
        runCatching {
            val (code, body) = get("${url.trimEnd('/')}/v1/rates/public", null)
            if (code !in 200..299) error("http $code")
            val j = JSONObject(body)
            SupportRates(
                min = j.optDouble("topup_support_min", 0.005),
                max = j.optDouble("topup_support_max", 0.05),
                def = j.optDouble("topup_support_default", 0.005),
                byokTier1Rub = j.optDouble("byok_pro_tier1_rub", 200.0).toInt(),
                byokTier2Rub = j.optDouble("byok_pro_tier2_rub", 500.0).toInt(),
            )
        }.getOrDefault(SupportRates(0.005, 0.05, 0.005, 200, 500))
    }

    /** Пополнение кошелька (тест-режим на сервере до подключения СБП). Возвращает новый баланс. */
    suspend fun topUp(context: Context, amountRub: Double, support: Double): Result<Double> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post(
                "${sess.url.trimEnd('/')}/v1/wallet/topup",
                JSONObject().put("amount_rub", amountRub).put("support", support), sess.token,
            )
            if (code !in 200..299) error(errorOf(code, body))
            JSONObject(body).optDouble("balance_rub", 0.0)
        }
    }

    /** Тариф подписки (BILL-TIERS): фикс-цена + класс доступа моделей + fair-use лимит. */
    data class Plan(
        val id: String, val name: String,
        val priceRub: Double, val yourPriceRub: Double, // yourPrice — с учётом персональной скидки
        val cls: String, val fairUseRub: Double,
    )

    /** Список тарифов с сервера (GET /v1/plans). Токен — для персональной скидки на цену. */
    suspend fun fetchPlans(context: Context): Result<List<Plan>> = withContext(Dispatchers.IO) {
        val url = current(context)?.url ?: DEFAULT_URL
        val token = current(context)?.token
        runCatching {
            val (code, body) = get("${url.trimEnd('/')}/v1/plans", token)
            if (code !in 200..299) error(errorOf(code, body))
            val arr = JSONObject(body).optJSONArray("plans") ?: org.json.JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Plan(
                    id = o.optString("id"), name = o.optString("name"),
                    priceRub = o.optDouble("price_rub", 0.0),
                    yourPriceRub = o.optDouble("your_price_rub", o.optDouble("price_rub", 0.0)),
                    cls = o.optString("class"), fairUseRub = o.optDouble("fair_use_rub", 0.0),
                )
            }
        }
    }

    /** Оформить фиксированный тариф (POST /v1/subscription/buy-plan). */
    suspend fun buyPlan(context: Context, planId: String): Result<String> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post(
                "${sess.url.trimEnd('/')}/v1/subscription/buy-plan",
                JSONObject().put("plan", planId), sess.token,
            )
            if (code !in 200..299) error(errorOf(code, body))
            val j = JSONObject(body)
            "тариф «${j.optString("plan")}» до ${j.optString("sub_expires", "").take(10)}"
        }
    }

    /** Разовая покупка BYOK-Pro (свой ключ без комиссии). tier — 200 или 500 ₽. */
    suspend fun buyByokPro(context: Context, tier: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post(
                "${sess.url.trimEnd('/')}/v1/byok-pro/buy",
                JSONObject().put("tier", tier), sess.token,
            )
            if (code !in 200..299) error(errorOf(code, body))
            Unit
        }
    }

    fun logout(context: Context) {
        SecureKeys.get(context).edit()
            .remove(KEY_TOKEN).remove(KEY_LOGIN).remove(KEY_EMAIL).remove(KEY_URL)
            .apply()
        CloudEngine.clearProxy(context) // стираем Bearer кошелька, а не только выключаем прокси
    }

    // ── ИИ-бот Telegram: сервер хостит бота владельца (обычный + бизнес). Пульт к /v1/tgbot/* ──
    // Бота создаёт сам пользователь у @BotFather; токен уходит на сервер только в теле POST, наружу
    // не возвращается (сервер хранит шифрованно). Модель — как в чате (id облачной модели).

    /** Бот, как его отдаёт сервер (без токена). */
    data class TgBotView(
        val id: String, val botUsername: String, val instructions: String,
        val mode: String, val model: String, val enabled: Boolean,
        val maxDayRub: Long, val maxAgentSteps: Int, val businessConnections: Int,
    )

    private fun parseBot(o: JSONObject) = TgBotView(
        id = o.optString("id"),
        botUsername = o.optString("bot_username"),
        instructions = o.optString("instructions"),
        mode = o.optString("mode", "both"),
        model = o.optString("model"),
        enabled = o.optBoolean("enabled", false),
        maxDayRub = o.optLong("max_day_rub", 0),
        maxAgentSteps = o.optInt("max_agent_steps", 10),
        businessConnections = o.optInt("business_connections", 0),
    )

    /** Список ботов владельца. */
    suspend fun tgBots(context: Context): Result<List<TgBotView>> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = get("${sess.url.trimEnd('/')}/v1/tgbot/list", sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            val arr = JSONObject(body).optJSONArray("bots") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseBot) }
        }
    }

    /**
     * Зарегистрировать бота по токену @BotFather. mode: regular|business|both. maxDayRub — суточный потолок.
     * name/description — профиль бота (setMyName/Description). greeting — приветствие на /start (обычный режим).
     */
    suspend fun registerTgBot(
        context: Context, botToken: String, instructions: String, mode: String, model: String,
        name: String, description: String, greeting: String, maxDayRub: Long, maxAgentSteps: Int,
    ): Result<TgBotView> =
        withContext(Dispatchers.IO) {
            val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
            runCatching {
                val (code, body) = post(
                    "${sess.url.trimEnd('/')}/v1/tgbot/register",
                    JSONObject().put("token", botToken).put("instructions", instructions).put("mode", mode)
                        .put("model", model).put("name", name).put("description", description)
                        .put("greeting", greeting).put("max_day_rub", maxDayRub).put("max_agent_steps", maxAgentSteps),
                    sess.token,
                )
                Log.d("PlusAI", "tgbot/register code=$code body=${body.take(200)}")
                if (code !in 200..299) error(errorOf(code, body))
                parseBot(JSONObject(body))
            }
        }

    /** Включить/выключить бота (раннер на сервере стартует/глохнет). */
    suspend fun setTgBotEnabled(context: Context, id: String, enabled: Boolean): Result<TgBotView> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post(
                "${sess.url.trimEnd('/')}/v1/tgbot/update",
                JSONObject().put("id", id).put("enabled", enabled), sess.token,
            )
            if (code !in 200..299) error(errorOf(code, body))
            parseBot(JSONObject(body))
        }
    }

    /** Изменить настройки бота (max_agent_steps и др.). */
    suspend fun updateTgBot(context: Context, id: String, maxAgentSteps: Int? = null, instructions: String? = null, model: String? = null): Result<TgBotView> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val obj = JSONObject().put("id", id)
            if (maxAgentSteps != null) obj.put("max_agent_steps", maxAgentSteps)
            if (instructions != null) obj.put("instructions", instructions)
            if (model != null) obj.put("model", model)
            val (code, body) = post("${sess.url.trimEnd('/')}/v1/tgbot/update", obj, sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            parseBot(JSONObject(body))
        }
    }

    /** Перезапустить бота. */
    suspend fun restartTgBot(context: Context, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post("${sess.url.trimEnd('/')}/v1/tgbot/restart", JSONObject().put("id", id), sess.token)
            Log.d("PlusAI", "tgbot/restart code=$code")
            if (code !in 200..299) error(errorOf(code, body))
        }
    }

    /** Удалить бота (гасит раннера на сервере). */
    suspend fun deleteTgBot(context: Context, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = current(context) ?: return@withContext Result.failure(IllegalStateException("не выполнен вход"))
        runCatching {
            val (code, body) = post("${sess.url.trimEnd('/')}/v1/tgbot/delete", JSONObject().put("id", id), sess.token)
            if (code !in 200..299) error(errorOf(code, body))
            Unit
        }
    }

    private suspend fun call(context: Context, url: String, path: String, req: JSONObject): Result<Session> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = url.trim().ifBlank { DEFAULT_URL }.trimEnd('/')
                // Не отправляем логин/пароль по открытому HTTP (опечатка в URL сервера).
                require(base.startsWith("https://") || base.startsWith("http://127.0.0.1") || base.startsWith("http://localhost")) {
                    "адрес сервера должен быть https://"
                }
                val (code, body) = post("$base$path", req, null)
                if (code !in 200..299) error(errorOf(code, body))
                val j = JSONObject(body)
                val sess = Session(
                    login = j.optString("login"),
                    email = j.optString("email"),
                    token = j.getString("token"),
                    url = base,
                )
                // Сохраняем сессию и включаем маршрут прокси на этот аккаунт.
                SecureKeys.get(context).edit()
                    .putString(KEY_TOKEN, sess.token)
                    .putString(KEY_LOGIN, sess.login)
                    .putString(KEY_EMAIL, sess.email)
                    .putString(KEY_URL, base)
                    .apply()
                // Провайдер — DEEPSEEK (OpenAI-совместимый путь /v1/chat/completions на нашем
                // сервере). YANDEX/GIGACHAT-маршруты (/v1/proxy/*) сервер не обслуживает.
                CloudEngine.setProxy(context, enabled = true, url = base, token = sess.token, provider = CloudProvider.DEEPSEEK)
                sess
            }
        }

    private fun get(fullUrl: String, bearer: String?): Pair<Int, String> {
        val c = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 10000; readTimeout = 15000
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    private fun post(fullUrl: String, body: JSONObject, bearer: String?): Pair<Int, String> {
        val (code, text) = postOnce(fullUrl, body, bearer, null)
        // Анти-абуз PoW: сервер (за флагом AIAGENT_POW_ENFORCE) отвечает 428 {pow_required,challenge}.
        // Решаем «хеш-ловушку» прозрачно и повторяем запрос один раз — обычному клиенту доли секунды.
        if (code == 428) {
            val challenge = runCatching {
                val j = JSONObject(text)
                if (j.optBoolean("pow_required")) j.optString("challenge") else ""
            }.getOrDefault("")
            if (challenge.isNotBlank()) {
                PowSolver.solve(challenge)?.let { return postOnce(fullUrl, body, bearer, it) }
            }
        }
        return code to text
    }

    private fun postOnce(fullUrl: String, body: JSONObject, bearer: String?, powHeader: String?): Pair<Int, String> {
        val bodyBytes = body.toString().toByteArray()
        val url = URL(fullUrl)
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            powHeader?.let { setRequestProperty("X-PlusAI-PoW", it) }
            // Per-request подпись (defense-in-depth; сервер сверяет за флагом AIAGENT_SIGN_ENFORCE,
            // иначе игнорирует заголовки). Аутентифицированный запрос → ключ из токена, иначе общий.
            val key = if (bearer != null) ReqSig.deriveKey(bearer) else DEFAULT_SIGN_KEY.toByteArray()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val nonce = ReqSig.newNonce()
            setRequestProperty("X-PlusAI-TS", ts)
            setRequestProperty("X-PlusAI-Nonce", nonce)
            // url.file = путь+query — зеркалит серверный r.URL.RequestURI(), поэтому подпись не сломается,
            // если у ручки появится query-параметр (url.path отбросил бы его). Сейчас query нет — эквивалентно.
            setRequestProperty("X-PlusAI-Sig", ReqSig.sign(key, "POST", url.file, ts, nonce, bodyBytes))
            connectTimeout = 10000; readTimeout = 15000
        }
        try {
            c.outputStream.use { it.write(bodyBytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect() // закрываем соединение и на путях с исключением
        }
    }

    // Достаём {"error":...} из тела; если тело не наше JSON — показываем HTTP-код,
    // а не «ошибка сети» (запрос дошёл до сервера, это его ответ).
    private fun errorOf(code: Int, body: String): String =
        runCatching { JSONObject(body).optString("error").ifBlank { "ошибка сервера ($code)" } }
            .getOrDefault("ошибка сервера ($code)")

    // Ключ подписи по умолчанию для неаутентифицированных запросов — маркер схемы, совпадает с
    // server DefaultSignKey. Не секрет (открыт), а порог: подпись + PoW отсекают не-наши клиенты.
    private const val DEFAULT_SIGN_KEY = "plusai/reqsig/v1/default-key"

    private const val KEY_TOKEN = "acct_token"
    private const val KEY_LOGIN = "acct_login"
    private const val KEY_EMAIL = "acct_email"
    private const val KEY_URL = "acct_url"
    private const val KEY_SYNC_PASS = "sync_passphrase"
}
