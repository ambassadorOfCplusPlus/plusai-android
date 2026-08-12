package ru.aiagent.app.utils

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Набор повседневных инструментов (не для кодеров): дата, конвертация, текст, поиск, статус. */

/** datetime — текущие дата/время и простая арифметика дат. */
class DateTimeTool : AgentTool {
    override val id = "datetime"
    override val danger = DangerLevel.SAFE
    override val schema =
        """дата/время; args: {"op":"now|days_until|weekday|add_days","date":"2026-12-31","days":10}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val op = str(argsJson, "op") ?: "now"
        return try {
            val res = when (op) {
                "now" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm, EEEE"))
                "days_until" -> {
                    val d = LocalDate.parse(str(argsJson, "date"))
                    "${ChronoUnit.DAYS.between(LocalDate.now(), d)} дн."
                }
                "weekday" -> LocalDate.parse(str(argsJson, "date"))
                    .format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale("ru")))
                "add_days" -> LocalDate.now().plusDays(
                    (numOrNull(argsJson, "days") ?: return ToolResult.Failure("нужно args.days")).toLong(),
                ).toString()
                else -> return ToolResult.Failure("op: now|days_until|weekday|add_days")
            }
            ok("op" to op, "result" to res)
        } catch (t: Throwable) { ToolResult.Failure("datetime: ${t.message}") }
    }
}

/**
 * sequential_thinking — «блокнот рассуждений» (идея из MCP sequential-thinking): модель фиксирует ОДИН шаг
 * мысли (гипотеза/ревизия/ветка), получает подтверждение и вызывает снова, пока next_needed=false. Помогает
 * слабым моделям (в т.ч. скидочному пулу) думать структурно. §0/§7: рассуждение остаётся в теле tool-вызова,
 * НЕ вливается в финальный ответ. Без I/O и личных данных → SAFE. Состояние не хранит: нумерацию/ветвление
 * ведёт сама модель, инструмент валидирует и подсказывает «продолжай/готово».
 */
class SequentialThinkingTool : AgentTool {
    override val id = "sequential_thinking"
    override val danger = DangerLevel.SAFE
    override val schema =
        """пошаговое размышление (шаг НЕ попадает в ответ пользователю): фиксируй один шаг, вызывай снова, """ +
            """пока не готов; затем дай чистый ответ. args: {"thought":"текущий шаг","thought_number":1,"total_thoughts":5,"next_needed":true}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        // Разбираем через JSONObject, а НЕ str()/numOrNull: их regex ловит только КАВЫЧЕЧНЫЕ значения, а
        // модель шлёт thought_number/total_thoughts/next_needed голыми JSON-числом/булевым (как в схеме) →
        // иначе управляющий сигнал next_needed молча терялся бы (модель не могла бы остановиться/продолжить).
        val o = runCatching { org.json.JSONObject(argsJson) }.getOrNull()
            ?: return ToolResult.Failure("битый JSON")
        val thought = (if (o.isNull("thought")) "" else o.optString("thought")).trim()
        if (thought.isEmpty()) return ToolResult.Failure("нужен args.thought (текущий шаг рассуждения)")
        val n = o.optInt("thought_number", 1).coerceAtLeast(1)
        val total = o.optInt("total_thoughts", n).coerceAtLeast(n)
        // optBoolean принимает и булев true/false, и строки "true"/"false"; по умолчанию — пока n < total.
        val nextNeeded = if (o.has("next_needed") && !o.isNull("next_needed")) o.optBoolean("next_needed", n < total)
        else n < total
        val hint = if (nextNeeded) "продолжай: следующий шаг через sequential_thinking (thought_number ${n + 1})"
        else "рассуждение завершено — дай пользователю чистый финальный ответ БЕЗ этих шагов"
        return ok(
            "recorded" to "шаг $n из $total",
            "next_needed" to nextNeeded.toString(),
            "guidance" to hint,
        )
    }
}

/** unit_convert — длина/масса/температура/данные/скорость. */
class UnitConvertTool : AgentTool {
    override val id = "unit_convert"
    override val danger = DangerLevel.SAFE
    override val schema =
        """конвертация величин; args: {"value":10,"from":"km","to":"mi"} (m,km,cm,mm,mi,ft,in; kg,g,lb,oz; c,f,k; b,kb,mb,gb; kmh,ms,mph)"""

    // Всё к базовой единице (метр/кг/байт/м-с), температура — отдельно.
    private val f = mapOf(
        "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001, "mi" to 1609.344, "ft" to 0.3048, "in" to 0.0254,
        "kg" to 1.0, "g" to 0.001, "lb" to 0.453592, "oz" to 0.0283495,
        "b" to 1.0, "kb" to 1024.0, "mb" to 1048576.0, "gb" to 1073741824.0,
        "ms" to 1.0, "kmh" to 0.277778, "mph" to 0.44704,
    )

    // Размерность каждой единицы. Без неё пересчёт между РАЗНЫМИ величинами проходил молча: базовые
    // единицы всех групп равны 1.0, поэтому «10 kg в m» давало 10, а «10 km в lb» — 22045.86, и модель
    // подавала выдуманное число как факт. Теперь несовпадение размерностей — явная ошибка.
    private val dim = mapOf(
        "m" to "len", "km" to "len", "cm" to "len", "mm" to "len", "mi" to "len", "ft" to "len", "in" to "len",
        "kg" to "mass", "g" to "mass", "lb" to "mass", "oz" to "mass",
        "b" to "data", "kb" to "data", "mb" to "data", "gb" to "data",
        "ms" to "speed", "kmh" to "speed", "mph" to "speed",
    )

    override suspend fun invoke(argsJson: String): ToolResult {
        val v = num(argsJson, "value")
        val from = (str(argsJson, "from") ?: "").lowercase()
        val to = (str(argsJson, "to") ?: "").lowercase()
        val temp = setOf("c", "f", "k")
        return try {
            val out = if (from in temp && to in temp) convTemp(v, from, to) else {
                val a = f[from] ?: return ToolResult.Failure("неизвестная единица: $from")
                val b = f[to] ?: return ToolResult.Failure("неизвестная единица: $to")
                if (dim[from] != dim[to]) {
                    return ToolResult.Failure("несовместимые величины: $from и $to нельзя пересчитать друг в друга")
                }
                v * a / b
            }
            ok("result" to round(out).toString(), "unit" to to)
        } catch (t: Throwable) { ToolResult.Failure("convert: ${t.message}") }
    }

    private fun convTemp(v: Double, from: String, to: String): Double {
        val c = when (from) { "c" -> v; "f" -> (v - 32) * 5 / 9; else -> v - 273.15 }
        return when (to) { "c" -> c; "f" -> c * 9 / 5 + 32; else -> c + 273.15 }
    }
    private fun round(x: Double) = Math.round(x * 10000.0) / 10000.0
}

/** text_tools — подсчёт, регистр, base64, хеши. */
class TextTool : AgentTool {
    override val id = "text_tools"
    override val danger = DangerLevel.SAFE
    override val schema =
        """операции над текстом; args: {"op":"count|upper|lower|reverse|base64_encode|base64_decode|sha256|md5","text":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val op = str(argsJson, "op") ?: return ToolResult.Failure("нет args.op")
        val t = str(argsJson, "text") ?: return ToolResult.Failure("нет args.text")
        return try {
            val r = when (op) {
                "count" -> "символов: ${t.length}, слов: ${t.trim().split(Regex("\\s+")).count { it.isNotBlank() }}, строк: ${t.lines().size}"
                "upper" -> t.uppercase()
                "lower" -> t.lowercase()
                "reverse" -> t.reversed()
                "base64_encode" -> java.util.Base64.getEncoder().encodeToString(t.toByteArray())
                "base64_decode" -> String(java.util.Base64.getDecoder().decode(t.trim()))
                "sha256" -> hash("SHA-256", t)
                "md5" -> hash("MD5", t)
                else -> return ToolResult.Failure("op: count|upper|lower|reverse|base64_encode|base64_decode|sha256|md5")
            }
            ok("op" to op, "result" to r)
        } catch (t2: Throwable) { ToolResult.Failure("text: ${t2.message}") }
    }
    private fun hash(algo: String, s: String) =
        MessageDigest.getInstance(algo).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** search_files — поиск по имени и содержимому файлов в рабочей папке. */
class SearchFilesTool(private val workspace: File) : AgentTool {
    override val id = "search_files"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """найти файлы в рабочей папке по имени/содержимому; args: {"query":"текст"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val q = (str(argsJson, "query") ?: return ToolResult.Failure("нет args.query")).lowercase()
        val root = workspace.canonicalFile
        val hits = mutableListOf<String>()
        // maxDepth ограничивает обход; symlink-директории не входим (побег из песочницы/циклы);
        // дополнительно проверяем, что канонический путь файла остаётся внутри workspace.
        workspace.walkTopDown().maxDepth(8)
            .onEnter { dir -> runCatching { dir.canonicalPath == dir.absolutePath || dir.canonicalPath.startsWith(root.path) }.getOrDefault(false) }
            .filter { it.isFile }
            .forEach { fFile ->
                if (runCatching { !fFile.canonicalPath.startsWith(root.path) }.getOrDefault(true)) return@forEach
                val nameHit = fFile.name.lowercase().contains(q)
                val textHit = fFile.length() < 2_000_000 &&
                    fFile.extension.lowercase() in setOf("txt", "md", "csv", "json", "log", "kt", "py") &&
                    runCatching { fFile.readText().lowercase().contains(q) }.getOrDefault(false)
                if (nameHit || textHit) hits += fFile.toRelativeString(root) + if (textHit) " (в тексте)" else ""
            }
        return ok("found" to hits.size.toString(), "files" to hits.take(30).joinToString("; "))
    }
}

/** device_status — батарея, память, хранилище, версия ОС. */
class DeviceStatusTool(private val context: Context) : AgentTool {
    override val id = "device_status"
    override val danger = DangerLevel.SAFE
    override val schema = """состояние устройства (батарея, память, место, версия); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_073_741_824.0
        return ok(
            "battery" to "$battery%",
            "ram_free_mb" to (mi.availMem / 1048576).toString(),
            "ram_total_mb" to (mi.totalMem / 1048576).toString(),
            "storage_free_gb" to "%.1f".format(freeGb),
            "android" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}

// ── общие хелперы ──
internal fun str(json: String, field: String): String? = ru.aiagent.core.agent.tools.jsonField(json, field)

internal fun numOrNull(json: String, field: String): Double? =
    Regex("\"$field\"\\s*:\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

internal fun num(json: String, field: String): Double = numOrNull(json, field) ?: 0.0

internal fun esc(s: String) = ru.aiagent.core.agent.tools.escapeJson(s)

internal fun ok(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(pairs.joinToString(",", "{", "}") { "\"${it.first}\":\"${esc(it.second)}\"" })
