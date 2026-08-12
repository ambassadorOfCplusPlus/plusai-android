package ru.aiagent.app.integrations

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.AppSettings
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * Календарь агента (S10) — локально через CalendarProvider. Чтение личных событий —
 * IMPORTANT с подтверждением (кроме bypass); создание события — тоже с подтверждением.
 * Ничего не уходит на сервер (§7).
 */

private fun jField(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

private const val NO_READ = "нет доступа к календарю — выдай разрешение в Настройки → Интеграции → Календарь"

/** Отдать события с учётом режима: в hybrid облаку — резюме ЛОКАЛЬНОЙ модели (сырьё не уходит); иначе как есть. */
private suspend fun calendarResult(context: Context, rawJson: String, focus: String): ToolResult {
    if (AppSettings.calendarCloudMode(context) != "hybrid") return ToolResult.Success(rawJson)
    val instr = "Кратко изложи события календаря пользователя" +
        (if (focus.isNotBlank()) " по запросу «$focus»" else "") + ". Только по данным ниже, по делу.\n\n$rawJson"
    val answer = runCatching { ru.aiagent.app.ChatEngine.agentGenerate(context, instr) }
        .getOrElse {
            return ToolResult.Failure("для приватного чтения календаря (режим «через локальную модель») нужна " +
                "локальная модель — установите/выберите её на вкладке Модели, либо переключите режим Календаря на «Напрямую»")
        }
    return ToolResult.Success(JSONObject().put("answer", answer.trim().take(4000)).toString())
}

/** calendar_upcoming — ближайшие события. */
class CalendarUpcomingTool(private val context: Context) : AgentTool {
    override val id = "calendar_upcoming"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.calendarCloudMode(context) == "local"
    override val schema = """ближайшие события календаря; args: {"days":7,"limit":20}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!CalendarClient.canRead(context)) return ToolResult.Failure(NO_READ)
        val days = jField(argsJson, "days")?.toIntOrNull() ?: 7
        val limit = jField(argsJson, "limit")?.toIntOrNull() ?: 20
        val now = System.currentTimeMillis()
        val list = CalendarClient.events(context, now, now + days.coerceIn(1, 365).toLong() * 86_400_000, limit)
        return calendarResult(context, eventsJson(list), "")
    }
}

/** search_calendar — найти событие по слову. */
class SearchCalendarTool(private val context: Context) : AgentTool {
    override val id = "search_calendar"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.calendarCloudMode(context) == "local"
    override val schema = """найти событие в календаре; args: {"query":"встреча","days":90,"limit":10}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!CalendarClient.canRead(context)) return ToolResult.Failure(NO_READ)
        val q = jField(argsJson, "query") ?: return ToolResult.Failure("нужен args.query")
        val days = jField(argsJson, "days")?.toIntOrNull() ?: 90
        val limit = jField(argsJson, "limit")?.toIntOrNull() ?: 10
        return calendarResult(context, eventsJson(CalendarClient.search(context, q, days, limit)), q)
    }
}

/** create_calendar_event — создать событие (запись → подтверждение). */
class CreateCalendarEventTool(private val context: Context) : AgentTool {
    override val id = "create_calendar_event"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.calendarCloudMode(context) == "local"
    override val schema =
        """создать событие; args: {"title":"Встреча","start":"2026-07-10 15:00","end":"2026-07-10 16:00","location":"офис","description":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!CalendarClient.canWrite(context)) return ToolResult.Failure("нет разрешения на запись календаря — выдай в Настройки → Интеграции → Календарь")
        val title = jField(argsJson, "title") ?: return ToolResult.Failure("нужен args.title")
        val startStr = jField(argsJson, "start") ?: return ToolResult.Failure("нужен args.start (yyyy-MM-dd HH:mm)")
        val begin = CalendarClient.parseTime(startStr) ?: return ToolResult.Failure("не понял время start")
        val end = jField(argsJson, "end")?.let { CalendarClient.parseTime(it) } ?: (begin + 3600_000)
        return try {
            val eid = CalendarClient.create(context, title, begin, end, jField(argsJson, "location"), jField(argsJson, "description"))
            ToolResult.Success(JSONObject().put("id", eid).put("status", "создано").toString())
        } catch (t: Throwable) {
            ToolResult.Failure("календарь: ${t.message}")
        }
    }
}

/** update_calendar_event — изменить событие: меняются только переданные поля (запись → подтверждение). */
class UpdateCalendarEventTool(private val context: Context) : AgentTool {
    override val id = "update_calendar_event"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    // §7: в режиме local календарь недоступен облаку — как и у upcoming/search/create (иначе изменение
    // события облачной моделью в строгом режиме противоречит ожиданию «облако календарь не видит»).
    override val privateData get() = AppSettings.calendarCloudMode(context) == "local"
    override val schema =
        """изменить событие (только переданные поля); args: {"event_id":"123","title":"опц","start":"опц 2026-07-10 15:00","end":"опц","location":"опц","description":"опц"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!CalendarClient.canWrite(context)) return ToolResult.Failure("нет разрешения на запись календаря — выдай в Настройки → Интеграции → Календарь")
        val id = jField(argsJson, "event_id")?.toLongOrNull() ?: return ToolResult.Failure("нужен args.event_id")
        val begin = jField(argsJson, "start")?.let { CalendarClient.parseTime(it) ?: return ToolResult.Failure("не понял время start") }
        val end = jField(argsJson, "end")?.let { CalendarClient.parseTime(it) ?: return ToolResult.Failure("не понял время end") }
        val title = jField(argsJson, "title")
        val location = jField(argsJson, "location")
        val description = jField(argsJson, "description")
        if (title == null && begin == null && end == null && location == null && description == null)
            return ToolResult.Failure("нечего менять — передай хотя бы одно поле")
        return try {
            val n = CalendarClient.update(context, id, title, begin, end, location, description)
            if (n > 0) ToolResult.Success(JSONObject().put("updated", id).toString())
            else ToolResult.Failure("событие $id не найдено")
        } catch (t: Throwable) {
            ToolResult.Failure("календарь: ${t.message}")
        }
    }
}

/** delete_calendar_event — удалить событие (опасно → подтверждение). */
class DeleteCalendarEventTool(private val context: Context) : AgentTool {
    override val id = "delete_calendar_event"
    override val danger = DangerLevel.DANGEROUS
    override val alwaysConfirm = true
    // §7: в режиме local календарь недоступен облаку (как upcoming/search/create/update) — иначе облачная
    // модель могла бы удалить событие в строгом режиме, где пользователь считает календарь невидимым.
    override val privateData get() = AppSettings.calendarCloudMode(context) == "local"
    override val schema = """удалить событие; args: {"event_id":"123"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!CalendarClient.canWrite(context)) return ToolResult.Failure("нет разрешения на запись календаря — выдай в Настройки → Интеграции → Календарь")
        val id = jField(argsJson, "event_id")?.toLongOrNull() ?: return ToolResult.Failure("нужен args.event_id")
        return try {
            val n = CalendarClient.delete(context, id)
            if (n > 0) ToolResult.Success(JSONObject().put("deleted", id).toString())
            else ToolResult.Failure("событие $id не найдено")
        } catch (t: Throwable) {
            ToolResult.Failure("календарь: ${t.message}")
        }
    }
}

private fun eventsJson(list: List<CalendarClient.Event>): String {
    val arr = JSONArray()
    for (e in list) {
        arr.put(JSONObject()
            .put("id", e.id).put("title", e.title)
            .put("start", e.beginText()).put("end", e.endText())
            .put("location", e.location).put("description", e.description))
    }
    return JSONObject().put("events", arr).toString()
}

fun calendarTools(context: Context): List<AgentTool> =
    listOf(
        CalendarUpcomingTool(context), SearchCalendarTool(context), CreateCalendarEventTool(context),
        UpdateCalendarEventTool(context), DeleteCalendarEventTool(context),
    )
