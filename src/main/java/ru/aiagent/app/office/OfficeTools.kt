package ru.aiagent.app.office

import android.content.Context
import com.chaquo.python.Python
import ru.aiagent.app.python.RunPythonTool
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File

/**
 * Офисные инструменты для обычных пользователей (не кодеров): презентации и Word.
 * Под капотом — python-pptx / python-docx (встроенный Python), но агенту это простые
 * высокоуровневые вызовы со структурой. IMPORTANT (создают файл), без alwaysConfirm.
 */

/** create_presentation — .pptx из структуры слайдов. */
class CreatePresentationTool(
    private val appContext: Context,
    private val workspacePath: String,
) : AgentTool {
    override val id = "create_presentation"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """создать презентацию PowerPoint (.pptx); args: {"path":"итоги.pptx","title":"Заголовок",""" +
            """"subtitle":"подзаголовок","slides":[{"title":"Слайд 1","bullets":["пункт","пункт"]}]}"""

    override suspend fun invoke(argsJson: String): ToolResult =
        buildOffice(appContext, workspacePath, argsJson, "build_pptx", ".pptx")
}

/** create_word — .docx из заголовка и секций. */
class CreateWordTool(
    private val appContext: Context,
    private val workspacePath: String,
) : AgentTool {
    override val id = "create_word"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """создать документ Word (.docx); args: {"path":"отчёт.docx","title":"Заголовок",""" +
            """"sections":[{"heading":"Раздел","text":"абзац","bullets":["пункт"]}]}"""

    override suspend fun invoke(argsJson: String): ToolResult =
        buildOffice(appContext, workspacePath, argsJson, "build_docx", ".docx")
}

private fun buildOffice(
    context: Context,
    workspace: String,
    argsJson: String,
    pyFunc: String,
    ext: String,
): ToolResult {
    val path = jsonField(argsJson, "path") ?: "документ$ext"
    val safeName = File(path).name.let { if (it.endsWith(ext, true)) it else it + ext }
    val out = File(File(workspace).apply { mkdirs() }, safeName).absolutePath
    // Watchdog: генерация pptx/docx не должна вешать агента (как и run_python).
    val res = ru.aiagent.app.code.watchdog(30_000, "office") {
        RunPythonTool.ensureStarted(context)
        Python.getInstance().getModule("plusai_office").callAttr(pyFunc, argsJson, out).toString()
    }
    return res.fold(
        onSuccess = { status ->
            if (status.startsWith("[ошибка]")) ToolResult.Failure(status)
            else ToolResult.Success("""{"path":"${esc(safeName)}","result":"${esc(status)}"}""")
        },
        onFailure = { ToolResult.Failure("office: ${it.message?.take(200)}") },
    )
}

/** calculate — безопасный калькулятор (SAFE, без Python и подтверждений). */
class CalculateTool : AgentTool {
    override val id = "calculate"
    override val danger = DangerLevel.SAFE
    override val schema = """посчитать математическое выражение; args: {"expr":"sqrt(144)+sin(pi/2)"}. Поддержка: + - * / % ^, скобки, унарный минус; функции sin cos tan asin acos atan (радианы; градусные варианты sind cosd tand), sinh cosh tanh, sqrt cbrt abs sign exp ln log10 log2 log(x[,base]) pow(a,b) hypot(a,b) atan2(y,x) floor ceil round deg rad fact max(…) min(…); константы pi e tau"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val expr = jsonField(argsJson, "expr") ?: return ToolResult.Failure("нет args.expr")
        return try {
            val v = Calc(expr).parse()
            if (!v.isFinite()) return ToolResult.Failure("результат не определён (деление на ноль/переполнение)")
            val out = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            ToolResult.Success("""{"expr":"${esc(expr)}","result":$out}""")
        } catch (t: Throwable) {
            ToolResult.Failure("не удалось вычислить: ${t.message}")
        }
    }
}

/** Мини-парсер арифметики (+ - * / % ^, скобки, унарный минус) — без eval/инъекций. */
private class Calc(private val s: String) {
    private var pos = 0
    fun parse(): Double {
        val v = expr()
        skip()
        require(pos >= s.length) { "лишние символы у позиции $pos" }
        return v
    }
    private fun skip() { while (pos < s.length && s[pos] == ' ') pos++ }
    private fun expr(): Double {
        var v = term()
        while (true) { skip(); when (peek()) {
            '+' -> { pos++; v += term() }
            '-' -> { pos++; v -= term() }
            else -> return v
        } }
    }
    private fun term(): Double {
        var v = unary()
        while (true) { skip(); when (peek()) {
            '*' -> { pos++; v *= unary() }
            '/' -> { pos++; v /= unary() }
            '%' -> { pos++; v %= unary() }
            else -> return v
        } }
    }
    // Унарный минус СЛАБЕЕ степени: `-2^2` = −(2^2) = −4 (как на калькуляторе), а не (−2)^2.
    private fun unary(): Double { skip(); return if (peek() == '-') { pos++; -unary() } else power() }
    private fun power(): Double {
        val b = atom(); skip()
        // Показатель — через unary, чтобы `2^-3` работало (правоассоциативно: `2^2^3` = 2^(2^3)).
        return if (peek() == '^') { pos++; Math.pow(b, unary()) } else b
    }
    private fun atom(): Double {
        skip()
        if (peek() == '(') { pos++; val v = expr(); skip(); require(peek() == ')') { "нет )" }; pos++; return v }
        // Идентификатор: имя функции (при следующей '(') или константа (pi/e/tau).
        if (peek().isLetter()) {
            val nStart = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
            val name = s.substring(nStart, pos).lowercase()
            skip()
            if (peek() == '(') {
                pos++
                val args = ArrayList<Double>()
                skip()
                if (peek() != ')') {
                    args.add(expr()); skip()
                    while (peek() == ',') { pos++; args.add(expr()); skip() }
                }
                require(peek() == ')') { "нет ) у функции $name" }; pos++
                return applyFunc(name, args)
            }
            return constant(name)
        }
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
        require(pos > start) { "ожидалось число у позиции $pos" }
        return s.substring(start, pos).toDouble()
    }
    private fun peek(): Char = if (pos < s.length) s[pos] else ' '

    private fun constant(name: String): Double = when (name) {
        "pi" -> Math.PI
        "e" -> Math.E
        "tau" -> Math.PI * 2
        else -> throw IllegalArgumentException("неизвестная константа: $name")
    }

    private fun applyFunc(name: String, a: List<Double>): Double {
        fun one(): Double { require(a.size == 1) { "$name ожидает 1 аргумент, дано ${a.size}" }; return a[0] }
        return when (name) {
            "sin" -> Math.sin(one()); "cos" -> Math.cos(one()); "tan" -> Math.tan(one())
            "sind" -> Math.sin(Math.toRadians(one())); "cosd" -> Math.cos(Math.toRadians(one())); "tand" -> Math.tan(Math.toRadians(one()))
            "asin" -> Math.asin(one()); "acos" -> Math.acos(one()); "atan" -> Math.atan(one())
            "sinh" -> Math.sinh(one()); "cosh" -> Math.cosh(one()); "tanh" -> Math.tanh(one())
            "sqrt" -> Math.sqrt(one()); "cbrt" -> Math.cbrt(one())
            "abs" -> Math.abs(one()); "sign", "signum" -> Math.signum(one())
            "exp" -> Math.exp(one()); "ln" -> Math.log(one()); "log10" -> Math.log10(one()); "log2" -> Math.log(one()) / Math.log(2.0)
            "floor" -> Math.floor(one()); "ceil" -> Math.ceil(one()); "round" -> Math.round(one()).toDouble()
            "deg" -> Math.toDegrees(one()); "rad" -> Math.toRadians(one())
            "fact" -> factorial(one())
            "log" -> when (a.size) { 1 -> Math.log10(a[0]); 2 -> Math.log(a[0]) / Math.log(a[1]); else -> throw IllegalArgumentException("log ожидает 1 или 2 аргумента") }
            "pow" -> { require(a.size == 2) { "pow ожидает 2 аргумента" }; Math.pow(a[0], a[1]) }
            "hypot" -> { require(a.size == 2) { "hypot ожидает 2 аргумента" }; Math.hypot(a[0], a[1]) }
            "atan2" -> { require(a.size == 2) { "atan2 ожидает 2 аргумента" }; Math.atan2(a[0], a[1]) }
            "max" -> { require(a.isNotEmpty()) { "max без аргументов" }; a.max() }
            "min" -> { require(a.isNotEmpty()) { "min без аргументов" }; a.min() }
            else -> throw IllegalArgumentException("неизвестная функция: $name")
        }
    }

    private fun factorial(x: Double): Double {
        require(x >= 0 && x == Math.floor(x) && x <= 170) { "факториал: целое 0..170" }
        var r = 1.0; var i = 2; val n = x.toInt(); while (i <= n) { r *= i; i++ }; return r
    }
}

private fun jsonField(json: String, field: String): String? =
    Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        .find(json)?.groupValues?.get(1)
        // ЕДИНЫЙ проход unescapeJson: раньше цепочка replace НЕ разэкранировала \/ → модель, шлющая
        // "10\/2" (частое переэкранирование у DeepSeek), ломала calculate ("лишние символы у позиции").
        ?.let { ru.aiagent.core.agent.tools.unescapeJson(it) }

private fun esc(s: String) = ru.aiagent.core.agent.tools.escapeJson(s)
