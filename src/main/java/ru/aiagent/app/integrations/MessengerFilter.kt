package ru.aiagent.app.integrations

import android.content.Context
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// Фильтр чувствительного для мессенджеров (сейчас — Telegram). Общий слой чтения:
// маскирует коды входа/OTP и подавляет тело у сервисных/официальных отправителей,
// чтобы коды Госуслуг/банков НЕ попадали в транскрипт агента (и тем более в облако).
// Раньше жил рядом с MAX-клиентом; MAX убран, фильтр остался — он мессенджеро-независим.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Настройки фильтра чувствительного (редактируемые пользователем; по умолчанию защита ВКЛючена).
 * Хранятся в обычных SharedPreferences — это не секрет, а пользовательская политика приватности.
 */
object MessengerFilterSettings {
    private const val PREFS = "plusai_msgfilter"
    private const val KEY_SUPPRESS = "filter_suppress_sensitive" // Слой 2 вкл/выкл
    private const val KEY_SENDERS = "filter_sensitive_senders"   // редактируемый список (regex-альтернативы через |)

    /** Слой 2 (подавление тела у чувствительных отправителей) — по умолчанию ВКЛ. */
    fun suppressSensitive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SUPPRESS, true)

    fun setSuppressSensitive(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SUPPRESS, on).apply()

    /** Дефолтный список чувствительных отправителей (имена чатов/ботов). Пользователь может править. */
    const val DEFAULT_SENDERS =
        "Госуслуг|ЕСИА|MOS\\.RU|Сбер|ВТБ|Тинькофф|Т-Банк|Альфа|ФНС|налог|\\bбанк\\b|\\bbank\\b|безопасн|security"

    /** Строка-паттерн (альтернативы через |). Пусто/сброс → дефолт. */
    fun sensitiveSenders(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SENDERS, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SENDERS

    fun setSensitiveSenders(context: Context, pattern: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SENDERS, pattern).apply()
}

/**
 * Мессенджеры доставляют коды входа/OTP (Госуслуги, банки, приложения) через ботов прямо в чат.
 * Агент НЕ должен выносить эти коды в транскрипт: транскрипт может уйти в облако или быть выкраден
 * инъекцией. Поэтому ВСЕ пути чтения сообщений проходят через этот фильтр.
 *
 * Слой 1 — редакция кодов (ВСЕГДА, к тексту каждого сообщения).
 * Слой 2 — подавление тела у чувствительных отправителей (конфиг MessengerFilterSettings, по умолчанию ВКЛ).
 *
 * Реализовано чистыми функциями над строками — детерминированно и тестируемо в отрыве от сети.
 */
object MessengerFilter {

    /** Маркеры (ru+en, регистронезависимо) — рядом с ними цифровые группы считаем кодом. */
    private val MARKERS = listOf(
        "код подтверждения", "код для входа", "подтвердите вход", "confirmation code",
        "verification code", "one-time", "one time", "никому не сообщайте", "не сообщайте",
        "одноразов", "госуслуг", "есиа", "2fa", "otp", "код", "пароль", "password", "pin",
    )

    /**
     * Группа из 3–8 цифр, возможно с разделителем-дефисом/пробелом (в т.ч. «123-456»).
     * Считаем именно ЦИФРЫ (не длину со скобками), чтобы точно попасть в диапазон 3–8.
     */
    private val DIGIT_GROUP = Regex("""\d(?:[\d \-]*\d)?""")

    /** Голое сообщение = 4–12 цифр целиком (сам код, без текста) — маскируем всегда. Верх 12, а не 8:
     *  часть банков/2FA шлёт 9–10-значные коды, а «голое» число всё равно почти всегда код. */
    private val BARE_CODE = Regex("""^\s*\d{4,12}\s*$""")

    /** Split-код 123-456 / 123 456 — почти всегда OTP; маскируем ВСЕГДА (сплошной \d{4,12} его не ловит). */
    private val SPLIT_CODE = Regex("""\d{3}[-\s]\d{3}""")

    /** «Голая» группа 4–12 цифр — маскируем даже БЕЗ маркера (встроенный код: «Enter 483920 to continue»). */
    private val STANDALONE = Regex("""\d{4,12}""")

    /** Алфа-цифровой код (буквы+цифры, 4–12), маскируем ТОЛЬКО рядом с маркером «код:» — иначе побил бы
     *  обычные слова. Ловит OTP вида «код: A1B2C3», которые чисто-цифровой DIGIT_GROUP пропускал. */
    private val ALNUM_CODE = Regex("""\b(?=[A-Za-z0-9]{4,12}\b)(?=\S*\d)[A-Za-z0-9]+\b""")

    /** Голый алфа-цифровой OTP БЕЗ слова-маркера (напр. «A1B2C3»). Маскируем консервативно: ТОЛЬКО
     *  ALLCAPS 5–10 с буквой И цифрой — классический формат кода, обычная проза так не выглядит.
     *  Узкий шаблон минимизирует ложные срабатывания (обычные слова/годы/имена не попадают). (audit) */
    private val ALNUM_STANDALONE = Regex("""\b(?=[A-Z0-9]{5,10}\b)(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\d)[A-Z0-9]+\b""")

    /** Плейсхолдер Слоя 2. */
    const val SUPPRESSED_BODY = "[тело скрыто: сервисный/официальный аккаунт — откройте мессенджер вручную]"

    /**
     * Слой 1: вернуть (текст-с-маскированными-кодами, был_ли_замаскирован).
     * Логика:
     *  - если всё сообщение — 4–8 цифр → «••••» (голый код всегда);
     *  - иначе, если рядом есть маркер → маскируем цифровые группы 3–8 цифр, оставляем текст,
     *    добавляем пометку « [код скрыт]».
     */
    fun redactCodes(text: String): Pair<String, Boolean> {
        if (BARE_CODE.matches(text)) return "••••" to true
        val lower = text.lowercase()
        val hasMarker = MARKERS.any { lower.contains(it) }
        var masked = false
        // Split-код маскируем ВСЕГДА (и с маркером, и без).
        var out = SPLIT_CODE.replace(text) { masked = true; "••••" }
        out = if (hasMarker) {
            // Рядом с маркером — любая группа 3–12 цифр…
            var o = DIGIT_GROUP.replace(out) { m ->
                val digits = m.value.count { it.isDigit() }
                // 3..12: рядом с маркером кода 9–10-значный OTP тоже надо маскировать (раньше уходил открытым).
                if (digits in 3..12) { masked = true; "••••" } else m.value
            }
            // …и алфа-цифровой код (буквы+цифры), напр. «код: A1B2C3» — чисто-цифровой паттерн его пропускал.
            o = ALNUM_CODE.replace(o) { m ->
                if (m.value.any { it.isDigit() } && m.value.any { it.isLetter() }) { masked = true; "••••" } else m.value
            }
            o
        } else {
            // БЕЗ маркера — «голую» группу 4–8 цифр всё равно маскируем (встроенный код без слов-маркеров;
            // раньше такой текст возвращался как есть, и OTP вида «Enter 483920» протекал в транскрипт).
            var o = STANDALONE.replace(out) { masked = true; "••••" }
            // …и голый ALLCAPS-алфа-цифровой OTP (напр. «A1B2C3») — раньше уходил открытым без маркера. (audit)
            o = ALNUM_STANDALONE.replace(o) { masked = true; "••••" }
            o
        }
        // Пометку « [код скрыт]» дописываем только при маркере (иначе шум на обычных числах).
        return if (masked && hasMarker) "$out [код скрыт]" to true else out to masked
    }

    /**
     * Слой 2: чувствителен ли отправитель/чат.
     * Признак = флаг протокола (бот/официальный/сервисный аккаунт) ИЛИ имя матчит редактируемый
     * список MessengerFilterSettings. [flags] — «сырые» булевы признаки из кадра (bot/official/service/verified).
     */
    fun isSensitiveSender(context: Context, name: String?, flags: Set<String>): Boolean {
        if (flags.any { it in SENSITIVE_FLAGS }) return true
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        // Список — альтернативы через '|'. Компилируем КАЖДУЮ отдельно: один битый паттерн пользователя не
        // должен вырубать всю защиту (иначе одна опечатка → Слой 2 off для банков/Госуслуг). Префикс `(?U)`
        // включает Unicode-границы слова — без него `\bбанк\b` НЕ матчит кириллицу (по умолчанию \b — ASCII).
        for (alt in MessengerFilterSettings.sensitiveSenders(context).split('|')) {
            val p = alt.trim()
            if (p.isEmpty()) continue
            val rx = runCatching { Regex("(?U)$p", RegexOption.IGNORE_CASE) }.getOrNull() ?: continue
            if (runCatching { rx.containsMatchIn(n) }.getOrDefault(false)) return true
        }
        return false
    }

    private val SENSITIVE_FLAGS = setOf("bot", "official", "service", "verified")

    /**
     * Санитайз одного сообщения для транскрипта. [chatSensitive] — уже вычисленная чувствительность
     * чата (Слой 2 на уровне чата, чтобы не звать сеть на каждое сообщение). Если чувствителен либо
     * чат, либо сам отправитель сообщения — тело подавляется целиком; иначе применяется Слой 1.
     *
     * Возвращает JSON: {id, from, time, text, redacted?, suppressed?}. Метаданные сохраняются всегда.
     */
    fun sanitizeMessage(
        context: Context,
        raw: JSONObject,
        chatSensitive: Boolean,
    ): JSONObject {
        val id = raw.opt("id") ?: raw.opt("messageId") ?: JSONObject.NULL
        val from = optStr(raw, "sender").ifBlank { optStr(raw, "senderName").ifBlank { optStr(raw, "from") } }
        val time = raw.opt("time") ?: raw.opt("date") ?: JSONObject.NULL
        val text = optStr(raw, "text")

        val out = JSONObject().put("id", id).put("from", from).put("time", time)

        // Data retention: «удаление для себя» бывает клиентским — сервер может вернуть удалённое
        // сообщение со status:"REMOVED", но с сохранённым text. Отправитель считает его удалённым —
        // не выносим содержимое в агента (и тем более в облако). Надгробие с метаданными.
        if (optStr(raw, "status").equals("REMOVED", ignoreCase = true)) {
            return out.put("text", "[удалённое сообщение]").put("removed", true)
        }

        // Слой 2: чувствителен чат ИЛИ сам отправитель (напр. бот в группе).
        val senderFlags = boolFlags(raw)
        val senderSensitive = isSensitiveSender(context, from, senderFlags)
        val suppress = MessengerFilterSettings.suppressSensitive(context) && (chatSensitive || senderSensitive)
        if (suppress) {
            return out.put("text", SUPPRESSED_BODY).put("suppressed", true)
        }

        // Слой 1: редакция кодов всегда.
        val (safe, redacted) = redactCodes(text)
        out.put("text", safe)
        if (redacted) out.put("redacted", true)
        return out
    }

    /** Булевы признаки-флаги из кадра сообщения/участника (имена RE-производные, читаем терпимо). */
    private fun boolFlags(o: JSONObject): Set<String> {
        val s = mutableSetOf<String>()
        if (o.optBoolean("bot") || o.optBoolean("isBot")) s += "bot"
        if (o.optBoolean("official") || o.optBoolean("isOfficial")) s += "official"
        if (o.optBoolean("service") || o.optBoolean("isService")) s += "service"
        if (o.optBoolean("verified")) s += "verified"
        return s
    }

    /**
     * Квирк org.json: optString при JSON null возвращает строку "null", а не пустую. isNull-гард
     * обязателен, иначе в транскрипт попадёт мусорное «null».
     */
    fun optStr(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key)
}
