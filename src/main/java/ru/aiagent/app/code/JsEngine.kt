package ru.aiagent.app.code

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * JavaScript на устройстве через Rhino. На ART обязателен optimizationLevel=-1 (интерпретатор,
 * без генерации байткода — иначе падает). print()/console.log собираются в вывод. Отдельно —
 * REPL с СОХРАНЯЮЩИМСЯ scope: переменные живут между вызовами (для пошаговой отладки гипотез).
 */
object JsEngine {
    private var replScope: Scriptable? = null
    // Сериализация: watchdog гоняет каждый вызов на своём потоке, а Rhino-scope (особенно
    // сохраняющийся replScope) не потокобезопасен — параллельная мутация ломает слоты.
    private val lock = Any()

    // CPU-лимит: interrupt() из watchdog НЕ прерывает вычислительный цикл (`while(true){}`), а Thread.stop()
    // на новых Android бросает UOE. Rhino в режиме интерпретатора (optimizationLevel=-1) умеет наблюдать за
    // счётчиком инструкций — вешаем на него дедлайн по времени: скрипт сам прервётся у предела.
    private const val CPU_BUDGET_MS = 12_000L
    private const val TS_BUDGET_MS = 40_000L // транспиляция включает загрузку компилятора TS (тяжелее)
    private val deadline = ThreadLocal.withInitial { 0L }

    // Глобальную фабрику Rhino ставим ОДИН раз (до первого Context.enter). Ошибку (уже установлена/
    // контекст уже входили) глотаем — тогда наблюдателя нет, но крах не наступает.
    private val factoryReady: Boolean = try {
        if (!ContextFactory.hasExplicitGlobal()) {
            ContextFactory.initGlobal(object : ContextFactory() {
                override fun makeContext(): Context =
                    super.makeContext().apply { instructionObserverThreshold = 10_000 } // проверять каждые ~10k инстр.

                override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                    val dl = deadline.get()
                    if (dl != 0L && System.currentTimeMillis() > dl) {
                        throw Error("скрипт превысил лимит времени (возможно, бесконечный цикл)")
                    }
                }
            })
        }
        true
    } catch (_: Throwable) {
        false
    }

    /** Одноразовый прогон в свежем scope. */
    fun run(code: String): String = execFresh(code, CPU_BUDGET_MS)

    /** Прогон в свежем scope с заданным CPU-бюджетом (мс). internal — чтобы тест мог проверить лимит быстро. */
    internal fun execFresh(code: String, budgetMs: Long): String = synchronized(lock) {
        deadline.set(System.currentTimeMillis() + budgetMs)
        val cx = enter()
        try {
            val scope = cx.initStandardObjects()
            val sb = install(cx, scope)
            val r = cx.evaluateString(scope, code, "user.js", 1, null)
            finish(sb, r)
        } finally {
            Context.exit(); deadline.set(0L)
        }
    }

    /**
     * Транспилировать TypeScript в JS компилятором [tscSource] (пак typescript.js, чистый JS) и исполнить
     * результат в песочнице. Компилятор и исполнение — в изолированных scope; Java-мост закрыт (ClassShutter).
     */
    internal fun transpileAndRun(tscSource: String, userCode: String): String = synchronized(lock) {
        deadline.set(System.currentTimeMillis() + TS_BUDGET_MS)
        val cx = enter()
        try {
            // 1) Грузим компилятор TS (глобал `ts`) в отдельном scope и транспилируем.
            val tsScope = cx.initStandardObjects()
            cx.evaluateString(tsScope, tscSource, "typescript.js", 1, null)
            ScriptableObject.putProperty(tsScope, "__ts_src__", userCode)
            val jsAny = cx.evaluateString(tsScope, "ts.transpile(String(__ts_src__))", "transpile.js", 1, null)
            val js = Context.toString(jsAny)
            // 2) Исполняем полученный JS в СВЕЖЕМ scope с print/console (изоляция от компилятора).
            val runScope = cx.initStandardObjects()
            val sb = install(cx, runScope)
            val r = cx.evaluateString(runScope, js, "user.ts.js", 1, null)
            finish(sb, r)
        } finally {
            Context.exit(); deadline.set(0L)
        }
    }

    /** REPL: выражение в сохраняющемся scope (reset — очистить контекст). */
    fun repl(expr: String, reset: Boolean): String = synchronized(lock) {
        deadline.set(System.currentTimeMillis() + CPU_BUDGET_MS)
        val cx = enter()
        try {
            var scope = replScope
            if (scope == null || reset) {
                scope = cx.initStandardObjects(); replScope = scope
            }
            val sb = install(cx, scope)
            val r = cx.evaluateString(scope, expr, "repl.js", 1, null)
            finish(sb, r)
        } finally {
            Context.exit(); deadline.set(0L)
        }
    }

    /**
     * code-mode: прогон скрипта-оркестратора в ТОЙ ЖЕ песочнице (ClassShutter закрыт, CPU-бюджет активен).
     * В scope доступны tool(name, args)->строка и log(x). [invokeTool] — СИНХРОННЫЙ гейтированный вызов
     * инструмента (вызывающий оборачивает suspend через runBlocking): он проходит те же барьеры, что и
     * обычный вызов, поэтому оркестрация из скрипта НЕ обход защиты. Мост строковый (JS сам JSON.stringify).
     */
    fun runOrchestration(code: String, invokeTool: (String, String) -> String): String = synchronized(lock) {
        deadline.set(System.currentTimeMillis() + CPU_BUDGET_MS)
        val cx = enter()
        try {
            val scope = cx.initStandardObjects()
            val sb = install(cx, scope)
            val toolFn = object : BaseFunction() {
                override fun call(c: Context, s: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val name = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                    val a0 = if (args.size > 1) args[1] else null
                    val a = if (a0 == null || a0 is Undefined) "{}" else Context.toString(a0)
                    return invokeTool(name, a)
                }
            }
            ScriptableObject.putProperty(scope, "__tool", toolFn)
            cx.evaluateString(
                scope,
                "function tool(n,a){return __tool(n, a==null?'{}':(typeof a==='string'?a:JSON.stringify(a)));}" +
                    "function log(x){print(x==null?'':(typeof x==='string'?x:JSON.stringify(x)));}",
                "codemode-prelude.js", 1, null,
            )
            val r = cx.evaluateString(scope, code, "codemode.js", 1, null)
            finish(sb, r)
        } finally {
            Context.exit(); deadline.set(0L)
        }
    }

    private fun enter(): Context = Context.enter().apply {
        optimizationLevel = -1
        languageVersion = Context.VERSION_ES6
        // ПЕСОЧНИЦА: скрипт не должен видеть НИ ОДНОГО Java-класса. Без этого Rhino отдаёт мост
        // `Packages`/`java`/`importClass`, и `java.lang.Runtime.getRuntime().exec(...)` = RCE в обход
        // DangerPolicy. ClassShutter возвращает false на всё → любой доступ к Java из JS запрещён.
        // Ставится на СВЕЖИЙ контекст каждый вызов (setClassShutter нельзя дважды на один контекст).
        setClassShutter { _ -> false }
    }

    /** Ставит print/console в scope, пишущие в свежий буфер (переустанавливается каждый вызов). */
    private fun install(cx: Context, scope: Scriptable): StringBuilder {
        val sb = StringBuilder()
        val printFn = object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                sb.append(args.joinToString(" ") { Context.toString(it) }).append('\n')
                return Undefined.instance
            }
        }
        ScriptableObject.putProperty(scope, "print", printFn)
        cx.evaluateString(scope, "var console={log:print,error:print,warn:print};", "shim.js", 1, null)
        return sb
    }

    private fun finish(sb: StringBuilder, r: Any?): String {
        val ret = if (r == null) "" else Context.toString(r)
        val out = (sb.toString() + if (ret == "undefined") "" else ret).trim()
        return out.ifBlank { "(выполнено, вывода нет)" }.take(8000)
    }
}
