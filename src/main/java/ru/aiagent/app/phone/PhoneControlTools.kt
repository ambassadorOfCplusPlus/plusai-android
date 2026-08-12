package ru.aiagent.app.phone

import kotlinx.coroutines.suspendCancellableCoroutine
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import kotlin.coroutines.resume

/** Локальный извлекатель строкового JSON-поля (core-agent.jsonString — internal). */
internal fun jsonStr(json: String, field: String): String? =
    Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        ?.groupValues?.get(1)?.let { ru.aiagent.core.agent.tools.unescapeJson(it) }

/**
 * Инструменты управления телефоном (S11). Доступны только когда служба включена
 * пользователем. Действия помечены DANGEROUS → подтверждение во всех режимах, кроме
 * bypass (политика §3.6). Гейт по возможностям модели — на уровне сборки набора (L8).
 */

private fun svc() = PhoneControlService.instance

/** read_screen — текстовый снимок экрана (SAFE, только чтение). */
class ReadScreenTool(private val context: android.content.Context) : AgentTool {
    override val id = "read_screen"
    override val danger = DangerLevel.SAFE
    // Снимок экрана — приватные данные (банк, переписки). По умолчанию облаку НЕ отдаём (§7); режим
    // «Экран в облако» = direct открывает облачной модели содержимое экрана (Настройки → Управление телефоном).
    override val privateData get() = ru.aiagent.app.AppSettings.screenCloudMode(context) != "direct"
    override val schema = """снимок экрана: видимый текст и координаты; args: {}"""
    override suspend fun invoke(argsJson: String): ToolResult {
        val s = svc() ?: return ToolResult.Failure("служба управления телефоном выключена")
        return ToolResult.Success("""{"screen":"${esc(s.readScreen())}"}""")
    }
}

/** tap — тап по координатам (DANGEROUS). */
class TapTool : AgentTool {
    override val id = "tap"
    override val danger = DangerLevel.DANGEROUS
    override val schema = """тап по экрану; args: {"x":123,"y":456}"""
    override suspend fun invoke(argsJson: String): ToolResult {
        val s = svc() ?: return ToolResult.Failure("служба выключена")
        val x = intField(argsJson, "x") ?: return ToolResult.Failure("нет x")
        val y = intField(argsJson, "y") ?: return ToolResult.Failure("нет y")
        val ok = suspendCancellableCoroutine { c -> s.tap(x, y) { c.resume(it) } }
        return if (ok) ToolResult.Success("""{"tapped":[$x,$y]}""") else ToolResult.Failure("жест отменён")
    }
}

/** swipe — свайп (DANGEROUS). */
class SwipeTool : AgentTool {
    override val id = "swipe"
    override val danger = DangerLevel.DANGEROUS
    override val schema = """свайп; args: {"x1":..,"y1":..,"x2":..,"y2":..,"ms":300}"""
    override suspend fun invoke(argsJson: String): ToolResult {
        val s = svc() ?: return ToolResult.Failure("служба выключена")
        val x1 = intField(argsJson, "x1") ?: return ToolResult.Failure("нет x1")
        val y1 = intField(argsJson, "y1") ?: return ToolResult.Failure("нет y1")
        val x2 = intField(argsJson, "x2") ?: return ToolResult.Failure("нет x2")
        val y2 = intField(argsJson, "y2") ?: return ToolResult.Failure("нет y2")
        val ms = intField(argsJson, "ms")?.toLong() ?: 300L
        val ok = suspendCancellableCoroutine { c -> s.swipe(x1, y1, x2, y2, ms) { c.resume(it) } }
        return if (ok) ToolResult.Success("""{"swiped":true}""") else ToolResult.Failure("жест отменён")
    }
}

/** nav — системные кнопки back/home (DANGEROUS). */
class NavTool : AgentTool {
    override val id = "nav"
    override val danger = DangerLevel.DANGEROUS
    override val schema = """системная навигация; args: {"action":"back|home"}"""
    override suspend fun invoke(argsJson: String): ToolResult {
        val s = svc() ?: return ToolResult.Failure("служба выключена")
        return when (jsonStr(argsJson, "action")) {
            "back" -> if (s.back()) ok("back") else ToolResult.Failure("не удалось")
            "home" -> if (s.home()) ok("home") else ToolResult.Failure("не удалось")
            else -> ToolResult.Failure("action: back|home")
        }
    }
    private fun ok(a: String) = ToolResult.Success("""{"nav":"$a"}""")
}

/** Набор инструментов управления телефоном (только при включённой службе). */
fun phoneControlTools(context: android.content.Context): List<AgentTool> =
    listOf(ReadScreenTool(context), TapTool(), SwipeTool(), NavTool())

private fun intField(json: String, field: String): Int? =
    Regex("\"$field\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

// Общий escapeJson: экранирует и управляющие символы (\t,\r,\b,\f,<0x20) — ручной 3-replace давал битый JSON.
private fun esc(s: String) = ru.aiagent.core.agent.tools.escapeJson(s)
