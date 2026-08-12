package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aiagent.app.cloud.CloudModelInfo
import ru.aiagent.app.cloud.CloudModels
import ru.aiagent.app.cloud.modelMatchesQuery
import ru.aiagent.core.agent.AutonomyMode
import ru.aiagent.core.cloud.CloudProvider
import java.io.File

// Геттеры (не val!): P.* реактивны на смену темы/акцента — читаем на каждой рекомпозиции.
private val Cream: Color get() = ru.aiagent.app.ui.P.Bg
private val Ink: Color get() = ru.aiagent.app.ui.P.Text
private val InkSoft: Color get() = ru.aiagent.app.ui.P.TextSoft
private val Coral: Color get() = ru.aiagent.app.ui.P.Accent
private val Card: Color get() = ru.aiagent.app.ui.P.Surface
private val Line: Color get() = ru.aiagent.app.ui.P.Line

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppSettings.mode(context)) }
    var planEnabled by remember { mutableStateOf(AppSettings.planEnabled(context)) }
    var strictIntegrity by remember { mutableStateOf(AppSettings.strictIntegrity(context)) }
    var speak by remember { mutableStateOf(AppSettings.speakByDefault(context)) }
    var models by remember { mutableStateOf(ChatEngine.availableModels(context)) }
    // Общий на весь экран: и секция «Файлы», и список инструментов должны видеть один флаг.
    var fileAccess by remember { mutableStateOf(AppSettings.fileAccessEnabled(context)) }
    // Генерация картинок: выбранная image-модель и открытие полноэкранного пикера. Состояние поднято
    // сюда, чтобы выбор из оверлея-пикера сразу отражался в строке секции.
    var imageModel by remember { mutableStateOf(AppSettings.imageModel(context)) }
    var imagePickerOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp, 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад", tint = Ink,
                modifier = Modifier.clickable { onBack() })
            Text("  Настройки", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        HorizontalDivider(color = Line)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Разделы (перенесены из бокового меню — разгружаем главный экран, правка F).
            item {
                Section("Разделы") {
                    NavRow("Модели") { onNavigate("models") }
                    HorizontalDivider(color = Line)
                    NavRow("Инструменты") { onNavigate("tools") }
                    HorizontalDivider(color = Line)
                    NavRow("Расширения") { onNavigate("extensions") }
                    HorizontalDivider(color = Line)
                    NavRow("История правок") { onNavigate("history") }
                    HorizontalDivider(color = Line)
                    NavRow("Свой API (ключ / свой endpoint) — бесплатно") { onNavigate("byokpro") }
                    HorizontalDivider(color = Line)
                    NavRow("Интеграции") { onNavigate("integrations") }
                    HorizontalDivider(color = Line)
                    NavRow("MCP-серверы (внешние инструменты)") { onNavigate("mcp") }
                }
            }
            // Оформление (редизайн): тема (тёмная/светлая) + акцент. Пишем в AppSettings и в P —
            // P.* реактивны, поэтому весь UI перекрашивается сразу.
            item {
                Section("Оформление") {
                    ToggleRow(
                        "Светлая тема",
                        ru.aiagent.app.ui.P.mode == ru.aiagent.app.ui.ThemeMode.LIGHT,
                    ) { on ->
                        ru.aiagent.app.ui.P.mode =
                            if (on) ru.aiagent.app.ui.ThemeMode.LIGHT else ru.aiagent.app.ui.ThemeMode.DARK
                        AppSettings.setLightTheme(context, on)
                    }
                    HorizontalDivider(color = Line)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Акцент", color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val light = ru.aiagent.app.ui.P.mode == ru.aiagent.app.ui.ThemeMode.LIGHT
                            ru.aiagent.app.ui.AccentChoice.entries.forEach { a ->
                                val sel = ru.aiagent.app.ui.P.accent == a
                                val swatch = if (light) a.light else a.dark
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .border(
                                            width = if (sel) 2.dp else 0.dp,
                                            color = if (sel) Ink else Color.Transparent,
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            ru.aiagent.app.ui.P.accent = a
                                            AppSettings.setAccent(context, a.name)
                                        },
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    if (sel) Icon(Icons.Outlined.Check, "выбран", tint = Color.White, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }
            }
            // Режим автономии
            item {
                Section("Режим автономии") {
                    // Plan (read-only) показываем в выборе, только если включён опцией ниже.
                    AutonomyMode.entries.filter { it != AutonomyMode.PLAN || planEnabled }.forEach { m ->
                        ModeRow(m, selected = m == mode) {
                            mode = m
                            AppSettings.setMode(context, m)
                        }
                    }
                    ToggleRow("Показывать Plan-режим (только чтение)", planEnabled) {
                        planEnabled = it
                        AppSettings.setPlanEnabled(context, it)
                        // Выключили, а активен Plan → возвращаемся на Normal (иначе застряли бы в read-only).
                        if (!it && mode == AutonomyMode.PLAN) {
                            mode = AutonomyMode.NORMAL
                            AppSettings.setMode(context, AutonomyMode.NORMAL)
                        }
                    }
                }
            }
            // SSH-подключения (для ssh_run; креды на устройстве, в облако не уходят)
            item {
                Section("SSH-подключения") { SshConnections(context) }
            }
            // Секреты {{secret:имя}} (паритет CLI plusai secret): значения в Keychain, в облако не уходят.
            item {
                Section("Секреты") { SecretsSection(context) }
            }
            // Диагностика: пошаговая проверка связки «клиент → сервер → модель» (как plusai doctor).
            item {
                Section("Диагностика") { DoctorSection(context) }
            }
            // Голос
            item {
                Section("Голос") {
                    ToggleRow("Озвучивать ответы по умолчанию", speak) {
                        speak = it
                        AppSettings.setSpeakByDefault(context, it)
                    }
                }
            }
            // Генерация картинок: пользователь сам ВКЛЮЧАЕТ функцию и ВЫБИРАЕТ облачную image-модель.
            item {
                var imgEnabled by remember { mutableStateOf(AppSettings.imageGenEnabled(context)) }
                Section("Генерация картинок") {
                    ToggleRow("Генерация картинок", imgEnabled) {
                        imgEnabled = it
                        AppSettings.setImageGenEnabled(context, it)
                    }
                    Text(
                        "Платно: картинки генерируются облачной image-моделью по вашему выбору.",
                        color = InkSoft, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    )
                    if (imgEnabled) {
                        HorizontalDivider(color = Line)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { imagePickerOpen = true }
                                .padding(14.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Image-модель", color = Ink, fontSize = 15.sp)
                                Text(imageModel, color = InkSoft, fontSize = 12.sp, maxLines = 1)
                            }
                            Text("Выбрать", color = Coral, fontSize = 13.sp)
                        }
                    }
                }
            }
            // Параметры генерации: по умолчанию — рекомендованные для модели (сервер подставляет из
            // каталога), можно переопределить свою температуру.
            item {
                var customSampling by remember { mutableStateOf(AppSettings.customSampling(context)) }
                var temp by remember { mutableStateOf(AppSettings.temperature(context)) }
                Section("Параметры генерации") {
                    ToggleRow("Свой сэмплинг (иначе — рекомендованный для модели)", customSampling) {
                        customSampling = it
                        AppSettings.setCustomSampling(context, it)
                    }
                    if (customSampling) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                            Text("Температура: ${String.format(java.util.Locale.US, "%.2f", temp)}",
                                color = Ink, fontSize = 13.sp)
                            androidx.compose.material3.Slider(
                                value = temp,
                                onValueChange = { temp = it },
                                onValueChangeFinished = { AppSettings.setTemperature(context, temp) },
                                valueRange = 0f..2f,
                                colors = androidx.compose.material3.SliderDefaults.colors(
                                    thumbColor = Coral, activeTrackColor = Coral,
                                ),
                            )
                            Text("Ниже — точнее и детерминированнее, выше — креативнее.",
                                color = Ink.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    } else {
                        Text("Применяются рекомендованные для каждой модели значения (из каталога RouterAI).",
                            color = Ink.copy(alpha = 0.6f), fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
            // Файлы: доступ ко всем файлам телефона (пользователь выдаёт сам).
            item {
                var granted by remember {
                    mutableStateOf(
                        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                            android.os.Environment.isExternalStorageManager(),
                    )
                }
                // Пере-проверяем при возврате с системного экрана.
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME)
                            granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                                android.os.Environment.isExternalStorageManager()
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                Section("Файлы") {
                    var outsideSandbox by remember { mutableStateOf(AppSettings.outsideSandboxEnabled(context)) }
                    ToggleRow("Доступ агента к файлам", fileAccess) {
                        fileAccess = it
                        AppSettings.setFileAccessEnabled(context, it)
                    }
                    Text(
                        if (fileAccess) "Агент может читать и создавать документы, таблицы, искать по файлам."
                        else "Агент не может читать и записывать ваши документы и файлы.",
                        color = InkSoft, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    )
                    HorizontalDivider(color = Line)
                    ToggleRow("Доступ ко всей файловой системе (только в режиме bypass)", outsideSandbox) {
                        outsideSandbox = it
                        AppSettings.setOutsideSandboxEnabled(context, it)
                    }
                    Text(
                        "Опасно: агент сможет читать/писать любые файлы. Работает только с режимом автономии bypass.",
                        color = InkSoft, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    )
                    HorizontalDivider(color = Line)
                    ToggleRow("Строгая проверка целостности (анти-репак)", strictIntegrity) {
                        strictIntegrity = it
                        AppSettings.setStrictIntegrity(context, it)
                    }
                    Text(
                        "Блокировать спаривание телефон↔ПК, если подпись приложения не совпала с эталоном (репак). " +
                            "Включать ПОСЛЕ вписывания релизного cert в native/integrity — иначе официальная сборка без " +
                            "вписанного cert перестанет спариваться. Активный отладчик блокируется всегда, независимо от флага.",
                        color = InkSoft, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    )
                    HorizontalDivider(color = Line)
                    // Системный доступ ко всем файлам — можно выдать И ОТОЗВАТЬ (открывает системный экран).
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }.onFailure {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }.padding(14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Доступ ко всем файлам", color = Ink, fontSize = 15.sp)
                            Text(
                                if (granted) "Разрешено. Нажмите, чтобы ОТОЗВАТЬ в системе."
                                else "Нужно для работы с реальными файлами. Или ниже — только выбранные папки.",
                                color = InkSoft, fontSize = 12.sp,
                            )
                        }
                        Text(if (granted) "Отозвать" else "Выдать", color = Coral, fontSize = 13.sp)
                    }
                    HorizontalDivider(color = Line)
                    // Гранулярно: ограничить агента конкретными папками (не всё хранилище).
                    var folders by remember { mutableStateOf(AppSettings.allowedFolders(context)) }
                    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                    ) { uri ->
                        if (uri != null) {
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }
                            treeUriToPath(uri)?.let { AppSettings.addAllowedFolder(context, it); folders = AppSettings.allowedFolders(context) }
                        }
                    }
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("Только выбранные папки", color = Ink, fontSize = 15.sp)
                        Text(
                            if (folders.isEmpty()) "Пусто — агент видит всё хранилище (при доступе выше)."
                            else "Агент работает ТОЛЬКО в этих папках (остальное не видит):",
                            color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                        )
                        folders.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(p.removePrefix("/storage/emulated/0/").ifBlank { p }, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("Убрать", color = Coral, fontSize = 12.sp,
                                    modifier = Modifier.clickable { AppSettings.removeAllowedFolder(context, p); folders = AppSettings.allowedFolders(context) })
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("+ Добавить папку", color = Coral, fontSize = 14.sp,
                            modifier = Modifier.clickable { runCatching { treePicker.launch(null) } })
                    }
                }
            }
            // База знаний (RAG) — как облачная модель работает с твоими документами.
            item {
                var ragMode by remember { mutableStateOf(AppSettings.ragCloudMode(context)) }
                Section("База знаний (RAG) в облаке") {
                    Text(
                        "Как облачная модель ищет по твоим проиндексированным документам. Почта, календарь и " +
                            "Диск — отдельными тумблерами в разделе Интеграции.",
                        color = InkSoft, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
                    )
                    val ragOpts = listOf(
                        Triple("hybrid", "Через локальную модель", "Сырые документы не уходят: локальная модель читает найденное и отдаёт облаку только ответ. Нужна установленная локальная модель."),
                        Triple("direct", "Напрямую", "Облачная модель получает сами найденные фрагменты (до 4 кусков + имена файлов). Быстрее, но документы уходят провайдеру."),
                        Triple("local", "Только локально", "Поиск по документам доступен лишь локальной модели; облаку — нет (строгий §7)."),
                    )
                    ragOpts.forEach { (key, title, desc) ->
                        val sel = ragMode == key
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { ragMode = key; AppSettings.setRagCloudMode(context, key) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Top,
                        ) {
                            Icon(
                                if (sel) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                null, tint = if (sel) Coral else InkSoft,
                                modifier = Modifier.size(20.dp).padding(top = 1.dp),
                            )
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(title, color = if (sel) Coral else Ink, fontSize = 15.sp)
                                Text(desc, color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
                            }
                        }
                    }
                }
            }
            // Экран телефона (read_screen) — видит ли облачная модель содержимое экрана.
            item {
                var scrMode by remember { mutableStateOf(AppSettings.screenCloudMode(context)) }
                Section("Экран телефона в облаке") {
                    Text(
                        "Снимок экрана (банки, переписки) — приватные данные. По умолчанию облачная модель его " +
                            "НЕ видит. Действия (нажатия/свайпы) от этого не зависят.",
                        color = InkSoft, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
                    )
                    listOf(
                        Triple("local", "Только локально", "Облачная модель экран не видит (безопасно)."),
                        Triple("direct", "Разрешить облаку", "Облачная модель видит содержимое экрана."),
                    ).forEach { (key, title, desc) ->
                        val sel = scrMode == key
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { scrMode = key; AppSettings.setScreenCloudMode(context, key) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Top,
                        ) {
                            Icon(
                                if (sel) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                null, tint = if (sel) Coral else InkSoft, modifier = Modifier.size(20.dp).padding(top = 1.dp),
                            )
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(title, color = if (sel) Coral else Ink, fontSize = 15.sp)
                                Text(desc, color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
                            }
                        }
                    }
                }
            }
            // Сервер владельца (кошелёк + метринг). Альтернатива BYOK: платишь через кошелёк.
            item {
                val s = remember { ru.aiagent.app.cloud.CloudEngine.proxyConfig(context) }
                var enabled by remember { mutableStateOf(s.first) }
                var url by remember { mutableStateOf(s.second) }
                var token by remember { mutableStateOf(s.third) }
                var balance by remember { mutableStateOf<String?>(null) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                Section("Сервер владельца (кошелёк)") {
                    Column(Modifier.padding(10.dp)) {
                        ToggleRow("Ходить в облако через сервер (не BYOK)", enabled) { enabled = it }
                        // Адрес сервера фиксированный (сервис владельца) — не редактируется. Показываем
                        // для прозрачности; при пустом берём дефолт.
                        val serverUrl = url.ifBlank { ru.aiagent.app.cloud.Account.DEFAULT_URL }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Сервер: ", color = InkSoft, fontSize = 13.sp)
                            Text(serverUrl, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = token, onValueChange = { token = it }, singleLine = true,
                            // Токен авторизует трату кошелька — маскируем (U12), как ключ DeepSeek.
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            placeholder = { Text("токен пользователя", color = InkSoft) },
                            label = { Text("Токен (id кошелька)", color = InkSoft) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Coral, unfocusedBorderColor = Line, focusedTextColor = Ink, unfocusedTextColor = Ink),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(balance ?: "баланс не проверен", color = InkSoft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("Проверить", color = Coral, fontSize = 12.sp, modifier = Modifier.clickable {
                                scope.launch { balance = ru.aiagent.app.cloud.CloudEngine.checkBalance(serverUrl, token) }
                            }.padding(end = 12.dp))
                            Text("Сохранить", color = Cream, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.background(Coral, RoundedCornerShape(10.dp)).clickable {
                                    ru.aiagent.app.cloud.CloudEngine.setProxyCfg(context, enabled, serverUrl, token)
                                }.padding(horizontal = 14.dp, vertical = 7.dp))
                        }
                    }
                }
            }
            // Модели
            item {
                Text("Модели (${models.size})", color = InkSoft, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(models) { f ->
                ModelRow(f) {
                    f.delete()
                    models = ChatEngine.availableModels(context)
                }
            }
            item {
                Text(
                    "Модели лежат в приватной папке приложения. Загрузка новых — на вкладке «Модели» в меню.",
                    color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
        // Полноэкранный оверлей выбора image-модели поверх настроек.
        if (imagePickerOpen) {
            ImageModelPicker(
                current = imageModel,
                onPick = { m ->
                    imageModel = m.id
                    AppSettings.setImageModel(context, m.id)
                    imagePickerOpen = false
                },
                onBack = { imagePickerOpen = false },
            )
        }
    }
}

/**
 * Выбор облачной image-модели: полноэкранный список из [CloudModels.fetchImageModels] с поиском и
 * ценой ₽/1М. Стиль карточек — как в ModelsScreen. Сетевой вызов идёт на Dispatchers.IO, состояние —
 * через remember/mutableStateOf; текущая модель [current] помечается галочкой.
 */
@Composable
private fun ImageModelPicker(
    current: String,
    onPick: (CloudModelInfo) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<CloudModelInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { CloudModels.fetchImageModels(context) } }
            .onSuccess { all = it }
            .onFailure { loadError = it.message }
        loading = false
    }
    val filtered = remember(query, all) {
        val q = query.trim()
        if (q.isBlank()) all else all.filter { modelMatchesQuery(it, q) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp, 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад", tint = Ink,
                modifier = Modifier.clickable { onBack() }.padding(end = 8.dp).size(24.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Поиск image-моделей…", color = InkSoft) },
                singleLine = true, shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Coral, unfocusedBorderColor = Line,
                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                ),
            )
        }
        HorizontalDivider(color = Line)
        when {
            loading -> Box(Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(color = Coral)
            }
            loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), androidx.compose.ui.Alignment.Center) {
                Text("Не удалось загрузить каталог image-моделей: $loadError", color = InkSoft, fontSize = 13.sp)
            }
            all.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), androidx.compose.ui.Alignment.Center) {
                Text("Image-моделей в каталоге не найдено.", color = InkSoft, fontSize = 13.sp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Найдено: ${filtered.size}. Цена ₽ за 1М токенов (вход/выход).",
                        color = InkSoft, fontSize = 12.sp)
                }
                items(filtered) { m ->
                    val sel = m.id == current
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Card, RoundedCornerShape(12.dp))
                            .clickable { onPick(m) }
                            .padding(14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = if (sel) Coral else Ink, fontSize = 14.sp, maxLines = 1)
                            Text(m.id, color = InkSoft, fontSize = 11.sp, maxLines = 1)
                        }
                        Text("%.0f/%.0f ₽".format(m.priceIn, m.priceOut), color = Coral, fontSize = 12.sp)
                        if (sel) Text("  ✓", color = Coral, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Реальный путь из SAF tree-URI основного хранилища: primary:Папка → /storage/emulated/0/Папка. */
private fun treeUriToPath(uri: android.net.Uri): String? {
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2) return null
    val (vol, rel) = parts
    return if (vol.equals("primary", true)) "/storage/emulated/0/$rel".trimEnd('/')
    else "/storage/$vol/$rel".trimEnd('/')
}

/**
 * Секреты {{secret:имя}} (паритет CLI `plusai secret`): значения на устройстве (Keychain), в облако
 * не уходят; в диалоге/командах агент оперирует плейсхолдером, подстановка — перед исполнением.
 */
@Composable
private fun SecretsSection(context: android.content.Context) {
    // Сейв грузим из Keychain-хранилища; правки сразу сохраняем (список и флаги в одном месте).
    val scope = rememberCoroutineScope()
    var vault by remember { mutableStateOf(SecretStore.load(context)) }
    var enabled by remember { mutableStateOf(vault.enabled) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val secrets = remember(vault) { vault.all }
    fun persist() {
        SecretStore.save(context, vault)
        scope.launch { SecretStore.reconnect(context) }
    }
    Column {
        ToggleRow("Подстановка секретов", enabled) {
            enabled = it
            vault.enabled = it
            persist()
            status = if (it) "активна — в диалоге пишите {{secret:имя}}" else "выключена — секреты не маскируются"
        }
        Text(
            if (enabled) "Значения подставляются на устройстве перед исполнением и не уходят в облако."
            else "Секреты не маскируются и не подставляются.",
            color = InkSoft, fontSize = 12.sp,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        )
        if (enabled) {
            HorizontalDivider(color = Line)
            if (secrets.isEmpty())
                Text("Тайн пока нет. Добавьте ниже — потом используйте {{secret:имя}} в диалоге.",
                    color = InkSoft, fontSize = 12.sp,
                    modifier = Modifier.padding(14.dp, 8.dp))
            secrets.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(14.dp, 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("{{secret:${s.name}}}", color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("Удалить", color = Coral, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            vault.remove(s.name); persist()
                            vault = SecretStore.load(context)
                        })
                }
            }
            SshField("Имя тайны (напр. mail_pass)", name) { name = it }
            SshField("Значение", value, password = true) { value = it }
            Text("+ Добавить тайну", color = Coral, fontSize = 14.sp,
                modifier = Modifier.padding(14.dp, 10.dp).clickable {
                    val n = name.trim()
                    if (ru.aiagent.core.agent.SecretVault.isValidName(n) && value.isNotBlank()) {
                        vault.set(n, value)
                        persist()
                        name = ""; value = ""
                        status = "добавлено ${ru.aiagent.core.agent.SecretVault.placeholder(n)}"
                        vault = SecretStore.load(context)
                    } else status = "имя: буквы/цифры/подчёркивание/дефис; значение не пустое"
                })
            if (status.isNotBlank())
                Text(status, color = InkSoft, fontSize = 11.sp,
                    modifier = Modifier.padding(14.dp, 0.dp, 14.dp, 8.dp))
        }
    }
}

/** Пошаговая проверка связки «клиент → сервер → модель» (как plusai doctor в CLI/десктопе). */
@Composable
private fun DoctorSection(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column {
        Text(
            "Проверка связки «клиент → сервер → модель»: доступен ли маршрут (BYOK/облако/локальная модель)," +
                " отвечает ли модель. Убедитесь, что модель загружена — диагностика использует текущую.",
            color = InkSoft, fontSize = 12.sp,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
        )
        Text("Проверить и вывести отчёт", color = Cream, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.background(Coral, RoundedCornerShape(10.dp)).clickable(enabled = !busy) {
                if (busy) return@clickable
                busy = true
                log = ""
                scope.launch {
                    val sb = StringBuilder()
                    try {
                        sb.append("Проверка связки «клиент → сервер → модель»\n")
                        // 1) Локальная модель (gguf/task в files/models).
                        val local = ChatEngine.availableModels(context)
                        if (local.isEmpty())
                            sb.append("1. локальная модель: не найдена (скачайте на экране «Модели»)\n")
                        else {
                            sb.append("1. локальная модель: найдено ${local.size} (" +
                                local.joinToString(", ") { it.nameWithoutExtension } + ")\n")
                        }
                        // 2) Облачный маршрут: прокси владельца → free-тир Zen → BYOK → вход в аккаунт → ничего.
                        val ce = ru.aiagent.app.cloud.CloudEngine
                        val proxy = ce.proxyConfig(context)
                        sb.append("2. облачный маршрут: " +
                            when {
                                proxy.first && proxy.third.isNotBlank() ->
                                    "прокси владельца (${proxy.second})"
                                ru.aiagent.app.cloud.CloudEngine.zenEnabled(context) ->
                                    "бесплатные модели (напрямую, без ключа)"
                                ce.byokKey(context, CloudProvider.DEEPSEEK)?.isNotBlank() == true ->
                                    "BYOK (ключ есть)"
                                ru.aiagent.app.cloud.Account.isLoggedIn(context) -> "аккаунт (сервер владельца)"
                                else -> "не настроено (Настройки → Аккаунт / Свой API)"
                            } + "\n")
                        // 3) Сеть: один запрос к каталогу моделей (проверяет сеть + маршрут разом).
                        sb.append("3. сеть/каталог: ")
                        try {
                            val cat = withContext(Dispatchers.IO) {
                                ru.aiagent.app.cloud.CloudModels.fetchAll(context) }
                            sb.append("OK, моделей: ${cat.size}\n")
                        } catch (e: Exception) {
                            sb.append("сбой — ${e.message}\n")
                        }
                        // 4) Модель: короткий запрос к ЛОКАЛЬНОЙ модели (если есть).
                        if (local.isNotEmpty()) {
                            sb.append("4. локальная модель: ")
                            try {
                                val text = withContext(Dispatchers.IO) {
                                    ChatEngine.rawGenerate(context, "Ответь одним словом: работает")
                                }
                                sb.append("ответила: ${text.take(60)}\n")
                            } catch (e: Exception) { sb.append("ошибка: ${e.message}\n") }
                        }
                    } catch (e: Exception) {
                        sb.append("диагностика: " + e.message + "\n")
                    }
                    log = sb.toString()
                    busy = false
                }
            }.padding(14.dp, 8.dp))
        if (log.isNotBlank())
            Text(log, color = Ink, fontSize = 12.sp, modifier = Modifier.padding(14.dp, 4.dp, 14.dp, 8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = InkSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(14.dp))
                .padding(4.dp),
        ) { content() }
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(title, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("›", color = InkSoft, fontSize = 18.sp)
    }
}

@Composable
private fun ModeRow(mode: AutonomyMode, selected: Boolean, onClick: () -> Unit) {
    val (title, desc) = when (mode) {
        AutonomyMode.PLAN -> "Plan" to "Только чтение: сначала предлагает план"
        AutonomyMode.NORMAL -> "Normal" to "Читает выделенное; правки — с разрешения"
        AutonomyMode.AUTO -> "Auto" to "Правит сам; на опасное — спрашивает"
        AutonomyMode.BYPASS -> "Bypass" to "Делает всё сам, без подтверждений"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(desc, color = InkSoft, fontSize = 12.sp)
        }
        if (selected) Text("✓", color = Coral, fontSize = 18.sp)
    }
}

@Composable
private fun SshConnections(context: android.content.Context) {
    var conns by remember { mutableStateOf(SshStore.names(context)) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Column {
        if (conns.isEmpty())
            Text("Нет подключений. Добавь ниже — потом: ssh_run с именем и командой.",
                color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(14.dp, 8.dp))
        conns.forEach { n ->
            val c = SshStore.get(context, n)
            Row(
                Modifier.fillMaxWidth().padding(14.dp, 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(n, color = Ink, fontSize = 15.sp)
                    Text("${c?.optString("user")}@${c?.optString("host")}:${c?.optInt("port", 22)}",
                        color = InkSoft, fontSize = 12.sp)
                }
                Text("Удалить", color = Coral, fontSize = 13.sp,
                    modifier = Modifier.clickable { SshStore.remove(context, n); conns = SshStore.names(context) })
            }
        }
        SshField("Имя (напр. prod)", name) { name = it }
        SshField("Хост", host) { host = it }
        SshField("Порт", port) { port = it }
        SshField("Пользователь", user) { user = it }
        SshField("Пароль", pass, password = true) { pass = it }
        Text("Добавить подключение", color = Coral, fontSize = 14.sp,
            modifier = Modifier.padding(14.dp, 10.dp).clickable {
                if (name.isNotBlank() && host.isNotBlank() && user.isNotBlank()) {
                    SshStore.put(context, name.trim(), host.trim(), port.toIntOrNull() ?: 22,
                        user.trim(), pass.ifBlank { null }, null, null)
                    conns = SshStore.names(context); name = ""; host = ""; port = "22"; user = ""; pass = ""
                }
            })
        Text("Пароль/ключ хранятся на устройстве и в облако не передаются — модель видит только имя подключения.",
            color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(14.dp, 0.dp, 14.dp, 10.dp))
    }
}

@Composable
private fun SshField(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp),
        placeholder = { Text(label, color = InkSoft) }, singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Coral, unfocusedBorderColor = Line,
            focusedTextColor = Ink, unfocusedTextColor = Ink,
        ),
    )
}

@Composable
private fun ToggleRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(title, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = value, onCheckedChange = { onChange(it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = Coral.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun ModelRow(file: File, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.nameWithoutExtension, color = Ink, fontSize = 15.sp)
            Text("${file.length() / (1024 * 1024)} МБ", color = InkSoft, fontSize = 12.sp)
        }
        Text("Удалить", color = Coral, fontSize = 13.sp, modifier = Modifier.clickable { onDelete() })
    }
}
