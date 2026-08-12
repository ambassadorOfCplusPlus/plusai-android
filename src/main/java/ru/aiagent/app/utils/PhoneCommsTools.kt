package ru.aiagent.app.utils

import android.Manifest
import android.app.NotificationManager
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * Инструменты связи и системных настроек телефона (S16+): SMS, контакты, громкость,
 * яркость, режим «не беспокоить».
 * Стиль — как [deviceExtraTools] (конструктор с context: Context, хелперы str/num/ok/esc).
 *
 * §7: чтение SMS — приватные данные (privateData=true) → облачным путям не выдаётся.
 * L8: отправка SMS тратит деньги/шлёт от лица пользователя → DANGEROUS + alwaysConfirm;
 * создание контакта и чтение SMS — IMPORTANT + alwaysConfirm (пишем/читаем личное).
 */

// ──────────────────────────── SMS: ОТПРАВКА ────────────────────────────

/**
 * sms_send — отправить SMS. L8: тратит деньги и отправляется от лица пользователя,
 * поэтому DANGEROUS + alwaysConfirm (подтверждение всегда, кроме bypass).
 * Длинные сообщения режутся divideMessage → sendMultipartTextMessage.
 * Разрешение SEND_SMS.
 */
class SmsSendTool(private val context: Context) : AgentTool {
    override val id = "sms_send"
    override val danger = DangerLevel.DANGEROUS // тратит деньги и шлёт от лица пользователя
    override val alwaysConfirm = true // никогда молча, кроме bypass
    override val schema =
        """отправить SMS на номер; args: {"number":"+79001234567","text":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure("нет разрешения на отправку SMS — выдай в настройках (SEND_SMS)")
        }
        val number = str(argsJson, "number")?.trim()
            ?: return ToolResult.Failure("нет args.number")
        if (number.isEmpty()) return ToolResult.Failure("пустой args.number")
        val text = str(argsJson, "text")
            ?: return ToolResult.Failure("нет args.text")
        if (text.isEmpty()) return ToolResult.Failure("пустой args.text")
        return try {
            val sms = smsManager(context)
                ?: return ToolResult.Failure("SmsManager недоступен на этом устройстве")
            val parts = sms.divideMessage(text)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, text, null, null)
            }
            ok(
                "status" to "SMS отправлено",
                "number" to number,
                "parts" to parts.size.toString(),
            )
        } catch (t: Throwable) {
            ToolResult.Failure("sms_send: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── SMS: ЧТЕНИЕ ────────────────────────────

/**
 * sms_read — прочитать последние входящие SMS. §7: приватные данные (privateData=true) —
 * облачным путям не выдаётся. IMPORTANT + alwaysConfirm (чтение личной переписки).
 * Разрешение READ_SMS.
 */
class SmsReadTool(private val context: Context) : AgentTool {
    override val id = "sms_read"
    override val danger = DangerLevel.IMPORTANT // чтение личной переписки
    override val alwaysConfirm = true // подтверждение всегда, кроме bypass
    override val privateData = true // §7: тело SMS не уходит в облако
    override val schema =
        """прочитать последние входящие SMS; args: {"limit":20,"from":"+79001234567"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure("нет разрешения на чтение SMS — выдай в настройках (READ_SMS)")
        }
        val limit = numOrNull(argsJson, "limit")?.toInt()?.coerceIn(1, 200) ?: 20
        val from = str(argsJson, "from")?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
            )
            val selection = if (from != null) "${Telephony.Sms.ADDRESS} LIKE ?" else null
            val selectionArgs = if (from != null) arrayOf("%$from%") else null
            val sort = "${Telephony.Sms.DATE} DESC"
            val items = StringBuilder()
            var count = 0
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, selection, selectionArgs, sort,
            )?.use { c ->
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext() && count < limit) {
                    val address = (if (addrIdx >= 0) c.getString(addrIdx) else null).orEmpty()
                    val body = (if (bodyIdx >= 0) c.getString(bodyIdx) else null).orEmpty()
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    if (count > 0) items.append(",")
                    items.append(
                        """{"from":"${esc(address)}","text":"${esc(body)}","date":$date}""",
                    )
                    count++
                }
            }
            ToolResult.Success("""{"count":$count,"messages":[$items]}""")
        } catch (t: Throwable) {
            ToolResult.Failure("sms_read: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── КОНТАКТ: СОЗДАНИЕ ────────────────────────────

/**
 * contacts_create — создать контакт (batch insert в ContactsContract: RawContact + Data-строки
 * имени, телефона и, опционально, email). IMPORTANT + alwaysConfirm (пишем в адресную книгу).
 * Разрешение WRITE_CONTACTS.
 */
class ContactsCreateTool(private val context: Context) : AgentTool {
    override val id = "contacts_create"
    override val danger = DangerLevel.IMPORTANT // запись в адресную книгу
    override val alwaysConfirm = true // подтверждение всегда, кроме bypass
    override val schema =
        """создать контакт; args: {"name":"Иван","phone":"+79001234567","email":"ivan@mail.ru"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure("нет разрешения на запись контактов — выдай в настройках (WRITE_CONTACTS)")
        }
        val name = str(argsJson, "name")?.trim()
            ?: return ToolResult.Failure("нет args.name")
        if (name.isEmpty()) return ToolResult.Failure("пустой args.name")
        val phone = str(argsJson, "phone")?.trim()?.takeIf { it.isNotEmpty() }
        val email = str(argsJson, "email")?.trim()?.takeIf { it.isNotEmpty() }
        if (phone == null && email == null) {
            return ToolResult.Failure("нужен хотя бы один из args.phone / args.email")
        }
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            // RawContact без привязки к аккаунту (локальный контакт) — индекс 0 в back-reference.
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build(),
            )
            // Имя.
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build(),
            )
            // Телефон.
            if (phone != null) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                        )
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                        )
                        .build(),
                )
            }
            // Email (опционально).
            if (email != null) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                        )
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(
                            ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                        )
                        .build(),
                )
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            ok(
                "status" to "контакт создан",
                "name" to name,
                "phone" to (phone ?: ""),
                "email" to (email ?: ""),
            )
        } catch (t: Throwable) {
            ToolResult.Failure("contacts_create: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── ГРОМКОСТЬ ────────────────────────────

/** set_volume — задать громкость выбранного потока (media/ring/alarm) в процентах. SAFE. */
class SetVolumeTool(private val context: Context) : AgentTool {
    override val id = "set_volume"
    override val danger = DangerLevel.SAFE
    override val schema =
        """задать громкость; args: {"stream":"media|ring|alarm","percent":50}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val streamName = str(argsJson, "stream")?.trim()?.lowercase() ?: "media"
        val stream = when (streamName) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> return ToolResult.Failure("неизвестный поток: $streamName (media|ring|alarm)")
        }
        val percent = numOrNull(argsJson, "percent")?.toInt()?.coerceIn(0, 100)
            ?: return ToolResult.Failure("нет args.percent (0..100)")
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(stream)
            val value = (percent * max + 50) / 100 // округление к ближайшему делению
            am.setStreamVolume(stream, value, 0)
            ok(
                "status" to "громкость задана",
                "stream" to streamName,
                "percent" to percent.toString(),
                "value" to "$value/$max",
            )
        } catch (t: Throwable) {
            ToolResult.Failure("set_volume: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── ЯРКОСТЬ ────────────────────────────

/**
 * set_brightness — задать яркость экрана в процентах через Settings.System.SCREEN_BRIGHTNESS.
 * Требует особого разрешения WRITE_SETTINGS: если не выдано (Settings.System.canWrite==false) —
 * Failure с подсказкой выдать его в настройках. IMPORTANT.
 */
class SetBrightnessTool(private val context: Context) : AgentTool {
    override val id = "set_brightness"
    override val danger = DangerLevel.IMPORTANT
    override val schema = """задать яркость экрана; args: {"percent":50}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (!Settings.System.canWrite(context)) {
            return ToolResult.Failure(
                "нет разрешения на изменение системных настроек — выдай его вручную: " +
                    "Настройки → Приложения → Plus AI → Изменение системных настроек (WRITE_SETTINGS)",
            )
        }
        val percent = numOrNull(argsJson, "percent")?.toInt()?.coerceIn(0, 100)
            ?: return ToolResult.Failure("нет args.percent (0..100)")
        return try {
            // SCREEN_BRIGHTNESS: 0..255. Нижний порог 10 (≈4%): при percent=0 экран не гаснет полностью.
            val value = ((percent * 255 + 50) / 100).coerceIn(10, 255)
            // Переводим в ручной режим, иначе авто-яркость перезапишет значение.
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value,
            )
            ok(
                "status" to "яркость задана",
                "percent" to percent.toString(),
                "value" to "$value/255",
            )
        } catch (t: Throwable) {
            ToolResult.Failure("set_brightness: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── НЕ БЕСПОКОИТЬ ────────────────────────────

/**
 * dnd_mode — включить/выключить режим «не беспокоить» через
 * NotificationManager.setInterruptionFilter. Требует особого доступа ACCESS_NOTIFICATION_POLICY:
 * если не выдан (isNotificationPolicyAccessGranted==false) — Failure с подсказкой. IMPORTANT.
 * on=true → INTERRUPTION_FILTER_PRIORITY (штатный «не беспокоить»: будильники проходят),
 * а не NONE (полная тишина), чтобы не глушить будильники.
 */
class DndModeTool(private val context: Context) : AgentTool {
    override val id = "dnd_mode"
    override val danger = DangerLevel.IMPORTANT
    override val schema = """режим «не беспокоить» вкл/выкл; args: {"on":true}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolResult.Failure(
                "нет доступа к политике уведомлений — выдай его вручную: " +
                    "Настройки → Приложения → Спец. доступ → Доступ к режиму «Не беспокоить» → " +
                    "Plus AI (ACCESS_NOTIFICATION_POLICY)",
            )
        }
        val on = boolField(argsJson, "on") ?: true
        return try {
            // on: штатный «не беспокоить» (PRIORITY) — глушит уведомления, но ПРОПУСКАЕТ будильники;
            // NONE (полная тишина) заглушил бы и будильники, поэтому не используем.
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            ok("status" to if (on) "режим «не беспокоить» включён" else "режим «не беспокоить» выключен")
        } catch (t: Throwable) {
            ToolResult.Failure("dnd_mode: ${t.message?.take(200)}")
        }
    }
}

// ──────────────────────────── ХЕЛПЕРЫ ────────────────────────────

/** SmsManager: на API 31+ — из системного сервиса, раньше — устаревший getDefault(). */
private fun smsManager(context: Context): SmsManager? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    }

/** Парсинг булева аргумента: принимает и {"on":true}, и {"on":"true"}. */
private fun boolField(json: String, field: String): Boolean? =
    Regex("\"$field\"\\s*:\\s*\"?(true|false)\"?", RegexOption.IGNORE_CASE)
        .find(json)?.groupValues?.get(1)?.equals("true", ignoreCase = true)

// ──────────────────────────── ФАБРИКА ────────────────────────────

/** Набор инструментов связи и системных настроек телефона. */
fun phoneCommsTools(context: Context): List<AgentTool> = listOf(
    SmsSendTool(context),
    SmsReadTool(context),
    ContactsCreateTool(context),
    SetVolumeTool(context),
    SetBrightnessTool(context),
    DndModeTool(context),
)
