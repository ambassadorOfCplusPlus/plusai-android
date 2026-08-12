package ru.aiagent.app.remote

import android.content.Context
import org.json.JSONObject

/**
 * Стабильный identity-ключ устройства (зеркало десктопного `DeviceIdentity`).
 *
 * ЗАЧЕМ. Раньше [E2ECrypto] создавался заново при каждом старте (и отдельно для ролей контроллера
 * `PcScreen` и хоста `RemoteAgentService`) — публичный ключ менялся каждую сессию, поэтому
 * персистентный TOFU-пиннинг и сверка SAS были бессмысленны. Теперь ключ сохраняется и
 * переиспользуется: публичный ключ = стабильная identity, ОДНА на обе роли.
 *
 * Персистенс инъектируется ([load]/[save] JSON с приватным+публичным ключом), чтобы тест был
 * герметичным; прод-обёртка [fromPrefs] хранит в SharedPreferences.
 */
object DeviceIdentity {
    fun loadOrCreate(load: () -> String?, save: (String) -> Unit): E2ECrypto {
        val raw = load()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val o = JSONObject(raw)
                return E2ECrypto.fromKeys(o.getString("priv"), o.getString("pub"))
            }
        }
        val crypto = E2ECrypto()
        val json = JSONObject()
            .put("priv", crypto.exportPrivateKeyBase64())
            .put("pub", crypto.publicKeyBase64())
            .toString()
        save(json)
        return crypto
    }

    /** Прод-хранилище: ШИФРОВАННЫЕ prefs (SecureKeys/EncryptedSharedPreferences, ключ на Android Keystore),
     *  ключ `identity`. Приватный ECDH-ключ — долгоживущий секрет (кража = полное имперсонирование устройства
     *  и расшифровка трафика связки), поэтому НЕ в открытых prefs (audit). Паритет с C# (DPAPI/SecretStore). */
    fun fromPrefs(context: Context): E2ECrypto {
        val secure = ru.aiagent.app.cloud.SecureKeys.get(context)
        var raw = secure.getString("identity", null)
        // Миграция: раньше identity лежала в ОТКРЫТЫХ prefs `plusai_remote` — при первом запуске переносим
        // в шифрованное хранилище и стираем открытую копию (иначе приватный ключ оставался бы в plaintext).
        if (raw.isNullOrBlank()) {
            val legacy = context.getSharedPreferences("plusai_remote", Context.MODE_PRIVATE)
            val old = legacy.getString("identity", null)
            if (!old.isNullOrBlank()) {
                secure.edit().putString("identity", old).apply()
                legacy.edit().remove("identity").apply()
                raw = old
            }
        }
        // ВАЖНО (как в C#): существующий-но-нечитаемый ключ НЕ перезаписываем — транзиентная порча иначе
        // сменила бы identity навсегда и порвала все сопряжения. Нераспарсили → эфемерно эту сессию.
        if (!raw.isNullOrBlank()) {
            runCatching {
                val o = JSONObject(raw)
                return E2ECrypto.fromKeys(o.getString("priv"), o.getString("pub"))
            }
            return E2ECrypto()
        }
        val crypto = E2ECrypto()
        val json = JSONObject()
            .put("priv", crypto.exportPrivateKeyBase64())
            .put("pub", crypto.publicKeyBase64())
            .toString()
        secure.edit().putString("identity", json).apply()
        return crypto
    }
}
