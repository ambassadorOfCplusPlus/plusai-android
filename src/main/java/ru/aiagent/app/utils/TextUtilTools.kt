package ru.aiagent.app.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import ru.aiagent.core.agent.tools.jsonField
import java.security.SecureRandom
import java.util.Base64
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Текстовые утилиты-инструменты (§3.4): конвертация цвета, проверка регулярных выражений,
 * шифрование/дешифрование строк паролем (AES-256-GCM) и рендер Markdown → HTML.
 * Всё на чистом Kotlin/JDK stdlib — без сети и без новых зависимостей. Все инструменты SAFE.
 */

// --- общие крипто-параметры (AES-256-GCM + PBKDF2), синхронны для encrypt/decrypt ---
private const val TU_SALT_LEN = 16
private const val TU_IV_LEN = 12
private const val TU_GCM_TAG_BITS = 128
private const val TU_PBKDF2_ITERATIONS = 120_000
private const val TU_AES_KEY_BITS = 256

/** Ключ AES-256 из пароля через PBKDF2WithHmacSHA256. */
private fun tuDeriveKey(password: String, salt: ByteArray): SecretKeySpec {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, TU_PBKDF2_ITERATIONS, TU_AES_KEY_BITS)
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
}

// ---------------------------------------------------------------------------
// color_convert
// ---------------------------------------------------------------------------

/** Разобранный цвет в RGB (0..255). */
private data class Rgb(val r: Int, val g: Int, val b: Int)

private val TU_RGB_RE = Regex("""rgba?\(([^)]+)\)""", RegexOption.IGNORE_CASE)
private val TU_HSL_RE = Regex("""hsla?\(([^)]+)\)""", RegexOption.IGNORE_CASE)

/** Разобрать HEX (#RGB/#RRGGBB), rgb(r,g,b) или hsl(h,s,l) в RGB (или null). */
private fun parseColor(raw: String): Rgb? {
    val s = raw.trim()
    if (s.startsWith("#")) {
        val hex = s.substring(1)
        return when (hex.length) {
            3 -> runCatching {
                Rgb(
                    (hex[0].toString().repeat(2)).toInt(16),
                    (hex[1].toString().repeat(2)).toInt(16),
                    (hex[2].toString().repeat(2)).toInt(16),
                )
            }.getOrNull()
            6 -> runCatching {
                Rgb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
            }.getOrNull()
            else -> null
        }
    }
    TU_RGB_RE.find(s)?.let { m ->
        val parts = m.groupValues[1].split(",").map { it.trim().removeSuffix("%") }
        if (parts.size < 3) return null
        return runCatching {
            Rgb(
                parts[0].toDouble().roundToInt().coerceIn(0, 255),
                parts[1].toDouble().roundToInt().coerceIn(0, 255),
                parts[2].toDouble().roundToInt().coerceIn(0, 255),
            )
        }.getOrNull()
    }
    TU_HSL_RE.find(s)?.let { m ->
        val parts = m.groupValues[1].split(",").map { it.trim().removeSuffix("%") }
        if (parts.size < 3) return null
        return runCatching {
            val h = parts[0].toDouble()
            val sat = parts[1].toDouble() / 100.0
            val lig = parts[2].toDouble() / 100.0
            hslToRgb(h, sat, lig)
        }.getOrNull()
    }
    return null
}

private fun hslToRgb(h: Double, s: Double, l: Double): Rgb {
    if (s == 0.0) {
        val v = (l * 255).roundToInt().coerceIn(0, 255)
        return Rgb(v, v, v)
    }
    fun hue2rgb(p: Double, q: Double, t: Double): Double {
        var tt = t
        if (tt < 0) tt += 1
        if (tt > 1) tt -= 1
        return when {
            tt < 1.0 / 6 -> p + (q - p) * 6 * tt
            tt < 1.0 / 2 -> q
            tt < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - tt) * 6
            else -> p
        }
    }
    val q = if (l < 0.5) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    val hn = h / 360.0
    val r = hue2rgb(p, q, hn + 1.0 / 3)
    val g = hue2rgb(p, q, hn)
    val b = hue2rgb(p, q, hn - 1.0 / 3)
    return Rgb(
        (r * 255).roundToInt().coerceIn(0, 255),
        (g * 255).roundToInt().coerceIn(0, 255),
        (b * 255).roundToInt().coerceIn(0, 255),
    )
}

private fun rgbToHsl(c: Rgb): Triple<Int, Int, Int> {
    val rf = c.r / 255.0
    val gf = c.g / 255.0
    val bf = c.b / 255.0
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val l = (max + min) / 2
    if (max == min) return Triple(0, 0, (l * 100).roundToInt())
    val d = max - min
    val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
    var h = when (max) {
        rf -> (gf - bf) / d + (if (gf < bf) 6 else 0)
        gf -> (bf - rf) / d + 2
        else -> (rf - gf) / d + 4
    }
    h /= 6
    return Triple((h * 360).roundToInt(), (s * 100).roundToInt(), (l * 100).roundToInt())
}

/** color_convert — конвертация цвета между HEX / RGB / HSL. */
class ColorConvertTool : AgentTool {
    override val id = "color_convert"
    override val danger = DangerLevel.SAFE
    override val schema =
        """конвертировать цвет между HEX/RGB/HSL; args: {"value":"#FF8800","to":"rgb|hex|hsl"} (to по умолчанию hex)"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val value = jsonField(argsJson, "value") ?: return ToolResult.Failure("нет args.value")
        val to = (jsonField(argsJson, "to") ?: "hex").lowercase()
        val rgb = parseColor(value)
            ?: return ToolResult.Failure("не удалось разобрать цвет: $value (ожидается #RGB/#RRGGBB, rgb(...) или hsl(...))")
        val result = when (to) {
            "hex" -> "#%02X%02X%02X".format(rgb.r, rgb.g, rgb.b)
            "rgb" -> "rgb(${rgb.r}, ${rgb.g}, ${rgb.b})"
            "hsl" -> {
                val (h, s, l) = rgbToHsl(rgb)
                "hsl($h, $s%, $l%)"
            }
            else -> return ToolResult.Failure("неизвестный формат to=$to (rgb|hex|hsl)")
        }
        val out = JSONObject()
            .put("result", result)
            .put("to", to)
            .put("rgb", JSONArray().put(rgb.r).put(rgb.g).put(rgb.b))
        return ToolResult.Success(out.toString())
    }
}

// ---------------------------------------------------------------------------
// regex_test
// ---------------------------------------------------------------------------

/** Пределы regex_test: защита от катастрофического бэктрекинга (ReDoS). */
private const val REGEX_TIMEOUT_MS = 3000L
private const val REGEX_MAX_TEXT = 100_000

/** regex_test — проверить регулярное выражение по тексту (java.util.regex). */
class RegexTestTool : AgentTool {
    override val id = "regex_test"
    override val danger = DangerLevel.SAFE
    override val schema =
        """проверить регулярное выражение по тексту (совпадения, группы, позиции); args: {"pattern":"\\d+","text":"a12b34","flags":"imsux — опц"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val pattern = jsonField(argsJson, "pattern") ?: return ToolResult.Failure("нет args.pattern")
        val text = jsonField(argsJson, "text") ?: return ToolResult.Failure("нет args.text")
        // Ограничение длины входа снижает риск и стоимость катастрофического бэктрекинга.
        if (text.length > REGEX_MAX_TEXT) {
            return ToolResult.Failure("текст слишком велик для regex_test (> $REGEX_MAX_TEXT символов)")
        }
        val flagsStr = jsonField(argsJson, "flags") ?: ""
        var flags = 0
        if (flagsStr.contains('i')) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (flagsStr.contains('m')) flags = flags or Pattern.MULTILINE
        if (flagsStr.contains('s')) flags = flags or Pattern.DOTALL
        if (flagsStr.contains('x')) flags = flags or Pattern.COMMENTS
        if (flagsStr.contains('u')) flags = flags or Pattern.UNICODE_CHARACTER_CLASS
        val p = runCatching { Pattern.compile(pattern, flags) }
            .getOrElse { return ToolResult.Failure("невалидное регулярное выражение: ${it.message}") }

        // java-regex не прерывается кооперативно (Thread.interrupt его не останавливает на обычной
        // String), поэтому withTimeoutOrNull напрямую вокруг matcher.find() не вернул бы управление.
        // Считаем в отдельном потоке-демоне и ограничиваем время через ожидание CompletableDeferred:
        // при таймауте отдаём Failure и управление, а поток-демон дозавершится сам.
        val deferred = CompletableDeferred<JSONObject>()
        Thread {
            try {
                val matcher = p.matcher(text)
                val results = JSONArray()
                var count = 0
                while (matcher.find() && count < 500) {
                    val o = JSONObject()
                        .put("match", matcher.group())
                        .put("start", matcher.start())
                        .put("end", matcher.end())
                    val groups = JSONArray()
                    for (g in 1..matcher.groupCount()) groups.put(matcher.group(g) ?: JSONObject.NULL)
                    o.put("groups", groups)
                    results.put(o)
                    count++
                }
                deferred.complete(
                    JSONObject()
                        .put("matches", results.length() > 0)
                        .put("count", results.length())
                        .put("results", results),
                )
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }.apply { isDaemon = true; name = "regex_test" }.start()

        val out = try {
            withTimeoutOrNull(REGEX_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            return ToolResult.Failure("regex_test: ${t.message?.take(160)}")
        } ?: return ToolResult.Failure(
            "слишком сложный паттерн или слишком долгое сопоставление (таймаут $REGEX_TIMEOUT_MS мс)",
        )
        return ToolResult.Success(out.toString())
    }
}

// ---------------------------------------------------------------------------
// encrypt_text / decrypt_text
// ---------------------------------------------------------------------------

/** encrypt_text — зашифровать строку паролем (AES-256-GCM + PBKDF2). */
class EncryptTextTool : AgentTool {
    override val id = "encrypt_text"
    override val danger = DangerLevel.SAFE
    override val schema =
        """зашифровать строку паролем (AES-256-GCM); возвращает Base64(соль+IV+шифртекст); args: {"text":"...","password":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val text = jsonField(argsJson, "text") ?: return ToolResult.Failure("нет args.text")
        val password = jsonField(argsJson, "password") ?: return ToolResult.Failure("нет args.password")
        val rnd = SecureRandom()
        val salt = ByteArray(TU_SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(TU_IV_LEN).also { rnd.nextBytes(it) }
        val encrypted = runCatching {
            val key = tuDeriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TU_GCM_TAG_BITS, iv))
            cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        }.getOrElse { return ToolResult.Failure("ошибка шифрования: ${it.message}") }
        val packed = salt + iv + encrypted
        val b64 = Base64.getEncoder().encodeToString(packed)
        return ToolResult.Success("""{"data":"${escapeJson(b64)}"}""")
    }
}

/** decrypt_text — расшифровать строку, зашифрованную encrypt_text. */
class DecryptTextTool : AgentTool {
    override val id = "decrypt_text"
    override val danger = DangerLevel.SAFE
    override val schema =
        """расшифровать строку из encrypt_text (Base64 соль+IV+шифртекст); args: {"data":"base64...","password":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val data = jsonField(argsJson, "data") ?: return ToolResult.Failure("нет args.data")
        val password = jsonField(argsJson, "password") ?: return ToolResult.Failure("нет args.password")
        val packed = runCatching { Base64.getDecoder().decode(data.trim()) }
            .getOrElse { return ToolResult.Failure("некорректный Base64 в args.data") }
        if (packed.size < TU_SALT_LEN + TU_IV_LEN + 1) {
            return ToolResult.Failure("данные слишком короткие для соль+IV+шифртекст")
        }
        val salt = packed.copyOfRange(0, TU_SALT_LEN)
        val iv = packed.copyOfRange(TU_SALT_LEN, TU_SALT_LEN + TU_IV_LEN)
        val ct = packed.copyOfRange(TU_SALT_LEN + TU_IV_LEN, packed.size)
        val plain = runCatching {
            val key = tuDeriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TU_GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse { return ToolResult.Failure("не удалось расшифровать (неверный пароль или повреждённые данные)") }
        return ToolResult.Success("""{"text":"${escapeJson(plain)}"}""")
    }
}

// ---------------------------------------------------------------------------
// markdown_to_html
// ---------------------------------------------------------------------------

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private val TU_CODE_RE = Regex("`([^`]+)`")
private val TU_LINK_RE = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val TU_BOLD_RE = Regex("""\*\*(.+?)\*\*""")
private val TU_ITALIC_RE = Regex("""\*(.+?)\*""")

/** Инлайн-разметка: экранируем HTML, затем code / ссылки / bold / italic. */
private fun mdInline(s: String): String {
    var t = escapeHtml(s)
    t = TU_CODE_RE.replace(t) { "<code>${it.groupValues[1]}</code>" }
    t = TU_LINK_RE.replace(t) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
    t = TU_BOLD_RE.replace(t) { "<strong>${it.groupValues[1]}</strong>" }
    t = TU_ITALIC_RE.replace(t) { "<em>${it.groupValues[1]}</em>" }
    return t
}

private val TU_FENCE_OPEN_RE = Regex("""^\s*```(.*)$""")
private val TU_FENCE_CLOSE_RE = Regex("""^\s*```\s*$""")
private val TU_HEADING_RE = Regex("""^(#{1,6})\s+(.*)$""")
private val TU_UL_RE = Regex("""^\s*[-*+]\s+(.*)$""")
private val TU_OL_RE = Regex("""^\s*\d+\.\s+(.*)$""")

/** Компактный рендер Markdown → HTML (заголовки, bold/italic/code, ссылки, списки, блоки кода, параграфы). */
private fun markdownToHtml(md: String): String {
    val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val sb = StringBuilder()
    val para = StringBuilder()
    var inUl = false
    var inOl = false

    fun flushPara() {
        if (para.isNotEmpty()) {
            sb.append("<p>").append(mdInline(para.toString().trim())).append("</p>\n")
            para.setLength(0)
        }
    }
    fun closeUl() { if (inUl) { sb.append("</ul>\n"); inUl = false } }
    fun closeOl() { if (inOl) { sb.append("</ol>\n"); inOl = false } }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = TU_FENCE_OPEN_RE.find(line)
        if (fence != null && line.trimStart().startsWith("```")) {
            flushPara(); closeUl(); closeOl()
            i++
            val code = StringBuilder()
            while (i < lines.size && !TU_FENCE_CLOSE_RE.matches(lines[i])) {
                code.append(lines[i]).append("\n"); i++
            }
            if (i < lines.size) i++ // пропустить закрывающий ```
            sb.append("<pre><code>").append(escapeHtml(code.toString())).append("</code></pre>\n")
            continue
        }
        if (line.isBlank()) { flushPara(); closeUl(); closeOl(); i++; continue }
        val h = TU_HEADING_RE.find(line)
        if (h != null) {
            flushPara(); closeUl(); closeOl()
            val level = h.groupValues[1].length
            sb.append("<h$level>").append(mdInline(h.groupValues[2].trim())).append("</h$level>\n")
            i++; continue
        }
        val ul = TU_UL_RE.find(line)
        if (ul != null) {
            flushPara(); closeOl()
            if (!inUl) { sb.append("<ul>\n"); inUl = true }
            sb.append("<li>").append(mdInline(ul.groupValues[1].trim())).append("</li>\n")
            i++; continue
        }
        val ol = TU_OL_RE.find(line)
        if (ol != null) {
            flushPara(); closeUl()
            if (!inOl) { sb.append("<ol>\n"); inOl = true }
            sb.append("<li>").append(mdInline(ol.groupValues[1].trim())).append("</li>\n")
            i++; continue
        }
        closeUl(); closeOl()
        if (para.isNotEmpty()) para.append(" ")
        para.append(line.trim())
        i++
    }
    flushPara(); closeUl(); closeOl()
    return sb.toString().trim()
}

/** markdown_to_html — конвертация Markdown в HTML. */
class MarkdownToHtmlTool : AgentTool {
    override val id = "markdown_to_html"
    override val danger = DangerLevel.SAFE
    override val schema =
        """конвертировать Markdown в HTML (заголовки, bold/italic/code, ссылки, списки, блоки кода); args: {"markdown":"# Заголовок\n**жирный**"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val markdown = jsonField(argsJson, "markdown") ?: return ToolResult.Failure("нет args.markdown")
        val html = markdownToHtml(markdown)
        return ToolResult.Success("""{"html":"${escapeJson(html)}"}""")
    }
}

// ---------------------------------------------------------------------------
// Фабрика
// ---------------------------------------------------------------------------

/** Текстовые утилиты: конвертация цвета, regex, шифрование/дешифрование, Markdown → HTML. */
fun textUtilTools(): List<AgentTool> = listOf(
    ColorConvertTool(),
    RegexTestTool(),
    EncryptTextTool(),
    DecryptTextTool(),
    MarkdownToHtmlTool(),
)
