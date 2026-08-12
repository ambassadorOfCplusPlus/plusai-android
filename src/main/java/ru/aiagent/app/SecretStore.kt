package ru.aiagent.app

import android.content.Context
import ru.aiagent.app.cloud.SecureKeys
import ru.aiagent.core.agent.SecretGate
import ru.aiagent.core.agent.SecretVault

/**
 * Хранилище тайн `{{secret:имя}}` для Android — паритет CLI/десктопа (plusai secret), но на устройстве.
 * Значения лежат в EncryptedSharedPreferences ([SecureKeys], Android Keystore под капотом) и в облако
 * никогда не уходят: в модель уходит плейсхолдер, реальное значение подставляется через [SecretGate]
 * в последний момент перед исполнением инструмента.
 *
 * Сейв ПРОЦЕССНЫЙ (одна точка подстановки на всё приложение): каждый раз загружается заново из
 * хранилища при старте/входе в раздел Настроек, и сразу подключается к [SecretGate].
 */
object SecretStore {
    private const val KEY_PAYLOAD = "secrets_payload"     // зашифрованный список (значения!)
    private const val KEY_ENABLED = "secrets_enabled"     // подстановка активна
    private const val KEY_AUTODETECT = "secrets_autodetect" // авто-выявление тайн по образцу

    /** Проверить: подстановка включена пользователем. */
    fun enabled(context: Context): Boolean =
        SecureKeys.get(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, v: Boolean) {
        SecureKeys.get(context).edit().putBoolean(KEY_ENABLED, v).apply()
        reconnect(context) // перепривязать сейв (enabled поменялся)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    /** Авто-выявление (карты/токены/пароли по образцу) — по умолчанию вкл, когда маскирование вкл. */
    fun autodetect(context: Context): Boolean =
        SecureKeys.get(context).getBoolean(KEY_AUTODETECT, true)

    fun setAutodetect(context: Context, v: Boolean) {
        SecureKeys.get(context).edit().putBoolean(KEY_AUTODETECT, v).apply()
        load(context)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    /** Загрузить сейв из Keychain-хранилища и подключить в петлю агента. Дёшево — зовём на старте. */
    fun load(context: Context): SecretVault {
        val s = SecureKeys.get(context)
        val vault = SecretVault().apply {
            enabled = s.getBoolean(KEY_ENABLED, false)
            autoDetect = s.getBoolean(KEY_AUTODETECT, true)
            load(s.getString(KEY_PAYLOAD, "") ?: "")
        }
        SecretGate.use(vault)
        return vault
    }

    fun reconnect(context: Context) = load(context)

    /** Сохранить текущий сейв (после правки списка в настройках). */
    fun save(context: Context, vault: SecretVault) {
        SecureKeys.get(context).edit().putString(KEY_PAYLOAD, vault.serialize()).apply()
        SecretGate.use(vault)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }
}