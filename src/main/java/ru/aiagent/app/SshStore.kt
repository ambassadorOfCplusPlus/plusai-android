package ru.aiagent.app

import android.content.Context
import org.json.JSONObject

/**
 * Хранилище именованных SSH-подключений НА УСТРОЙСТВЕ. Ключевой смысл (§7): модель оперирует только
 * ИМЕНЕМ подключения, а host/user/пароль/ключ резолвятся здесь локально и в args инструмента (а значит
 * и в облако) НЕ попадают. Хранится в app-private prefs (недоступны другим приложениям).
 *
 * v1: креды лежат в app-private хранилище без доп. шифрования at-rest. Инвариант «не утекают в облако»
 * обеспечен резолвом по имени в [ru.aiagent.app.integrations.sshTools]; шифрование at-rest — отдельно.
 */
object SshStore {
    private const val PREFS = "plusai_ssh"
    private const val KEY = "connections" // JSON: { name: {host,port,user,password?,privateKeyPem?,passphrase?} }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun save(context: Context, o: JSONObject) =
        prefs(context).edit().putString(KEY, o.toString()).apply()

    /** Имена сохранённых подключений. */
    fun names(context: Context): List<String> =
        load(context).keys().asSequence().toList().sorted()

    fun hasAny(context: Context): Boolean = load(context).length() > 0

    /** Параметры подключения по имени, либо null. */
    fun get(context: Context, name: String): JSONObject? =
        load(context).optJSONObject(name)

    /** Auth-JSON для бэкенда: {"password":…} или {"privateKeyPem":…,"passphrase":…}. */
    fun authJson(conn: JSONObject): String {
        val o = JSONObject()
        conn.optString("password").takeIf { it.isNotBlank() }?.let { o.put("password", it) }
        conn.optString("privateKeyPem").takeIf { it.isNotBlank() }?.let { o.put("privateKeyPem", it) }
        conn.optString("passphrase").takeIf { it.isNotBlank() }?.let { o.put("passphrase", it) }
        return o.toString()
    }

    fun put(
        context: Context, name: String, host: String, port: Int, user: String,
        password: String?, privateKeyPem: String?, passphrase: String?,
    ) {
        val o = load(context)
        o.put(name, JSONObject().apply {
            put("host", host); put("port", if (port > 0) port else 22); put("user", user)
            if (!password.isNullOrBlank()) put("password", password)
            if (!privateKeyPem.isNullOrBlank()) put("privateKeyPem", privateKeyPem)
            if (!passphrase.isNullOrBlank()) put("passphrase", passphrase)
        })
        save(context, o)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun remove(context: Context, name: String) {
        val o = load(context); o.remove(name); save(context, o)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }
}
