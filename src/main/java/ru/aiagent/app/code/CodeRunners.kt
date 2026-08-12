package ru.aiagent.app.code

import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson

/** run_javascript — JavaScript (Rhino). print()/console.log — вывод. */
class RunJavaScriptTool : AgentTool {
    override val id = "run_javascript"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // произвольный код без изоляции
    override val schema = """выполнить JavaScript (Rhino); print(...) для вывода; args: {"code":"print(2+2)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val code = strArg(argsJson, "code") ?: return ToolResult.Failure("нет args.code")
        return watchdog(30_000, "js") { JsEngine.run(code) }.fold(
            onSuccess = { ToolResult.Success("""{"stdout":"${escapeJson(it)}"}""") },
            onFailure = { ToolResult.Failure("js: ${it.message?.take(300)}") },
        )
    }
}

/** repl_eval — вычислить выражение в СОХРАНЯЮЩЕМСЯ JS-контексте (переменные живут между вызовами). */
class ReplEvalTool : AgentTool {
    override val id = "repl_eval"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema =
        """вычислить выражение в сохраняющемся JS-контексте (переменные живут между вызовами — для проверки гипотез); args: {"expr":"x=5; x*x","reset":false}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val expr = strArg(argsJson, "expr") ?: return ToolResult.Failure("нет args.expr")
        val reset = Regex("\"reset\"\\s*:\\s*true").containsMatchIn(argsJson)
        return watchdog(15_000, "repl") { JsEngine.repl(expr, reset) }.fold(
            onSuccess = { ToolResult.Success("""{"result":"${escapeJson(it)}"}""") },
            onFailure = { ToolResult.Failure("repl: ${it.message?.take(300)}") },
        )
    }
}

/** run_typescript — TypeScript: транспилируется в JS (пак typescript.js) и исполняется в Rhino.
 *  Модуль скачиваемый (ASSET_PACK «typescript»); без него инструмент не выдаётся агенту (см. CodeModules). */
class RunTypeScriptTool(private val context: android.content.Context) : AgentTool {
    override val id = "run_typescript"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema =
        """выполнить TypeScript (транспиляция в JS + Rhino); console.log/print для вывода; args: {"code":"const x:number=2; console.log(x*3)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val code = strArg(argsJson, "code") ?: return ToolResult.Failure("нет args.code")
        val tsc = java.io.File(RuntimePackManager.installDir(context, "typescript"), "typescript.js")
        if (!tsc.exists()) return ToolResult.Failure("модуль TypeScript не установлен — включите/скачайте его в Настройках → Модули")
        return watchdog(45_000, "ts") { JsEngine.transpileAndRun(tsc.readText(), code) }.fold(
            onSuccess = { ToolResult.Success("""{"stdout":"${escapeJson(it)}"}""") },
            onFailure = { ToolResult.Failure("ts: ${it.message?.take(300)}") },
        )
    }
}

/** run_lua — Lua (LuaJ). print(...) — вывод. */
class RunLuaTool : AgentTool {
    override val id = "run_lua"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema = """выполнить Lua-код (LuaJ); print(...) для вывода; args: {"code":"print(2+2)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val code = strArg(argsJson, "code") ?: return ToolResult.Failure("нет args.code")
        // NB: LuaJ-раннер остаётся на best-effort watchdog. Per-instruction debug-хук (DebugLib) для CPU-
        // лимита в LuaJ 3.0.1 оказался несовместим — его onInstruction рушит исполнение обычного кода
        // (состояние хука не инициализировано к первой инструкции). Настоящий CPU-лимит Lua = отдельная
        // задача (свой мини-интерпретаторный цикл или обновление LuaJ). JS-раннер (Rhino) уже лимитирован.
        return watchdog(30_000, "lua") {
            val g = JsePlatform.standardGlobals()
            sandboxLua(g) // убрать Java-мост/шелл/файлы/native-load (иначе Lua = RCE)
            val sb = StringBuilder()
            g.set("print", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    for (i in 1..args.narg()) { if (i > 1) sb.append('\t'); sb.append(args.arg(i).tojstring()) }
                    sb.append('\n'); return NONE
                }
            })
            g.load(code, "user.lua").call()
            sb.toString().trim().ifBlank { "(выполнено, вывода нет)" }.take(8000)
        }.fold(
            onSuccess = { ToolResult.Success("""{"stdout":"${escapeJson(it)}"}""") },
            onFailure = { ToolResult.Failure("lua: ${it.message?.take(300)}") },
        )
    }

    private companion object {
        /**
         * Убирает из Lua-глобалов всё, чем можно выйти за пределы вычислений:
         * - luajava — прямой мост к Java-классам (`luajava.bindClass("java.lang.Runtime")` = RCE);
         * - io — файлы и io.popen (запуск процессов);
         * - package/require/loadfile/dofile — загрузка модулей и package.loadlib (нативные .so);
         * - опасные os.* (execute/exit/remove/rename/getenv/tmpname).
         * Остаётся чистая вычислительная база: math/string/table/os.time|date|clock.
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
    }
}
