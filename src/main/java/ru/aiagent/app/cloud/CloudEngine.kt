package ru.aiagent.app.cloud

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.aiagent.core.cloud.CloudClients
import ru.aiagent.core.cloud.CloudConfig
import ru.aiagent.core.cloud.CloudProvider
import ru.aiagent.core.cloud.CloudRoute

/**
 * Облачный движок приложения (S3): выбирает провайдера и маршрут (прокси владельца или
 * BYOK), берёт ключ из зашифрованного хранилища, отдаёт стрим. Гибрид локаль↔облако:
 * используется при эскалации ([СЛОЖНО]) или явном выборе облачной модели.
 */
object CloudEngine {

    /** Настроено ли облако (есть ключ BYOK, свой endpoint, включён прокси владельца или free-тир Zen). */
    fun isConfigured(context: Context): Boolean {
        val s = SecureKeys.get(context)
        return s.getBoolean(KEY_PROXY_ENABLED, false) ||
            customEnabled(context) ||
            // CUSTOM исключаем: это маркер маршрута (свой endpoint), а не BYOK-провайдер с рабочим base URL;
            // его «настроенность» — это customEnabled выше, а не наличие ключа byok_CUSTOM.
            CloudProvider.values().any { it != CloudProvider.CUSTOM && !s.getString(keyOf(it), null).isNullOrBlank() } ||
            zenEnabled(context)
    }

    /** Включён ли «свой API» (свой OpenAI-совместимый endpoint пользователя): есть флаг + URL. */
    fun customEnabled(context: Context): Boolean {
        val s = SecureKeys.get(context)
        return s.getBoolean(KEY_CUSTOM_ENABLED, false) && !s.getString(KEY_CUSTOM_URL, null).isNullOrBlank()
    }

    /** Включён ли анонимный free-тир (без ключа, запрос напрямую). */
    fun zenEnabled(context: Context): Boolean = SecureKeys.get(context).getBoolean(KEY_ZEN_ENABLED, false)

    /** Включить/выключить free-тир Zen. Выключает прокси владельца (тир ходит напрямую). */
    fun setZen(context: Context, enabled: Boolean) {
        SecureKeys.get(context).edit()
            .putBoolean(KEY_ZEN_ENABLED, enabled)
            .apply()
        if (enabled) setProxyEnabled(context, false)
        CloudModels.invalidate()
    }

    /** Текущий провайдер по умолчанию (L4-порядок; первый настроенный). */
    fun defaultProvider(context: Context): CloudProvider? {
        val s = SecureKeys.get(context)
        if (s.getBoolean(KEY_PROXY_ENABLED, false)) {
            return runCatching { CloudProvider.valueOf(s.getString(KEY_PROXY_PROVIDER, CloudProvider.DEEPSEEK.name)!!) }
                .getOrDefault(CloudProvider.DEEPSEEK)
        }
        // CUSTOM НЕ выбираем как дефолт-провайдера: у CloudClients.of(CUSTOM) пустой base URL, и reply()
        // в НЕ-custom ветке построил бы "/v1/chat/completions" без хоста → malformed request. Свой endpoint
        // маршрутизируется отдельной customEnabled-веткой, а не как BYOK-провайдер.
        // Анонимный free-тир Zen (явно включён) — раньше BYOK-ключей: он бесплатный и настроен всегда.
        if (zenEnabled(context)) return CloudProvider.ZEN
        return CloudProvider.values().firstOrNull { it != CloudProvider.CUSTOM && !s.getString(keyOf(it), null).isNullOrBlank() }
    }

    fun reply(
        context: Context,
        prompt: String,
        model: String? = null,
        provider: CloudProvider? = defaultProvider(context),
        // Думающая модель на текстовой ветке: рассуждать, но не возвращать мысли (§7).
        reasoningExclude: Boolean = false,
    ): Flow<String> {
        val s = SecureKeys.get(context)
        // «Свой API» пользователя — приоритетный маршрут: прямое подключение к его OpenAI-совместимому
        // endpoint (его URL + его ключ), в обход прокси/кошелька. Работает независимо от выбранного provider.
        if (customEnabled(context)) {
            val url = (s.getString(KEY_CUSTOM_URL, null) ?: "").trim()
            // Модель СВОЕГО endpoint'а — из настройки custom_model (свой сервер не знает id из каталога
            // RouterAI/скидочного пула, который пользователь выбрал в шапке чата; тот стал бы 400/404).
            // Пустой custom_model → как крайний случай пробрасываем выбранную модель.
            val customModel = s.getString(KEY_CUSTOM_MODEL, null)?.takeIf { it.isNotBlank() }
            val cfg = CloudConfig(
                route = CloudRoute.Custom(url),
                // Пустой ключ → null (иначе ушёл бы заголовок «Authorization: Bearer » — часть серверов на нём 401).
                apiKey = s.getString(keyOf(CloudProvider.CUSTOM), null)?.takeIf { it.isNotBlank() },
                model = customModel ?: model,
                temperature = ru.aiagent.app.AppSettings.temperatureOverride(context),
                reasoningExclude = reasoningExclude,
            )
            return CloudClients.of(CloudProvider.CUSTOM).generate(prompt, cfg)
        }
        val p = provider ?: return emptyFlow()
        // Анонимный free-тир: запрос НАПРЯМУЮ на официальный бесплатный endpoint с телефона, без ключа
        // (Bearer не шлём — проверено, анонимный endpoint отвечает 200) и без прокси владельца.
        if (p == CloudProvider.ZEN) {
            val cfg = CloudConfig(
                route = CloudRoute.Byok(CloudProvider.ZEN),
                apiKey = null, // анонимный тир — Authorization не нужен
                model = model, // null → дефолт deepseek-v4-flash-free из CloudClients.of(ZEN)
                temperature = ru.aiagent.app.AppSettings.temperatureOverride(context),
                reasoningExclude = reasoningExclude,
            )
            return CloudClients.of(CloudProvider.ZEN).generate(prompt, cfg)
        }
        val proxy = s.getBoolean(KEY_PROXY_ENABLED, false)
        val cfg = CloudConfig(
            route = if (proxy) CloudRoute.OwnerProxy else CloudRoute.Byok(p),
            // Дефолт = тот же хост владельца, что и Account.DEFAULT_URL (api.plus-ai.ru): иначе при пустом
            // ключе URL Bearer кошелька ушёл бы на ДРУГОЙ хост (plus-ai.ru) — рассинхрон/утечка токена.
            proxyBaseUrl = s.getString(KEY_PROXY_URL, ru.aiagent.app.cloud.Account.DEFAULT_URL) ?: ru.aiagent.app.cloud.Account.DEFAULT_URL,
            apiKey = if (proxy) s.getString(KEY_PROXY_TOKEN, null) else s.getString(keyOf(p), null),
            model = model, // конкретная облачная модель (из каталога RouterAI)
            // Пользовательский override температуры (иначе сервер подставит рекомендованную для модели).
            temperature = ru.aiagent.app.AppSettings.temperatureOverride(context),
            reasoningExclude = reasoningExclude,
        )
        return CloudClients.of(p).generate(prompt, cfg)
    }

    // Настройки (запись из экрана настроек). Любая смена маршрута/ключа сбрасывает кэш каталога
    // (CloudModels.invalidate) — иначе показали бы цены/модели прошлого маршрута (в т.ч. сырые цены = утечка маржи).
    fun setByokKey(context: Context, provider: CloudProvider, key: String?) {
        SecureKeys.get(context).edit().putString(keyOf(provider), key?.trim()).apply()
        CloudModels.invalidate()
        AutoSync.schedulePush(context)
    }

    fun byokKey(context: Context, provider: CloudProvider): String? =
        SecureKeys.get(context).getString(keyOf(provider), null)

    fun setProxy(context: Context, enabled: Boolean, url: String?, token: String?, provider: CloudProvider) {
        SecureKeys.get(context).edit()
            .putBoolean(KEY_PROXY_ENABLED, enabled)
            .putString(KEY_PROXY_URL, url?.trim())
            .putString(KEY_PROXY_TOKEN, token?.trim())
            .putString(KEY_PROXY_PROVIDER, provider.name)
            .apply()
        CloudModels.invalidate()
        AutoSync.schedulePush(context)
    }

    /** (enabled, url, token) для UI Настроек. */
    fun proxyConfig(context: Context): Triple<Boolean, String, String> {
        val s = SecureKeys.get(context)
        return Triple(
            s.getBoolean(KEY_PROXY_ENABLED, false),
            s.getString(KEY_PROXY_URL, "https://api.plus-ai.ru") ?: "https://api.plus-ai.ru",
            s.getString(KEY_PROXY_TOKEN, "") ?: "",
        )
    }

    fun setProxyCfg(context: Context, enabled: Boolean, url: String, token: String) {
        SecureKeys.get(context).edit()
            .putBoolean(KEY_PROXY_ENABLED, enabled)
            .putString(KEY_PROXY_URL, url.trim())
            .putString(KEY_PROXY_TOKEN, token.trim())
            .apply()
        CloudModels.invalidate()
        AutoSync.schedulePush(context)
    }

    /** Полностью стереть маршрут прокси (при выходе из аккаунта — токен = доступ к кошельку). */
    fun clearProxy(context: Context) {
        SecureKeys.get(context).edit()
            .putBoolean(KEY_PROXY_ENABLED, false)
            .remove(KEY_PROXY_URL).remove(KEY_PROXY_TOKEN).remove(KEY_PROXY_PROVIDER)
            .apply()
        CloudModels.invalidate()
    }

    /** Проверить баланс кошелька на сервере (GET /v1/wallet/balance). */
    suspend fun checkBalance(url: String, token: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val u = java.net.URL("${url.trimEnd('/')}/v1/wallet/balance")
            val c = (u.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token"); connectTimeout = 8000; readTimeout = 8000
            }
            val body = try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                c.disconnect() // закрываем соединение и на путях ошибки (401/500/сеть)
            }
            // Строгий парс (A8): отличаем реальный баланс от иной схемы/ошибки сервера,
            // а не показываем «0 ₽»/«?» вслепую при неожиданном ответе.
            val json = org.json.JSONObject(body)
            when {
                json.has("balance_rub") -> "баланс: ${json.getDouble("balance_rub")} ₽"
                json.has("error") -> "баланс недоступен: ${json.optString("error").take(60)}"
                else -> "баланс: неожиданный ответ сервера"
            }
        }.getOrElse { "ошибка: ${it.message?.take(60)}" }
    }

    /**
     * (baseUrl, token) для ПРЯМЫХ нестримовых вызовов (нативный function-calling) через прокси
     * владельца. null — если прокси-режим не включён (BYOK/без токена): тогда агент откатывается
     * на текстовый tool-протокол. FC требует серверного проброса `tools` в RouterAI.
     */
    fun proxyEndpoint(context: Context): Pair<String, String>? {
        val s = SecureKeys.get(context)
        // «Свой API» имеет приоритет: если он включён, НЕ отдаём эндпоинт прокси владельца — иначе нативный
        // function-calling (CloudFunctionAgent) при одновременно включённом прокси ушёл бы на сервер владельца
        // (списание кошелька + данные туда, хотя пользователь выбрал свой endpoint именно чтобы этого избежать).
        // null → агент падает на текстовый tool-протокол, а тот идёт через reply() → свой endpoint.
        if (customEnabled(context)) return null
        if (!s.getBoolean(KEY_PROXY_ENABLED, false)) return null
        val url = (s.getString(KEY_PROXY_URL, null) ?: "").trim()
        val token = (s.getString(KEY_PROXY_TOKEN, null) ?: "").trim()
        return if (url.isBlank() || token.isBlank()) null else url to token
    }

    /** Включить/выключить маршрут прокси, СОХРАНив url/токен (в отличие от setProxyCfg с пустыми полями,
     *  который затирал бы Bearer кошелька). Нужно при подключении «своего API»: прокси уступает приоритет,
     *  но токен кошелька остаётся — при отключении своего endpoint облако снова работает без ре-логина. */
    fun setProxyEnabled(context: Context, enabled: Boolean) {
        SecureKeys.get(context).edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply()
        CloudModels.invalidate()
        AutoSync.schedulePush(context)
    }

    // --- «Свой API» (свой OpenAI-совместимый endpoint пользователя): URL + ключ + дефолт-модель. ---

    /** Сохранить свой endpoint. Пустой url → выключаем (enabled=false), чтобы не роутить в никуда. */
    fun setCustomEndpoint(context: Context, enabled: Boolean, url: String, key: String?, model: String?) {
        SecureKeys.get(context).edit()
            .putBoolean(KEY_CUSTOM_ENABLED, enabled && url.isNotBlank())
            .putString(KEY_CUSTOM_URL, url.trim())
            .putString(keyOf(CloudProvider.CUSTOM), key?.trim())
            .putString(KEY_CUSTOM_MODEL, model?.trim())
            .apply()
        CloudModels.invalidate()
        AutoSync.schedulePush(context)
    }

    /** Только выключить/включить «свой API», СОХРАНив url/ключ/модель (в отличие от setCustomEndpoint с
     *  пустыми полями, который их стирает). Нужно при сохранении ДРУГОГО маршрута (BYOK), чтобы не потерять
     *  настроенный endpoint пользователя. */
    fun setCustomEnabled(context: Context, enabled: Boolean) {
        SecureKeys.get(context).edit().putBoolean(KEY_CUSTOM_ENABLED, enabled).apply()
        CloudModels.invalidate()
        AutoSync.schedulePush(context)
    }

    /** (enabled, url, key, model) для UI. */
    fun customEndpoint(context: Context): CustomEndpoint {
        val s = SecureKeys.get(context)
        return CustomEndpoint(
            enabled = s.getBoolean(KEY_CUSTOM_ENABLED, false),
            url = s.getString(KEY_CUSTOM_URL, "") ?: "",
            key = s.getString(keyOf(CloudProvider.CUSTOM), "") ?: "",
            model = s.getString(KEY_CUSTOM_MODEL, "") ?: "",
        )
    }

    data class CustomEndpoint(val enabled: Boolean, val url: String, val key: String, val model: String)

    private fun keyOf(p: CloudProvider) = "byok_${p.name}"
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_URL = "proxy_url"
    private const val KEY_PROXY_TOKEN = "proxy_token"
    private const val KEY_PROXY_PROVIDER = "proxy_provider"
    private const val KEY_CUSTOM_ENABLED = "custom_enabled"
    private const val KEY_CUSTOM_URL = "custom_url"
    private const val KEY_CUSTOM_MODEL = "custom_model"
    private const val KEY_ZEN_ENABLED = "zen_enabled"
}

/** Зашифрованное хранилище ключей (Android Keystore под капотом). */
object SecureKeys {
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun get(context: Context): android.content.SharedPreferences {
        prefs?.let { return it }
        return synchronized(this) {
            prefs?.let { return it } // повторная проверка под локом (EncryptedSharedPreferences не потокобезопасен при создании)
            create(context).also { prefs = it }
        }
    }

    /** true, если Keystore недоступен и секреты хранятся в незашифрованном фолбэке — UI обязан предупредить. */
    @Volatile var usingInsecureFallback = false
        private set

    private fun create(context: Context): android.content.SharedPreferences {
        val ctx = context.applicationContext
        fun build(): android.content.SharedPreferences {
            val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                ctx, "plusai_secure", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        // Несколько попыток БЕЗ разрушения: разовый сбой Keystore (direct-boot, устройство залочено,
        // глюк вендора) обычно проходит с короткой задержкой. Раньше ПЕРВАЯ же осечка стирала всё
        // хранилище (Bearer кошелька, BYOK-ключи, токены почты/Диска) — пользователь молча вылетал из
        // аккаунта и всех интеграций из-за временной ошибки.
        val state = ctx.getSharedPreferences("plusai_secure_state", Context.MODE_PRIVATE)
        repeat(3) { attempt ->
            runCatching {
                val p = build()
                state.edit().putInt("create_fails", 0).apply() // успех — сбрасываем счётчик
                return p
            }
            if (attempt < 2) runCatching { Thread.sleep(120L * (attempt + 1)) }
        }
        // Не поднялось в ЭТОЙ сессии. НЕ стираем сразу: длительный транзиентный сбой (direct-boot,
        // холодный старт до разблокировки) прошёл бы за секунды — стирание потеряло бы Bearer/BYOK
        // безвозвратно. Считаем неудачи ПО ЗАПУСКАМ: стираем (реальная порча — напр. ротация ключа)
        // только если хранилище не открывается НЕСКОЛЬКО запусков подряд.
        val fails = state.getInt("create_fails", 0) + 1
        state.edit().putInt("create_fails", fails).apply()
        if (fails >= 3) {
            runCatching {
                ctx.deleteSharedPreferences("plusai_secure")
                state.edit().putInt("create_fails", 0).apply()
                return build()
            }
        }
        // Keystore реально недоступен (редкие прошивки): помечаем режим деградации, чтобы UI
        // явно предупредил пользователя, а не тихо хранил Bearer/ключи открытым текстом.
        usingInsecureFallback = true
        return ctx.getSharedPreferences("plusai_secure_fallback", Context.MODE_PRIVATE)
    }
}
