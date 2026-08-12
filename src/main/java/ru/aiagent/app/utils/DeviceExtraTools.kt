package ru.aiagent.app.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.view.Display
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.aiagent.app.phone.PhoneControlService
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Дополнительные инструменты устройства (S11+): контакты, системные уведомления, снимок экрана.
 * Стиль — как остальные device-тулзы (конструктор с context: Context, хелперы str/num/ok/esc).
 *
 * §7: контакты — приватные данные. Помечены privateData=true → облачным путям не выдаются
 * (тело контактов не уходит в облако).
 */

// ──────────────────────────── КОНТАКТЫ ────────────────────────────

/** contacts_list — список контактов (имя + телефоны). §7: приватные данные. */
class ContactsListTool(private val context: Context) : AgentTool {
    override val id = "contacts_list"
    override val danger = DangerLevel.IMPORTANT // §7: чтение всей адресной книги — с подтверждением, как email/calendar
    override val alwaysConfirm = true // симметрия с §7-источниками: подтверждение всегда, кроме bypass (как search_email)
    override val privateData = true // §7: контакты не отдаём облаку
    override val schema =
        """список контактов (имя и телефон); args: {"limit":50}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!hasContactsPermission(context)) {
            return ToolResult.Failure("нет разрешения на контакты — выдай в настройках (READ_CONTACTS)")
        }
        val limit = numOrNull(argsJson, "limit")?.toInt()?.coerceIn(1, 500) ?: 50
        return try {
            val list = queryContacts(context, filter = null, limit = limit)
            ToolResult.Success(contactsJson(list))
        } catch (t: Throwable) {
            ToolResult.Failure("contacts: ${t.message?.take(200)}")
        }
    }
}

/** contacts_search — поиск контакта по имени или номеру. §7: приватные данные. */
class ContactsSearchTool(private val context: Context) : AgentTool {
    override val id = "contacts_search"
    override val danger = DangerLevel.IMPORTANT // §7: чтение адресной книги — с подтверждением, как email/calendar
    override val alwaysConfirm = true // симметрия с §7-источниками: подтверждение всегда, кроме bypass (как search_email)
    override val privateData = true // §7: контакты не отдаём облаку
    override val schema =
        """поиск контакта по имени или номеру; args: {"query":"Иван"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!hasContactsPermission(context)) {
            return ToolResult.Failure("нет разрешения на контакты — выдай в настройках (READ_CONTACTS)")
        }
        val query = str(argsJson, "query")?.trim()
            ?: return ToolResult.Failure("нет args.query")
        if (query.isEmpty()) return ToolResult.Failure("пустой args.query")
        return try {
            val list = queryContacts(context, filter = query, limit = 100)
            ToolResult.Success(contactsJson(list))
        } catch (t: Throwable) {
            ToolResult.Failure("contacts: ${t.message?.take(200)}")
        }
    }
}

private data class Contact(val name: String, val phone: String)

private fun hasContactsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Читает телефоны из ContactsContract. filter != null → поиск по имени/номеру через
 * CONTENT_FILTER_URI (система сама матчит и по DISPLAY_NAME, и по NUMBER).
 */
private fun queryContacts(context: Context, filter: String?, limit: Int): List<Contact> {
    val uri = if (filter == null) {
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    } else {
        ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI.buildUpon()
            .appendPath(filter).build()
    }
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    val sort = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
    val out = ArrayList<Contact>(limit.coerceAtMost(64))
    val seen = HashSet<String>()
    context.contentResolver.query(uri, projection, null, null, sort)?.use { c ->
        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (c.moveToNext() && out.size < limit) {
            val name = (if (nameIdx >= 0) c.getString(nameIdx) else null)?.trim().orEmpty()
            val phone = (if (numIdx >= 0) c.getString(numIdx) else null)?.trim().orEmpty()
            if (name.isEmpty() && phone.isEmpty()) continue
            // Дедуп: у контакта может быть несколько одинаковых записей номера.
            if (!seen.add("$name|$phone")) continue
            out.add(Contact(name.ifEmpty { "(без имени)" }, phone.ifEmpty { "(нет номера)" }))
        }
    }
    return out
}

private fun contactsJson(list: List<Contact>): String {
    val items = list.joinToString(",") { """{"name":"${esc(it.name)}","phone":"${esc(it.phone)}"}""" }
    return """{"count":${list.size},"contacts":[$items]}"""
}

// ──────────────────────────── УВЕДОМЛЕНИЕ ────────────────────────────

/** notification_send — системное уведомление через NotificationManagerCompat. */
class NotificationSendTool(private val context: Context) : AgentTool {
    override val id = "notification_send"
    override val danger = DangerLevel.SAFE
    override val schema =
        """показать системное уведомление; args: {"title":"Заголовок","text":"Текст"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        // Android 13+ требует POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure("нет разрешения на уведомления — выдай в настройках (POST_NOTIFICATIONS)")
        }
        val title = str(argsJson, "title") ?: return ToolResult.Failure("нет args.title")
        val text = str(argsJson, "text") ?: return ToolResult.Failure("нет args.text")
        return try {
            val nm = NotificationManagerCompat.from(context)
            val channel = NotificationChannelCompat.Builder(
                CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT,
            ).setName("Plus AI — агент").build()
            nm.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            val notifId = NOTIF_ID.incrementAndGet()
            nm.notify(notifId, notification)
            ok("status" to "уведомление показано", "id" to notifId.toString())
        } catch (t: Throwable) {
            ToolResult.Failure("notification: ${t.message?.take(200)}")
        }
    }

    private companion object {
        const val CHANNEL_ID = "plusai_agent"
        val NOTIF_ID = AtomicInteger(1000)
    }
}

// ──────────────────────────── СКРИНШОТ ────────────────────────────

/**
 * take_screenshot — снимок экрана в файл песочницы. ВАЖНО (IMPORTANT).
 *
 * Реализация переиспользует уже существующую службу [PhoneControlService] (AccessibilityService):
 * её унаследованный public-метод takeScreenshot() (API 30+) отдаёт настоящий кадр. Отдельного
 * MediaProjection в проекте нет; фейковый скриншот не создаётся.
 *
 * Реально работает, когда: (1) служба управления телефоном включена пользователем и
 * (2) Android 11+ (API 30). Иначе — понятный Failure.
 */
class TakeScreenshotTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "take_screenshot"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true // пишет PNG в песочницу
    override val schema = """снимок экрана в файл; args: {"out":"screen.png"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val out = str(argsJson, "out") ?: "screen.png"
        val file = resolve(out) ?: return ToolResult.Failure("путь вне разрешённой папки: $out")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ToolResult.Failure("снимок экрана доступен только на Android 11+ (API 30)")
        }
        val svc = PhoneControlService.instance
            ?: return ToolResult.Failure(
                "захват экрана требует включённой службы управления телефоном " +
                    "(Настройки → Спец. возможности → Plus AI). Без неё снимок недоступен.",
            )

        return try {
            val bitmap = captureScreen(svc)
                ?: return ToolResult.Failure("не удалось получить кадр экрана")
            file.parentFile?.mkdirs()
            file.outputStream().use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) }
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            ok("status" to "снимок сохранён", "path" to out, "size" to "${w}x$h")
        } catch (t: Throwable) {
            ToolResult.Failure("screenshot: ${t.message?.take(200)}")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreen(svc: PhoneControlService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(context),
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        val bmp = try {
                            val hw = screenshot.hardwareBuffer
                            try {
                                // wrapHardwareBuffer даёт hardware-bitmap; копируем в software, чтобы сжать в PNG.
                                val hwBmp = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                val soft = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBmp?.recycle()
                                soft
                            } finally {
                                // Нативный буфер закрываем ВСЕГДА, даже при исключении в wrap/copy (иначе утечка).
                                hw.close()
                            }
                        } catch (t: Throwable) {
                            null
                        }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
}

// ──────────────────────────── ВИБРАЦИЯ ────────────────────────────

/** vibrate — короткая вибрация. Требует разрешения VIBRATE (обычное, выдаётся автоматически). */
class VibrateTool(private val context: Context) : AgentTool {
    override val id = "vibrate"
    override val danger = DangerLevel.SAFE
    override val schema = """вибрация устройства; args: {"ms":300}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val ms = numOrNull(argsJson, "ms")?.toLong()?.coerceIn(1, 10_000) ?: 300L
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            }
            if (!vibrator.hasVibrator()) return ToolResult.Failure("на устройстве нет вибромотора")
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            ok("status" to "вибрация", "ms" to ms.toString())
        } catch (t: Throwable) {
            ToolResult.Failure("vibrate: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── ФОНАРИК ────────────────────────────

/**
 * torch — включить/выключить фонарик (вспышку). Разрешения не требует:
 * CameraManager.setTorchMode работает без CAMERA-разрешения.
 */
class TorchTool(private val context: Context) : AgentTool {
    override val id = "torch"
    override val danger = DangerLevel.SAFE
    override val schema = """фонарик вкл/выкл; args: {"on":true}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val on = boolOrNull(argsJson, "on") ?: true
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.Failure("на устройстве нет вспышки")
            cm.setTorchMode(camId, on)
            ok("status" to if (on) "фонарик включён" else "фонарик выключен")
        } catch (t: Throwable) {
            ToolResult.Failure("torch: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── СЕТЬ ────────────────────────────

/**
 * wifi_info — сведения о текущем соединении: IP, тип (wifi/cellular), по возможности SSID.
 * SSID на Android 10+ требует location-разрешения (оно есть в манифесте); если недоступно —
 * возвращаем без SSID, не падаем.
 */
class WifiInfoTool(private val context: Context) : AgentTool {
    override val id = "wifi_info"
    override val danger = DangerLevel.SAFE
    override val schema = """сведения о сети (IP, тип, SSID); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
                ?: return ToolResult.Success("""{"connected":false}""")
            val caps = cm.getNetworkCapabilities(network)
            val type = when {
                caps == null -> "unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            // IP: первый не-loopback адрес из LinkProperties активной сети.
            val ip = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress
                ?: "неизвестен"

            val fields = StringBuilder("""{"connected":true,"type":"${esc(type)}","ip":"${esc(ip)}"""")
            // SSID — только для wifi и при наличии location-разрешения; иначе тихо опускаем.
            if (type == "wifi") {
                ssidOrNull()?.let { fields.append(""","ssid":"${esc(it)}"""") }
            }
            fields.append("}")
            ToolResult.Success(fields.toString())
        } catch (t: Throwable) {
            ToolResult.Failure("wifi_info: ${t.message?.take(200)}")
        }
    }

    /** Пытается получить SSID; при отсутствии разрешения/данных возвращает null (не падает). */
    private fun ssidOrNull(): String? = try {
        val hasLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            null
        } else {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wm?.connectionInfo?.ssid?.trim('"')
            if (raw.isNullOrBlank() || raw == "<unknown ssid>" || raw == "0x") null else raw
        }
    } catch (t: Throwable) {
        null
    }
}

/** Парсинг булева аргумента: принимает и {"on":true}, и {"on":"true"}. */
private fun boolOrNull(json: String, field: String): Boolean? =
    Regex("\"$field\"\\s*:\\s*\"?(true|false)\"?", RegexOption.IGNORE_CASE)
        .find(json)?.groupValues?.get(1)?.equals("true", ignoreCase = true)

// ──────────────────────────── ДАТЧИКИ ────────────────────────────

/**
 * sensor_read — снять показания датчика за короткое окно.
 * Датчики событийные: регистрируем listener на окно [ms] (кламп 100..5000), возвращаем
 * последние полученные значения (массив float) и единицы измерения.
 */
class SensorReadTool(private val context: Context) : AgentTool {
    override val id = "sensor_read"
    override val danger = DangerLevel.SAFE
    override val schema =
        """снять показания датчика; args: {"sensor":"accelerometer|gyroscope|light|proximity|magnetometer","ms":1000}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val name = str(argsJson, "sensor")?.trim()?.lowercase()
            ?: return ToolResult.Failure("нет args.sensor")
        val (type, units) = when (name) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER to "m/s^2"
            "gyroscope" -> Sensor.TYPE_GYROSCOPE to "rad/s"
            "light" -> Sensor.TYPE_LIGHT to "lx"
            "proximity" -> Sensor.TYPE_PROXIMITY to "cm"
            "magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD to "uT"
            else -> return ToolResult.Failure(
                "неизвестный датчик: $name (accelerometer|gyroscope|light|proximity|magnetometer)",
            )
        }
        val ms = numOrNull(argsJson, "ms")?.toLong()?.coerceIn(100, 5000) ?: 1000L
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(type)
                ?: return ToolResult.Failure("на устройстве нет датчика: $name")
            val values = readSensor(sm, sensor, ms)
                ?: return ToolResult.Failure("датчик $name не прислал данных за ${ms}мс")
            val arr = values.joinToString(",") { "%.4f".format(it) }
            ToolResult.Success(
                """{"sensor":"${esc(name)}","values":[$arr],"units":"${esc(units)}","ms":$ms}""",
            )
        } catch (t: Throwable) {
            ToolResult.Failure("sensor_read: ${t.message?.take(200)}")
        }
    }

    /**
     * Регистрирует listener на [ms], копит последнее событие, по таймауту снимает listener и
     * отдаёт последние значения (или null, если событий не было). suspendCancellableCoroutine +
     * отложенный Runnable как таймаут; безопасно при отмене корутины.
     */
    private suspend fun readSensor(sm: SensorManager, sensor: Sensor, ms: Long): FloatArray? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val last = AtomicReference<FloatArray?>(null)
            val done = AtomicBoolean(false)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    last.set(event.values.copyOf())
                }
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            val finish = Runnable {
                if (done.compareAndSet(false, true)) {
                    sm.unregisterListener(listener)
                    if (cont.isActive) cont.resume(last.get())
                }
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            handler.postDelayed(finish, ms)
            cont.invokeOnCancellation {
                handler.removeCallbacks(finish)
                if (done.compareAndSet(false, true)) sm.unregisterListener(listener)
            }
        }
}

// ──────────────────────────── УСТАНОВЛЕННЫЕ ПРИЛОЖЕНИЯ ────────────────────────────

/**
 * installed_apps — список установленных приложений (packageName + label + versionName).
 * По умолчанию системные (FLAG_SYSTEM) отфильтрованы. Требует QUERY_ALL_PACKAGES.
 */
class InstalledAppsTool(private val context: Context) : AgentTool {
    override val id = "installed_apps"
    override val danger = DangerLevel.SAFE
    override val schema =
        """список установленных приложений; args: {"system":false}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val includeSystem = boolOrNull(argsJson, "system") ?: false
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val items = StringBuilder()
            var count = 0
            for (info in apps) {
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                val label = pm.getApplicationLabel(info).toString()
                val version = try {
                    pm.getPackageInfo(info.packageName, 0).versionName
                } catch (t: Throwable) {
                    null
                }
                if (count > 0) items.append(",")
                items.append(
                    """{"package":"${esc(info.packageName)}","label":"${esc(label)}",""" +
                        """"version":"${esc(version ?: "?")}"}""",
                )
                count++
            }
            ToolResult.Success("""{"count":$count,"system":$includeSystem,"apps":[$items]}""")
        } catch (t: Throwable) {
            ToolResult.Failure("installed_apps: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── ЭКРАН НЕ ГАСНЕТ ────────────────────────────

/**
 * keep_screen_on — удержать устройство активным на [minutes] (кламп 1..120), on:false — отпустить.
 *
 * ЧЕСТНО: фоновый инструмент без окна Activity не может гарантированно держать ЭКРАН включённым —
 * это делает только флаг окна FLAG_KEEP_SCREEN_ON у видимой Activity. Здесь берётся
 * PowerManager.PARTIAL_WAKE_LOCK: удерживает активным CPU (задача не засыпает), но экран может
 * погаснуть. Ссылка на wakelock хранится в companion, повторный вызов заменяет предыдущий.
 * Требует разрешения WAKE_LOCK.
 */
class KeepScreenOnTool(private val context: Context) : AgentTool {
    override val id = "keep_screen_on"
    override val danger = DangerLevel.SAFE
    override val schema =
        """не давать устройству засыпать (PARTIAL wakelock — держит CPU, но НЕ гарантирует включённый экран); args: {"on":true,"minutes":10}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val on = boolOrNull(argsJson, "on") ?: true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            synchronized(lock) {
                // Всегда отпускаем предыдущий, чтобы не копить wakelock'и.
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                if (!on) {
                    return@synchronized ok("status" to "wakelock отпущен, устройство может засыпать")
                }
                val minutes = numOrNull(argsJson, "minutes")?.toLong()?.coerceIn(1, 120) ?: 10L
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlusAI:keep_screen_on")
                wl.setReferenceCounted(false)
                wl.acquire(minutes * 60_000L)
                wakeLock = wl
                ok(
                    "status" to "устройство удержано активным на $minutes мин (PARTIAL wakelock)",
                    "note" to "держит CPU; включённость экрана этим способом не гарантируется",
                    "minutes" to minutes.toString(),
                )
            }
        } catch (t: Throwable) {
            ToolResult.Failure("keep_screen_on: ${t.message?.take(200)}")
        }
    }

    private companion object {
        val lock = Any()
        @Volatile var wakeLock: PowerManager.WakeLock? = null
    }
}

// ──────────────────────────── БАТАРЕЯ ────────────────────────────

/**
 * battery_history — состояние батареи.
 * ЧЕСТНО: публичного API истории разряда в Android НЕТ. Возвращается подробное ТЕКУЩЕЕ состояние
 * (уровень, зарядка, источник, температура, напряжение, здоровье, технология) из BatteryManager и
 * sticky-intent ACTION_BATTERY_CHANGED.
 */
class BatteryHistoryTool(private val context: Context) : AgentTool {
    override val id = "battery_history"
    override val danger = DangerLevel.SAFE
    override val schema =
        """текущее состояние батареи (история разряда системой НЕ предоставляется); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return ToolResult.Failure("не удалось прочитать состояние батареи")
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "none"
                else -> "unknown"
            }
            val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                .let { if (it >= 0) it / 10.0 else null }
            val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                .let { if (it >= 0) it else null }
            val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            }
            val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

            val sb = StringBuilder("""{"note":"текущее состояние; история разряда системой не предоставляется"""")
            sb.append(""","level_pct":$pct""")
            sb.append(""","charging":$charging""")
            sb.append(""","power_source":"${esc(plugged)}"""")
            sb.append(""","health":"${esc(health)}"""")
            tempC?.let { sb.append(""","temperature_c":${"%.1f".format(it)}""") }
            voltageMv?.let { sb.append(""","voltage_mv":$it""") }
            technology?.let { sb.append(""","technology":"${esc(it)}"""") }
            sb.append("}")
            ToolResult.Success(sb.toString())
        } catch (t: Throwable) {
            ToolResult.Failure("battery_history: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── ФАБРИКА ────────────────────────────

/** Набор дополнительных инструментов устройства. */
fun deviceExtraTools(context: Context, resolve: (String) -> File?): List<AgentTool> = listOf(
    ContactsListTool(context),
    ContactsSearchTool(context),
    NotificationSendTool(context),
    TakeScreenshotTool(context, resolve),
    VibrateTool(context),
    TorchTool(context),
    WifiInfoTool(context),
    SensorReadTool(context),
    InstalledAppsTool(context),
    KeepScreenOnTool(context),
    BatteryHistoryTool(context),
)
