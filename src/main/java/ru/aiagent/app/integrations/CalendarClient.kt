package ru.aiagent.app.integrations

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Локальный календарь (S10) через системный CalendarProvider. Ничего не уходит на
 * сервер (ТЗ §7): агент читает/создаёт события прямо на устройстве. Доступ — по
 * разрешениям READ_CALENDAR/WRITE_CALENDAR, которые выдаёт пользователь.
 */
object CalendarClient {

    data class Event(
        val id: Long,
        val title: String,
        val begin: Long, // unix millis
        val end: Long,
        val location: String,
        val description: String,
    ) {
        fun beginText(): String = fmt(begin)
        fun endText(): String = fmt(end)
    }

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** События в диапазоне [from, to] (millis), отсортированы по началу. */
    fun events(context: Context, from: Long, to: Long, limit: Int): List<Event> {
        if (!canRead(context)) return emptyList()
        val out = ArrayList<Event>()
        val proj = arrayOf(
            CalendarContract.Events._ID, CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
        )
        val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0"
        val cur = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj,
            sel, arrayOf(from.toString(), to.toString()),
            "${CalendarContract.Events.DTSTART} ASC",
        ) ?: return emptyList()
        cur.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += Event(
                    id = c.getLong(0),
                    title = c.getString(1) ?: "(без названия)",
                    begin = c.getLong(2),
                    end = if (c.isNull(3)) c.getLong(2) else c.getLong(3),
                    location = c.getString(4) ?: "",
                    description = (c.getString(5) ?: "").take(300),
                )
            }
        }
        return out
    }

    /** Поиск по названию/описанию/месту в ближайшие [days] дней (± месяц назад). */
    fun search(context: Context, query: String, days: Int, limit: Int): List<Event> {
        val now = System.currentTimeMillis()
        val from = now - 30L * 24 * 3600 * 1000
        val to = now + days.coerceIn(1, 365).toLong() * 24 * 3600 * 1000
        val q = query.lowercase()
        return events(context, from, to, 500)
            .filter { it.title.lowercase().contains(q) || it.description.lowercase().contains(q) || it.location.lowercase().contains(q) }
            .take(limit)
    }

    /** id первого доступного для записи календаря (обычно основной аккаунт). */
    private fun writableCalendarId(context: Context): Long? {
        val cur = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        ) ?: return null
        cur.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    /** Создать событие. Возвращает id или бросает. */
    fun create(context: Context, title: String, begin: Long, end: Long, location: String?, description: String?): Long {
        require(canWrite(context)) { "нет разрешения на запись календаря" }
        val calId = writableCalendarId(context) ?: error("нет календаря для записи")
        val tz = ZoneId.systemDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, if (end > begin) end else begin + 3600_000)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("не удалось создать событие")
        return ContentUris.parseId(uri)
    }

    /** Обновить событие: пишутся только переданные (не-null) поля. Возвращает число изменённых строк. */
    fun update(
        context: Context, id: Long,
        title: String?, begin: Long?, end: Long?, location: String?, description: String?,
    ): Int {
        require(canWrite(context)) { "нет разрешения на запись календаря" }
        val values = ContentValues().apply {
            title?.let { put(CalendarContract.Events.TITLE, it) }
            if (begin != null || end != null) put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            begin?.let { put(CalendarContract.Events.DTSTART, it) }
            end?.let { put(CalendarContract.Events.DTEND, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        return context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null)
    }

    /** Удалить событие по id. Возвращает число удалённых строк. */
    fun delete(context: Context, id: Long): Int {
        require(canWrite(context)) { "нет разрешения на запись календаря" }
        return context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
    }

    /** Разбор времени: ISO 8601 или «yyyy-MM-dd HH:mm» (в локальной зоне) → millis. */
    fun parseTime(s: String): Long? {
        val t = s.trim()
        runCatching { return java.time.Instant.parse(t).toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        runCatching { // только дата → полдень
            return java.time.LocalDate.parse(t).atTime(12, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return t.toLongOrNull() // уже millis
    }

    private fun fmt(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
