package ru.aiagent.app.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.app.python.RunPythonTool
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * Утилитарные инструменты агента: защищённое key-value хранилище конфигурации
 * (config_set / config_get) и рендер Jinja2-шаблонов (template_render).
 *
 * Хранилище — отдельные EncryptedSharedPreferences «plusai_config» (Android Keystore
 * под капотом), тот же паттерн MasterKey (AES256_GCM), что и у BYOK-ключей облака в
 * [ru.aiagent.app.cloud.SecureKeys]. Отдельный файл, чтобы конфиг агента не смешивался
 * с секретами кошелька/облака и не стирался при пересоздании того хранилища.
 *
 * config_set/config_get — SAFE (пишут/читают только собственный конфиг). template_render —
 * IMPORTANT: рендер идёт Python-движком Jinja2 (через песочницу), шаблон приходит из данных,
 * поэтому выполнение требует подтверждения, как у run_python.
 */

/**
 * Защищённое хранилище конфигурации агента (отдельный prefs-файл «plusai_config»).
 *
 * ТОЛЬКО EncryptedSharedPreferences (Android Keystore). config_set по схеме кладёт api_token и
 * прочие секреты — plaintext-фолбэка НЕТ: если Keystore недоступен (редкие прошивки/direct-boot),
 * [get] возвращает null, а инструменты отвечают Failure «защищённое хранилище недоступно».
 * Секрет никогда не пишется в открытый prefs.
 */
object ConfigStore {
    @Volatile private var prefs: android.content.SharedPreferences? = null

    /** @return защищённое хранилище, либо null если Keystore/EncryptedSharedPreferences недоступны. */
    fun get(context: Context): android.content.SharedPreferences? {
        prefs?.let { return it }
        return synchronized(this) {
            prefs?.let { return it } // повторная проверка под локом (EncryptedSharedPreferences не потокобезопасен при создании)
            create(context)?.also { prefs = it }
        }
    }

    private fun create(context: Context): android.content.SharedPreferences? {
        val ctx = context.applicationContext
        // Тот же паттерн MasterKey, что и у SecureKeys (BYOK): AES256_GCM в Android Keystore.
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return runCatching {
            EncryptedSharedPreferences.create(
                ctx, "plusai_config", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull() // Keystore недоступен → null. НЕ откатываемся на plaintext: в конфиге бывают секреты.
    }
}

/** config_set — сохранить значение в защищённое key-value хранилище конфигурации. */
class ConfigSetTool(private val context: Context) : AgentTool {
    override val id = "config_set"
    override val danger = DangerLevel.SAFE
    override val schema =
        """сохранить значение в защищённое хранилище конфигурации; args: {"key":"api_token","value":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("некорректный JSON args")
        val key = obj.optString("key").takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Failure("нужен args.key")
        val value = obj.optString("value")
        val prefs = ConfigStore.get(context)
            ?: return@withContext ToolResult.Failure("защищённое хранилище недоступно — конфиг не сохранён (Keystore недоступен)")
        prefs.edit().putString(key, value).apply()
        ToolResult.Success(JSONObject().put("ok", true).put("key", key).toString())
    }
}

/**
 * config_get — прочитать значение по ключу, либо перечислить ключи.
 * {"key":"api_token"} → значение или null. {"key":""} или {"list":true} → список ключей БЕЗ значений.
 */
class ConfigGetTool(private val context: Context) : AgentTool {
    override val id = "config_get"
    override val danger = DangerLevel.SAFE
    // §7: хранилище шифрованное ИМЕННО потому, что в конфиге бывают секреты (пример ключа — api_token).
    // Без privateData тул попадал в облачный набор (allTools.filterNot{privateData}), и модель могла
    // прочитать секрет, а его значение уезжало провайдеру в следующем же запросе.
    override val privateData = true
    override val schema =
        """прочитать значение конфигурации по ключу, либо список ключей; args: {"key":"api_token"} или {"list":true}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("некорректный JSON args")
        val prefs = ConfigStore.get(context)
            ?: return@withContext ToolResult.Failure("защищённое хранилище недоступно (Keystore недоступен)")
        val key = obj.optString("key")
        val wantList = obj.optBoolean("list", false) || key.isBlank()
        if (wantList) {
            // Только ключи, БЕЗ значений (значения могут быть чувствительны — токены и т.п.).
            val keys = prefs.all.keys.sorted()
            val arr = org.json.JSONArray().apply { keys.forEach { put(it) } }
            return@withContext ToolResult.Success(JSONObject().put("keys", arr).toString())
        }
        val value: String? = prefs.getString(key, null)
        // value == null → JSON null (ключ не найден).
        ToolResult.Success(JSONObject().put("key", key).put("value", value ?: JSONObject.NULL).toString())
    }
}

/**
 * template_render — рендер Jinja2-шаблона с контекстом.
 * {"template":"Привет {{name}}!","context":{"name":"Мир"}} → {"result":"Привет Мир!"}.
 * jinja2 предустановлен в Python (Chaquopy). При ошибке импорта jinja2 — простой fallback
 * (замена {{key}} на значения контекста).
 *
 * БЕЗОПАСНОСТЬ: рендер идёт через jinja2.sandbox.SandboxedEnvironment, а НЕ обычный
 * jinja2.Template — иначе шаблон вида {{().__class__.__mro__...}} даёт произвольный Python
 * (SSTI → RCE). Sandbox блокирует доступ к небезопасным атрибутам. Danger — IMPORTANT
 * (подтверждение, как у run_python): шаблон приходит из данных и выполняется Python-движком.
 */
class TemplateRenderTool(private val context: Context) : AgentTool {
    override val id = "template_render"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // как run_python: рендер Python-движком не исполняется молча (auto тоже спрашивает)
    override val schema =
        """отрендерить Jinja2-шаблон с контекстом; args: {"template":"Привет {{name}}!","context":{"name":"Мир"}}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("некорректный JSON args")
        val tmplStr = obj.optString("template").takeIf { obj.has("template") }
            ?: return@withContext ToolResult.Failure("нужен args.template")
        val ctxObj = obj.optJSONObject("context") ?: JSONObject()

        // Основной путь: настоящий Jinja2 через Chaquopy.
        val rendered = runCatching {
            RunPythonTool.ensureStarted(context)
            val py = Python.getInstance()
            val builtins = py.getBuiltins()
            // mapping для render: dict = builtins.dict(); dict.__setitem__(k, v) для каждого ключа.
            val dict = builtins.callAttr("dict")
            for (k in ctxObj.keys()) {
                dict.callAttr("__setitem__", k, ctxObj.get(k))
            }
            // SandboxedEnvironment().from_string(str).render(mapping) — песочница блокирует
            // доступ к небезопасным атрибутам (защита от SSTI/RCE через {{().__class__...}}).
            val env = py.getModule("jinja2.sandbox").callAttr("SandboxedEnvironment")
            val tmpl = env.callAttr("from_string", tmplStr)
            tmpl.callAttr("render", dict).toString()
        }.getOrElse {
            // Fallback: jinja2 недоступен/ошибка старта Python — простая подстановка {{key}}.
            simpleRender(tmplStr, ctxObj)
        }
        ToolResult.Success(JSONObject().put("result", rendered).toString())
    }

    /** Простой рендер без Jinja: замена {{ key }} на строковое значение контекста. */
    private fun simpleRender(template: String, ctx: JSONObject): String {
        var out = template
        for (k in ctx.keys()) {
            val v = ctx.get(k).toString()
            out = out.replace(Regex("\\{\\{\\s*" + Regex.escape(k) + "\\s*\\}\\}"), Regex.escapeReplacement(v))
        }
        return out
    }
}

/** Фабрика утилитарных инструментов (config_set / config_get / template_render). */
fun configTemplateTools(context: Context): List<AgentTool> = listOf(
    ConfigSetTool(context),
    ConfigGetTool(context),
    TemplateRenderTool(context),
)
