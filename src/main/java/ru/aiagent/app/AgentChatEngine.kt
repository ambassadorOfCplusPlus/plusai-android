package ru.aiagent.app

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import ru.aiagent.app.analytics.UsageAnalytics
import ru.aiagent.app.phone.DescribeImageTool
import ru.aiagent.app.phone.PhoneControlService
import ru.aiagent.app.phone.phoneControlTools
import ru.aiagent.core.agent.Agent
import ru.aiagent.core.agent.AgentEvent
import ru.aiagent.core.agent.AgentLoop
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.advertisedDescription
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.AutonomyMode
import ru.aiagent.core.agent.ChatGenerator
import ru.aiagent.core.agent.ConfirmationHandler
import ru.aiagent.core.agent.ListToolsTool
import ru.aiagent.core.agent.ModelClass
import ru.aiagent.core.agent.ToolGating
import ru.aiagent.core.agent.tools.SandboxFs
import ru.aiagent.core.agent.tools.fileTools
import ru.aiagent.toolsdocs.documentTools
import java.io.File

/**
 * Связка чат → агентный цикл S4 (внутренняя интеграция). Собирает инструменты под
 * класс модели (L8: слабее модель — уже набор) и режим, запускает [AgentLoop] на
 * загруженной локальной модели, отдаёт поток событий агента в UI.
 */
object AgentChatEngine {

    /**
     * Песочница файлов: рабочая папка приложения + (если пользователь дал «Доступ ко всем
     * файлам») всё внешнее хранилище /storage/emulated/0. Так агент выходит за пределы своей
     * папки только с явного разрешения пользователя (приватность §7 сохраняется).
     */
    private fun sandbox(context: Context): SandboxFs {
        val ws = File(context.filesDir, "workspace").apply { mkdirs() }
        val roots = mutableListOf(ws)
        val allowed = AppSettings.allowedFolders(context)
        if (allowed.isNotEmpty()) {
            // Гранулярный доступ: агент видит ТОЛЬКО выбранные пользователем папки (+ рабочую).
            allowed.forEach { p -> File(p).let { if (it.exists()) roots += it } }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()
        ) {
            // Иначе — всё хранилище, если выдан системный «доступ ко всем файлам».
            android.os.Environment.getExternalStorageDirectory()?.let { if (it.exists()) roots += it }
        }
        // Режим «вне песочницы»: агент работает со всей ФС без ограничения корнями. Двойной гейт —
        // явный тумблер пользователя И режим автономии bypass (в normal/auto песочница сохраняется).
        val unrestricted = AppSettings.outsideSandboxEnabled(context) &&
            AppSettings.mode(context) == ru.aiagent.core.agent.AutonomyMode.BYPASS
        // Даже вне песочницы закрываем app-private хранилища §7-инструментов (RAG-БД с сырым текстом,
        // зашифрованные ключи/prefs) — иначе read_file вне песочницы выгрузил бы их в облако в обход
        // ragCloudMode=local. Рабочая папка остаётся доступной (она в roots).
        val denied = if (unrestricted) listOf(
            File(context.dataDir, "databases"),   // rag_vectors.db и пр.
            File(context.dataDir, "shared_prefs"), // EncryptedSharedPreferences (ключи/конфиг)
            File(context.filesDir, "runtimes"),    // паки
        ) else emptyList()
        return SandboxFs(roots, unrestricted = unrestricted, deniedPaths = denied)
    }

    /** Каталог ВСЕХ инструментов (для экрана настроек — без фильтров вкл/выкл). */
    fun toolCatalog(context: Context): List<AgentTool> = rawTools(context, includeFiles = true)

    /** Набор инструментов с учётом настроек (доступ к файлам + отключённые пользователем). */
    fun allTools(context: Context): List<AgentTool> {
        // tooltext: каталог внешних .txt-оверрайдов описаний тулов (Android/data/<пакет>/files/tooltext/).
        // Ставим один раз; далее ToolText кэширует. Доступен по USB/файловому менеджеру для тюнинга.
        if (ru.aiagent.core.agent.ToolText.dirPath == null)
            ru.aiagent.core.agent.ToolText.dirPath = context.getExternalFilesDir("tooltext")?.absolutePath
        val fileAccess = AppSettings.fileAccessEnabled(context)
        val disabled = AppSettings.disabledTools(context)
        return rawTools(context, includeFiles = fileAccess).filterNot { it.id in disabled }
    }

    private fun rawTools(context: Context, includeFiles: Boolean): List<AgentTool> {
        // Подключаем OCR-движок (Tesseract) к экстрактору документов один раз: тогда
        // read_document/disk_read/extract_text/почтовые вложения умеют распознавать сканы и фото.
        if (ru.aiagent.toolsdocs.OcrHook.provider == null) {
            ru.aiagent.toolsdocs.OcrHook.provider = { ctx, f -> ru.aiagent.app.ocr.OcrEngine.extract(ctx, f) }
        }
        val fs = sandbox(context)
        val resolve: (String) -> File? = { p -> fs.resolve(p) }
        // ВАЖНО: порядок = приоритет. ToolGating.maxTools отсекает хвост для слабых
        // моделей (Gemma E2B видит первые 8, 0.5-1B — первые 3 SAFE). Поэтому впереди —
        // самое общеполезное, а нишевое/кодерское (java, зрение, интенты) — в конце.
        val tools = mutableListOf<AgentTool>()
        val ws = File(context.filesDir, "workspace")
        tools += ListToolsTool { tools.toList() }
        tools += fileTools(fs)                       // read/list/create/delete файлов (1-4)
        tools += documentTools(context, resolve)     // read_document, create_spreadsheet (5-6)
        tools += ru.aiagent.app.office.ExtractTextTool(context, resolve) // текст из фото/сканов (OCR)
        tools += ru.aiagent.app.rag.SearchKnowledgeTool(context) // 7 — поиск по базе знаний (RAG)
        tools += ru.aiagent.app.integrations.WebSearchTool(context) // 8 — поиск в интернете (сервер→DDG)
        tools += ru.aiagent.app.office.CalculateTool()          // 9
        tools += ru.aiagent.app.utils.FormatDataTool()          // проверка/форматирование JSON (база для API)
        tools += ru.aiagent.app.utils.TranslateTool(context)    // перевод на устройстве (ML Kit, офлайн)
        tools += ru.aiagent.app.utils.GeoLocationTool(context)  // текущее местоположение (GPS/сеть)
        tools += ru.aiagent.app.utils.HttpRequestTool()         // HTTP к REST API (GET/POST/…)
        tools += ru.aiagent.app.utils.CreateArtifactTool()  // create_artifact — HTML/JS страницы
        tools += ru.aiagent.app.utils.DownloadFileTool(resolve) // скачать файл по URL в рабочую папку
        tools += ru.aiagent.app.utils.CreateChartTool(resolve)  // диаграмма (bar/line/pie) картинкой в ответ
        tools += ru.aiagent.app.utils.GenerateQrTool(resolve)   // QR-код картинкой в ответ
        tools += ru.aiagent.app.utils.RenderSvgTool(resolve)    // векторный рисунок (SVG→PNG) в ответ
        tools += ru.aiagent.app.utils.ScanQrTool(resolve)       // распознать QR/штрихкод с фото
        tools += ru.aiagent.app.utils.ClipboardTool(context)    // буфер обмена
        tools += ru.aiagent.app.utils.ZipFilesTool(resolve)     // упаковать в zip
        tools += ru.aiagent.app.utils.UnzipFileTool(resolve)    // распаковать zip
        tools += ru.aiagent.app.utils.RememberTool(context)     // 10 — личная долговременная память ИИ
        tools += ru.aiagent.app.utils.RecallTool(context)
        tools += ru.aiagent.app.utils.ForgetTool(context)
        tools += ru.aiagent.app.utils.skillTools(context)       // НАВЫКИ (самообучение, Hermes): save_skill/list_skills/forget_skill
        tools += ru.aiagent.app.utils.SearchConversationsTool(context) // поиск по прошлым диалогам (Hermes), §7-локально
        // Рабочая память задачи + план — для ДОЛГИХ многошаговых задач (переживают обрезку истории чата).
        tools += ru.aiagent.app.utils.PlanUpdateTool(context)   // план-чеклист долгой задачи
        tools += ru.aiagent.app.utils.PlanShowTool(context)
        tools += ru.aiagent.app.utils.SessionNoteTool(context)  // накопитель промежуточных результатов (кап 10 МБ)
        tools += ru.aiagent.app.utils.SessionRecallTool(context)
        // Дальше — для сильных/облачных моделей (STANDARD не увидит из-за лимита 8):
        tools += ru.aiagent.app.rag.IndexDocumentTool(context, resolve) // добавить файл в базу
        tools += ru.aiagent.app.utils.DateTimeTool()
        tools += ru.aiagent.app.utils.SequentialThinkingTool() // «блокнот рассуждений» (идея MCP) — структурное мышление, §7-безопасно
        tools += ru.aiagent.app.office.CreatePresentationTool(context, ws.absolutePath)
        tools += ru.aiagent.app.office.CreateWordTool(context, ws.absolutePath)
        tools += ru.aiagent.app.utils.SearchFilesTool(ws)
        tools += ru.aiagent.app.utils.UnitConvertTool()
        if (ru.aiagent.app.code.CodeModules.active(context, "python"))
            tools += ru.aiagent.app.python.RunPythonTool(context, ws.absolutePath) // pandas/matplotlib
        tools += ru.aiagent.app.utils.TextTool()
        tools += ru.aiagent.app.utils.DeviceStatusTool(context)
        tools += ru.aiagent.app.utils.AddCalendarEventTool(context)
        tools += ru.aiagent.app.utils.SetAlarmTool(context)
        tools += ru.aiagent.app.utils.OpenUrlTool(context)
        tools += ru.aiagent.app.utils.ShareTextTool(context)
        tools += ru.aiagent.app.utils.MakeCallTool(context)             // make_call (звонок)
        // Пачка новых инструментов (аудит инструментов): utils/крипто/shell, PDF+картинки,
        // сеть/архивы, аудио (TTS), устройство (контакты/скриншот/уведомления).
        tools += ru.aiagent.app.utils.extraUtilTools(resolve)   // uuid/hash/пароль/шифр/diff/mkdir/validate/run_shell
        tools += ru.aiagent.app.utils.mediaTools(resolve)       // create_pdf, edit_image
        tools += ru.aiagent.app.utils.pdfOcrTool(context, resolve) // pdf_ocr (searchable-PDF: OCR-слой)
        tools += ru.aiagent.app.utils.netArchiveTools(resolve)  // dns_lookup/whois/web_socket/extract_archive
        tools += ru.aiagent.app.utils.audioTools(context, resolve)      // text_to_speech (+ transcribe заглушка)
        tools += ru.aiagent.app.utils.recordAudioTool(context, resolve) // record_audio (микрофон → WAV)
        tools += ru.aiagent.app.utils.deviceExtraTools(context, resolve) // contacts/notification/screenshot/vibrate/torch/wifi
        tools += ru.aiagent.app.utils.androidIntentTools(context, resolve) // intent_send (звонки/карты/deep-link), share_file
        // Вторая пачка по рекомендациям телефонной ИИшки: инфосервисы, локальные утилиты, config-хранилище,
        // сетевая диагностика, автоматизация (cron/webhook).
        tools += ru.aiagent.app.utils.infoServiceTools()               // wikipedia/weather/currency/news
        tools += ru.aiagent.app.utils.textUtilTools()                  // color_convert/regex_test/encrypt_text/markdown_to_html
        tools += ru.aiagent.app.utils.configTemplateTools(context)     // config_get/set (EncryptedSharedPreferences), template_render (Jinja2)
        tools += ru.aiagent.app.utils.netDiagTools(context, resolve)   // ping/traceroute/file_watch/qr_from_screen
        tools += ru.aiagent.app.utils.automationTools(context)         // cron_schedule, webhook_server
        tools += ru.aiagent.app.utils.nfcTools(context)                // nfc_read/nfc_write (foreground reader-mode)
        tools += ru.aiagent.app.utils.filePickerTools(context, resolve) // file_picker (SAF-выбор файла в песочницу)
        tools += ru.aiagent.app.utils.cameraTools(context, resolve)     // camera_capture (фото системной камерой)
        // Пачка по рекомендации внешнего ИИ: связь (SMS/контакты/контролы), продуктивность (задачи/RSS/
        // barcode/regex/export), документы+карты+перевод+аудио.
        tools += ru.aiagent.app.utils.phoneCommsTools(context)          // sms_send/read, contacts_create, set_volume/brightness, dnd_mode
        tools += ru.aiagent.app.utils.productivityTools(context, resolve) // task_*, rss_read, barcode_generate, regex_replace, export_memory
        tools += ru.aiagent.app.utils.docMapTools(context, resolve)     // pdf_merge/split/delete_pages, maps_route, translate_online, audio_normalize
        tools += ru.aiagent.app.utils.webRenderTools(context)           // web_render (скрейпинг SPA через WebView)
        tools += ru.aiagent.app.utils.pdfTablesTools(context, resolve)  // pdf_tables (таблицы PDF → CSV/JSON, pdfplumber)
        tools += ru.aiagent.app.utils.generateImageTools(context, resolve) // generate_image (RouterAI image-модели, по тумблеру)
        tools += ru.aiagent.app.utils.textAiTools(context)              // summarize, spell_check (локальная модель, офлайн)
        tools += ru.aiagent.app.utils.wallpaperPdfSignTools(context, resolve) // set_wallpaper, pdf_sign
        tools += ru.aiagent.app.ext.extensionTools(context, ws.absolutePath) // сторонние расширения (платформа, скриптовый тир)
        // Языковые раннеры — только для АКТИВНЫХ модулей (включён + доступен): выключенные языки не
        // «топят» выбор инструмента у модели, а скачиваемые (C/TypeScript) доступны лишь после установки.
        val cm = ru.aiagent.app.code.CodeModules
        if (cm.active(context, "java")) tools += ru.aiagent.app.code.RunJavaTool(context) // BeanShell (пак)
        if (cm.active(context, "javascript")) {
            tools += ru.aiagent.app.code.RunJavaScriptTool() // JS через Rhino
            tools += ru.aiagent.app.code.ReplEvalTool()      // REPL-инспектор (сохраняющийся JS-контекст)
        }
        if (cm.active(context, "typescript")) tools += ru.aiagent.app.code.RunTypeScriptTool(context)
        if (cm.active(context, "lua")) tools += ru.aiagent.app.code.RunLuaTool()      // LuaJ
        if (cm.active(context, "sql")) tools += ru.aiagent.app.code.RunSqlTool(context) // SQLite (память или файл в песочнице)
        tools += ru.aiagent.app.code.ReadLogsTool()      // дебаг: логи приложения (logcat) — не язык
        if (cm.active(context, "c")) tools += ru.aiagent.app.code.RunCTool(context)   // C через picoc
        if (cm.active(context, "ffmpeg")) tools += ru.aiagent.app.code.RunFfmpegTool(context, ws.absolutePath) // FFmpeg (скачиваемый пак)
        if (cm.active(context, "kotlin")) tools += ru.aiagent.app.code.RunKotlinTool(context) // kotlinc→DEX
        tools += DescribeImageTool(context, resolve) // зрение (SmolVLM + mmproj)
        // Интеграции (S10) — только если пользователь подключил аккаунт.
        if (ru.aiagent.app.integrations.GitHubAuth.isConnected(context)) {
            tools += ru.aiagent.app.integrations.gitHubTools(context)
            tools += ru.aiagent.app.integrations.gitTools(context, resolve) // git clone/status/commit/push (JGit)
        }
        // SSH — только если есть сохранённое подключение (иначе тулзы бесполезны, лишь жгут промпт).
        if (ru.aiagent.app.SshStore.hasAny(context))
            tools += ru.aiagent.app.integrations.sshTools(context) // ssh_run/ssh_list (JSch из пака `ssh`)
        if (ru.aiagent.app.integrations.MailAuth.isConnected(context))
            tools += ru.aiagent.app.integrations.emailTools(context) // почта локально (search_email/read_email)
        if (ru.aiagent.app.integrations.DiskAuth.isConnected(context))
            tools += ru.aiagent.app.integrations.diskTools(context, resolve) // Яндекс.Диск (list/read/upload)
        if (ru.aiagent.app.integrations.CalendarClient.canRead(context))
            tools += ru.aiagent.app.integrations.calendarTools(context) // календарь локально
        // Внешние облака/мессенджеры — только при подключении (OAuth через сервер / bot-токен).
        if (ru.aiagent.app.integrations.DriveAuth.isConnected(context))
            tools += ru.aiagent.app.integrations.googleDriveTools(context, resolve) // Google Диск
        if (ru.aiagent.app.integrations.DropboxAuth.isConnected(context))
            tools += ru.aiagent.app.integrations.dropboxTools(context, resolve)      // Dropbox
        if (ru.aiagent.app.integrations.OneDriveAuth.isConnected(context))
            tools += ru.aiagent.app.integrations.oneDriveTools(context, resolve)     // OneDrive (MS Graph)
        if (ru.aiagent.app.integrations.DiscordAuth.isConnected(context))
            tools += ru.aiagent.app.integrations.discordTools(context)               // Discord (bot-токен)
        if (ru.aiagent.app.integrations.TelegramAuth.isConnected(context))
            tools += ru.aiagent.app.integrations.telegramTools(context)              // Telegram (bot-токен, Bot API)
        // Telegram юзер-инструменты (tg_login/tg_chats/...) вырезаны — автоматизация Telegram через бота
        if (ru.aiagent.app.integrations.mcp.McpAuth.hasServers(context))
            tools += ru.aiagent.app.integrations.mcp.mcpTools(context)               // внешние MCP-серверы (удалённые тулы)
        if (PhoneControlService.isEnabled) tools += phoneControlTools(context) // только при включённой службе
        // Тумблер «Доступ агента к файлам»: при выкл. убираем ВСЕ файловые инструменты
        // (те, что объявили usesFiles) — единый механизм, без хардкод-списка id.
        return if (includeFiles) tools else tools.filterNot { it.usesFiles }
    }

    /**
     * Контекст о подключённых аккаунтах для системного промпта — чтобы агент НЕ переспрашивал
     * логин/почту, а сразу пользовался инструментами (частый провал: «дайте ваш GitHub username»).
     */
    // forCloud=true → облачная модель: НЕ раскрываем PII (адрес почты) и НЕ отправляем личную
    // долговременную память в облако (§7 — личные данные не покидают устройство без явного действия).
    fun userContext(context: Context, forCloud: Boolean = false): String {
        val parts = mutableListOf<String>()
        ru.aiagent.app.integrations.GitHubAuth.login(context)?.let {
            val who = if (forCloud) "" else " (логин: $it)"
            parts += "GitHub подключён$who. Для «мои репозитории/issues» сразу вызывай github_repos/github_issues — ник НЕ спрашивай."
        }
        ru.aiagent.app.integrations.MailAuth.account(context)?.email?.let { email ->
            val archive = if (AppSettings.emailIndexEnabled(context))
                " Для поиска по СОДЕРЖИМОМУ вложений (сканы/документы) — search_mail_archive (быстрый фоновый индекс)." else ""
            // В облако адрес НЕ отдаём (PII §7): инструменты знают его сами; облачной модели он не нужен.
            val addr = if (forCloud) "" else " ($email)"
            val noAsk = if (forCloud) "адрес отправителя НЕ спрашивай — он известен инструментам."
            else "адрес отправителя НЕ спрашивай (это $email)."
            parts += "Почта подключена$addr. Чтение — search_email/read_email, отправка — send_email; " +
                "$noAsk Найти документ ВНУТРИ сканов/PDF-вложений — " +
                "search_email с deep:true, затем read_email_attachment по нужному файлу.$archive"
        }
        if (ru.aiagent.app.integrations.CalendarClient.canRead(context)) {
            parts += "Календарь доступен. Для событий вызывай calendar_upcoming/search_calendar."
        }
        if (ru.aiagent.app.integrations.DiskAuth.isConnected(context)) {
            parts += "Яндекс Диск подключён. Список — disk_list, чтение файла — disk_read, загрузка — disk_upload."
        }
        val connected = if (parts.isEmpty()) "" else "Что уже подключено у пользователя:\n- " + parts.joinToString("\n- ")
        // Долговременная память ИИ (remember) — авто-подставляем И локальной, И облачной модели.
        // РЕШЕНИЕ ВЛАДЕЛЬЦА (снятие §7 для памяти): накопленную моделью память видит и облако. При этом
        // ядро §7 нетронуто — личные ФАЙЛЫ/RAG/почта/календарь/диск в облако НЕ уходят (их инструменты
        // остаются privateData). neutralize против персистентной indirect-инъекции (память = авторитетный
        // системный контекст). Хочешь вернуть приватность памяти — снова gate на !forCloud здесь и ниже.
        val memBlock = run {
            val memory = ru.aiagent.core.agent.ToolProtocol.neutralize(ru.aiagent.app.utils.MemoryStore.read(context))
            if (memory.isBlank()) "" else
                "Твоя долговременная память (запомнено ранее; учитывай, обновляй через remember):\n$memory"
        }
        // Текущий ПЛАН задачи авто-подставляем в контекст — и локально, и в облако (решение владельца,
        // см. memBlock). Кап 3000 символов (сам файл до 5 МБ — берём голову).
        val planBlock = run {
            val plan = ru.aiagent.core.agent.ToolProtocol.neutralize(ru.aiagent.app.utils.SessionStore.getPlan(context))
            when {
                plan.isBlank() -> ""
                plan.length <= 3000 -> "ТЕКУЩИЙ ПЛАН ЗАДАЧИ (веди его через plan_update, отмечай [x]):\n$plan"
                else -> "ТЕКУЩИЙ ПЛАН ЗАДАЧИ (начало; полностью — plan_show):\n${plan.take(3000)}\n…"
            }
        }
        return listOf(planBlock, memBlock, connected).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun withContextPrefix(context: Context, recentContext: String, userMessage: String, forCloud: Boolean = false): String {
        val known = userContext(context, forCloud)
        // НАВЫКИ (самообучение, идея Hermes): подставляем РЕЛЕВАНТНЫЕ запросу навыки (не все — по ключевым
        // словам), чтобы модель узнала свой прошлый приём. neutralize — как у памяти (навык авторский, но мог
        // вобрать инъекцию из данных). Инжектим и локально, и в облако (решение владельца, как memBlock).
        val skillBlock = run {
            val skills = ru.aiagent.app.utils.SkillStore.relevant(context, userMessage, 3)
            if (skills.isEmpty()) "" else ru.aiagent.core.agent.ToolProtocol.neutralize(
                "Твои НАВЫКИ под этот запрос (переиспользуй; новые сохраняй через save_skill):\n" +
                    // Кап на инъекцию: whenUse/steps обрезаем, чтобы 3 навыка не раздули промпт (детали — list_skills).
                    skills.joinToString("\n") { "• ${it.name} — когда: ${it.whenUse.take(120)}\n  шаги: ${it.steps.take(260)}" },
            )
        }
        return buildString {
            if (known.isNotBlank()) append(known).append("\n\n")
            if (skillBlock.isNotBlank()) append(skillBlock).append("\n\n")
            if (recentContext.isNotBlank()) append("Контекст диалога:\n").append(recentContext).append("\n\n")
            append("Запрос: ").append(userMessage)
        }
    }

    /** Инструменты для текущей модели: фильтр ТОЛЬКО по опасности (исполнимый набор). */
    fun toolsFor(context: Context): List<AgentTool> {
        val cls: ModelClass = ToolGating.classify(ChatEngine.currentBenchScore(context))
        return ToolGating.dangerAllowed(cls, allTools(context))
    }

    /**
     * Релевантный отбор (tool-RAG): из [tools] выбираем топ-[cap] по близости к [query]
     * эмбеддером bge-m3 — чтобы не топить промпт всеми ~40 инструментами. При недоступном
     * эмбеддере — откат на порядок-приоритет (первые [cap]). Остальное модель добирает через help.
     */
    internal suspend fun selectRelevant(
        context: Context, query: String, tools: List<AgentTool>, cap: Int,
    ): List<AgentTool> {
        if (tools.size <= cap) return tools
        val docs = tools.map { "${it.id}: ${it.advertisedDescription()}" }
        // rankCached: эмбеддинги описаний кэшируются (статичны) → за ход эмбеддится только запрос.
        val idx = ru.aiagent.app.rag.RagEngine.rankCached(context, query, docs, cap)
        return if (idx.isNotEmpty()) idx.map { tools[it] } else tools.take(cap)
    }

    // ── Гибрид tool-RAG: ЯДРО (всегда) + релевантный топ (RAG). Меньше нужных = дешевле и точнее (бенч). ──

    /** Ядро — всегда в наборе, не ранжируется. Адаптивно: слабой модели ещё уже (она тонет в тулах). */
    private fun coreToolIds(cls: ru.aiagent.core.agent.ModelClass): Set<String> = when (cls) {
        ru.aiagent.core.agent.ModelClass.WEAK -> setOf("read_file", "create_document", "run_shell", "calculate", "help")
        else -> setOf("read_file", "list_files", "create_document", "edit_file", "delete_file",
            "run_shell", "run_python", "calculate", "help")
    }

    /** Сколько релевантных добирать RAG сверх ядра. */
    private fun raggedCount(cls: ru.aiagent.core.agent.ModelClass): Int = when (cls) {
        ru.aiagent.core.agent.ModelClass.WEAK -> 3
        ru.aiagent.core.agent.ModelClass.STANDARD -> 5
        else -> 7
    }

    /** Набор для показа модели = ядро (пересечённое с исполнимым) ∪ RAG-топ по запросу. */
    private suspend fun advertisedIdsFor(
        context: Context, cls: ru.aiagent.core.agent.ModelClass, executable: List<AgentTool>, query: String,
    ): Set<String> {
        val core = coreToolIds(cls).filter { id -> executable.any { it.id == id } }.toSet()
        val rest = executable.filterNot { it.id in core }
        val ragged = selectRelevant(context, query, rest, raggedCount(cls)).map { it.id }
        return core + ragged
    }

    /** Текстовая ветка: показываемый набор (ядро + RAG-топ + find_tool + help) и полный execMap.
     * find_tool здесь — точечный help (текстовый протокол умеет вызвать любой исполнимый тул). */
    private suspend fun buildTextTools(
        context: Context, cls: ru.aiagent.core.agent.ModelClass, executable: List<AgentTool>,
        query: String, help: AgentTool,
    ): Pair<List<AgentTool>, Map<String, AgentTool>> {
        val findTool = ru.aiagent.core.agent.FindToolTool({ executable }) { q, docs, k ->
            ru.aiagent.app.rag.RagEngine.rankCached(context, q, docs, k)
        }
        val core = coreToolIds(cls).let { ids -> executable.filter { it.id in ids } }
        val rest = executable.filterNot { t -> core.any { it.id == t.id } }
        val ragged = selectRelevant(context, query, rest, raggedCount(cls))
        val advertised = core + ragged + findTool + help
        val execMap = (executable + help + findTool).associateBy { it.id }
        return advertised to execMap
    }

    private val SUBAGENT_IDS = setOf("spawn_agent", "parallel_agents", "verified_findings")

/**
     * Фабрика ДОЧЕРНЕГО облачного агента для субагент-тулзов (spawn/parallel/verified), глубина 1.
     * Набор ребёнка — облачный, БЕЗ субагентов и БЕЗ опасных (ребёнок бежит в bypass — иначе исполнил бы
     * опасное без подтверждения; §7-приватные тоже исключены). Тот же текстовый цикл, что у родителя.
     *
     * Профиль субагента ([SubAgentProfiles], OpenCode explore/audit/fix) суживает набор инструментов
     * до whitelist профиля — иначе у ребёнка тот же широкий набор, что у родителя. profile==null — весь
     * безопасный набор (как раньше).
     */
    private fun childCloudFactory(
        context: Context,
        cloudModel: String,
        profile: Pair<Set<String>, String>?,
        task: String,
    ): Agent = Agent { _, childMode ->
        flow {
            var childTools = allTools(context)
                .filterNot { it.privateData }
                .filterNot { it.danger == ru.aiagent.core.agent.DangerLevel.DANGEROUS }
                .filterNot { it.id in SUBAGENT_IDS }
            if (profile != null) {
                val allow = profile.first
                childTools = childTools.filter { it.id in allow }
            }
            val help = ListToolsTool { childTools }
            val (advertised, execMap) = buildTextTools(context, ru.aiagent.core.agent.ModelClass.STRONG, childTools, task, help)
            val generator = ChatGenerator { prompt ->
                val sb = StringBuilder()
                ru.aiagent.app.cloud.CloudEngine.reply(context, prompt, model = cloudModel, reasoningExclude = false)
                    .collect { sb.append(it) }
                sb.toString()
            }
            val loop = AgentLoop(generator, execMap, ConfirmationHandler { true }, advertised = advertised)
            emitAll(loop.run(task, childMode))
        }
    }

    /**
     * Запустить агентный цикл на сообщение пользователя (с кратким контекстом истории).
     * Модель ВИДИТ релевантный топ инструментов + help; ИСПОЛНИТЬ может любой из исполнимого
     * набора (help раскрывает полный список).
     * @param confirm вызывается для DANGEROUS/IMPORTANT операций (кроме bypass) — из UI.
     */
    fun run(
        context: Context,
        userMessage: String,
        recentContext: String,
        mode: AutonomyMode,
        confirm: suspend (String) -> Boolean,
    ): Flow<AgentEvent> = flow {
        // ПК-режим: запрос уходит на десктоп через E2E-релэй
        if (AppSettings.remoteBackendEnabled(context)) {
            emit(AgentEvent.ToolCall("remote_pc", "Отправляю на ПК..."))
            val client = ru.aiagent.app.remote.RemoteAiClient(context)
            val response = client.ask(userMessage)
            if (response.isError) emit(AgentEvent.Answer("ПК: ${response.text}"))
            else emit(AgentEvent.Answer(response.text))
            return@flow
        }
        // FEAT-CHECKPOINTS: базовый снимок ДО хода, снимок правок ПОСЛЕ (в finally — в т.ч. при отмене).
        ru.aiagent.app.history.CheckpointStore.ensureBaseline(context)
        try {
            val cls = ToolGating.classify(ChatEngine.currentBenchScore(context))
            val executable = toolsFor(context)
            val help = ListToolsTool { executable }
            val query = listOf(recentContext, userMessage).filter { it.isNotBlank() }.joinToString(" ")
            val (advertised, execMap) = buildTextTools(context, cls, executable, query, help)
            val blocked = AppSettings.blockedTools(context)
            val generator = ChatGenerator { prompt -> ChatEngine.agentGenerate(context, prompt) }
            val loop = AgentLoop(generator, execMap, ConfirmationHandler { confirm(it) }, advertised = advertised, blockedTools = blocked)
            emitAll(loop.run(withContextPrefix(context, recentContext, userMessage), mode).trackUsage(context, userMessage))
        } finally {
            // NonCancellable: при отмене хода корутина уже cancelled, и обычный withContext(IO) внутри
            // snapshot бросил бы CancellationException ДО тела — снимок правок оборванного хода потерялся бы.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                ru.aiagent.app.history.CheckpointStore.snapshot(context, userMessage)
            }
        }
    }

    /** Аналитика задача↔инструмент (§7-safe): собираем имена вызванных тулзов за ход и по УСПЕШНОМУ
     * завершению копим+шлём анонимные агрегаты. Ошибка/отмена (cause!=null) — не пишем. */
    private fun Flow<AgentEvent>.trackUsage(context: Context, userMessage: String): Flow<AgentEvent> {
        val tools = java.util.LinkedHashSet<String>()
        return onEach { if (it is AgentEvent.ToolCall) tools.add(it.toolId) }
            .onCompletion { cause -> if (cause == null) UsageAnalytics.recordAndSend(context, userMessage, tools) }
    }

    /**
     * Агентный цикл на ОБЛАЧНОЙ модели (когда выбрана облачная модель + режим «Агент»).
     * Генератор ходит в облако (RouterAI) вместо локальной модели, поэтому облачная модель
     * тоже получает системный промпт и инструменты. Приватные инструменты (почта/календарь)
     * ИСКЛЮЧЕНЫ — их результат ушёл бы в облако, нарушив §7. Файлы/код/документы допустимы:
     * пользователь сам выбрал облако (явное действие). Облако = STRONG: показываем топ + help.
     */
    fun runCloud(
        context: Context,
        cloudModel: String,
        userMessage: String,
        recentContext: String,
        mode: AutonomyMode,
        confirm: suspend (String) -> Boolean,
    ): Flow<AgentEvent> = flow {
        ru.aiagent.app.history.CheckpointStore.ensureBaseline(context)
        try {
            // L8 для облака (§0/§7): сужаем набор по силе облачной модели, а не даём всем полный набор как
            // STRONG. Слабая/дешёвая модель → без DANGEROUS (в bypass иначе исполнила бы опасное без подтверждения).
            val cloudCls = ru.aiagent.app.cloud.cloudModelClass(cloudModel)
            // Субагенты (только облако): делегирование/fan-out/verify. childFactory собирает ребёнка без
            // субагентов и опасных (глубина 1, анти-рекурсия), расход — через SpendGate. DANGEROUS → сборка
            // dangerAllowed отдаёт их лишь сильным облачным моделям (слабым не показываются).
            val childFactory: (Pair<Set<String>, String>?, String) -> Agent =
                { profile, task -> childCloudFactory(context, cloudModel, profile, task) }
            val spend = ru.aiagent.core.agent.SpendGate { est -> confirm("Субагенты/проверка: $est. Запустить?") }
            val subTools = listOf(
                ru.aiagent.core.agent.SpawnAgentTool(childFactory, spend),
                ru.aiagent.core.agent.ParallelAgentsTool(childFactory, spend),
                ru.aiagent.core.agent.VerifiedFindingsTool(childFactory, spend),
            )
            val executable0 = ToolGating.dangerAllowed(cloudCls, allTools(context) + subTools)
            val blocked = AppSettings.blockedTools(context)
            // code-mode (только сильная облачная модель, DANGEROUS): один JS-скрипт оркестрирует инструменты
            // через тот же AgentLoopSupport.executeGated, что и обе петли — те же барьеры, не обход. Доступный
            // скрипту набор — без самого execute, code-раннеров (реентрантность JsEngine.lock) и субагентов.
            val cmExclude = setOf("execute", "run_javascript", "run_typescript", "repl_eval",
                "spawn_agent", "parallel_agents", "verified_findings")
            val cmMap = executable0.filterNot { it.id in cmExclude }.associateBy { it.id }
            val codeMode = ru.aiagent.app.code.CodeModeTool(cmMap.keys.toSet()) { name, a ->
                val t = cmMap[name]
                if (t == null) ToolResult.Failure("нет инструмента $name")
                else ru.aiagent.core.agent.AgentLoopSupport.executeGated(t, a, mode, blocked, 60_000L) { p -> confirm(p) }
            }
            val executable = if (cloudCls == ru.aiagent.core.agent.ModelClass.STRONG) executable0 + codeMode else executable0
            // Родной формат инструментов ПОД КОНКРЕТНУЮ модель (по каталогу RouterAI):
            //  - умеет нативный function-calling → CloudFunctionAgent (RouterAI транслирует в
            //    провайдерский формат), ничего не утекает в текст;
            //  - не умеет → текстовый протокол в диалекте семейства (Hermes/Mistral/Llama/generic).
            val fmt = ru.aiagent.app.cloud.CloudToolFormat.of(context, cloudModel)
            // «Мышление» включаем, только если И модель его умеет (по каталогу RouterAI: supported_parameters),
            // И пользователь включил тумблер. Раньше reasoning форсился для ВСЕХ умеющих моделей (агрессивный
            // фолбек: лишние токены/латентность на «думанье», которого юзер не просил). Тумблер по умолчанию выкл.
            val wantReasoning = fmt.reasoning && AppSettings.reasoning(context)
            if (fmt.native && ru.aiagent.app.cloud.CloudFunctionAgent.endpoint(context) != null) {
                // Облачные модели с native FC получают ВСЕ инструменты
                val allExec = rawTools(context, includeFiles = true) + subTools
                val findTool = ru.aiagent.core.agent.FindToolTool({ allExec }) { q, docs, k ->
                    ru.aiagent.app.rag.RagEngine.rankCached(context, q, docs, k)
                }
                try {
                    emitAll(ru.aiagent.app.cloud.CloudFunctionAgent.run(
                        context, cloudModel, userMessage, recentContext, allExec + findTool, mode, blocked, { confirm(it) },
                        reasoning = wantReasoning, structured = fmt.structured,
                        reasoningEffort = fmt.reasoningEffort, contextLength = fmt.contextLength,
                        advertisedIds = null,
                    ))
                    return@flow
                } catch (_: ru.aiagent.app.cloud.ToolsUnsupportedException) {
                    // каталог соврал/модель отвергла tools → откат на текстовый диалект ниже
                }
            }
            val help = ListToolsTool { executable }
            val query = listOf(recentContext, userMessage).filter { it.isNotBlank() }.joinToString(" ")
            val (advertised, execMap) = buildTextTools(context, cloudCls, executable, query, help)
            val generator = ChatGenerator { prompt ->
                val sb = StringBuilder()
                // Думающая модель без нативного FC → на текстовой ветке тоже включаем reasoning с
                // exclude, чтобы мысли не утекли в ответ (§7); иначе полагались бы только на зачистку тегов.
                // Тоже по тумблеру + возможности модели (wantReasoning), а не форсом на всех умеющих.
                ru.aiagent.app.cloud.CloudEngine.reply(context, prompt, model = cloudModel, reasoningExclude = wantReasoning)
                    .collect { sb.append(it) }
                sb.toString()
            }
            val loop = AgentLoop(
                generator, execMap, ConfirmationHandler { confirm(it) }, advertised = advertised, blockedTools = blocked,
                maxSteps = ru.aiagent.app.cloud.CloudFunctionAgent.stepBudget(cloudModel), // адаптивно по размеру модели
                dialect = fmt.dialect, // текстовый вызов в родном формате семейства модели
            )
            // forCloud=true: облачной модели НЕ отдаём PII (адрес почты) и личную память (§7).
            emitAll(loop.run(withContextPrefix(context, recentContext, userMessage, forCloud = true), mode).trackUsage(context, userMessage))
        } finally {
            // NonCancellable: при отмене хода корутина уже cancelled, и обычный withContext(IO) внутри
            // snapshot бросил бы CancellationException ДО тела — снимок правок оборванного хода потерялся бы.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                ru.aiagent.app.history.CheckpointStore.snapshot(context, userMessage)
            }
        }
    }
}
