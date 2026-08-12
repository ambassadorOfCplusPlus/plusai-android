package ru.aiagent.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.aiagent.app.cloud.CloudTaskClient
import ru.aiagent.app.ui.P

/**
 * Экран ОБЛАЧНОЙ ЗАДАЧИ (Фаза 3) — интерактивный, как диалог: выбираешь файлы спец-кнопкой, ставишь
 * задачу, агент показывает план, может уточнить (ask_user), а ты в любой момент можешь заглянуть и
 * дописать/спросить — даже пока он генерирует (steering). Задача выполняется НА СЕРВЕРЕ под аккаунтом.
 */
@Composable
fun CloudTaskScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tasks = remember { mutableStateListOf<CloudTaskClient.Task>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        CloudTaskClient.list(context)
            .onSuccess { tasks.clear(); tasks.addAll(it) }
            .onFailure { loadError = "Не удалось загрузить задачи: ${it.message}" }
    }

    val selected = tasks.firstOrNull { it.id == selectedId }
    if (selected != null) {
        TaskChat(
            initial = selected,
            onBack = { selectedId = null },
            onUpdated = { upd -> val i = tasks.indexOfFirst { it.id == upd.id }; if (i >= 0) tasks[i] = upd },
        )
        return
    }

    CloudTaskList(
        tasks = tasks,
        loadError = loadError,
        onBack = onBack,
        onOpen = { selectedId = it.id },
        onCreated = { t -> tasks.add(0, t); selectedId = t.id },
    )
}

/** Список задач + форма создания (запрос, модель, файлы). Создание открывает чат задачи. */
@Composable
private fun CloudTaskList(
    tasks: List<CloudTaskClient.Task>,
    loadError: String?,
    onBack: () -> Unit,
    onOpen: (CloudTaskClient.Task) -> Unit,
    onCreated: (CloudTaskClient.Task) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    val files = remember { mutableStateListOf<CloudTaskClient.Upload>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(loadError) }
    var modelOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        modelOptions = runCatching { ru.aiagent.app.cloud.CloudModels.fetchAll(context) }
            .getOrDefault(emptyList()).map { it.id to it.name }
        if (model.isBlank()) model = modelOptions.firstOrNull()?.first ?: ""
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val maxPer = 10L * 1024 * 1024
            val maxTotal = 12L * 1024 * 1024
            for (uri in uris) {
                runCatching {
                    val name = queryName(context, uri)
                    val size = querySize(context, uri)
                    if (size > maxPer) { status = "Файл $name > 10 МБ — пропущен"; return@runCatching }
                    val already = files.sumOf { it.bytes.size.toLong() }
                    if (size in 1..maxTotal && already + size > maxTotal) { status = "Суммарно > 12 МБ — пропущен $name"; return@runCatching }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                    if (bytes.size > maxPer) { status = "Файл $name > 10 МБ — пропущен"; return@runCatching }
                    if (already + bytes.size > maxTotal) { status = "Суммарно > 12 МБ — пропущен $name"; return@runCatching }
                    files.add(CloudTaskClient.Upload(name, bytes))
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", tint = P.Text,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp).size(24.dp))
            Text("Облачные задачи", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = P.Text)
        }
        HorizontalDivider(color = P.Line)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Задача выполнится на сервере под твоим аккаунтом (с твоими файлами). Агент покажет план, " +
                    "может уточнить, а ты в любой момент можешь дописать или спросить — даже пока он работает.",
                    color = P.TextSoft, fontSize = 12.5.sp)
            }
            item {
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text("Что сделать?", color = P.TextSoft) },
                    placeholder = { Text("напр.: разбери прикреплённые счета и сведи в таблицу", color = P.TextSoft) },
                    modifier = Modifier.fillMaxWidth().height(120.dp), colors = fieldColors(),
                )
            }
            item {
                Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { showModels = !showModels }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Модель", color = P.TextSoft, fontSize = 12.sp)
                            Text(modelOptions.firstOrNull { it.first == model }?.second ?: model.ifBlank { "не выбрана" },
                                color = P.Text, fontSize = 15.sp)
                        }
                        Text(if (showModels) "▲" else "▼", color = P.TextSoft, fontSize = 14.sp)
                    }
                    if (showModels) {
                        OutlinedTextField(
                            value = modelQuery, onValueChange = { modelQuery = it }, singleLine = true,
                            placeholder = { Text("поиск модели", color = P.TextSoft) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = fieldColors(),
                        )
                        val filtered = modelOptions.filter {
                            modelQuery.isBlank() || it.first.contains(modelQuery, true) || it.second.contains(modelQuery, true)
                        }.take(30)
                        for ((id, name) in filtered) {
                            Text(name, color = if (id == model) P.Accent else P.Text, fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth().clickable { model = id; showModels = false }.padding(vertical = 8.dp))
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Файлы (${files.size})", color = P.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("+ Выбрать файлы", color = P.Accent, fontSize = 14.sp,
                            modifier = Modifier.clickable { runCatching { picker.launch(arrayOf("*/*")) } })
                    }
                    for ((idx, f) in files.withIndex()) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${f.name} · ${f.bytes.size / 1024} КБ", color = P.TextSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("Убрать", color = P.Accent, fontSize = 12.sp, modifier = Modifier.clickable { files.removeAt(idx) })
                        }
                    }
                }
            }
            item {
                status?.let { Text(it, color = P.Accent, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                Text(
                    if (busy) "Создаю…" else "Поставить задачу",
                    color = P.Bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().background(P.Accent, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy) {
                            if (prompt.isBlank()) { status = "Впиши, что сделать"; return@clickable }
                            if (model.isBlank()) { status = "Выбери модель"; return@clickable }
                            busy = true; status = null
                            scope.launch {
                                CloudTaskClient.create(context, prompt.trim(), model, files.toList())
                                    .onSuccess { t -> prompt = ""; files.clear(); onCreated(t) }
                                    .onFailure { status = "Ошибка: ${it.message?.take(80)}" }
                                busy = false
                            }
                        }.padding(vertical = 14.dp),
                )
            }
            if (tasks.isNotEmpty()) {
                item { Text("Задачи", color = P.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                items(tasks.size) { i -> TaskRow(tasks[i]) { onOpen(tasks[i]) } }
            }
        }
    }
}

/** Компактная строка задачи в списке (тап открывает диалог). */
@Composable
private fun TaskRow(t: CloudTaskClient.Task, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.background(P.Surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(t.status)
            Spacer(Modifier.size(8.dp))
            Text(statusLabel(t), color = P.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (t.chargeKop > 0) Text("%.2f ₽".format(t.chargeKop / 100.0), color = P.TextSoft, fontSize = 12.sp)
        }
        if (t.prompt.isNotBlank()) Text(t.prompt, color = P.TextSoft, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Диалог одной задачи: тред сообщений + поле для доспроса/ответа (можно писать и во время работы). */
@Composable
private fun TaskChat(
    initial: CloudTaskClient.Task,
    onBack: () -> Unit,
    onUpdated: (CloudTaskClient.Task) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember(initial.id) { mutableStateOf(initial) }
    var input by remember(initial.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun setTask(t: CloudTaskClient.Task) { task = t; onUpdated(t) }

    // Опрос статуса, пока задача работает или ждёт ответа (needs_input тоже опрашиваем — вдруг таймаут).
    LaunchedEffect(task.id, task.status, task.messages.size) {
        while (task.status == "running" || task.status == "needs_input") {
            delay(2500)
            CloudTaskClient.get(context, task.id).onSuccess { if (it.id == task.id) setTask(it) }
        }
    }
    // Прокрутка к последнему сообщению.
    LaunchedEffect(task.messages.size, task.status) {
        val n = task.messages.size
        if (n > 0) listState.animateScrollToItem(n)
    }

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "К списку", tint = P.Text,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp).size(24.dp))
            Column(Modifier.weight(1f)) {
                Text("Облачная задача", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.Text)
                Text(statusLabel(task), color = P.TextSoft, fontSize = 12.sp)
            }
            if (task.chargeKop > 0) Text("%.2f ₽".format(task.chargeKop / 100.0), color = P.TextSoft, fontSize = 12.sp)
        }
        HorizontalDivider(color = P.Line)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(task.messages.size) { i -> MessageBubble(task.messages[i]) }
            if (task.status == "running") {
                item { WorkingRow(task.steps) }
            }
            val tail = task.answer.ifBlank { task.error }
            if ((task.status == "done" || task.status == "error") && tail.isNotBlank() &&
                task.messages.none { it.role == "assistant" && it.text == tail }
            ) {
                item { MessageBubble(CloudTaskClient.Msg(if (task.status == "error") "question" else "assistant", tail, 0)) }
            }
            if (task.outputs.isNotEmpty()) {
                item { Text("Файлы: ${task.outputs.joinToString(", ")}", color = P.TextSoft, fontSize = 12.sp) }
            }
        }

        err?.let { Text(it, color = P.Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        if (task.status == "needs_input") {
            Text("Агент ждёт твоего ответа", color = P.Accent, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        // Композер: писать можно всегда — и для ответа, и чтобы вклиниться во время работы.
        Row(
            Modifier.fillMaxWidth().padding(10.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = {
                    Text(
                        when (task.status) {
                            "needs_input" -> "Ответь агенту…"
                            "running" -> "Уточнить/дописать на ходу…"
                            else -> "Спросить ещё или продолжить…"
                        }, color = P.TextSoft,
                    )
                },
                modifier = Modifier.weight(1f), colors = fieldColors(), maxLines = 4,
            )
            Spacer(Modifier.size(8.dp))
            val canSend = input.isNotBlank() && !sending
            Box(
                Modifier.size(48.dp).background(if (canSend) P.Accent else P.Line, RoundedCornerShape(14.dp))
                    .clickable(enabled = canSend) {
                        val txt = input.trim(); input = ""; sending = true; err = null
                        scope.launch {
                            CloudTaskClient.postMessage(context, task.id, txt)
                                .onSuccess { setTask(it) }
                                .onFailure { err = "Не отправилось: ${it.message?.take(60)}" }
                            sending = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (sending) CircularProgressIndicator(color = P.Bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.AutoMirrored.Outlined.Send, "Отправить", tint = if (canSend) P.Bg else P.TextSoft, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: CloudTaskClient.Msg) {
    when (msg.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(msg.text, color = P.Bg, fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 300.dp).background(P.Accent, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(10.dp, 8.dp))
        }
        "plan" -> Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("ПЛАН", color = P.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(msg.text, color = P.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        "question" -> Column(
            Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text("ВОПРОС", color = P.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(msg.text, color = P.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Text(msg.text, color = P.Text, fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 320.dp).background(P.Surface, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)).padding(10.dp, 8.dp))
        }
    }
}

@Composable
private fun WorkingRow(steps: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = P.Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(8.dp))
        Text(if (steps > 0) "работаю · шаг $steps" else "думаю…", color = P.TextSoft, fontSize = 13.sp)
    }
}

@Composable
private fun StatusIcon(status: String) {
    when (status) {
        "running" -> CircularProgressIndicator(color = P.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        "needs_input" -> Text("?", color = P.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        "done" -> Text("✓", color = P.Accent, fontSize = 16.sp)
        else -> Text("⚠", color = P.Accent, fontSize = 16.sp)
    }
}

private fun statusLabel(t: CloudTaskClient.Task): String = when (t.status) {
    "running" -> "Выполняется · шаг ${t.steps}"
    "needs_input" -> "Нужен твой ответ"
    "done" -> "Готово"
    else -> "Ошибка"
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = P.Text, unfocusedTextColor = P.Text,
    focusedBorderColor = P.Accent, unfocusedBorderColor = P.Line, cursorColor = P.Accent,
)

/** Имя файла из content-URI (для загрузки на сервер). */
private fun queryName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "file"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
    }
    return name
}

// Размер файла из курсора (OpenableColumns.SIZE) — чтобы отсечь большой файл ДО чтения в память.
private fun querySize(context: android.content.Context, uri: android.net.Uri): Long {
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) size = c.getLong(idx)
        }
    }
    return size
}
