package ru.aiagent.app.integrations

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.AppSettings
import ru.aiagent.app.cloud.SecureKeys
import ru.aiagent.app.rag.RagEngine
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * Почта — ЛОКАЛЬНО (ТЗ §7). Доступ по паролю приложения (Keystore). Инструменты
 * не «выгружают ящик»: они ищут релевантное письмо семантически ПРЯМО НА УСТРОЙСТВЕ
 * и отдают агенту только совпавшее — и то как IMPORTANT-операция с подтверждением
 * (кроме bypass). Письма нигде не индексируются и не сохраняются.
 */

/**
 * Хранилище доступа к почте в Keystore — НЕСКОЛЬКО аккаунтов (список) + активный.
 * `account()` возвращает активный (чтобы инструменты работали без изменений). Старая одиночная
 * учётка (плоские ключи) автоматически мигрирует в список при первом чтении.
 */
object MailAuth {
    private const val KEY_ACCOUNTS = "mail_accounts" // JSON-массив аккаунтов
    private const val KEY_ACTIVE = "mail_active"      // email активного аккаунта
    // legacy (одиночная учётка до мульти-аккаунта) — только для миграции.
    private const val KEY_PROVIDER = "mail_provider"
    private const val KEY_EMAIL = "mail_email"
    private const val KEY_SECRET = "mail_app_password"
    private const val KEY_HOST = "mail_imap_host"
    private const val KEY_OAUTH = "mail_oauth"
    private const val KEY_REFRESH = "mail_refresh_token"

    data class Account(
        val provider: String, val email: String, val secret: String,
        val host: String, val oauth: Boolean, val refresh: String? = null,
    )

    private fun toJson(a: Account) = JSONObject()
        .put("provider", a.provider).put("email", a.email).put("secret", a.secret)
        .put("host", a.host).put("oauth", a.oauth).apply { a.refresh?.let { put("refresh", it) } }

    private fun fromJson(o: JSONObject): Account? {
        val p = o.optString("provider"); val e = o.optString("email"); val s = o.optString("secret")
        if (p.isBlank() || e.isBlank() || s.isBlank()) return null
        val host = o.optString("host").ifBlank { MailClient.imapHost(p) }
        return Account(p, e, s, host, o.optBoolean("oauth"), o.optString("refresh").takeIf { it.isNotBlank() })
    }

    /** Все подключённые аккаунты (после миграции старой одиночной учётки). */
    fun accounts(context: Context): List<Account> {
        migrateLegacy(context)
        val raw = SecureKeys.get(context).getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }

    /** Активный аккаунт (на нём работают инструменты). Нет активного → первый в списке. */
    fun account(context: Context): Account? {
        val list = accounts(context)
        if (list.isEmpty()) return null
        val active = SecureKeys.get(context).getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.email == active } ?: list.first()
    }

    private fun writeAll(context: Context, list: List<Account>, active: String?) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        SecureKeys.get(context).edit()
            .putString(KEY_ACCOUNTS, arr.toString())
            .putString(KEY_ACTIVE, active ?: list.firstOrNull()?.email)
            .apply()
    }

    /** Добавить/заменить аккаунт по email и сделать активным. */
    fun add(context: Context, a: Account) {
        writeAll(context, accounts(context).filter { it.email != a.email } + a, a.email)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun setActive(context: Context, email: String) {
        if (accounts(context).any { it.email == email }) {
            SecureKeys.get(context).edit().putString(KEY_ACTIVE, email).apply()
            ru.aiagent.app.cloud.AutoSync.schedulePush(context)
        }
    }

    fun remove(context: Context, email: String) {
        val list = accounts(context).filter { it.email != email }
        val active = SecureKeys.get(context).getString(KEY_ACTIVE, null)
        writeAll(context, list, if (active == email) list.firstOrNull()?.email else active)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    /** Совместимость: добавляет аккаунт в список (и делает активным). */
    fun save(context: Context, provider: String, email: String, secret: String, host: String, oauth: Boolean, refresh: String? = null) =
        add(context, Account(provider, email, secret, host, oauth, refresh))

    /** Обновить access-токен АКТИВНОГО аккаунта (после refresh), сохранив его активным. */
    /**
     * Обновить токены КОНКРЕТНОГО ящика и вернуть его же. Раньше писали в account(context) — то есть в
     * АКТИВНЫЙ ящик, независимо от того, чей токен обновляли: при работе с неактивным ящиком (аргумент
     * account у почтовых тулов) свежие токены ящика B затирали учётку ящика A, B так и оставался
     * протухшим, а вызывающий получал обратно A и слал письмо «от него». Плюс add() переключал активный
     * ящик — здесь выбор активного сохраняем.
     */
    fun updateAccess(context: Context, email: String, newAccess: String, newRefresh: String?): Account? {
        val a = accounts(context).firstOrNull { it.email == email } ?: return null
        val updated = a.copy(secret = newAccess, refresh = newRefresh ?: a.refresh)
        val active = SecureKeys.get(context).getString(KEY_ACTIVE, null)
        writeAll(context, accounts(context).filter { it.email != email } + updated, active)
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
        return updated
    }

    /** Убрать ВСЕ аккаунты (и legacy-ключи). */
    fun clear(context: Context) {
        SecureKeys.get(context).edit()
            .remove(KEY_ACCOUNTS).remove(KEY_ACTIVE)
            .remove(KEY_PROVIDER).remove(KEY_EMAIL).remove(KEY_SECRET).remove(KEY_HOST).remove(KEY_OAUTH).remove(KEY_REFRESH).apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    private fun migrateLegacy(context: Context) {
        val s = SecureKeys.get(context)
        if (s.getString(KEY_ACCOUNTS, null) != null) return // уже в новом формате
        val p = s.getString(KEY_PROVIDER, null); val e = s.getString(KEY_EMAIL, null); val sec = s.getString(KEY_SECRET, null)
        if (p.isNullOrBlank() || e.isNullOrBlank() || sec.isNullOrBlank()) return
        val host = s.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: MailClient.imapHost(p)
        writeAll(context, listOf(Account(p, e, sec, host, s.getBoolean(KEY_OAUTH, false), s.getString(KEY_REFRESH, null))), e)
    }

    fun isConnected(context: Context): Boolean = accounts(context).isNotEmpty()
}

private const val NOT_CONNECTED = "Почта не подключена — открой Настройки → Интеграции → Почта"
private fun field(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** Идентификатор письма — тот же IMAP-uid, что отдаёт search_email/read_email (message_id — синоним). */
private fun mailUid(argsJson: String): Long? =
    (field(argsJson, "uid") ?: field(argsJson, "message_id"))?.toLongOrNull()

/** SMTP-хост для аккаунта (провайдерный, иначе imap.* → smtp.*) — как в send_email. */
private fun smtpOf(a: MailAuth.Account) =
    MailClient.smtpHost(a.provider).ifBlank { a.host.replaceFirst("imap", "smtp") }

/**
 * Выбор ящика для инструмента: если в args задан "account" (email) — работаем именно с ним; иначе с
 * активным. Возвращает (аккаунт, ошибка): ошибка непустая, если запрошенный ящик не подключён.
 */
private fun mailFor(context: Context, argsJson: String): Pair<MailAuth.Account?, String?> {
    val wanted = field(argsJson, "account")
    if (wanted != null) {
        val a = MailAuth.accounts(context).firstOrNull { it.email.equals(wanted, true) }
        if (a == null) {
            val have = MailAuth.accounts(context).joinToString { it.email }.ifBlank { "нет ни одного" }
            return null to "нет подключённого ящика «$wanted» (подключены: $have) — уточни account или подключи в Интеграциях"
        }
        return a to null
    }
    return MailAuth.account(context) to (if (MailAuth.isConnected(context)) null else NOT_CONNECTED)
}

/** list_mail_accounts — какие ящики подключены (email + провайдер). Модель указывает account в остальных. */
class ListMailAccountsTool(private val context: Context) : AgentTool {
    override val id = "list_mail_accounts"
    override val danger = DangerLevel.SAFE
    override val privateData get() = AppSettings.mailCloudMode(context) == "local"
    override val schema = """показать подключённые почтовые ящики; их email передавай в account других почтовых инструментов, чтобы работать с конкретным. args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val list = MailAuth.accounts(context)
        if (list.isEmpty()) return ToolResult.Success("""{"accounts":[],"note":"почта не подключена"}""")
        val active = MailAuth.account(context)?.email
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("email", it.email).put("provider", it.provider).put("active", it.email == active)) }
        return ToolResult.Success(JSONObject().put("accounts", arr).toString())
    }
}

/**
 * Если IMAP отказал по авторизации и это OAuth-аккаунт с refresh-токеном — обновляем
 * access-токен через сервер, сохраняем и возвращаем обновлённый аккаунт для повтора.
 * null → refresh не применим (не OAuth / нет refresh / ошибка не про авторизацию).
 */
private suspend fun refreshedAccount(context: Context, acc: MailAuth.Account, err: Throwable): MailAuth.Account? {
    if (!acc.oauth || acc.refresh.isNullOrBlank()) return null
    val m = (err.message ?: "").lowercase()
    if (!m.contains("auth") && !m.contains("login") && !m.contains("credential") && !m.contains("expired")) return null
    val tok = OAuthBroker.refresh(context, acc.provider, acc.refresh).getOrNull() ?: return null
    // Возвращаем ИМЕННО обновлённый ящик (acc), а не account(context): при обновлении токена неактивного
    // ящика активный — чужой, и повтор уходил бы на другой ящик/хост с чужим токеном.
    return MailAuth.updateAccess(context, acc.email, tok.access, tok.refresh.takeIf { it.isNotBlank() })
}

/**
 * search_email — найти письмо по смыслу запроса. Тянет последние N писем по IMAP,
 * ранжирует ЛОКАЛЬНО эмбеддером и возвращает только top-совпадения (заголовки+сниппет).
 * Полное тело — отдельным read_email по uid (тоже с подтверждением).
 */
class SearchEmailTool(private val context: Context) : AgentTool {
    override val id = "search_email"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // доступ к личной почте — подтверждение всегда, кроме bypass
    override val privateData get() = AppSettings.mailCloudMode(context) == "local" // облаку в hybrid/direct
    override val schema =
        """найти письмо по смыслу; в имена вложений тоже смотрит. Для поиска ВНУТРИ сканов/PDF-вложений — """ +
            """deep:true (медленнее, распознаёт вложения OCR). args: {"query":"договор аренды","scan":40,"top":3,"deep":false,"account":"email ящика, необязательно (по умолчанию активный)"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val query = field(argsJson, "query") ?: return ToolResult.Failure("нужен args.query")
        val scan = field(argsJson, "scan")?.toIntOrNull() ?: 40
        val top = field(argsJson, "top")?.toIntOrNull() ?: 3
        val deep = field(argsJson, "deep")?.equals("true", true) == true

        var work = acc
        var res = MailClient.fetchRecent(acc.host, acc.email, acc.secret, acc.oauth, scan)
        res.exceptionOrNull()?.let { err -> // истёк OAuth-токен → обновить и повторить
            refreshedAccount(context, acc, err)?.let { fresh ->
                work = fresh
                res = MailClient.fetchRecent(fresh.host, fresh.email, fresh.secret, fresh.oauth, scan)
            }
        }
        val mails = res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        if (mails.isEmpty()) return ToolResult.Success("""{"matches":[],"note":"входящих нет"}""")

        // deep: распознаём документы/сканы во вложениях (капы: батарея/трафик). Текст ↔ uid.
        val attachText = HashMap<Long, String>()
        if (deep) {
            var budget = MAX_DEEP_ATTACHMENTS
            for (m in mails) {
                if (budget <= 0) break
                for (name in m.attachments) {
                    if (budget <= 0) break
                    if (!isExtractable(name)) continue
                    budget--
                    val bytes = MailClient.fetchAttachment(work.host, work.email, work.secret, work.oauth, m.uid, name).getOrNull() ?: continue
                    val text = extractAttachmentText(context, name, bytes)
                    if (text.isNotBlank()) attachText[m.uid] = (attachText[m.uid].orEmpty() + "\n" + text).take(8000)
                }
            }
        }

        // Локальное семантическое ранжирование: тема + отправитель + сниппет + имена/текст вложений.
        val docs = mails.map { m ->
            buildString {
                append(m.subject).append("\nОт: ").append(m.from).append('\n').append(m.snippet)
                if (m.attachments.isNotEmpty()) append("\nВложения: ").append(m.attachments.joinToString(", "))
                attachText[m.uid]?.let { append('\n').append(it) }
            }
        }
        val ranked = RagEngine.rankBySimilarity(context, query, docs, top)
        // Если эмбеддер не установлен — простой отбор по вхождению слов (fallback).
        val picked = if (ranked.isNotEmpty()) ranked.map { mails[it] }
        else mails.filter { m -> query.split(" ").any { w -> w.length > 2 && docs[mails.indexOf(m)].contains(w, true) } }.take(top)

        // HYBRID (§7): сырые письма читает ЛОКАЛЬНАЯ модель и синтезирует ответ — наружу уходит только он,
        // тело письма остаётся на устройстве. Нет локальной модели → честный отказ (не сливаем сырьё).
        if (AppSettings.mailCloudMode(context) == "hybrid") {
            val raw = picked.joinToString("\n---\n") { m ->
                "От: ${m.from}\nТема: ${m.subject}\n${m.snippet}" + (attachText[m.uid]?.let { "\n" + it.take(1500) } ?: "")
            }
            val instr = "Ответь на запрос по письмам пользователя. Только по ним, кратко, по делу; если ответа " +
                "в них нет — так и скажи. Не цитируй лишнего.\n\nЗапрос: $query\n\nПисьма:\n${raw.take(6000)}"
            val answer = runCatching { ru.aiagent.app.ChatEngine.agentGenerate(context, instr) }
                .getOrElse {
                    return ToolResult.Failure("для приватного поиска почты (режим «через локальную модель») нужна " +
                        "локальная модель — установите/выберите её на вкладке Модели, либо переключите режим почты на «Напрямую» в Интеграциях")
                }
            val uids = JSONArray().apply { picked.forEach { put(it.uid) } }
            return ToolResult.Success(JSONObject().put("answer", answer.trim().take(4000)).put("found", picked.size).put("uids", uids).toString())
        }

        val arr = JSONArray()
        for (m in picked) {
            arr.put(JSONObject()
                .put("uid", m.uid).put("from", m.from).put("subject", m.subject)
                .put("date", m.date).put("snippet", m.snippet)
                .put("attachments", JSONArray(m.attachments))
                .apply { attachText[m.uid]?.let { put("attachment_text", it.take(1500)) } })
        }
        return ToolResult.Success(JSONObject().put("matches", arr)
            .apply { if (deep) put("note", "вложения распознаны OCR (deep)") }.toString())
    }
}

private const val MAX_DEEP_ATTACHMENTS = 12 // сколько вложений максимум OCR-им за один deep-поиск

/** Расширения, из которых умеем извлекать текст (документы + картинки/сканы через OCR). */
private fun isExtractable(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("pdf", "docx", "xlsx", "txt", "csv", "md") ||
        ext in ru.aiagent.app.ocr.OcrEngine.IMAGE_EXTS
}

/** Пишет байты вложения во временный файл (с правильным расширением) и извлекает текст. */
private fun extractAttachmentText(context: Context, name: String, bytes: ByteArray): String {
    // Имя вложения подконтрольно отправителю → чистим расширение (path-traversal через ../).
    val ext = name.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.lowercase().take(5).ifBlank { "bin" }
    val tmp = java.io.File(context.cacheDir, "att_${System.nanoTime()}.$ext")
    return try {
        tmp.writeBytes(bytes)
        runCatching { ru.aiagent.toolsdocs.readDocumentText(context, tmp) }.getOrDefault("")
    } finally {
        tmp.delete()
    }
}

/** read_email_attachment — прочитать конкретное вложение письма (pdf/скан/фото — с OCR). */
class ReadEmailAttachmentTool(private val context: Context) : AgentTool {
    override val id = "read_email_attachment"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.mailCloudMode(context) != "direct" // сырьё → облаку только в direct
    override val schema =
        """прочитать вложение письма в текст (pdf/скан/фото — OCR); args: {"uid":12345,"filename":"договор.pdf","account":"email ящика, необязательно"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val uid = field(argsJson, "uid")?.toLongOrNull() ?: return ToolResult.Failure("нужен args.uid")
        val filename = field(argsJson, "filename") ?: return ToolResult.Failure("нужен args.filename")
        var res = MailClient.fetchAttachment(acc.host, acc.email, acc.secret, acc.oauth, uid, filename)
        res.exceptionOrNull()?.let { err ->
            refreshedAccount(context, acc, err)?.let { fresh ->
                res = MailClient.fetchAttachment(fresh.host, fresh.email, fresh.secret, fresh.oauth, uid, filename)
            }
        }
        val bytes = res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        val text = extractAttachmentText(context, filename, bytes)
        if (text.isBlank()) return ToolResult.Failure("не удалось извлечь текст из «$filename» (пустой скан?)")
        return ToolResult.Success(JSONObject().put("filename", filename)
            .put("chars", text.length).put("text", text.take(200_000)).toString())
    }
}

/** read_email — полный текст письма по uid (из результата search_email). */
class ReadEmailTool(private val context: Context) : AgentTool {
    override val id = "read_email"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.mailCloudMode(context) != "direct" // сырьё → облаку только в direct
    override val schema = """прочитать письмо целиком; args: {"uid":12345,"account":"email ящика, необязательно"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val uid = field(argsJson, "uid")?.toLongOrNull() ?: return ToolResult.Failure("нужен args.uid")
        var res = MailClient.readBody(acc.host, acc.email, acc.secret, acc.oauth, uid)
        res.exceptionOrNull()?.let { err ->
            refreshedAccount(context, acc, err)?.let { fresh ->
                res = MailClient.readBody(fresh.host, fresh.email, fresh.secret, fresh.oauth, uid)
            }
        }
        val m = res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        return ToolResult.Success(JSONObject()
            .put("from", m.from).put("subject", m.subject).put("date", m.date).put("text", m.snippet).toString())
    }
}

/**
 * send_email — отправить письмо с ящика пользователя. Необратимое внешнее действие →
 * DANGEROUS + alwaysConfirm (подтверждение в любом режиме, кроме bypass). SMTP напрямую
 * с устройства: тело не проходит через наш сервер (§7); privateData — не выдаётся облаку.
 */
class SendEmailTool(private val context: Context) : AgentTool {
    override val id = "send_email"
    override val danger = DangerLevel.DANGEROUS
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.mailCloudMode(context) == "local" // облаку в hybrid/direct
    override val schema =
        """отправить письмо с вашей почты; args: {"to":"адрес@домен","subject":"тема","body":"текст письма","account":"email ящика-отправителя, необязательно"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val to = field(argsJson, "to") ?: return ToolResult.Failure("нужен args.to (адрес получателя)")
        val subject = field(argsJson, "subject") ?: "(без темы)"
        val body = field(argsJson, "body") ?: return ToolResult.Failure("нужен args.body (текст письма)")
        fun smtpOf(a: MailAuth.Account) =
            MailClient.smtpHost(a.provider).ifBlank { a.host.replaceFirst("imap", "smtp") }

        var res = MailClient.send(smtpOf(acc), acc.email, acc.secret, acc.oauth, to, subject, body)
        res.exceptionOrNull()?.let { err -> // истёк OAuth-токен → обновить и повторить
            refreshedAccount(context, acc, err)?.let { fresh ->
                res = MailClient.send(smtpOf(fresh), fresh.email, fresh.secret, fresh.oauth, to, subject, body)
            }
        }
        res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        return ToolResult.Success(
            JSONObject().put("sent", true).put("to", to).put("subject", subject).toString(),
        )
    }
}

/**
 * reply_email — ответить на письмо. Читает по IMAP только заголовки оригинала (тред), тело
 * ответа отправляет по SMTP напрямую с устройства (§7). DANGEROUS + подтверждение (кроме bypass) — паритет с send_email.
 */
class ReplyEmailTool(private val context: Context) : AgentTool {
    override val id = "reply_email"
    override val danger = DangerLevel.DANGEROUS // необратимое исходящее письмо — паритет с send_email
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.mailCloudMode(context) == "local" // облаку в hybrid/direct
    override val schema =
        """ответить на письмо (тема «Re: …», заголовки In-Reply-To/References ставятся сами); uid — из """ +
            """search_email/read_email. args: {"uid":12345,"text":"текст ответа","account":"email ящика, необязательно"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val uid = mailUid(argsJson) ?: return ToolResult.Failure("нужен args.uid (из search_email)")
        val text = field(argsJson, "text") ?: return ToolResult.Failure("нужен args.text (текст ответа)")
        var res = MailClient.reply(acc.host, smtpOf(acc), acc.email, acc.secret, acc.oauth, uid, text)
        res.exceptionOrNull()?.let { err -> // истёк OAuth-токен → обновить и повторить
            refreshedAccount(context, acc, err)?.let { fresh ->
                res = MailClient.reply(fresh.host, smtpOf(fresh), fresh.email, fresh.secret, fresh.oauth, uid, text)
            }
        }
        res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        return ToolResult.Success(JSONObject().put("replied", true).put("uid", uid).toString())
    }
}

/**
 * delete_email — удалить письмо (в корзину, если она есть, иначе флаг Deleted + expunge).
 * Необратимо → DANGEROUS + подтверждение (кроме bypass).
 */
class DeleteEmailTool(private val context: Context) : AgentTool {
    override val id = "delete_email"
    override val danger = DangerLevel.DANGEROUS
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.mailCloudMode(context) == "local"
    override val schema =
        """удалить письмо (в корзину, если она есть, иначе пометить удалённым и expunge); """ +
            """args: {"uid":12345,"account":"email ящика, необязательно"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val uid = mailUid(argsJson) ?: return ToolResult.Failure("нужен args.uid (из search_email)")
        var res = MailClient.delete(acc.host, acc.email, acc.secret, acc.oauth, uid)
        res.exceptionOrNull()?.let { err ->
            refreshedAccount(context, acc, err)?.let { fresh ->
                res = MailClient.delete(fresh.host, fresh.email, fresh.secret, fresh.oauth, uid)
            }
        }
        val how = res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        return ToolResult.Success(JSONObject().put("deleted", true).put("uid", uid).put("how", how).toString())
    }
}

/**
 * move_email — переместить письмо в папку (copy в целевую + Deleted/expunge в исходной).
 * Папка создаётся, если её нет. IMPORTANT + подтверждение (кроме bypass).
 */
class MoveEmailTool(private val context: Context) : AgentTool {
    override val id = "move_email"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.mailCloudMode(context) == "local"
    override val schema =
        """переместить письмо в папку (создаётся, если её нет); """ +
            """args: {"uid":12345,"folder":"Archive","account":"email ящика, необязательно"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val uid = mailUid(argsJson) ?: return ToolResult.Failure("нужен args.uid (из search_email)")
        val folder = field(argsJson, "folder") ?: return ToolResult.Failure("нужен args.folder (папка назначения)")
        var res = MailClient.move(acc.host, acc.email, acc.secret, acc.oauth, uid, folder)
        res.exceptionOrNull()?.let { err ->
            refreshedAccount(context, acc, err)?.let { fresh ->
                res = MailClient.move(fresh.host, fresh.email, fresh.secret, fresh.oauth, uid, folder)
            }
        }
        val note = res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        val out = JSONObject().put("moved", true).put("uid", uid).put("folder", folder)
        if (note != null) out.put("note", note)
        return ToolResult.Success(out.toString())
    }
}

/** mark_read — пометить письмо прочитанным/непрочитанным (флаг SEEN). SAFE. */
class MarkReadTool(private val context: Context) : AgentTool {
    override val id = "mark_read"
    override val danger = DangerLevel.SAFE
    override val privateData get() = AppSettings.mailCloudMode(context) == "local"
    override val schema =
        """пометить письмо прочитанным (read:true) или непрочитанным (read:false); """ +
            """args: {"uid":12345,"read":true,"account":"email ящика, необязательно"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val (acc0, mailErr) = mailFor(context, argsJson)
        val acc = acc0 ?: return ToolResult.Failure(mailErr ?: NOT_CONNECTED)
        val uid = mailUid(argsJson) ?: return ToolResult.Failure("нужен args.uid (из search_email)")
        val read = field(argsJson, "read")?.equals("false", true) != true // по умолчанию — прочитано
        var res = MailClient.setSeen(acc.host, acc.email, acc.secret, acc.oauth, uid, read)
        res.exceptionOrNull()?.let { err ->
            refreshedAccount(context, acc, err)?.let { fresh ->
                res = MailClient.setSeen(fresh.host, fresh.email, fresh.secret, fresh.oauth, uid, read)
            }
        }
        res.getOrElse { return ToolResult.Failure("почта: ${it.message}") }
        return ToolResult.Success(JSONObject().put("uid", uid).put("read", read).toString())
    }
}

/**
 * search_mail_archive — поиск по СОДЕРЖИМОМУ вложений почты через фоновый зашифрованный индекс
 * (мгновенно, без OCR в момент запроса). Доступен только при включённой фоновой индексации.
 */
class SearchMailArchiveTool(private val context: Context) : AgentTool {
    override val id = "search_mail_archive"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val privateData get() = AppSettings.mailCloudMode(context) != "direct" // архив (сырьё) → облаку только в direct
    override val schema =
        """искать по содержимому вложений почты (индекс сканов/документов, мгновенно); args: {"query":"договор аренды","top":3}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val query = field(argsJson, "query") ?: return ToolResult.Failure("нужен args.query")
        val top = field(argsJson, "top")?.toIntOrNull() ?: 3
        val qv = ru.aiagent.app.rag.RagEngine.embed(context, query)
        if (qv.isEmpty()) return ToolResult.Failure("эмбеддер не установлен — поиск по индексу недоступен")
        val hits = EmailIndexStore(context).use { it.search(qv, top) }
        if (hits.isEmpty()) {
            return ToolResult.Success("""{"matches":[],"note":"в индексе пусто (фоновая индексация ещё не набрала данные)"}""")
        }
        val arr = JSONArray()
        for (h in hits) {
            arr.put(JSONObject().put("uid", h.emailUid).put("filename", h.filename)
                .put("subject", h.subject).put("from", h.sender).put("text", h.text.take(2000)))
        }
        return ToolResult.Success(JSONObject().put("matches", arr).toString())
    }
}

fun emailTools(context: Context): List<AgentTool> = buildList {
    // list_mail_accounts выдаём, только когда ящиков БОЛЬШЕ одного (иначе лишний инструмент — выбирать не из чего).
    if (MailAuth.accounts(context).size > 1) add(ListMailAccountsTool(context))
    add(SearchEmailTool(context)); add(ReadEmailTool(context))
    add(ReadEmailAttachmentTool(context)); add(SendEmailTool(context))
    add(ReplyEmailTool(context)); add(DeleteEmailTool(context))
    add(MoveEmailTool(context)); add(MarkReadTool(context))
    if (AppSettings.emailIndexEnabled(context)) add(SearchMailArchiveTool(context))
}
