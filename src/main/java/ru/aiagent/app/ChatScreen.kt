package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import ru.aiagent.app.ui.ThinkingDots
import ru.aiagent.app.ui.TypingCaret
import ru.aiagent.app.ui.messageEnter
import ru.aiagent.app.ui.pressable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import ru.aiagent.core.agent.AgentEvent
import ru.aiagent.core.inference.ChatMessage
import ru.aiagent.core.inference.ChatRole

// Вычистка спец-токенов чат-шаблонов, если модель выдала их как текст (в т.ч.
// искажённые варианты типа <end_of_of_turn>). Инвариант ТЗ §7 — не течём в ответ.
// Только реальные управляющие токены чат-шаблонов (не трогаем легитимные <s>/<system>/<user>
// в коде/HTML/тексте ответа — их вырезание само было бы утечкой из §7 наоборот).
private val SPECIAL_TOKEN_RE = Regex(
    "<\\|?/?\\s*(end_of[a-z_]*turn|start_of_turn|im_start|im_end|eos|bos|unk|pad|end_of_text|\\|endoftext\\|)\\s*\\|?/?>",
    RegexOption.IGNORE_CASE,
)

private fun stripSpecialTokens(s: String): String = SPECIAL_TOKEN_RE.replace(s, "").trimEnd()

/** Пути картинок из JSON-результата инструмента: "images":["/a.png","/b.png"]. */
private fun extractImagePaths(json: String): List<String> {
    val arr = Regex("\"images\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1)
        ?: return emptyList()
    return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arr).map {
        it.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }.filter { it.isNotBlank() }.toList()
}

// Инструкция режима «Размышление»: модель выводит ход мысли в <think>…</think>,
// затем итоговый ответ. UI показывает размышление отдельным свёрнутым блоком.
private const val REASONING_PREFIX =
    "Сначала подробно порассуждай пошагово внутри тегов <think> и </think>, " +
        "затем дай краткий итоговый ответ обычным текстом.\n\nВопрос: "

/**
 * Спец-режимы ответа (выбираются в bottom-sheet по кнопке «+»). Каждый добавляет инструкцию в
 * системный контекст, меняя формат/поведение ответа. Взаимоисключающие (один за раз).
 */
enum class SpecMode(val title: String) {
    NONE(""),
    RESEARCH("Глубокое исследование"),
    ARTIFACT("Артефакты"),
    WEBDEV("Веб-разработка"),
}

/** Доп-инструкция режима в системный промпт (пусто для NONE). */
private fun specInstruction(m: SpecMode): String = when (m) {
    SpecMode.NONE -> ""
    SpecMode.RESEARCH ->
        "Режим ГЛУБОКОГО ИССЛЕДОВАНИЯ: разбей вопрос на под-вопросы, собери факты из нескольких " +
            "источников (web_search, база знаний, файлы), сопоставь их и дай развёрнутый ответ со ссылками " +
            "на источники. Не ограничивайся первым результатом — перепроверь."
    SpecMode.ARTIFACT ->
        "Режим АРТЕФАКТ: выдай результат как ЗАКОНЧЕННЫЙ самодостаточный артефакт — полный HTML-файл " +
            "(inline CSS/JS), либо цельный код/документ, готовый к использованию БЕЗ доработок. Оберни в ```."
    SpecMode.WEBDEV ->
        "Режим ВЕБ-РАЗРАБОТКА: ты — веб-разработчик. Сгенерируй ПОЛНУЮ веб-страницу — HTML+CSS+JS в " +
            "ОДНОМ файле, современный адаптивный дизайн, рабочую и готовую к открытию в браузере. Оберни в ```html."
}

// Палитра редизайна — из ru.aiagent.app.ui.P. ГЕТТЕРЫ (не val!): P.* реактивны на смену
// темы/акцента, поэтому читаем их на каждой рекомпозиции, а не фиксируем при загрузке класса.
private val Cream: Color get() = ru.aiagent.app.ui.P.Bg
private val Ink: Color get() = ru.aiagent.app.ui.P.Text
private val InkSoft: Color get() = ru.aiagent.app.ui.P.TextSoft
private val Coral: Color get() = ru.aiagent.app.ui.P.Accent
private val UserBubble: Color get() = ru.aiagent.app.ui.P.Surface

/** kind: "user" | "assistant" | "tool" (служебная строка вызова инструмента). */
private data class UiMessage(val kind: String, var text: String)

/**
 * Удерживаемое состояние одного чата (переживает уничтожение ChatScreen). AppShell рендерит один экран
 * через when(route) → уход в настройки/историю и назад ПОЛНОСТЬЮ пересоздаёт ChatScreen, и всё, что в
 * remember (черновик, сообщения, флаг генерации, job), терялось; генерация в AppScope продолжалась, но
 * UI к ней уже не был привязан → «исчезала». Здесь состояние живёт в реестре уровня процесса, экран лишь
 * ПЕРЕПОДКЛЮЧАЕТСЯ к своей сессии. Переживает навигацию, поворот, смену темы, скрытие клавиатуры.
 */
private class ChatSession {
    val messages = androidx.compose.runtime.mutableStateListOf<UiMessage>()
    val convIdState = androidx.compose.runtime.mutableStateOf<String?>(null)
    val draftState = androidx.compose.runtime.mutableStateOf("")
    val generatingState = androidx.compose.runtime.mutableStateOf(false)
    val genJobState = androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null)
    val pendingSessionState = androidx.compose.runtime.mutableStateOf<String?>(null) // id серверной генерации
    // Диалог подтверждения опасной операции — в СЕССИИ (не screen-remember): иначе уход с экрана во время
    // подтверждения осиротил бы CompletableDeferred, и агент навсегда завис бы на await() (generating=true).
    val pendingConfirmState =
        androidx.compose.runtime.mutableStateOf<Pair<String, kotlinx.coroutines.CompletableDeferred<Boolean>>?>(null)
    var loaded = false // сообщения из ConversationStore уже загружены (не перезагружать при переподключении)
}

/** Реестр живых сессий по ключу (id диалога или «new:<resetKey>»). Лёгкая очистка завершённых. */
private object ChatSessions {
    private val map = LinkedHashMap<String, ChatSession>()

    /** Зарегистрировать живую сессию под её (только что созданным) id диалога: открытие этого диалога по
     *  id вернёт ЖИВУЮ сессию (с текущей генерацией/сообщениями), а не свежую с диска. */
    fun registerId(id: String, session: ChatSession) {
        map[id] = session
    }

    fun get(conversationId: String?, resetKey: Int): ChatSession {
        val key = conversationId ?: "new:$resetKey"
        val session = map.getOrPut(key) { ChatSession() }
        // Держим немного сессий: выкидываем ЗАВЕРШЁННЫЕ (без активной генерации), кроме текущей —
        // их сообщения всё равно перечитаются из ConversationStore при повторном открытии.
        if (map.size > 6) {
            val it = map.entries.iterator()
            while (it.hasNext() && map.size > 6) {
                val e = it.next()
                if (e.key != key && e.value.genJobState.value?.isActive != true) it.remove()
            }
        }
        return session
    }
}

/** Обучающие подсказки при ПЕРВОМ включении режима (показываются один раз). */
private val HINTS = mapOf(
    "agent" to "Режим «Агент» — модель не просто отвечает, а вызывает инструменты: работает с файлами, кодом, документами, почтой, ищет в интернете. Опасные действия спрашивают подтверждение.",
    "reasoning" to "«Размышление» — модель рассуждает пошагово перед ответом. Точнее на сложных задачах, но медленнее.",
    "orchestrator" to "«Оркестратор» — сильная модель строит план, дешёвая выполняет по шагам, затем проверка результата. Дороже, зато лучше на сложных задачах. Нужно облако.",
)

// Авто-compact: при какой длине истории (символов) сжимать и сколько последних сообщений оставлять.
// n_ctx локальной модели 4096 токенов; ~8000 симв. истории ≈ ~2600 токенов — резервируем место ответу.
private const val COMPACT_CHARS = 8000
private const val COMPACT_KEEP = 4

/** Символы конца завершённой мысли — если ответ оканчивается ими, «Продолжить» не показываем. */
private val TERMINAL_CHARS =
    setOf('.', '!', '?', '…', '»', ')', ']', '}', '"', '\'', '`', '”', '’', '%', ';', '。', '」', '】', '*')

/** Мелкая текстовая кнопка-действие под сообщением (копировать / редактировать). */
@Composable
private fun MsgAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = InkSoft, fontSize = 12.sp,
        modifier = modifier.clickable { onClick() }.padding(vertical = 3.dp, horizontal = 2.dp),
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String? = null,
    resetKey: Int = 0,
    onSettings: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onMenu: () -> Unit = {},
    onOpenPc: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Состояние чата — в удерживаемой сессии (переживает пересоздание экрана), экран лишь переподключается.
    val session = remember(conversationId, resetKey) { ChatSessions.get(conversationId, resetKey) }
    val messages = session.messages
    val store = remember { ru.aiagent.app.data.ConversationStore(context) }
    var convId by session.convIdState
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun copyText(t: String) {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(t))
        android.widget.Toast.makeText(context, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
    }
    // Обучение: при первом включении режима показываем подсказку (один раз).
    var hintText by remember { mutableStateOf<String?>(null) }
    fun hintOnce(id: String) {
        if (!AppSettings.hintShown(context, id)) { hintText = HINTS[id]; AppSettings.markHint(context, id) }
    }
    // Ответ выглядит оборванным? Эвристика: у локальной модели нет сигнала finish_reason,
    // поэтому считаем ответ завершённым, если он оканчивается пунктуацией конца мысли,
    // закрывающей скобкой/кавычкой/символом или цифрой. Иначе — вероятен обрыв → «Продолжить».
    fun looksTruncated(t: String): Boolean {
        val c = t.trimEnd().lastOrNull() ?: return false
        return !c.isDigit() && c !in TERMINAL_CHARS
    }

    fun persist() {
        if (messages.isEmpty()) return
        val id = convId ?: ru.aiagent.app.data.ConversationStore.newId(context).also {
            convId = it
            ChatSessions.registerId(it, session) // открыть этот новый диалог по id → та же живая сессия
        }
        // Снимок списка — на текущем потоке (snapshot state), сериализация+запись на диск — вне
        // main-потока (U7: save() пишет весь список синхронно → фризы/ANR на длинном чате).
        // Устойчивый снимок: persist() может вызваться из finally на IO, пока Main делает
        // messages.clear() при смене диалога → ConcurrentModificationException. При сбое чтения
        // просто выходим без сохранения (лучше не сохранить, чем упасть).
        val snapshot = runCatching {
            messages.map { ru.aiagent.app.data.StoredMsg(it.kind, it.text) }.toMutableList()
        }.getOrNull() ?: return
        // Не сохраняем хвостовой ПУСТОЙ пузырь ассистента (добавляется до первого токена): при сохранении
        // на ON_STOP в это окно он застрял бы пустым на диске без возможности продолжить.
        while (snapshot.isNotEmpty() && snapshot.last().kind == "assistant" && snapshot.last().text.isBlank()) {
            snapshot.removeAt(snapshot.size - 1)
        }
        if (snapshot.isEmpty()) return
        val now = System.currentTimeMillis()
        // app-scope, а НЕ scope экрана: сохранение результата не должно теряться при уходе с чата
        // (иначе генерация в AppScope завершилась бы, но её persist отменился вместе с экраном).
        val pending = session.pendingSessionState.value
        AppScope.io.launch {
            store.save(ru.aiagent.app.data.Conversation(id, "", now, snapshot, pending))
            // Автосинк: изменения беседы уезжают на сервер (E2E) и приходят на другие устройства.
            ru.aiagent.app.cloud.AutoSync.schedulePush(context)
        }
    }
    var input by session.draftState // черновик держим в сессии — не слетает при навигации/повороте
    val sendInteraction = remember { MutableInteractionSource() } // scale 0.88 при нажатии «↑»
    var generating by session.generatingState
    var genJob by session.genJobState // для остановки ИИ; в сессии — переживает пересоздание экрана
    var reasoning by remember { mutableStateOf(AppSettings.reasoning(context)) } // режим «Размышление»
    var escalation by remember { mutableStateOf<String?>(null) } // запрос для эскалации в облако
    // Выбор модели восстанавливаем из настроек (иначе после перезахода — дефолт Gemma).
    var cloudModel by remember { mutableStateOf(AppSettings.lastCloudModel(context)) }
    // Разовый промпт «разрешить фоновую работу» при первой локальной генерации без battery-исключения.
    var showBatteryPrompt by remember { mutableStateOf(false) }
    var showCloudPicker by remember { mutableStateOf(false) }     // полноэкранный поиск по каталогу RouterAI
    var modelLabel by remember { mutableStateOf(AppSettings.lastModelLabel(context) ?: ChatEngine.currentModelLabel(context)) }
    // Восстанавливаем локальную модель в движок (если сохранена и не выбрана облачная).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (AppSettings.lastCloudModel(context) == null) {
            AppSettings.lastLocalModel(context)?.let { ChatEngine.selectModel(context, it) }
        }
    }
    var orchestrator by remember { mutableStateOf(false) } // режим план→исполнение→оценка
    // Пресет оркестратора. Если пользователь задал СВОИ модели (главная + рабочие) — стартуем с них.
    var orchPreset by remember {
        mutableStateOf(
            AppSettings.orchPlanner(context)?.let {
                ru.aiagent.app.cloud.OrchestratorPreset.custom(it, AppSettings.orchExecutors(context))
            } ?: ru.aiagent.app.cloud.ORCHESTRATOR_PRESETS.first(),
        )
    }
    var showOrchConfig by remember { mutableStateOf(false) }        // экран выбора своих моделей
    var orchPickTarget by remember { mutableStateOf<String?>(null) } // "planner" | "worker" | null
    // Черновик конструктора своих моделей (главная + рабочие), инициализируется при открытии экрана.
    var cfgPlanner by remember { mutableStateOf<String?>(null) }
    val cfgWorkers = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Переподключение к сессии. Ключ — session (меняется только при смене диалога/новом чате), поэтому
    // при возврате на экран (та же сессия) генерация НЕ отменяется и сообщения НЕ перезагружаются —
    // экран просто снова показывает то, что в сессии. Загрузка с диска — один раз (session.loaded).
    // ВАЖНО: не глушим genJob чужой сессии — прежний диалог догенерирует в AppScope и сохранится сам.
    // Возобновление отвязанной серверной генерации: диалог был открыт с незавершённой сессией (процесс
    // убили во время генерации) → дозабираем ответ с сервера по session_id. Сервер догенерировал сам.
    fun resumeServerGen(sid: String) {
        if (genJob != null) return // уже идёт генерация — не трогаем чужой ход (pending ведёт активная петля)
        // Возобновляем ТОЛЬКО если id вменяемый и прокси владельца доступен (иначе GET ушёл бы на дефолт-хост
        // с пустым токеном → 401 и ложная ошибка/уведомление). Заодно отсекает мусорные id от старых записей.
        val valid = sid.isNotBlank() && sid != "null"
        if (!valid || !ru.aiagent.app.cloud.ServerGen.available(context)) {
            // Не можем дозабрать (мусорный id ИЛИ маршрут сменился — свой API/логаут). Реальную серверную
            // сессию ПРЕРЫВАЕМ на сервере (иначе владелец платит за ответ, который телефон не заберёт), и
            // ПЕРСИСТИМ очистку pending (иначе протухший sid остаётся на диске → ложные ошибки при след. открытии).
            if (valid) AppScope.io.launch { runCatching { ru.aiagent.app.cloud.ServerGen.abort(context, sid) } }
            session.pendingSessionState.value = null
            persist()
            return
        }
        // Хвостовой ассистент — ЧАСТИЧНЫЙ ответ ЭТОГО хода (pending=sid ещё стоит; завершённый ход очистил бы
        // pending). Переиспользуем и ОЧИЩАЕМ его: poll отдаёт весь буфер с курсора 0 и перезальёт целиком —
        // иначе поверх НЕПУСТОГО частичного (его пишет ON_STOP) добавился бы дублирующий пузырь.
        val last = messages.lastOrNull()
        val idx = if (last?.kind == "assistant") {
            messages[messages.lastIndex] = messages[messages.lastIndex].copy(text = "")
            messages.lastIndex
        } else { messages += UiMessage("assistant", ""); messages.lastIndex }
        generating = true
        GenerationService.begin(context)
        session.pendingSessionState.value = sid
        var jobRef: kotlinx.coroutines.Job? = null
        jobRef = AppScope.io.launch {
            var acc = "" // сервер отдаёт весь буфер с курсора 0 → перезаписываем строку целиком
            try {
                ru.aiagent.app.cloud.ServerGen.poll(context, sid).collect { piece ->
                    acc += piece
                    val shown = stripSpecialTokens(acc)
                    withContext(Dispatchers.Main) {
                        if (idx in messages.indices) messages[idx] = messages[idx].copy(text = shown)
                    }
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) { error = t.message }
            } finally {
                withContext(Dispatchers.Main) {
                    if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                }
                if (genJob === jobRef) persist()
            }
        }
        genJob = jobRef
    }

    androidx.compose.runtime.LaunchedEffect(session) {
        if (!session.loaded) {
            session.loaded = true
            convId = conversationId
            if (conversationId != null) {
                val conv = store.load(conversationId)
                conv?.messages?.forEach { messages += UiMessage(it.kind, it.text) }
                // Была незавершённая серверная генерация → дозабрать (ответ дописался на сервере).
                conv?.pendingSession?.let { resumeServerGen(it) }
            }
        }
        // Открываем чат СНИЗУ (на свежих сообщениях), как в любом мессенджере.
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    // Диалог подтверждения опасных операций (агентный цикл, §3.6) — держим в сессии (переживает уход
    // с экрана во время подтверждения; иначе агент завис бы на await()).
    var pendingConfirm by session.pendingConfirmState
    // Режим: агент с инструментами vs простой чат (стриминг).
    var agentMode by remember { mutableStateOf(AppSettings.agentMode(context)) }
    // Панель «+» (bottom-sheet) вместо россыпи пилюль, и спец-режимы ответа.
    var showTools by remember { mutableStateOf(false) }
    var specMode by remember { mutableStateOf(SpecMode.NONE) }
    // Проект: постоянные инструкции в системный промпт, пока активен.
    var projectInstr by remember { mutableStateOf(AppSettings.projectInstructions(context)) }
    var showProjectEditor by remember { mutableStateOf(false) }

    // Общий обработчик загруженного файла (документ/фото/видео): копирует в рабочую папку + индексирует.
    fun handlePicked(uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            try {
                val name = docName(context, uri)
                val ws = java.io.File(context.filesDir, "workspace").apply { mkdirs() }
                val dest = java.io.File(ws, name)
                val copied = context.contentResolver.openInputStream(uri)?.use { inp ->
                    dest.outputStream().use { inp.copyTo(it) }
                } ?: 0L
                var ragNote = ""
                if (copied > 0 && ru.aiagent.app.rag.RagEngine.embeddingModelFile(context) != null) {
                    runCatching {
                        val text = ru.aiagent.toolsdocs.extractDocumentText(context, dest)
                        if (text.isBlank()) false
                        else ru.aiagent.app.rag.RagEngine.indexText(context, name, text).isSuccess
                    }.getOrDefault(false).let { if (it) ragNote = " · в базе знаний" }
                }
                withContext(Dispatchers.Main) {
                    if (copied > 0) {
                        messages += UiMessage("tool", "Файл добавлен в рабочую папку: $name$ragNote")
                        if (!agentMode) { agentMode = true; AppSettings.setAgentMode(context, true) }
                    } else error = "не удалось прочитать файл"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { error = "не удалось загрузить файл: ${t.message}" }
            }
        }
    }

    // Пикеры: документ (любой тип), фото, видео — через SAF, единый обработчик handlePicked.
    val docPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { handlePicked(it) }
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { handlePicked(it) }
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { handlePicked(it) }

    // Голос (S9b): STT в поле ввода, TTS-озвучка ответов по переключателю.
    var listening by remember { mutableStateOf(false) }
    var speakAnswers by remember { mutableStateOf(AppSettings.speakByDefault(context)) }
    val tts = remember { ru.aiagent.voice.AndroidTts(context) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    // Сохраняем чат при СВОРАЧИВАНИИ приложения (ON_STOP), а не только в finally генерации: если процесс
    // потом убьют (свайп из недавних / агрессивная прошивка / память) — сообщения и частичный ответ уже
    // на диске. persist() пишет в AppScope.io (не привязан к экрану) и пропускает пустой чат.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) persist()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var sttJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun startListening() {
        if (listening) { sttJob?.cancel(); listening = false; return }
        listening = true
        sttJob = scope.launch(Dispatchers.Main) {
            try {
                ru.aiagent.voice.AndroidStt(context).listen().collect { ev ->
                    when (ev) {
                        is ru.aiagent.voice.SttEvent.Partial -> input = ev.text
                        is ru.aiagent.voice.SttEvent.Final -> { input = ev.text; listening = false }
                        is ru.aiagent.voice.SttEvent.Error -> { error = "распознавание: ${ev.message}"; listening = false }
                    }
                }
            } catch (t: Throwable) {
                error = "распознавание речи недоступно: ${t.message ?: "нет сервиса/разрешения"}"
            } finally { listening = false }
        }
    }

    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startListening() }

    fun onMic() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    // Авто-compact (как /compact в Claude Code): при заполнении контекста сжимаем старую историю
    // в резюме локальной моделью, чтобы не переполнять n_ctx (иначе деградация/краш). Кроме bypass — всегда.
    suspend fun compactIfNeeded() {
        val chat = messages.filter { it.kind == "user" || it.kind == "assistant" || it.kind == "summary" }
        if (chat.sumOf { it.text.length } < COMPACT_CHARS || messages.size <= COMPACT_KEEP + 1) return
        val keepFrom = messages.size - COMPACT_KEEP
        val older = messages.subList(0, keepFrom)
            .filter { it.kind == "user" || it.kind == "assistant" || it.kind == "summary" }
            .map { ChatMessage(if (it.kind == "user") ChatRole.USER else ChatRole.ASSISTANT, it.text) }
        if (older.isEmpty()) return
        val summary = runCatching { ChatEngine.summarize(context, older) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        withContext(Dispatchers.Main) {
            val kept = messages.subList(keepFrom, messages.size).toList()
            messages.clear()
            messages.add(UiMessage("summary", summary))
            messages.addAll(kept)
            persist()
        }
    }

    /** История для модели с учётом compact: «summary» → контекстная реплика. */
    fun modelHistory(): List<ChatMessage> = messages
        .filter { it.kind == "user" || it.kind == "assistant" || it.kind == "summary" }
        .map {
            when (it.kind) {
                "user" -> ChatMessage(ChatRole.USER, it.text)
                "summary" -> ChatMessage(ChatRole.USER, "[Ранее в диалоге, сжато]: ${it.text}")
                else -> ChatMessage(ChatRole.ASSISTANT, it.text)
            }
        }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || generating) return
        input = ""
        error = null
        escalation = null // прошлая эскалация неактуальна для нового запроса
        // Краткий контекст: последние реплики user/assistant (без служебных tool-строк).
        val history = messages
            .filter { it.kind == "user" || it.kind == "assistant" || it.kind == "summary" }
            .takeLast(6)
            .joinToString("\n") {
                when (it.kind) {
                    "user" -> "Пользователь: ${it.text}"
                    "summary" -> "[Ранее, сжато]: ${it.text}"
                    else -> "Агент: ${it.text}"
                }
            }
        // Инструкции активного режима/проекта — впереди контекста (действуют на все пути отправки).
        val modeExtra = buildString {
            if (projectInstr.isNotBlank()) appendLine("КОНТЕКСТ ПРОЕКТА (учитывай всегда): $projectInstr")
            val si = specInstruction(specMode)
            if (si.isNotEmpty()) append(si)
        }.trim()
        val recent = if (modeExtra.isEmpty()) history else (modeExtra + if (history.isBlank()) "" else "\n\n$history")
        messages += UiMessage("user", text)
        persist() // СРАЗУ на диск: если процесс убьют до конца генерации — сообщение и чат не пропадут
        generating = true
        GenerationService.begin(context) // держать процесс живым, пока идёт генерация (переживёт сворачивание)
        // Локальная генерация в фоне надёжна лишь с исключением из оптимизации батареи (иначе OEM убьёт
        // сервис, как жаловались). Просим один раз, ровно когда это нужно.
        if (cloudModel == null && !BatteryOptimization.isExempt(context) && !BatteryOptimization.asked(context))
            showBatteryPrompt = true

        // Оркестратор: планировщик(Pro) → исполнитель(Flash-агент) → критик(Pro).
        if (orchestrator && ru.aiagent.app.cloud.CloudEngine.isConfigured(context)) {
            val confirmO: suspend (String) -> Boolean = { prompt ->
                val d = kotlinx.coroutines.CompletableDeferred<Boolean>(); pendingConfirm = prompt to d; d.await()
            }
            var jobRef: kotlinx.coroutines.Job? = null
            jobRef = AppScope.io.launch {
                try {
                    ru.aiagent.app.cloud.OrchestratorEngine.run(
                        context, text, orchPreset, AppSettings.mode(context),
                        ru.aiagent.core.agent.ConfirmationHandler { confirmO(it) },
                    ).collect { line ->
                        withContext(Dispatchers.Main) {
                            when (line.kind) {
                                "plan" -> messages += UiMessage("tool", "План:\n${line.text}")
                                "step" -> messages += UiMessage("tool", "${line.text}")
                                "tool" -> messages += UiMessage("tool", line.text)
                                "eval" -> messages += UiMessage("tool", "Оценка: ${line.text.take(300)}")
                                "answer" -> messages += UiMessage("assistant", stripSpecialTokens(line.text))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) { error = "оркестратор: ${t.message?.take(200)}" }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                    }
                    if (genJob === jobRef) persist()
                }
            }
            genJob = jobRef
            return
        }

        // Облачная модель + режим «Агент» → облачный агентный цикл (системный промпт + инструменты,
        // кроме приватных). Иначе (режим «Чат») — простой стриминговый ответ ниже.
        if (cloudModel != null && agentMode && ru.aiagent.app.cloud.CloudEngine.isConfigured(context)) {
            val confirmC: suspend (String) -> Boolean = { prompt ->
                val d = kotlinx.coroutines.CompletableDeferred<Boolean>(); pendingConfirm = prompt to d; d.await()
            }
            var jobRef: kotlinx.coroutines.Job? = null
            jobRef = AppScope.io.launch {
                var lastAnswer = ""
                try {
                    val mode = AppSettings.mode(context)
                    // «Мышление»: если модель умеет размышлять САМА (по каталогу) — идём нативно
                    // (reasoning_content → шторка), без текстового <think> (лишние токены + второй блок).
                    // Если НЕ умеет (малые модели) — включаем <think>-фолбэк через промпт.
                    val nativeReason = runCatching {
                        ru.aiagent.app.cloud.CloudToolFormat.of(context, cloudModel!!).reasoning
                    }.getOrDefault(false)
                    val promptText = if (reasoning && !nativeReason) REASONING_PREFIX + text else text
                    AgentChatEngine.runCloud(context, cloudModel!!, promptText, recent, mode, confirmC).collect { ev ->
                        withContext(Dispatchers.Main) {
                            when (ev) {
                                is AgentEvent.ToolCall -> messages += UiMessage("tool", "→ ${ev.toolId} ${ev.argsJson.take(80)}")
                                is AgentEvent.ToolObservation -> {
                                    val txt = when (val r = ev.result) {
                                        is ru.aiagent.core.agent.ToolResult.Success -> "✓ ${r.outputJson.take(120)}"
                                        is ru.aiagent.core.agent.ToolResult.Failure -> "✗ ${r.message.take(120)}"
                                        else -> "…"
                                    }
                                    messages += UiMessage("tool", txt)
                                }
                                is AgentEvent.Answer -> { lastAnswer = ev.text; messages += UiMessage("assistant", "${stripSpecialTokens(ev.text)}") }
                                is AgentEvent.EscalationSuggested -> { escalation = ev.reason; messages += UiMessage("assistant", "${ev.reason}") }
                                // «Мышление»: размышление модели → отдельная свёрнутая шторка (агент отдаёт его целиком).
                                is AgentEvent.Thinking -> messages += UiMessage("reasoning", ev.text)
                                else -> {}
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) { error = "облако-агент: ${t.message?.take(200)}" }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                    }
                    if (genJob === jobRef) persist()
                    if (speakAnswers && lastAnswer.isNotBlank()) scope.launch(Dispatchers.Default) { tts.speak(lastAnswer) }
                }
            }
            genJob = jobRef
            return
        }

        // Облачная модель в режиме «Чат» — весь запрос уходит в облако (RouterAI), с кратким контекстом.
        if (cloudModel != null && ru.aiagent.app.cloud.CloudEngine.isConfigured(context)) {
            val idx = messages.size
            messages += UiMessage("assistant", "")
            var jobRef: kotlinx.coroutines.Job? = null
            jobRef = AppScope.io.launch {
                var acc = ""
                try {
                    // Умеет размышлять сама → нативно (reasoning_content → шторка); иначе — <think>-фолбэк.
                    val nativeReason = runCatching {
                        ru.aiagent.app.cloud.CloudToolFormat.of(context, cloudModel!!).reasoning
                    }.getOrDefault(false)
                    val q = if (reasoning && !nativeReason) REASONING_PREFIX + text else text
                    val body = if (recent.isBlank()) q else "$recent\nПользователь: $q"
                    val prompt = ChatEngine.CHAT_SYSTEM + "\n\n" + body
                    // При включённом прокси владельца → ОТВЯЗАННАЯ серверная генерация: ответ дописывается
                    // на сервере, даже если приложение закроют; телефон дозаберёт по session_id при возврате.
                    val stream = if (ru.aiagent.app.cloud.ServerGen.available(context)) {
                        val sid = ru.aiagent.app.cloud.ServerGen.start(
                            context, cloudModel!!,
                            listOf(ru.aiagent.core.inference.ChatMessage(ru.aiagent.core.inference.ChatRole.USER, prompt)),
                        )
                        withContext(Dispatchers.Main) { session.pendingSessionState.value = sid }
                        persist() // сохранить чат + id сессии: убьют процесс — при открытии возобновим опрос
                        ru.aiagent.app.cloud.ServerGen.poll(context, sid)
                    } else {
                        ru.aiagent.app.cloud.CloudEngine.reply(context, prompt, model = cloudModel)
                    }
                    stream.collect { piece ->
                        acc += piece
                        withContext(Dispatchers.Main) {
                            if (idx < messages.size) messages[idx] = messages[idx].copy(text = "${stripSpecialTokens(acc)}")
                        }
                    }
                    if (acc.isBlank()) withContext(Dispatchers.Main) {
                        if (idx < messages.size) messages[idx] = messages[idx].copy(text = "(пустой ответ)")
                    }
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) {
                        error = "облако: ${t.message?.take(200)}"
                        if (idx < messages.size && messages[idx].text == "") messages.removeAt(idx)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                    }
                    if (genJob === jobRef) persist()
                    if (speakAnswers && acc.isNotBlank()) scope.launch(Dispatchers.Default) { tts.speak(acc) }
                }
            }
            genJob = jobRef
            return
        }

        // Простой чат: стриминг без tool-промпта.
        if (!agentMode) {
            var jobRef: kotlinx.coroutines.Job? = null
            jobRef = AppScope.io.launch {
                var acc = ""
                var idx = -1
                try {
                    compactIfNeeded() // авто-compact ДО построения истории (не переполнить n_ctx)
                    val history = withContext(Dispatchers.Main) {
                        val base = modelHistory()
                        val h = if (reasoning && base.isNotEmpty())
                            base.dropLast(1) + base.last().copy(text = REASONING_PREFIX + base.last().text)
                        else base
                        idx = messages.size            // стабильный индекс строки ассистента
                        messages += UiMessage("assistant", "")
                        h
                    }
                    // Локальная модель — стрим на устройстве (серверный досыл здесь не применим: этот бранч
                    // достигается только при cloudModel==null; облачный чат обрабатывается выше, ветка 618).
                    ChatEngine.reply(context, history).collect { piece ->
                        acc += piece
                        val shown = stripSpecialTokens(acc) // не течём спец-токенами (§7)
                        withContext(Dispatchers.Main) {
                            if (idx in messages.indices) messages[idx] = messages[idx].copy(text = shown)
                        }
                    }
                } catch (t: Throwable) {
                    // Остановка пользователем (cancel) — не ошибка; частичный ответ оставляем.
                    if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) {
                        error = t.message
                        if (idx in messages.indices && messages[idx].kind == "assistant" && messages[idx].text.isBlank()) {
                            messages.removeAt(idx)
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                    }
                    if (genJob === jobRef) persist()
                    if (speakAnswers && acc.isNotBlank()) scope.launch(Dispatchers.Default) { tts.speak(acc) }
                }
            }
            genJob = jobRef
            return
        }

        // Подтверждение опасных операций пробрасываем в UI-диалог.
        val confirm: suspend (String) -> Boolean = { prompt ->
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            pendingConfirm = prompt to deferred
            deferred.await()
        }

        var jobRef: kotlinx.coroutines.Job? = null
        jobRef = AppScope.io.launch {
            var lastAnswer = ""
            try {
                val mode = AppSettings.mode(context)
                val promptText = if (reasoning) REASONING_PREFIX + text else text
                AgentChatEngine.run(context, promptText, recent, mode, confirm).collect { ev ->
                    // SnapshotStateList мутируем только на Main (иначе гонка с рекомпозицией).
                    withContext(Dispatchers.Main) {
                        when (ev) {
                            is AgentEvent.ToolCall ->
                                messages += UiMessage("tool", "→ ${ev.toolId} ${ev.argsJson.take(80)}")
                            is AgentEvent.ToolObservation -> {
                                val r = ev.result
                                val txt = when (r) {
                                    is ru.aiagent.core.agent.ToolResult.Success -> "✓ ${r.outputJson.take(120)}"
                                    is ru.aiagent.core.agent.ToolResult.Failure -> "✗ ${r.message}"
                                    else -> "…"
                                }
                                messages += UiMessage("tool", txt)
                                // Графики/картинки из результата (напр. matplotlib) — вложениями в чат (S7).
                                if (r is ru.aiagent.core.agent.ToolResult.Success) {
                                    extractImagePaths(r.outputJson).forEach { p ->
                                        if (java.io.File(p).exists()) messages += UiMessage("image", p)
                                    }
                                }
                            }
                            is AgentEvent.EscalationSuggested -> {
                                val cloudReady = ru.aiagent.app.cloud.CloudEngine.isConfigured(context)
                                messages += UiMessage(
                                    "assistant",
                                    "⚡ Задача сложна для локальной модели. ${ev.reason}" +
                                        if (cloudReady) "\n\nМожно спросить облако — кнопка ниже." else "\n(облако не настроено — добавьте ключ в Настройках)",
                                )
                                if (cloudReady) escalation = text
                            }
                            is AgentEvent.Clarification ->
                                messages += UiMessage("assistant", ev.question)
                            is AgentEvent.Answer -> {
                                lastAnswer = stripSpecialTokens(ev.text)
                                messages += UiMessage("assistant", lastAnswer)
                            }
                            is AgentEvent.Thinking -> {} // не показываем сырые размышления
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) { error = t.message }
            } finally {
                withContext(Dispatchers.Main) {
                    if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                }
                if (genJob === jobRef) persist()
                if (speakAnswers && lastAnswer.isNotBlank()) scope.launch(Dispatchers.Default) { tts.speak(lastAnswer) }
            }
        }
        genJob = jobRef
    }

    // Редактировать вопрос: вернуть текст в поле ввода и отрезать ветку с этого места.
    fun editUser(idx: Int) {
        if (idx < 0 || idx >= messages.size || generating) return
        input = messages[idx].text
        while (messages.size > idx) messages.removeAt(messages.size - 1)
        if (messages.isEmpty()) {
            // Обрезали до пустоты (правка первого сообщения): persist() на пустом списке ничего
            // не сохраняет, поэтому удаляем устаревшую беседу из хранилища и начинаем как новую.
            convId?.let { store.delete(it) }
            convId = null
        } else {
            persist()
        }
        keyboard?.show()
    }

    // «Продолжить» — дописать оборванный последний ответ в тот же пузырь.
    fun continueLast() {
        if (generating) return
        val idx = messages.indexOfLast { it.kind == "assistant" }
        if (idx < 0 || messages[idx].text.isBlank()) return
        generating = true
        GenerationService.begin(context) // foreground+wakelock и на «Продолжить» (баланс счётчика с finish)
        error = null
        val isCloud = cloudModel != null
        val history = modelHistory() // включает сжатый контекст (summary)
        var jobRef: kotlinx.coroutines.Job? = null
        jobRef = AppScope.io.launch {
            var acc = messages[idx].text
            try {
                val flow = if (isCloud) {
                    val partial = messages[idx].text
                    ru.aiagent.app.cloud.CloudEngine.reply(
                        context,
                        "Продолжи ровно с того места, где твой ответ оборвался, без повторов и без вступлений:\n\n$partial",
                        model = cloudModel,
                    )
                } else {
                    ChatEngine.continueReply(context, history)
                }
                flow.collect { piece ->
                    acc += piece
                    withContext(Dispatchers.Main) {
                        if (idx < messages.size) messages[idx] = messages[idx].copy(text = stripSpecialTokens(acc))
                    }
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) { error = t.message?.take(200) }
            } finally {
                withContext(Dispatchers.Main) {
                    if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                }
                if (genJob === jobRef) persist()
            }
        }
        genJob = jobRef
    }

    // Универсальное «Продолжить» (в т.ч. для агента). Оборванный ТЕКСТ (чат) — дописываем в тот же пузырь;
    // иначе (агентный режим или ответа ещё нет — процесс убили на полуслове) перезапускаем текущий режим на
    // истории с явным «продолжи», чтобы агент довёл задачу до конца, не теряя контекст.
    fun continueGen() {
        if (generating || messages.isEmpty()) return
        val lastAssistant = messages.indexOfLast { it.kind == "assistant" }
        val partialText = lastAssistant >= 0 && messages[lastAssistant].text.isNotBlank() && messages.last().kind == "assistant"
        if (!agentMode && partialText) {
            continueLast()
        } else {
            // Не затираем набранный черновик пользователя — шлём его; иначе даём команду «продолжи».
            if (input.isBlank()) input = "Продолжи выполнение с того места, где остановился, и доведи задачу до конца."
            send()
        }
    }

    fun askCloud() {
        val q = escalation ?: return
        escalation = null
        error = null
        generating = true
        GenerationService.begin(context) // foreground+wakelock и на «Спросить облако» (баланс счётчика)
        // A9: передаём облаку накопленный контекст диалога, а не голый вопрос — иначе эскалация
        // начинает с нуля и теряет всё, что уже наработал локальный агент.
        val convo = modelHistory().takeLast(8).joinToString("\n") {
            (if (it.role == ChatRole.USER) "Пользователь: " else "Ассистент: ") + it.text
        }
        val prompt = if (convo.isBlank()) q else "Контекст диалога:\n$convo\n\nЗапрос: $q"
        val idx = messages.size
        messages += UiMessage("assistant", "")
        var jobRef: kotlinx.coroutines.Job? = null
        jobRef = AppScope.io.launch {
            var acc = ""
            try {
                ru.aiagent.app.cloud.CloudEngine.reply(context, prompt).collect { piece ->
                    acc += piece
                    withContext(Dispatchers.Main) {
                        // §7: фильтруем спец-токены и на пути эскалации (как в облачном чате).
                        if (idx < messages.size) messages[idx] = messages[idx].copy(text = "${stripSpecialTokens(acc)}")
                    }
                }
                if (acc.isBlank()) withContext(Dispatchers.Main) {
                    if (idx < messages.size) messages[idx] = messages[idx].copy(text = "(пустой ответ облака)")
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) {
                    error = "облако: ${t.message?.take(200)}"
                    if (idx < messages.size && messages[idx].text == "") messages.removeAt(idx)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (genJob === jobRef) { generating = false; GenerationService.finish(context); session.pendingSessionState.value = null; persist(); genJob = null }
                }
                if (genJob === jobRef) persist()
            }
        }
        genJob = jobRef
    }

    // Автоскролл при стриминге — только если пользователь уже у низа. Иначе (прокрутил вверх
    // читать) список не дёргается вниз на каждый токен (U8).
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= messages.lastIndex - 1
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty() && atBottom) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // Шапка с выбором модели
        var menuOpen by remember { mutableStateOf(false) }
        // Пере-опрашиваем при открытии — чтобы новые скачанные модели появлялись.
        var models by remember { mutableStateOf(ChatEngine.availableModels(context)) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); onMenu() }) {
                Icon(Icons.Outlined.Menu, "меню", tint = Ink, modifier = Modifier.size(24.dp))
            }
            Box {
                // Модель — аккуратная «пилюля» с коротким именем.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(enabled = !generating) {
                            models = ChatEngine.availableModels(context)
                            menuOpen = true
                        }
                        .background(UserBubble.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                ) {
                    Text(modelLabel, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = InkSoft, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (models.isEmpty()) {
                        DropdownMenuItem(text = { Text("Нет моделей — скачайте на вкладке Модели") }, onClick = { menuOpen = false })
                    }
                    models.forEach { f ->
                        val fname = ChatEngine.friendlyName(f.name)
                        val active = fname == modelLabel
                        DropdownMenuItem(
                            leadingIcon = {
                                if (active) Icon(Icons.Outlined.Check, null, tint = Coral, modifier = Modifier.size(18.dp))
                                else Spacer(Modifier.size(18.dp))
                            },
                            text = {
                                Column {
                                    Text(fname, color = Ink, fontSize = 14.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                    Text("${f.length() / (1024 * 1024)} МБ · ${if (f.extension == "task") "MediaPipe" else "gguf"}",
                                        color = InkSoft, fontSize = 11.sp)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                cloudModel = null // назад на локальную
                                scope.launch {
                                    ChatEngine.selectModel(context, f.name)
                                    modelLabel = fname
                                    AppSettings.saveModel(context, f.name, null, fname) // запомнить выбор
                                    // диалог НЕ трогаем — смена модели продолжает текущий чат
                                }
                            },
                        )
                    }
                    // Облачные модели (RouterAI) — если ключ настроен в Настройках.
                    if (ru.aiagent.app.cloud.CloudEngine.isConfigured(context)) {
                        HorizontalDivider(color = UserBubble)
                        DropdownMenuItem(enabled = false, text = { Text("Облако (RouterAI)", color = InkSoft, fontSize = 12.sp) }, onClick = {})
                        DropdownMenuItem(
                            leadingIcon = { Spacer(Modifier.size(18.dp)) },
                            text = { Text("Все модели (поиск)…", color = Coral, fontSize = 14.sp) },
                            onClick = { menuOpen = false; showCloudPicker = true },
                        )
                        ru.aiagent.app.cloud.ROUTERAI_MODELS.forEach { cm ->
                            val active = cloudModel == cm.id
                            DropdownMenuItem(
                                leadingIcon = {
                                    if (active) Icon(Icons.Outlined.Check, null, tint = Coral, modifier = Modifier.size(18.dp))
                                    else Spacer(Modifier.size(18.dp))
                                },
                                text = {
                                    Column {
                                        Text("${cm.name}", color = Ink, fontSize = 14.sp,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                        Text("${cm.price}/1M · ${cm.note}", color = InkSoft, fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    cloudModel = cm.id
                                    modelLabel = "${cm.name}"
                                    AppSettings.saveModel(context, null, cm.id, "${cm.name}")
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { speakAnswers = !speakAnswers; if (!speakAnswers) tts.stop() }) {
                Icon(
                    if (speakAnswers) Icons.AutoMirrored.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                    if (speakAnswers) "Выключить озвучку" else "Включить озвучку",
                    tint = if (speakAnswers) Coral else InkSoft, modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = {
                // Отменяем генерацию и снимаем foreground САМИ: обнуляя genJob, мы «отвязываем» finally
                // корутины (его guard genJob===jobRef не сработает), поэтому finish() зовём здесь.
                genJob?.let { it.cancel(); GenerationService.finish(context) }
                genJob = null; generating = false; escalation = null
                persist() // сохранить текущий чат ДО очистки (иначе ответ из памяти пропал бы)
                messages.clear(); convId = null; onNewChat()
            }) {
                Icon(Icons.Outlined.Edit, "новый диалог", tint = Ink, modifier = Modifier.size(21.dp))
            }
        }
        HorizontalDivider(color = UserBubble)

        // Пилюля «Телефон | ПК»: этот чат — телефон; тап «ПК» открывает диалог с ПК (E2E),
        // меняется только показываемый диалог (та же логика, что на десктопе).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            DevicePill(pcActive = false, onPhone = {}, onPc = onOpenPc)
        }

        // Лента сообщений
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (messages.isEmpty()) {
                item { EmptyHint(onSuggestion = { input = it }) }
            }
            itemsIndexed(messages) { i, m ->
              // Появление сообщения: fade + подъём (350ms). Ключ по индексу — играет один раз.
              Box(Modifier.fillMaxWidth().messageEnter(key = i)) {
                when (m.kind) {
                    // Вопрос: выделяемый текст (частичное копирование) + кнопки Копировать/Редактировать.
                    "user" -> Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                m.text, color = Ink, fontSize = 15.sp,
                                modifier = Modifier
                                    .background(UserBubble, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(end = 4.dp)) {
                            MsgAction("Редактировать") { editUser(i) }
                            MsgAction("Копировать") { copyText(m.text) }
                        }
                    }

                    // Tool-строка → моно-чип (референс): фон surface, рамка line, ведущий маркер
                    // цветом (→ вызов = accent, ✓ готово = ok, ✗ ошибка = accent), текст моноширинный.
                    "tool" -> {
                        val marker = m.text.firstOrNull()
                        val known = marker == '✓' || marker == '✗' || marker == '→'
                        val mColor = when (marker) { '✓' -> ru.aiagent.app.ui.P.Ok; else -> Coral }
                        val rest = if (known) m.text.drop(1).trim() else m.text
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ru.aiagent.app.ui.P.Surface, RoundedCornerShape(10.dp))
                                .border(1.dp, ru.aiagent.app.ui.P.Line, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (known) Text(
                                marker.toString(), color = mColor, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                rest, color = InkSoft, fontSize = 12.5.sp, lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Размышление агента (облако, «Мышление») → свёрнутая шторка «💭 Размышления», раскрытие по клику.
                    "reasoning" -> {
                        var open by remember(i) { mutableStateOf(false) }
                        Column(
                            Modifier.fillMaxWidth()
                                .background(ru.aiagent.app.ui.P.Surface, RoundedCornerShape(12.dp))
                                .border(1.dp, ru.aiagent.app.ui.P.Line, RoundedCornerShape(12.dp))
                                .clickable { open = !open }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Размышления  " + (if (open) "▲" else "▼"),
                                color = InkSoft, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                            )
                            if (open) Text(m.text, color = InkSoft, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }

                    "image" -> ChatImage(m.text)

                    // Сжатый контекст (авто-compact): свёрнутая заметка, разворачивается тапом.
                    "summary" -> {
                        var open by remember(i) { mutableStateOf(false) }
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Coral.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                                .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "— контекст сжат для экономии памяти " + (if (open) "▲" else "▼") + " —",
                                color = InkSoft, fontSize = 12.sp,
                            )
                            if (open) Text(m.text, color = InkSoft, fontSize = 13.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }

                    // Ответ: выделяемый текст (частичное копирование) + кнопка «Копировать».
                    else -> Column {
                        androidx.compose.foundation.text.selection.SelectionContainer { AssistantMessage(m.text) }
                        // Пока стримится этот (последний) ответ — мигающая каретка вместо «Копировать».
                        if (i == messages.lastIndex && generating) {
                            Row(verticalAlignment = Alignment.CenterVertically) { TypingCaret() }
                        } else {
                            MsgAction("Копировать") { copyText(m.text) }
                        }
                    }
                }
              }
            }
            escalation?.let {
                item {
                    Text(
                        "Спросить облако",
                        color = Coral, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(Coral.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                            .clickable(enabled = !generating) { askCloud() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            // Кнопка «Продолжить»: если текстовый ответ оборван (не завершён пунктуацией) ИЛИ если ход
            // оборвался ДО ответа — последняя строка это вопрос/вызов инструмента/размышление (агента убили
            // на полуслове). Тогда даём одним нажатием довести задачу до конца.
            if (!generating && escalation == null &&
                messages.lastOrNull()?.let {
                    (it.kind == "assistant" && it.text.isNotBlank() && looksTruncated(it.text)) ||
                        it.kind == "user" || it.kind == "tool" || it.kind == "reasoning"
                } == true
            ) {
                item {
                    Text(
                        "Продолжить",
                        color = Coral, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(UserBubble.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .clickable { continueGen() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            // Индикатор «думает» — три пульсирующие точки. Показываем, пока ответ ещё НЕ пошёл
            // (последний пузырь ассистента пуст / это вопрос или tool-строка); как только пошёл
            // стриминг — эстафету принимает мигающая каретка в конце ответа (выше).
            val streamingAnswer = messages.lastOrNull()?.let { it.kind == "assistant" && it.text.isNotBlank() } == true
            if (generating && !streamingAnswer) {
                item { ThinkingDots() }
            }
            error?.let { e ->
                item { Text("⚠ $e", color = Coral, fontSize = 13.sp) }
            }
        }

        // Компактная строка АКТИВНЫХ режимов над инпутом (сами режимы — в панели «+»). Показываем
        // только включённое; тап по чипу — выключить. Разгружает ввод (как у Qwen).
        val activeChips = buildList {
            if (agentMode) add(Triple("Инструменты", Icons.Outlined.Build) { agentMode = false; AppSettings.setAgentMode(context, false) })
            if (reasoning) add(Triple("Мышление", Icons.Outlined.Lightbulb) { reasoning = false; AppSettings.setReasoning(context, false) })
            if (orchestrator) add(Triple(orchPreset.name.substringBefore(' '), Icons.Outlined.AccountTree) { orchestrator = false })
            if (specMode != SpecMode.NONE) add(Triple(specMode.title, Icons.Outlined.Bolt) { specMode = SpecMode.NONE })
            if (projectInstr.isNotBlank()) add(Triple("Проект", Icons.Outlined.Folder) { })
        }
        if (activeChips.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                activeChips.forEach { (label, icon, off) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { off() }
                            .background(Coral.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(icon, null, tint = Coral, modifier = Modifier.size(15.dp))
                        Text(" $label", color = Coral, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Icon(Icons.Outlined.Close, "выключить", tint = Coral.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                    }
                }
            }
        }

        // Ввод
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); showTools = true }) {
                Icon(Icons.Outlined.Add, "инструменты и режимы", tint = InkSoft, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = { onMic() }) {
                Icon(
                    if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    if (listening) "Остановить запись" else "Голосовой ввод",
                    tint = if (listening) Coral else InkSoft, modifier = Modifier.size(22.dp),
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (listening) "Слушаю…" else "Спросите что-нибудь…", color = InkSoft) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = UserBubble,
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink,
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            if (generating) {
                // Квадрат-стоп: прерывает ИИ. Чистим ЗДЕСЬ (inline), а не в finally корутины: на cancel
                // withContext(Main) в finally бросает CancellationException ДО тела → его очистка не
                // выполнится, и generating застрял бы true (чат заклинен). persist() сохраняет частичный ответ.
                IconButton(
                    onClick = {
                        genJob?.cancel()
                        generating = false
                        GenerationService.finish(context)
                        // Серверная генерация (отвязанная) не остановится сама на cancel телефона —
                        // просим сервер прервать её, иначе он догенерит и спишет полностью.
                        val sid = session.pendingSessionState.value
                        session.pendingSessionState.value = null
                        if (!sid.isNullOrBlank()) AppScope.io.launch { ru.aiagent.app.cloud.ServerGen.abort(context, sid) }
                        persist()
                        genJob = null
                    },
                    modifier = Modifier.semantics { contentDescription = "Остановить генерацию" },
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(Coral, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(11.dp).background(Cream, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            } else {
                // Круглая акцентная кнопка «↑» с откликом на нажатие (scale 0.88, 120ms).
                val sendActive = input.isNotBlank()
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(40.dp)
                        .pressable(sendInteraction)
                        .clip(CircleShape)
                        .background(if (sendActive) Coral else UserBubble)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = sendActive,
                        ) { send() }
                        .semantics { contentDescription = "Отправить" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SendIcon, null, tint = if (sendActive) Cream else InkSoft, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    // Полноэкранный поиск по каталогу RouterAI (~400 моделей) — как оверлей (Dialog),
    // иначе обычный Column не перекрывает чат и получает 0 высоты.
    // Панель «+»: загрузка файлов, тумблеры и режимы — вместо россыпи пилюль (разгрузка ввода, как у Qwen).
    if (showTools) {
        val cloudOn = ru.aiagent.app.cloud.CloudEngine.isConfigured(context)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showTools = false },
            containerColor = ru.aiagent.app.ui.P.Surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                // --- Загрузка ---
                Text("Загрузить", color = InkSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    @Composable
                    fun upTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(UserBubble).clickable { onClick(); showTools = false }
                                .padding(vertical = 14.dp),
                        ) {
                            Icon(icon, label, tint = Ink, modifier = Modifier.size(24.dp))
                            Text(label, color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    upTile(Icons.Outlined.Description, "Документ") { docPicker.launch(arrayOf("*/*")) }
                    upTile(Icons.Outlined.Image, "Фото") { imagePicker.launch(arrayOf("image/*")) }
                    upTile(Icons.Outlined.Videocam, "Видео") { videoPicker.launch(arrayOf("video/*")) }
                }

                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 14.dp), color = ru.aiagent.app.ui.P.Line)

                // --- Тумблеры ---
                @Composable
                fun toggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, on: Boolean, onToggle: () -> Unit) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 10.dp),
                    ) {
                        Icon(icon, null, tint = if (on) Coral else Ink, modifier = Modifier.size(22.dp))
                        Text("  $title", color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = on, onCheckedChange = { onToggle() })
                    }
                }
                toggleRow(Icons.Outlined.Build, "Инструменты (агент)", agentMode) {
                    agentMode = !agentMode; AppSettings.setAgentMode(context, agentMode); if (agentMode) hintOnce("agent")
                }
                toggleRow(Icons.Outlined.Lightbulb, "Мышление", reasoning) {
                    reasoning = !reasoning; AppSettings.setReasoning(context, reasoning); if (reasoning) hintOnce("reasoning")
                }
                val remoteBackend = remember { AppSettings.remoteBackendEnabled(context) }
                var remoteOn by remember { mutableStateOf(remoteBackend) }
                toggleRow(Icons.Outlined.Build, "PC backend", remoteOn) {
                    remoteOn = !remoteOn; AppSettings.setRemoteBackendEnabled(context, remoteOn)
                }

                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 14.dp), color = ru.aiagent.app.ui.P.Line)

                // --- Режимы (взаимоисключающие) ---
                @Composable
                fun modeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().let { if (enabled) it.clickable { onClick() } else it }
                            .padding(vertical = 11.dp).alpha(if (enabled) 1f else 0.4f),
                    ) {
                        Icon(icon, null, tint = if (on) Coral else Ink, modifier = Modifier.size(22.dp))
                        Text("  $title", color = if (on) Coral else Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (on) Icon(Icons.Outlined.Check, "включено", tint = Coral, modifier = Modifier.size(18.dp))
                    }
                }
                // Глубокое исследование (промпт-режим — работает и локально; сильнее с облаком).
                modeRow(Icons.Outlined.School, "Глубокое исследование", specMode == SpecMode.RESEARCH) {
                    specMode = if (specMode == SpecMode.RESEARCH) SpecMode.NONE else SpecMode.RESEARCH; showTools = false
                }
                modeRow(Icons.Outlined.Bolt, "Артефакты", specMode == SpecMode.ARTIFACT) {
                    specMode = if (specMode == SpecMode.ARTIFACT) SpecMode.NONE else SpecMode.ARTIFACT; showTools = false
                }
                modeRow(Icons.Outlined.Code, "Веб-разработка", specMode == SpecMode.WEBDEV) {
                    specMode = if (specMode == SpecMode.WEBDEV) SpecMode.NONE else SpecMode.WEBDEV; showTools = false
                }
                // Оркестратор — только с облаком (многомодельный план→исполнение→оценка).
                // Тап включает/выключает БЕЗ закрытия панели; под ним — выбор пресета (регулировка).
                modeRow(Icons.Outlined.AccountTree, "Оркестратор", orchestrator, enabled = cloudOn) {
                    orchestrator = !orchestrator; if (orchestrator) hintOnce("orchestrator")
                }
                if (orchestrator) {
                    val isCustom = orchPreset.name.startsWith("Свои модели")
                    ru.aiagent.app.cloud.ORCHESTRATOR_PRESETS.forEach { p ->
                        val sel = !isCustom && orchPreset.name == p.name
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                orchPreset = p; AppSettings.clearOrchCustom(context)
                            }.padding(start = 34.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Icon(
                                if (sel) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                null, tint = if (sel) Coral else InkSoft, modifier = Modifier.size(18.dp),
                            )
                            Text("  ${p.name}", color = if (sel) Coral else Ink, fontSize = 14.sp)
                        }
                    }
                    // Свои модели: главная + несколько рабочих (открывает конструктор).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { showOrchConfig = true }
                            .padding(start = 34.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Icon(
                            if (isCustom) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                            null, tint = if (isCustom) Coral else InkSoft, modifier = Modifier.size(18.dp),
                        )
                        Text(
                            if (isCustom) "  ${orchPreset.name} · настроить" else "  Свои модели…",
                            color = if (isCustom) Coral else Ink, fontSize = 14.sp,
                        )
                    }
                }
                // Проект — постоянные инструкции (роль/контекст) для всех запросов.
                modeRow(Icons.Outlined.Folder, "Проект" + (if (projectInstr.isNotBlank()) " · активен" else ""),
                    projectInstr.isNotBlank()) {
                    showTools = false; showProjectEditor = true
                }
            }
        }
    }

    // Редактор инструкций проекта.
    if (showProjectEditor) {
        var draft by remember { mutableStateOf(projectInstr) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showProjectEditor = false },
            containerColor = ru.aiagent.app.ui.P.Surface,
            title = { Text("Проект", color = Ink) },
            text = {
                Column {
                    Text("Постоянные инструкции: роль, контекст, правила. Идут в каждый запрос, пока проект активен.",
                        color = InkSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("Напр.: Ты помощник отдела СПО. Отвечай официально, ссылайся на приказы…", color = InkSoft) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    projectInstr = draft.trim(); AppSettings.setProjectInstructions(context, projectInstr); showProjectEditor = false
                }) { Text("Сохранить", color = Coral) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    projectInstr = ""; AppSettings.setProjectInstructions(context, ""); showProjectEditor = false
                }) { Text("Выключить", color = InkSoft) }
            },
        )
    }

    if (showCloudPicker) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCloudPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CloudModelPicker(
                onPick = { info ->
                    cloudModel = info.id; modelLabel = "${info.name}"
                    AppSettings.saveModel(context, null, info.id, "${info.name}"); showCloudPicker = false
                },
                onBack = { showCloudPicker = false },
            )
        }
    }

    // Конструктор своих моделей оркестратора: одна главная (планировщик/критик) + рабочие (исполнители).
    if (showOrchConfig) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // Засеиваем черновик текущим пресетом — чтобы «Свои модели» стартовали от того, что выбрано.
            cfgPlanner = orchPreset.planner
            cfgWorkers.clear(); cfgWorkers.addAll(orchPreset.executors)
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showOrchConfig = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(Modifier.fillMaxSize().background(Cream).statusBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", tint = Ink,
                        modifier = Modifier.clickable { showOrchConfig = false }.padding(end = 8.dp).size(24.dp))
                    Text("Оркестратор — свои модели", color = Ink, fontSize = 17.sp)
                }
                Text("Главная планирует задачу и оценивает итог. Рабочие выполняют. Одна рабочая — делает весь план; несколько — шаги плана делятся между ними.",
                    color = InkSoft, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 14.dp))

                Text("ГЛАВНАЯ (планировщик + критик)", color = InkSoft, fontSize = 11.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)
                        .clip(RoundedCornerShape(12.dp)).background(UserBubble)
                        .clickable { orchPickTarget = "planner" }.padding(12.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Coral, modifier = Modifier.size(20.dp))
                    Text("  ${cfgPlanner?.substringAfterLast('/') ?: "выбрать модель"}",
                        color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Edit, "Изменить", tint = InkSoft, modifier = Modifier.size(18.dp))
                }

                Text("РАБОЧИЕ (исполнители) · ${cfgWorkers.size}", color = InkSoft, fontSize = 11.sp)
                Column(Modifier.weight(1f, fill = false)) {
                    cfgWorkers.toList().forEach { w ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                .clip(RoundedCornerShape(12.dp)).background(UserBubble).padding(12.dp),
                        ) {
                            Icon(Icons.Outlined.Build, null, tint = InkSoft, modifier = Modifier.size(18.dp))
                            Text("  ${w.substringAfterLast('/')}", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.Close, "Убрать", tint = InkSoft,
                                modifier = Modifier.size(18.dp).clickable { cfgWorkers.remove(w) })
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clickable { orchPickTarget = "worker" }.padding(vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Add, null, tint = Coral, modifier = Modifier.size(20.dp))
                    Text("  Добавить рабочую", color = Coral, fontSize = 14.sp)
                }

                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = {
                        AppSettings.clearOrchCustom(context)
                        orchPreset = ru.aiagent.app.cloud.ORCHESTRATOR_PRESETS.first()
                        showOrchConfig = false
                    }) { Text("Сбросить", color = InkSoft) }
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { showOrchConfig = false }) { Text("Отмена", color = InkSoft) }
                    androidx.compose.material3.TextButton(
                        enabled = cfgPlanner != null,
                        onClick = {
                            val planner = cfgPlanner ?: return@TextButton
                            val workers = cfgWorkers.toList().ifEmpty { listOf(planner) }
                            AppSettings.setOrchCustom(context, planner, workers)
                            orchPreset = ru.aiagent.app.cloud.OrchestratorPreset.custom(planner, workers)
                            showOrchConfig = false
                        },
                    ) { Text("Сохранить", color = Coral) }
                }
            }
        }
    }

    // Пикер модели для конструктора оркестратора (переиспользуем каталожный CloudModelPicker).
    if (orchPickTarget != null) {
        val target = orchPickTarget
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { orchPickTarget = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CloudModelPicker(
                onPick = { info ->
                    if (target == "planner") cfgPlanner = info.id
                    else if (!cfgWorkers.contains(info.id)) cfgWorkers.add(info.id)
                    orchPickTarget = null
                },
                onBack = { orchPickTarget = null },
            )
        }
    }

    // Диалог подтверждения опасной операции (§3.6): normal/auto спрашивают, bypass — нет.
    pendingConfirm?.let { (prompt, deferred) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deferred.complete(false); pendingConfirm = null },
            title = { Text("Подтвердите действие") },
            text = { Text(prompt) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { deferred.complete(true); pendingConfirm = null }) {
                    Text("Разрешить", color = Coral)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deferred.complete(false); pendingConfirm = null }) {
                    Text("Отклонить", color = InkSoft)
                }
            },
        )
    }

    // Разовый промпт «фоновая генерация»: без исключения из оптимизации батареи OEM убивает сервис.
    if (showBatteryPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBatteryPrompt = false; BatteryOptimization.markAsked(context) },
            title = { Text("Генерация в фоне") },
            text = {
                Text(
                    "Чтобы ответ не обрывался, когда приложение свёрнуто или экран погас, разрешите работу без " +
                        "ограничений батареи. Иначе система усыпляет процесс — и локальная модель встаёт (как в Termux: " +
                        "сессия держится, пока приложение не в экономии).",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showBatteryPrompt = false; BatteryOptimization.request(context) }) {
                    Text("Разрешить", color = Coral)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showBatteryPrompt = false; BatteryOptimization.markAsked(context) }) {
                    Text("Позже", color = InkSoft)
                }
            },
        )
    }

    // Обучающая подсказка при первом включении режима.
    hintText?.let { txt ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { hintText = null },
            title = { Text("Подсказка") },
            text = { Text(txt) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { hintText = null }) { Text("Понятно", color = Coral) }
            },
        )
    }
}

/** Картинка-вложение (график matplotlib и т.п.) из файла. */
@Composable
private fun ChatImage(path: String) {
    // Декод ВНЕ главного потока + даунсемпл до ~1600px по стороне (большое фото/график
    // иначе даёт джанк в композиции и десятки МБ несжатых пикселей → OOM).
    val bitmapState by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { decodeDownsampled(path, 1600)?.asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = bitmapState
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = "график",
            modifier = Modifier
                .fillMaxWidth()
                .background(UserBubble.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(4.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
        )
    } else {
        Text("[не удалось показать изображение]", color = InkSoft, fontSize = 12.sp)
    }
}

/** Декод картинки с даунсемплом (inSampleSize) до maxSide по большей стороне. */
private fun decodeDownsampled(path: String, maxSide: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val (w, h) = bounds.outWidth to bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (w / sample > maxSide || h / sample > maxSide) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(path, opts)
}

/** Ответ ассистента: если есть <think>…</think> — размышление в свёрнутом блоке (как DeepSeek). */
@Composable
private fun AssistantMessage(raw: String) {
    val openIdx = raw.indexOf("<think>")
    if (openIdx < 0) {
        ru.aiagent.app.ui.MarkdownText(raw, color = Ink, muted = InkSoft, codeBg = UserBubble, accent = Coral)
        return
    }
    val afterOpen = raw.substring(openIdx + "<think>".length)
    val closeIdx = afterOpen.indexOf("</think>")
    val inProgress = closeIdx < 0
    val thinking = (if (inProgress) afterOpen else afterOpen.substring(0, closeIdx)).trim()
    val answer = if (inProgress) "" else afterOpen.substring(closeIdx + "</think>".length).trim()
    val preface = raw.substring(0, openIdx).trim()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (preface.isNotEmpty()) ru.aiagent.app.ui.MarkdownText(preface, color = Ink, muted = InkSoft, codeBg = UserBubble, accent = Coral)
        if (thinking.isNotEmpty()) {
            // Шторка размышлений (паритет с десктопом): пока думает — пульсирующие точки + «Размышляю…»
            // и живой поток; как пошёл ответ — сворачивается в «💭 Размышления · Nс» с раскрытием по клику.
            var expanded by remember(inProgress) { mutableStateOf(inProgress) }
            // Длительность засекаем только для ЖИВОГО размышления (у истории она неизвестна).
            val startedLive = remember { inProgress }
            val startTime = remember { System.currentTimeMillis() }
            var thoughtSecs by remember { mutableStateOf(0) }
            LaunchedEffect(inProgress) {
                if (startedLive && !inProgress && thoughtSecs == 0)
                    thoughtSecs = ((System.currentTimeMillis() - startTime) / 1000).toInt().coerceAtLeast(1)
            }
            val durText = when {
                thoughtSecs >= 60 -> " · ${thoughtSecs / 60} мин ${thoughtSecs % 60} с"
                thoughtSecs > 0 -> " · $thoughtSecs с"
                else -> ""
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ru.aiagent.app.ui.P.Surface, RoundedCornerShape(12.dp))
                    .border(1.dp, ru.aiagent.app.ui.P.Line, RoundedCornerShape(12.dp))
                    .let { if (inProgress) it else it.clickable { expanded = !expanded } }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (inProgress) {
                    ThinkingDots(label = "Размышляю…")
                } else {
                    Text(
                        "Размышления$durText  " + (if (expanded) "▲" else "▼"),
                        color = InkSoft, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    )
                }
                if (expanded) Text(thinking, color = InkSoft, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        if (answer.isNotEmpty()) ru.aiagent.app.ui.MarkdownText(answer, color = Ink, muted = InkSoft, codeBg = UserBubble, accent = Coral)
    }
}

// --- Категоризация каталога облачных моделей: КУРИРУЕМЫЕ списки (отобраны вручную) ---
// Совпадение по подстроке id. Что не попало ни в одну категорию → «Новинки».
private val CUR_FLAGSHIP = listOf("claude-opus-4", "claude-sonnet-4", "claude-3.7-sonnet", "gpt-4.1", "gpt-4o",
    "gpt-5", "/o1", "/o3", "gemini-2.5-pro", "gemini-1.5-pro", "deepseek-v4", "deepseek-r1", "grok-4", "grok-3",
    "qwen-max", "mistral-large")
private val CUR_CODE = listOf("coder", "codestral", "code-llama", "starcoder", "deepseek-coder", "qwen-2.5-coder", "qwen3-coder")
private val CUR_REASON = listOf("/o1", "/o3", "deepseek-r1", "-r1", "qwq", "reasoning", "thinking", "gemini-2.5-pro", "claude-opus-4")
private val CUR_VALUE = listOf("claude-3.5-sonnet", "claude-3.7-sonnet", "gpt-4o-mini", "gemini-2.0-flash", "gemini-flash-1.5",
    "deepseek-chat", "deepseek-v4-flash", "qwen-2.5-72b", "llama-3.3-70b", "mistral-small-3")
private val CUR_CHEAP = listOf("gpt-4o-mini", "gpt-5-nano", "gemini-flash", "gemini-2.0-flash-lite", "claude-3-haiku",
    "claude-3.5-haiku", "deepseek-v4-flash", "qwen-2.5-7b", "llama-3.1-8b", "ministral", "mistral-7b")
private val CUR_GENERAL = listOf("deepseek-v4-flash", "deepseek-chat", "gpt-4o-mini", "gemini-flash", "gemini-2.0-flash",
    "claude-3.5-haiku", "claude-3-haiku", "llama-3.3-70b", "llama-3.1-8b", "mistral-small", "qwen-2.5-72b")

// Порог «экономных» по цене выхода (₽ за 1M токенов, с наценкой). Модель дешевле него = экономная —
// так «Экономные» гарантированно самые дешёвые, и «Новинки» не оказываются дешевле их (правка C).
private const val CHEAP_MAX_RUB_1M = 40.0

private fun modelCompany(id: String): String = id.substringBefore('/', "прочее").lowercase()
private fun modelSizeB(m: ru.aiagent.app.cloud.CloudModelInfo): Int? =
    Regex("(\\d+(?:\\.\\d+)?)\\s*b\\b", RegexOption.IGNORE_CASE)
        .find(m.id + " " + m.name)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()

private data class ModelCat(val label: String, val f: (ru.aiagent.app.cloud.CloudModelInfo) -> Boolean)
private fun idHas(kw: List<String>): (ru.aiagent.app.cloud.CloudModelInfo) -> Boolean =
    { m -> kw.any { m.id.contains(it, true) } }
private fun modelCats(): List<ModelCat> {
    val curated = listOf(
        ModelCat("Флагманы", idHas(CUR_FLAGSHIP)),
        ModelCat("Для кода", idHas(CUR_CODE)),
        ModelCat("Рассуждения/юристы", idHas(CUR_REASON)),
        ModelCat("Цена/качество", idHas(CUR_VALUE)),
        // Экономные = реально дешёвые по цене (плюс явно курируемые дешёвые) — чтобы «Новинки»
        // не оказывались дешевле «Экономных» (правка C).
        ModelCat("Экономные") { m -> m.priceOut in 0.0..CHEAP_MAX_RUB_1M || idHas(CUR_CHEAP)(m) },
        ModelCat("Обычный юзер", idHas(CUR_GENERAL)),
    )
    // «Новинки» — всё, что не попало ни в один курируемый список.
    return listOf(ModelCat("Все") { true }) + curated +
        ModelCat("Новинки") { m -> curated.none { it.f(m) } }
}

@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp)
            .background(if (selected) Coral else UserBubble, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (selected) Cream else Ink, fontSize = 12.5.sp) }
}

@Composable
private fun CloudModelPicker(onPick: (ru.aiagent.app.cloud.CloudModelInfo) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<ru.aiagent.app.cloud.CloudModelInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var catIdx by remember { mutableStateOf(0) }
    var company by remember { mutableStateOf<String?>(null) }
    var sizeBucket by remember { mutableStateOf(0) } // 0 все, 1 малые<10B, 2 средние 10-70B, 3 большие>70B
    var priceAsc by remember { mutableStateOf(true) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { ru.aiagent.app.cloud.CloudModels.fetchAll(ctx) }
            .onSuccess { all = it }.onFailure { loadError = it.message }
        loading = false
    }
    val cats = remember { modelCats() }
    val companies = remember(all) { all.map { modelCompany(it.id) }.distinct().sortedBy { it } }
    val filtered = remember(query, all, catIdx, company, sizeBucket, priceAsc) {
        all.asSequence()
            .filter { ru.aiagent.app.cloud.modelMatchesQuery(it, query) }
            .filter { cats[catIdx].f(it) }
            .filter { company == null || modelCompany(it.id) == company }
            .filter {
                if (sizeBucket == 0) true else {
                    val s = modelSizeB(it)
                    s != null && when (sizeBucket) { 1 -> s < 10; 2 -> s in 10..70; else -> s > 70 }
                }
            }
            .sortedBy { if (priceAsc) it.priceOut else -it.priceOut }
            .toList()
    }
    Column(modifier = Modifier.fillMaxSize().background(Cream).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад", tint = Ink,
                modifier = Modifier.clickable { onBack() }.padding(end = 8.dp).size(24.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Поиск среди ${all.size} моделей…", color = InkSoft) },
                singleLine = true, shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Coral, unfocusedBorderColor = UserBubble,
                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                ),
            )
            Text(if (priceAsc) " ₽↑" else " ₽↓", color = Coral, fontSize = 15.sp,
                modifier = Modifier.clickable { priceAsc = !priceAsc }.padding(6.dp))
        }
        // Категории
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            cats.forEachIndexed { i, c -> PickChip(c.label, catIdx == i) { catIdx = i } }
        }
        // Фирмы
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp)) {
            PickChip("Все фирмы", company == null) { company = null }
            companies.forEach { c -> PickChip(c, company == c) { company = if (company == c) null else c } }
        }
        // Размер
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp)) {
            listOf("Любой размер", "Малые <10B", "Средние 10–70B", "Большие >70B").forEachIndexed { i, l ->
                PickChip(l, sizeBucket == i) { sizeBucket = i }
            }
        }
        HorizontalDivider(color = UserBubble)
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Coral) }
            loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("Не удалось загрузить каталог: $loadError", color = InkSoft, fontSize = 13.sp)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                item {
                    Text("Найдено: ${filtered.size}", color = InkSoft, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                }
                items(filtered) { m ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, color = Ink, fontSize = 14.5.sp, maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false))
                            if (m.freePool) DiscountBadge()
                        }
                        Text(
                            "${modelCompany(m.id)}${modelSizeB(m)?.let { " · ${it}B" } ?: ""}  ·  %.0f/%.0f ₽/1M".format(m.priceIn, m.priceOut),
                            color = InkSoft, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                    HorizontalDivider(color = UserBubble.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Плашка-логотип 56×56, радиус 18, фон accentSoft (референс).
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ru.aiagent.app.ui.P.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Coral, modifier = Modifier.size(28.dp))
        }
        // Серифное приветствие ~34sp (референс Claude).
        Text(
            "Чем помочь?",
            color = Ink,
            fontFamily = FontFamily.Serif,
            fontSize = 34.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Первый ответ загрузит модель (~4 с). Попробуй:",
            color = InkSoft,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // «Идеи» — строки-кнопки: иконка акцентом слева, текст, «→» справа; рамка line, фон surface.
        listOf(
            "Составь план на завтра и поставь будильник на 7:30",
            "Прочитай PDF из загрузок и сделай краткое резюме",
            "Посчитай в Python: рост вклада 100 000 ₽ под 16% за 5 лет",
            "Создай таблицу расходов на месяц в Excel",
        ).forEach { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ru.aiagent.app.ui.P.Surface)
                    .border(1.dp, ru.aiagent.app.ui.P.Line, RoundedCornerShape(14.dp))
                    .clickable { onSuggestion(s) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(s, color = Ink, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Text("  →", color = Coral, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Имя документа из content-uri (DISPLAY_NAME), с запасным вариантом. */
private fun docName(context: android.content.Context, uri: android.net.Uri): String {
    var name: String? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
    }
    return (name ?: uri.lastPathSegment ?: "document").substringAfterLast('/')
}

// Простая векторная «стрелка вверх» (без Material Icons — держим зависимости минимальными).
private val SendIcon: ImageVector = ImageVector.Builder(
    name = "send", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
        moveTo(12f, 4f)
        lineTo(5f, 11f)
        lineTo(6.4f, 12.4f)
        lineTo(11f, 7.8f)
        lineTo(11f, 20f)
        lineTo(13f, 20f)
        lineTo(13f, 7.8f)
        lineTo(17.6f, 12.4f)
        lineTo(19f, 11f)
        close()
    }
}.build()
