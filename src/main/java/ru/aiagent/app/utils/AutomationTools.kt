package ru.aiagent.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Инструменты автоматизации (S11+): периодические напоминания (WorkManager) и приём одного
 * входящего HTTP-запроса (raw ServerSocket).
 *
 * Стиль — как в [DeviceExtraTools] (конструктор с context: Context, хелперы str/num/ok/esc из
 * UtilTools.kt). Весь IO — в withContext(Dispatchers.IO).
 */

// ──────────────────────────── ПЕРИОДИЧЕСКОЕ НАПОМИНАНИЕ ────────────────────────────

/**
 * cron_schedule — запланировать напоминание-уведомление через WorkManager. Два режима:
 *
 *  1. ИНТЕРВАЛ (как раньше, backward-compatible): {"interval_minutes":60,...} — периодическая
 *     задача. Минимальный период WorkManager = 15 минут, меньший интервал поднимается до 15.
 *  2. КАЛЕНДАРЬ (новое): {"time":"ЧЧ:ММ","days":"mon,tue|daily|weekdays|weekends",...} —
 *     срабатывание в конкретное время суток по выбранным дням недели (например «каждый
 *     понедельник в 9:00», «каждый день в 21:30»). PeriodicWork не умеет «понедельник 9:00»,
 *     поэтому это OneTimeWork с initialDelay до СЛЕДУЮЩЕГО совпадения, а воркер при
 *     срабатывании сам планирует следующее (самоподдерживающаяся цепочка).
 *
 * Действие каждого срабатывания в обоих режимах — системное уведомление (тот же канал, что у
 * notification_send). Планирование неточное: система может сдвигать запуск для экономии батареи.
 */
class CronScheduleTool(private val context: Context) : AgentTool {
    override val id = "cron_schedule"
    override val danger = DangerLevel.SAFE
    override val schema =
        """запланировать напоминание-уведомление (WorkManager). Режимы: (1) разовое — """ +
            """{"in_minutes":30,...} (сработает один раз через N минут — «напомни после задачи»); """ +
            """(2) интервал — {"interval_minutes":60,...} (мин. период 15 минут, меньше поднимается до 15); """ +
            """(3) календарь — {"time":"ЧЧ:ММ","days":"mon,tue,wed,thu,fri,sat,sun | daily | """ +
            """weekdays | weekends",...} (например «каждый понедельник в 9:00»; days необязателен, """ +
            """по умолчанию daily). Действие = системное уведомление. Отмена — {"cancel":"id"}. """ +
            """args: {"title":"Заголовок","message":"Текст","id":"опц", + in_minutes ЛИБО interval_minutes ЛИБО time[+days]}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        // Ветка отмены: {"cancel":"id"}
        str(argsJson, "cancel")?.trim()?.takeIf { it.isNotEmpty() }?.let { cancelId ->
            return try {
                WorkManager.getInstance(context).cancelUniqueWork(workName(cancelId))
                ToolResult.Success("""{"cancelled":"${esc(cancelId)}"}""")
            } catch (t: Throwable) {
                ToolResult.Failure("cron_schedule cancel: ${t.message?.take(200)}")
            }
        }

        val title = str(argsJson, "title") ?: return ToolResult.Failure("нет args.title")
        val message = str(argsJson, "message") ?: return ToolResult.Failure("нет args.message")

        // Календарный режим включается наличием args.time. Иначе — прежний интервальный режим.
        str(argsJson, "time")?.trim()?.takeIf { it.isNotEmpty() }?.let { timeStr ->
            return scheduleCalendar(argsJson, title, message, timeStr)
        }

        // Разовое напоминание: {"in_minutes":30} — сработает один раз через N минут. OneTime без
        // KEY_TIME/KEY_NAME, поэтому воркер НЕ перепланирует (паритет с десктопным mode=once).
        numOrNull(argsJson, "in_minutes")?.toLong()?.takeIf { it > 0 }?.let { inMin ->
            return scheduleOnce(argsJson, title, message, inMin)
        }

        // Кламп до минимального периода WorkManager (15 минут).
        val requested = numOrNull(argsJson, "interval_minutes")?.toLong() ?: 60L
        val minutes = requested.coerceIn(MIN_PERIOD_MIN, MAX_PERIOD_MIN)

        // id: явный или сгенерированный. Уникальное имя задачи + стабильный notif_id из него.
        val userId = str(argsJson, "id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "cron_${System.currentTimeMillis()}"
        val name = workName(userId)
        val notifId = (name.hashCode() and 0x7fffffff).coerceAtLeast(1)

        return try {
            val req = PeriodicWorkRequestBuilder<CronReminderWorker>(minutes, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        CronReminderWorker.KEY_TITLE to title,
                        CronReminderWorker.KEY_MESSAGE to message,
                        CronReminderWorker.KEY_NOTIF_ID to notifId,
                    ),
                )
                .build()
            // UPDATE: повторный вызов с тем же id перепланирует (не плодит дубли).
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)

            // Честно предупреждаем, если на Android 13+ нет разрешения на уведомления — задача
            // запланируется, но само уведомление система не покажет.
            val warn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ""","note":"нет разрешения POST_NOTIFICATIONS — уведомление не покажется, пока не выдашь""""
            } else {
                ""
            }
            ToolResult.Success(
                """{"scheduled":"${esc(userId)}","every_minutes":$minutes$warn}""",
            )
        } catch (t: Throwable) {
            ToolResult.Failure("cron_schedule: ${t.message?.take(200)}")
        }
    }

    /**
     * Разовое напоминание: OneTime через [inMin] минут, без перепланирования (воркеру не даём
     * KEY_TIME/KEY_NAME). «Напомни после этой задачи / через полчаса» — сработает один раз.
     */
    private fun scheduleOnce(argsJson: String, title: String, message: String, inMin: Long): ToolResult {
        val minutes = inMin.coerceIn(1L, MAX_PERIOD_MIN)
        val userId = str(argsJson, "id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "cron_${System.currentTimeMillis()}"
        val name = workName(userId)
        val notifId = (name.hashCode() and 0x7fffffff).coerceAtLeast(1)
        return try {
            val req = OneTimeWorkRequestBuilder<CronReminderWorker>()
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        CronReminderWorker.KEY_TITLE to title,
                        CronReminderWorker.KEY_MESSAGE to message,
                        CronReminderWorker.KEY_NOTIF_ID to notifId,
                    ),
                )
                .build()
            // REPLACE: повторный вызов с тем же id перепланирует разовое, не плодя дубли.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
            val warn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ""","note":"нет разрешения POST_NOTIFICATIONS — уведомление не покажется, пока не выдашь""""
            } else {
                ""
            }
            ToolResult.Success("""{"scheduled":"${esc(userId)}","mode":"once","in_minutes":$minutes$warn}""")
        } catch (t: Throwable) {
            ToolResult.Failure("cron_schedule: ${t.message?.take(200)}")
        }
    }

    /**
     * Календарный режим: {"time":"ЧЧ:ММ","days":"...","title":..,"message":..,"id":опц}.
     * Считает следующее совпадение через java.time и ставит OneTimeWork с initialDelay до него;
     * воркер при срабатывании перепланирует следующее (см. [CronReminderWorker]).
     */
    private fun scheduleCalendar(
        argsJson: String,
        title: String,
        message: String,
        timeStr: String,
    ): ToolResult {
        val time = CalendarCron.parseTime(timeStr)
            ?: return ToolResult.Failure(
                "неверное args.time — ожидается \"ЧЧ:ММ\" (например \"9:00\" или \"21:30\")",
            )
        val daysRaw = str(argsJson, "days")?.trim().orEmpty()
        val days = CalendarCron.parseDays(daysRaw)
            ?: return ToolResult.Failure(
                "неверные args.days — допустимо: mon,tue,wed,thu,fri,sat,sun через запятую, " +
                    "либо daily / weekdays / weekends (пусто = daily)",
            )
        // Каноничная строка дней для хранения в inputData (воркер её же перечитает).
        val daysCanon = CalendarCron.renderDays(days)

        val userId = str(argsJson, "id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "cron_${System.currentTimeMillis()}"
        val name = workName(userId)
        val notifId = (name.hashCode() and 0x7fffffff).coerceAtLeast(1)

        return try {
            val next = CalendarCron.nextOccurrence(LocalDateTime.now(CalendarCron.zone), time, days)
            CalendarCron.enqueue(context, name, notifId, title, message, timeStr, daysCanon, next)

            val warn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ""","note":"нет разрешения POST_NOTIFICATIONS — уведомление не покажется, пока не выдашь""""
            } else {
                ""
            }
            val daysJson = CalendarCron.daysJsonArray(days)
            ToolResult.Success(
                """{"scheduled":"${esc(userId)}","mode":"calendar",""" +
                    """"time":"${esc(CalendarCron.formatTime(time))}","days":$daysJson,""" +
                    """"next_fire":"${esc(next.toString())}"$warn}""",
            )
        } catch (t: Throwable) {
            ToolResult.Failure("cron_schedule: ${t.message?.take(200)}")
        }
    }

    private fun workName(id: String): String = "cron_reminder_$id"

    private companion object {
        const val MIN_PERIOD_MIN = 15L      // жёсткий минимум WorkManager
        const val MAX_PERIOD_MIN = 30L * 24 * 60 // разумный потолок (30 дней)
    }
}

/**
 * Воркер напоминания: на каждом срабатывании показывает системное уведомление с
 * заголовком/текстом из inputData. Канал — тот же, что у notification_send (idempotent).
 */
class CronReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val message = inputData.getString(KEY_MESSAGE) ?: return Result.success()
        val notifId = inputData.getInt(KEY_NOTIF_ID, DEFAULT_NOTIF_ID)

        // Показ уведомления. На Android 13+ без разрешения notify() тихо не покажет — не падаем
        // и НЕ выходим раньше времени, чтобы календарная цепочка всё равно перепланировалась.
        val hasNotifPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (hasNotifPerm) {
            try {
                val nm = NotificationManagerCompat.from(ctx)
                val channel = NotificationChannelCompat.Builder(
                    CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT,
                ).setName("Plus AI — агент").build()
                nm.createNotificationChannel(channel)

                val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                nm.notify(notifId, notification)
            } catch (t: Throwable) {
                // напоминание не должно уходить в бесконечные ретраи
            }
        }

        // Календарный режим: перепланировать следующее срабатывание (самоподдерживающаяся цепочка).
        // Признак режима — наличие KEY_TIME + KEY_NAME. Интервальный режим сюда не попадает.
        val timeStr = inputData.getString(KEY_TIME)
        val name = inputData.getString(KEY_NAME)
        if (timeStr != null && name != null) {
            try {
                val time = CalendarCron.parseTime(timeStr)
                val days = CalendarCron.parseDays(inputData.getString(KEY_DAYS) ?: "")
                if (time != null && days != null) {
                    // +1 минута к точке отсчёта, чтобы джиттер WorkManager не переставил на «сейчас».
                    val next = CalendarCron.nextOccurrence(
                        LocalDateTime.now(CalendarCron.zone).plusMinutes(1), time, days,
                    )
                    CalendarCron.enqueue(
                        ctx, name, notifId, title, message,
                        timeStr, CalendarCron.renderDays(days), next,
                    )
                }
            } catch (t: Throwable) {
                // не роняем цепочку из-за одной осечки перепланирования
            }
        }

        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_MESSAGE = "message"
        const val KEY_NOTIF_ID = "notif_id"
        // Календарный режим (иначе отсутствуют):
        const val KEY_TIME = "cron_time"
        const val KEY_DAYS = "cron_days"
        const val KEY_NAME = "cron_name"
        private const val DEFAULT_NOTIF_ID = 2000
        // Тот же канал, что у notification_send в DeviceExtraTools.kt.
        private const val CHANNEL_ID = "plusai_agent"
    }
}

// ──────────────────────────── КАЛЕНДАРНОЕ РАСПИСАНИЕ ────────────────────────────

/**
 * Чистая логика календарного планирования (парсинг времени/дней, расчёт следующего совпадения)
 * плюс постановка OneTimeWork. Используется и инструментом (первичная постановка), и воркером
 * (перепланирование). Всё на java.time (доступно через core library desugaring).
 */
internal object CalendarCron {

    val zone: ZoneId get() = ZoneId.systemDefault()

    /** Разбирает "ЧЧ:ММ" (допускает "9:00" без ведущего нуля, "9" = 9:00). null — если некорректно. */
    fun parseTime(raw: String): LocalTime? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val parts = s.split(":")
        if (parts.size > 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = if (parts.size == 2) (parts[1].trim().toIntOrNull() ?: return null) else 0
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    /**
     * Разбирает набор дней недели. Возвращает null при нераспознанных значениях (чёткая ошибка).
     * Пусто/"daily" → все 7 дней; "weekdays" → пн–пт; "weekends" → сб,вс; иначе список через запятую.
     */
    fun parseDays(raw: String): Set<DayOfWeek>? {
        val s = raw.trim().lowercase()
        val all = DayOfWeek.entries.toSet()
        if (s.isEmpty() || s in DAILY_WORDS) return all
        if (s in WEEKDAY_WORDS) return setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        if (s in WEEKEND_WORDS) return setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        val out = LinkedHashSet<DayOfWeek>()
        for (tokenRaw in s.split(",")) {
            val token = tokenRaw.trim()
            if (token.isEmpty()) continue
            val d = DAY_ALIASES[token] ?: return null
            out.add(d)
        }
        return if (out.isEmpty()) null else out
    }

    /** Первое LocalDateTime строго позже [reference], попадающее в [days] в момент [time]. */
    fun nextOccurrence(reference: LocalDateTime, time: LocalTime, days: Set<DayOfWeek>): LocalDateTime {
        for (offset in 0..7L) {
            val date = reference.toLocalDate().plusDays(offset)
            if (date.dayOfWeek in days) {
                val dt = date.atTime(time)
                if (dt.isAfter(reference)) return dt
            }
        }
        // Теоретически недостижимо (days непусто) — безопасный запас.
        return reference.toLocalDate().plusDays(1).atTime(time)
    }

    /** Ставит OneTimeWork с задержкой до [next] под уникальным именем [name] (REPLACE — без дублей). */
    fun enqueue(
        context: Context,
        name: String,
        notifId: Int,
        title: String,
        message: String,
        timeStr: String,
        daysCanon: String,
        next: LocalDateTime,
    ) {
        // Реальное прошедшее время до next (через Instant) — не номинальная разница LocalDateTime, которая
        // на переходах DST ошибается на час (WorkManager.initialDelay ждёт реальные миллисекунды).
        val delayMs = Duration.between(
            java.time.ZonedDateTime.now(zone).toInstant(),
            next.atZone(zone).toInstant(),
        ).toMillis().coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<CronReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    CronReminderWorker.KEY_TITLE to title,
                    CronReminderWorker.KEY_MESSAGE to message,
                    CronReminderWorker.KEY_NOTIF_ID to notifId,
                    CronReminderWorker.KEY_TIME to timeStr,
                    CronReminderWorker.KEY_DAYS to daysCanon,
                    CronReminderWorker.KEY_NAME to name,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    /** Каноничная строка дней ("mon,tue,...") в порядке недели. */
    fun renderDays(days: Set<DayOfWeek>): String =
        DayOfWeek.entries.filter { it in days }.joinToString(",") { SHORT_EN.getValue(it) }

    /** JSON-массив дней ["mon","tue"] в порядке недели. */
    fun daysJsonArray(days: Set<DayOfWeek>): String =
        DayOfWeek.entries.filter { it in days }.joinToString(",", "[", "]") { "\"${SHORT_EN.getValue(it)}\"" }

    fun formatTime(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    private val SHORT_EN: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to "mon", DayOfWeek.TUESDAY to "tue", DayOfWeek.WEDNESDAY to "wed",
        DayOfWeek.THURSDAY to "thu", DayOfWeek.FRIDAY to "fri", DayOfWeek.SATURDAY to "sat",
        DayOfWeek.SUNDAY to "sun",
    )

    private val DAILY_WORDS = setOf("daily", "everyday", "every", "all", "каждый", "ежедневно")
    private val WEEKDAY_WORDS = setOf("weekdays", "будни", "рабочие")
    private val WEEKEND_WORDS = setOf("weekends", "выходные")

    /** Алиасы дней: англ. (полн./сокр.) и рус. */
    private val DAY_ALIASES: Map<String, DayOfWeek> = buildMap {
        fun add(d: DayOfWeek, vararg keys: String) = keys.forEach { put(it, d) }
        add(DayOfWeek.MONDAY, "mon", "monday", "пн", "понедельник")
        add(DayOfWeek.TUESDAY, "tue", "tues", "tuesday", "вт", "вторник")
        add(DayOfWeek.WEDNESDAY, "wed", "weds", "wednesday", "ср", "среда")
        add(DayOfWeek.THURSDAY, "thu", "thur", "thurs", "thursday", "чт", "четверг")
        add(DayOfWeek.FRIDAY, "fri", "friday", "пт", "пятница")
        add(DayOfWeek.SATURDAY, "sat", "saturday", "сб", "суббота")
        add(DayOfWeek.SUNDAY, "sun", "sunday", "вс", "воскресенье")
    }
}

// ──────────────────────────── ВХОДЯЩИЙ HTTP-ЗАПРОС ────────────────────────────

/**
 * webhook_server — принять ОДИН входящий HTTP-запрос за ограниченное окно и вернуть его как JSON.
 *
 * Поднимает raw [ServerSocket] (только java.net, без внешних зависимостей), ждёт первое
 * соединение до seconds секунд, читает строку запроса, заголовки и тело, отвечает клиенту
 * коротким 200 OK и закрывается. Для постоянного приёма — вызывать инструмент повторно.
 * IMPORTANT: открывает сетевой порт на устройстве.
 */
class WebhookServerTool : AgentTool {
    override val id = "webhook_server"
    override val danger = DangerLevel.IMPORTANT
    override val schema =
        """принять ОДИН входящий HTTP-запрос за окно времени и вернуть его (метод/путь/заголовки/тело) """ +
            """как JSON; для постоянного сервера — вызывать повторно; args: {"port":8080,"seconds":30}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val port = (numOrNull(argsJson, "port")?.toInt() ?: 8080).coerceIn(1024, 65535)
        val seconds = (numOrNull(argsJson, "seconds")?.toInt() ?: 30).coerceIn(1, 120)
        val localIp = localIpv4() ?: "неизвестен"

        var server: ServerSocket? = null
        try {
            server = ServerSocket(port)
            server.soTimeout = seconds * 1000
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                return@withContext ToolResult.Failure(
                    "за $seconds сек. на порт $port никто не подключился (local_ip=$localIp)",
                )
            }
            socket.use { sock ->
                sock.soTimeout = READ_TIMEOUT_MS
                val remote = sock.inetAddress?.hostAddress ?: "?"
                val input = sock.getInputStream()

                val headerBytes = readHeaderBlock(input, MAX_HEADER)
                val headerText = String(headerBytes, Charsets.ISO_8859_1)
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val rlParts = requestLine.split(" ")
                val method = rlParts.getOrElse(0) { "" }
                val path = rlParts.getOrElse(1) { "" }
                val version = rlParts.getOrElse(2) { "" }

                // Заголовки (до первой пустой строки).
                val headers = LinkedHashMap<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }

                // Тело по Content-Length (с потолком).
                val contentLength = headers.entries
                    .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                    ?.value?.trim()?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    readN(input, contentLength.coerceAtMost(MAX_BODY))
                } else {
                    ByteArray(0)
                }
                val bodyText = String(body, Charsets.UTF_8)

                // Короткий ответ клиенту, чтобы соединение не висело.
                runCatching {
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        flush()
                    }
                }

                val headersJson = headers.entries.joinToString(",") {
                    """"${esc(it.key)}":"${esc(it.value)}""""
                }
                ToolResult.Success(
                    """{"method":"${esc(method)}","path":"${esc(path)}","http_version":"${esc(version)}",""" +
                        """"headers":{$headersJson},"body":"${esc(bodyText)}",""" +
                        """"remote_addr":"${esc(remote)}","local_ip":"${esc(localIp)}","port":$port}""",
                )
            }
        } catch (t: Throwable) {
            ToolResult.Failure("webhook_server: ${t.message?.take(200)}")
        } finally {
            runCatching { server?.close() }
        }
    }

    /** Читает блок заголовков до \r\n\r\n (или до предела/конца потока). */
    private fun readHeaderBlock(input: InputStream, max: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        var state = 0 // \r=1, \r\n=2, \r\n\r=3, \r\n\r\n=4
        while (buf.size() < max) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> 4
                else -> 0
            }
            if (state == 4) break
        }
        return buf.toByteArray()
    }

    /** Читает ровно n байт (или сколько успеет до конца потока/таймаута). */
    private fun readN(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = try {
                input.read(out, read, n - read)
            } catch (e: SocketTimeoutException) {
                break
            }
            if (r < 0) break
            read += r
        }
        return if (read == n) out else out.copyOf(read)
    }

    /** Первый не-loopback IPv4-адрес устройства (для удобства — куда слать запрос). */
    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_HEADER = 64 * 1024
        const val MAX_BODY = 1 * 1024 * 1024 // 1 МБ
    }
}

// ──────────────────────────── ФАБРИКА ────────────────────────────

/** Набор инструментов автоматизации. */
fun automationTools(context: Context): List<AgentTool> = listOf(
    CronScheduleTool(context),
    WebhookServerTool(),
)
