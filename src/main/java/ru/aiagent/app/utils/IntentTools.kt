package ru.aiagent.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Инструменты через системные интенты — реальная продуктивность без ключей/сети. */

private fun launch(context: Context, intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
}

/** add_calendar_event — создать событие в календаре (открывает редактор с заполненными полями). */
class AddCalendarEventTool(private val context: Context) : AgentTool {
    override val id = "add_calendar_event"
    override val danger = DangerLevel.IMPORTANT
    override val schema =
        """добавить событие в календарь (открывает редактор с заполненными полями); args: {"title":"Встреча","start":"2026-07-08 15:00","minutes":60,"location":"офис"}. start: ISO 8601 ("2026-07-08T15:00") или через пробел ("2026-07-08 15:00"); только дата ("2026-07-08") → событие на весь день"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val title = str(argsJson, "title") ?: return ToolResult.Failure("нет args.title")
        val startStr = str(argsJson, "start")
        return try {
            val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
            str(argsJson, "location")?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            if (!startStr.isNullOrBlank()) {
                val parsed = parseEventStart(startStr)
                    ?: return ToolResult.Failure("не понял дату «$startStr» — ожидаю 2026-07-08 15:00 или 2026-07-08T15:00")
                val startMs = parsed.first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                if (parsed.second) {
                    // Только дата — событие на весь день.
                    intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                    intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + 86_400_000L)
                } else {
                    val dur = (num(argsJson, "minutes").takeIf { it > 0 } ?: 60.0).toLong()
                    intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + dur * 60_000)
                }
            }
            if (launch(context, intent)) ok("status" to "открыт редактор события «$title»")
            else ToolResult.Failure("нет приложения календаря")
        } catch (t: Throwable) { ToolResult.Failure("calendar: ${t.message}") }
    }

    /** Разбор start в терпимом виде: ISO 8601 (T), через пробел, или только дата.
     * Возвращает (момент, allDay) либо null, если ни один формат не подошёл. */
    private fun parseEventStart(raw: String): Pair<LocalDateTime, Boolean>? {
        val s = raw.trim().replace('T', ' ').replace(Regex("\\s+"), " ")
        // С временем: yyyy-MM-dd HH:mm[:ss].
        for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-M-d H:m")) {
            runCatching { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p)) to false }
        }
        // Только дата → на весь день (полночь).
        for (p in listOf("yyyy-MM-dd", "yyyy-M-d")) {
            runCatching {
                val d = java.time.LocalDate.parse(s, DateTimeFormatter.ofPattern(p))
                return d.atStartOfDay() to true
            }
        }
        return null
    }
}

/** set_alarm — поставить будильник или таймер. */
class SetAlarmTool(private val context: Context) : AgentTool {
    override val id = "set_alarm"
    override val danger = DangerLevel.IMPORTANT
    override val schema =
        """будильник/таймер; args: {"type":"alarm","hour":7,"minute":30,"message":"подъём"} или {"type":"timer","seconds":300,"message":"чай"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val type = str(argsJson, "type") ?: "alarm"
        val msg = str(argsJson, "message") ?: "Plus AI"
        return try {
            val intent = if (type == "timer") {
                val secs = numOrNull(argsJson, "seconds")?.toInt()
                    ?: return ToolResult.Failure("нужно args.seconds для таймера")
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs.coerceAtLeast(1))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            } else {
                // Час обязателен — иначе молча ставили бы 00:00.
                val hour = numOrNull(argsJson, "hour")?.toInt()
                    ?: return ToolResult.Failure("нужно args.hour (0-23) для будильника")
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, num(argsJson, "minute").toInt())
                    .putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            }
            if (launch(context, intent)) ok("status" to "$type установлен: $msg")
            else ToolResult.Failure("нет приложения часов")
        } catch (t: Throwable) { ToolResult.Failure("alarm: ${t.message}") }
    }
}

/** open_url — открыть ссылку в браузере. */
class OpenUrlTool(private val context: Context) : AgentTool {
    override val id = "open_url"
    override val danger = DangerLevel.DANGEROUS
    override val alwaysConfirm = true
    override val schema = """открыть ссылку в браузере; args: {"url":"https://..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        var url = str(argsJson, "url") ?: return ToolResult.Failure("нет args.url")
        if (!url.startsWith("http")) url = "https://$url"
        return if (launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url))))
            ok("status" to "открыто: $url") else ToolResult.Failure("не удалось открыть")
    }
}

/**
 * make_call — позвонить по номеру. Если есть разрешение CALL_PHONE — набирает напрямую (ACTION_CALL);
 * иначе открывает звонилку с готовым номером (ACTION_DIAL), пользователь жмёт вызов сам.
 * DANGEROUS + подтверждение: звонок — внешнее необратимое действие.
 */
class MakeCallTool(private val context: Context) : AgentTool {
    override val id = "make_call"
    override val danger = DangerLevel.DANGEROUS
    override val alwaysConfirm = true
    override val schema = """позвонить по номеру телефона; args: {"number":"+79991234567"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val raw = str(argsJson, "number") ?: return ToolResult.Failure("нет args.number")
        val number = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (number.isBlank()) return ToolResult.Failure("некорректный номер: «$raw»")
        val uri = Uri.fromParts("tel", number, null)
        val granted = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        // Прямой вызов только при выданном CALL_PHONE (иначе ACTION_CALL кинет SecurityException).
        if (granted && launch(context, Intent(Intent.ACTION_CALL, uri)))
            return ok("status" to "звоню: $number")
        // Фолбэк: звонилка с набранным номером — вызов подтверждает пользователь.
        return if (launch(context, Intent(Intent.ACTION_DIAL, uri)))
            ok("status" to "открыта звонилка с номером $number — нажмите вызов")
        else ToolResult.Failure("нет приложения для звонков")
    }
}

/** share_text — поделиться текстом через системное меню. */
class ShareTextTool(private val context: Context) : AgentTool {
    override val id = "share_text"
    override val danger = DangerLevel.IMPORTANT
    override val schema = """поделиться текстом (системное меню); args: {"text":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val text = str(argsJson, "text") ?: return ToolResult.Failure("нет args.text")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return if (launch(context, Intent.createChooser(send, "Поделиться")))
            ok("status" to "открыто меню «Поделиться»") else ToolResult.Failure("не удалось")
    }
}
