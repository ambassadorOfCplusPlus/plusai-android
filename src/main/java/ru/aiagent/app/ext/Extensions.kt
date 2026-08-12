package ru.aiagent.app.ext

import android.content.Context
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.app.code.JsEngine
import ru.aiagent.app.python.RunPythonTool
import java.io.File
import java.util.zip.ZipInputStream

/**
 * СКРИПТОВЫЙ тир платформы расширений: сторонние инструменты на Python, JavaScript и Lua.
 * Расширение = папка в filesDir/extensions/<id>/ с manifest.json + entry-скриптом. Приложение читает
 * манифест, для каждого объявленного инструмента строит [ScriptExtensionTool], который гоняет
 * entry.run(tool_id, args) на выбранном языке (manifest.lang). Контракт для всех языков один: entry
 * определяет функцию run(tool_id, args) и возвращает результат; args приходит как нативный объект языка
 * (dict / JS-object / Lua-table), результат сериализуется обратно в JSON-строку и отдаётся как ToolResult.
 *
 * Раннеры:
 *  - python     → plusai_ext.run_ext(...) через Chaquopy (тот же раннер, что и run_python — общая песочница/режим).
 *  - javascript → Rhino через [JsEngine] (тот же движок, что run_javascript): ClassShutter закрывает Java-мост,
 *                 есть CPU-дедлайн. Реально изолирован — БЕЗОПАСНЕЕ python.
 *  - lua        → LuaJ (тот же движок и sandbox, что run_lua): убраны luajava/io/package/опасные os.* — RCE закрыт.
 *
 * Безопасность: python не изолирован (тот же риск, что run_python); JS/Lua исполняются в песочнице движков и
 * потому безопаснее. Во всех случаях (1) danger берётся из манифеста и по умолчанию не ниже IMPORTANT
 * (подтверждение), (2) реальный барьер — РЕВЬЮ владельцем перед публикацией в магазин, (3) паки скачиваются со
 * сверкой SHA-256 (как runtime-паки). Инструменты расширений проходят обычный фильтр отключённых тулзов и
 * гейтинг автономности — как встроенные.
 */
data class ExtToolSpec(val id: String, val name: String, val danger: DangerLevel, val schema: String, val usesFiles: Boolean)
data class ExtManifest(
    val id: String, val name: String, val version: String, val author: String,
    val lang: String, val entry: String, val dir: File, val tools: List<ExtToolSpec>,
)

object ExtensionManager {
    fun rootDir(context: Context): File = File(context.filesDir, "extensions").apply { mkdirs() }

    private fun danger(s: String?): DangerLevel = when (s?.lowercase()) {
        // Сторонний код НИКОГДА не может быть SAFE (самоаттестация недоверенного кода = RCE без подтверждения).
        // Минимум — IMPORTANT: "safe" из манифеста поднимается до IMPORTANT (см. CLAUDE.md: сторонний код ≥ IMPORTANT).
        "dangerous" -> DangerLevel.DANGEROUS
        else -> DangerLevel.IMPORTANT // safe/important/пусто → подтверждение обязательно
    }

    private fun parse(dir: File): ExtManifest? = runCatching {
        val mf = File(dir, "manifest.json")
        if (!mf.exists()) return null
        val j = JSONObject(mf.readText())
        val toolsArr = j.optJSONArray("tools") ?: return null
        val tools = (0 until toolsArr.length()).map { i ->
            val t = toolsArr.getJSONObject(i)
            ExtToolSpec(
                id = t.getString("id"),
                name = t.optString("name", t.getString("id")),
                danger = danger(t.optString("danger")),
                schema = t.optString("schema", t.optString("name", t.getString("id"))),
                usesFiles = t.optBoolean("uses_files", false),
            )
        }
        ExtManifest(
            id = j.getString("id"), name = j.optString("name", j.getString("id")),
            version = j.optString("version", "1.0.0"), author = j.optString("author", "—"),
            lang = j.optString("lang", "python").lowercase(), entry = j.optString("entry", "main.py"),
            dir = dir, tools = tools,
        )
    }.getOrNull()

    /** Установленные расширения (папки с валидным manifest.json). */
    fun installed(context: Context): List<ExtManifest> =
        rootDir(context).listFiles { f -> f.isDirectory }?.mapNotNull { parse(it) } ?: emptyList()

    /** Установить расширение из проверенного zip (внутри — manifest.json + код). Возвращает манифест. */
    fun installFromZip(context: Context, zip: File): Result<ExtManifest> = runCatching {
        // Читаем id заранее нельзя — распаковываем во временную папку, потом переносим по id из манифеста.
        val tmp = File(rootDir(context), ".incoming_${System.nanoTime()}").apply { deleteRecursively(); mkdirs() }
        val guard = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(guard)) error("zip-slip: ${e.name}")
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                e = zin.nextEntry
            }
        }
        val mf = parse(tmp) ?: error("нет валидного manifest.json")
        // id из недоверенного манифеста строит путь установки — валидируем как ОДИН безопасный сегмент,
        // иначе "../../databases/evil" в id → deleteRecursively/renameTo мимо rootDir (audit: path traversal).
        require(isSafeId(mf.id)) { "недопустимый id расширения: ${mf.id}" }
        val dst = File(rootDir(context), mf.id)
        dst.deleteRecursively()
        if (!tmp.renameTo(dst)) { tmp.copyRecursively(dst, overwrite = true); tmp.deleteRecursively() }
        parse(dst) ?: error("манифест не читается после установки")
    }

    fun remove(context: Context, id: String) {
        if (!isSafeId(id)) return // не даём удалить мимо rootDir по подсунутому id
        File(rootDir(context), id).deleteRecursively()
    }

    /** id расширения = один безопасный сегмент пути: буквы/цифры/._- , без разделителей и «..». */
    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && id != "." && id != ".." && id.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } &&
            !id.contains('/') && !id.contains('\\')
}

/** Инструмент из расширения: гоняет entry.run(tool_id, args) через Python-раннер. */
class ScriptExtensionTool(
    private val context: Context,
    private val manifest: ExtManifest,
    private val spec: ExtToolSpec,
    private val workspacePath: String,
) : AgentTool {
    override val id = spec.id
    override val danger = spec.danger
    // Python-расширение неизолировано и трогает ФС — usesFiles форсируем true ВСЕГДА (uses_files из манифеста —
    // самоаттестация недоверенного кода, ей не верим), чтобы расширение гейтилось тумблером доступа к файлам.
    override val usesFiles = true
    override val alwaysConfirm = spec.danger != DangerLevel.SAFE // сторонний код — подтверждаем (danger никогда не SAFE)
    // Сторонний код мог работать с личными данными → результаты НЕ уходят в облако без явного действия (§7).
    override val privateData = true
    override val schema = spec.schema

    override suspend fun invoke(argsJson: String): ToolResult {
        // Язык определяется полем manifest.lang (в манифесте нормализуется в нижний регистр при разборе).
        // "js" — синоним "javascript". Общая обёртка: watchdog(30s) → результат-JSON → Success, ошибка → Failure.
        val res = when (manifest.lang) {
            "python" -> ru.aiagent.app.code.watchdog(30_000, "ext:${spec.id}") {
                RunPythonTool.ensureStarted(context)
                Python.getInstance().getModule("plusai_ext")
                    .callAttr("run_ext", manifest.dir.absolutePath, manifest.entry, spec.id, argsJson, workspacePath)
                    .toString()
            }
            "javascript", "js" -> ru.aiagent.app.code.watchdog(30_000, "ext:${spec.id}") {
                val entry = File(manifest.dir, manifest.entry).readText()
                // Код расширения + IIFE, который вызывает run(tool_id, args). args — распарсенный JSON-объект
                // (JSON.parse экранированной строки), результат сериализуем JSON.stringify. Значение IIFE —
                // это и есть JSON-строка, которую JsEngine.run вернёт как итог выполнения.
                val wrapped = entry + "\n;(function(){" +
                    "if(typeof run!=='function')throw new Error('в расширении нет функции run(tool_id, args)');" +
                    "var __args=JSON.parse(" + jsQuote(argsJson.ifBlank { "{}" }) + ");" +
                    "var __r=run(" + jsQuote(spec.id) + ",__args);" +
                    "return JSON.stringify(__r===undefined?null:__r);})()"
                JsEngine.run(wrapped)
            }
            "lua" -> ru.aiagent.app.code.watchdog(30_000, "ext:${spec.id}") {
                val entry = File(manifest.dir, manifest.entry).readText()
                val g = JsePlatform.standardGlobals()
                sandboxLua(g) // тот же sandbox, что run_lua: убрать Java-мост/io/package/опасные os.* (иначе RCE)
                g.load(entry, "ext.lua").call() // выполняем чанк — он должен объявить глобальную функцию run
                val runFn = g.get("run")
                if (!runFn.isfunction()) error("в расширении нет функции run(tool_id, args)")
                val luaArgs = jsonToLua(argsJson) // args как Lua-table из JSON
                val out = runFn.call(LuaValue.valueOf(spec.id), luaArgs)
                luaToJson(out) // результат приводим к JSON-строке
            }
            else -> return ToolResult.Failure(
                "язык расширения не поддержан: ${manifest.lang} (поддерживаются python, javascript, lua)"
            )
        }
        return res.fold(
            onSuccess = { ToolResult.Success(it) },
            onFailure = { ToolResult.Failure("расширение ${manifest.id}: ${it.message?.take(200)}") },
        )
    }

    private companion object {
        /** Экранировать строку для вставки в одинарные кавычки JS-литерала (для JSON.parse('...')). */
        fun jsQuote(s: String): String {
            val b = StringBuilder(s.length + 2)
            b.append('\'')
            for (c in s) when (c) {
                '\\' -> b.append("\\\\")
                '\'' -> b.append("\\'")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\u2028' -> b.append("\\u2028") // JS line separator — иначе рвёт строковый литерал
                '\u2029' -> b.append("\\u2029") // JS paragraph separator
                else -> b.append(c)
            }
            b.append('\'')
            return b.toString()
        }

        /**
         * Тот же sandbox, что в run_lua (RunLuaTool): убирает всё, чем можно выйти за пределы вычислений —
         * luajava (мост к Java = RCE), io (файлы/popen), package/require/loadfile/dofile (модули и native .so),
         * опасные os.* (execute/exit/remove/rename/getenv/tmpname/setlocale). Остаётся math/string/table/os.time|date|clock.
         */
        fun sandboxLua(g: LuaValue) {
            for (name in listOf("luajava", "io", "package", "require", "loadfile", "dofile")) {
                g.set(name, LuaValue.NIL)
            }
            val os = g.get("os")
            if (!os.isnil()) {
                for (fn in listOf("execute", "exit", "remove", "rename", "getenv", "tmpname", "setlocale")) {
                    os.set(fn, LuaValue.NIL)
                }
            }
        }

        /** JSON-строка → LuaValue (объект→table со строковыми ключами, массив→table 1..n). Пустое → пустая table. */
        fun jsonToLua(json: String): LuaValue {
            val t = json.trim()
            if (t.isEmpty()) return LuaValue.tableOf()
            return runCatching { anyToLua(JSONTokener(t).nextValue()) }.getOrElse { LuaValue.tableOf() }
        }

        private fun anyToLua(v: Any?): LuaValue = when (v) {
            null, JSONObject.NULL -> LuaValue.NIL
            is JSONObject -> LuaValue.tableOf().also { tbl ->
                val keys = v.keys()
                while (keys.hasNext()) { val k = keys.next(); tbl.set(k, anyToLua(v.get(k))) }
            }
            is JSONArray -> LuaValue.tableOf().also { tbl ->
                for (i in 0 until v.length()) tbl.set(i + 1, anyToLua(v.get(i)))
            }
            is Boolean -> LuaValue.valueOf(v)
            is Int -> LuaValue.valueOf(v)
            is Long -> LuaValue.valueOf(v.toDouble())
            is Number -> LuaValue.valueOf(v.toDouble())
            is String -> LuaValue.valueOf(v)
            else -> LuaValue.valueOf(v.toString())
        }

        /** LuaValue → JSON-строка (то, что вернул run). Table с ключами 1..n → JSON-массив, иначе → JSON-объект. */
        fun luaToJson(v: LuaValue): String = when (val o = luaToObj(v)) {
            is JSONObject -> o.toString()
            is JSONArray -> o.toString()
            JSONObject.NULL -> "null"
            is Boolean -> o.toString()
            is Long -> o.toString()
            is Double -> if (o == Math.floor(o) && !o.isInfinite()) o.toLong().toString() else o.toString()
            is String -> JSONObject.quote(o) // корректная JSON-строка с кавычками/экранированием
            else -> JSONObject.quote(o.toString())
        }

        private fun luaToObj(v: LuaValue): Any = when {
            v.isnil() -> JSONObject.NULL
            v.isboolean() -> v.toboolean()
            v.isnumber() -> { // важно проверять число ДО строки: LuaJ считает числа строками-совместимыми
                val d = v.todouble()
                if (d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 9.2e18) d.toLong() else d
            }
            v.isstring() -> v.tojstring()
            v.istable() -> {
                val tbl = v.checktable()
                val n = tbl.length()
                val keys = tbl.keys()
                val isArray = n > 0 && keys.size == n && keys.all { it.isint() }
                if (isArray) JSONArray().also { for (i in 1..n) it.put(luaToObj(tbl.get(i))) }
                else JSONObject().also { for (k in keys) it.put(k.tojstring(), luaToObj(tbl.get(k))) }
            }
            else -> v.tojstring()
        }
    }
}

/** Инструменты всех установленных расширений (для реестра агента). */
fun extensionTools(context: Context, workspacePath: String): List<AgentTool> =
    ExtensionManager.installed(context).flatMap { mf ->
        mf.tools.map { ScriptExtensionTool(context, mf, it, workspacePath) }
    }
