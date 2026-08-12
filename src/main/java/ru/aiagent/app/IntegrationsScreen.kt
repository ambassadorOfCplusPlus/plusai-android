package ru.aiagent.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Event
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.aiagent.app.integrations.GitHubAuth
import ru.aiagent.app.ui.P
import ru.aiagent.integrations.GitHubClient

/**
 * Экран интеграций (S10). Пока — GitHub через OAuth Device Flow: агент получает
 * доступ к репозиториям (чтение, issues, поиск кода) от имени пользователя. Токен
 * хранится в Keystore, не покидает устройство. Дальше по плану — почта, календарь.
 */
@Composable
fun IntegrationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var login by remember { mutableStateOf(GitHubAuth.login(context)) }
    var connected by remember { mutableStateOf(GitHubAuth.isConnected(context)) }
    var deviceCode by remember { mutableStateOf<GitHubClient.DeviceCode?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "назад", tint = P.Text,
                modifier = Modifier.size(38.dp).clickable { onBack() }.padding(6.dp))
            Text("Интеграции", color = P.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            // Карточка GitHub
            Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Code, null, tint = P.Accent, modifier = Modifier.size(28.dp))
                    Text("  GitHub", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Агент сможет читать твои репозитории, issues, искать код и создавать issues " +
                        "(с подтверждением). Токен хранится только на телефоне.",
                    color = P.TextSoft, fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))

                when {
                    connected -> {
                        Text("Подключён: ${login ?: "—"} ✓", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.fillMaxWidth().height(46.dp).background(P.Bg, RoundedCornerShape(12.dp))
                                .clickable {
                                    GitHubAuth.clear(context); connected = false; login = null; deviceCode = null; status = null
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text("Отключить", color = P.Accent, fontSize = 15.sp) }
                    }

                    deviceCode != null -> {
                        val dc = deviceCode!!
                        Text("1. Открой страницу и введи код:", color = P.TextSoft, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(dc.userCode, color = P.Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            ActionBtn("Открыть GitHub", filled = true, modifier = Modifier.weight(1f)) {
                                openConsentPage(context, dc.verificationUri)
                            }
                            Spacer(Modifier.size(10.dp))
                            ActionBtn("Копировать код", filled = false, modifier = Modifier.weight(1f)) {
                                clipboard.setText(AnnotatedString(dc.userCode))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(status ?: "После ввода кода на GitHub подключение завершится автоматически…",
                            color = P.TextSoft, fontSize = 13.sp)
                    }

                    else -> {
                        ActionBtn(if (busy) "Подключаю…" else "Подключить GitHub", filled = true) {
                            if (busy) return@ActionBtn
                            busy = true; status = null
                            scope.launch {
                                GitHubClient.startDeviceFlow()
                                    .onSuccess { dc ->
                                        deviceCode = dc
                                        // Открываем страницу сразу для удобства.
                                        openConsentPage(context, dc.verificationUri)
                                        // Ждём ввод кода.
                                        GitHubClient.pollForToken(dc)
                                            .onSuccess { token ->
                                                val who = GitHubClient.whoAmI(token).getOrDefault("github")
                                                GitHubAuth.save(context, token, who)
                                                connected = true; login = who; deviceCode = null
                                                status = null
                                            }
                                            .onFailure { status = "Ошибка: ${it.message}"; deviceCode = null }
                                    }
                                    .onFailure { status = "Ошибка: ${it.message}" }
                                busy = false
                            }
                        }
                        status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SyncCard()
            Spacer(Modifier.height(16.dp))
            MailCard()
            Spacer(Modifier.height(16.dp))
            CalendarCard()
            Spacer(Modifier.height(16.dp))
            DiskCard()
            Spacer(Modifier.height(16.dp))
            GoogleDriveCard()
            Spacer(Modifier.height(16.dp))
            DropboxCard()
            Spacer(Modifier.height(16.dp))
            OneDriveCard()
            Spacer(Modifier.height(16.dp))
            DiscordCard()
            Spacer(Modifier.height(16.dp))
            TelegramCard()
            Spacer(Modifier.height(16.dp))
            TelegramUserCard()
            Spacer(Modifier.height(16.dp))
            TelegramAutomationCard()
            Spacer(Modifier.height(16.dp))
            MessengerFilterCard()
        }
    }
}

/**
 * Открыть страницу согласия провайдера. Custom Tab — вкладка поверх приложения: возврат по «назад»
 * не выкидывает пользователя из Plus AI и не рушит состояние экрана (при поллинге токена это важно —
 * карточка продолжает ждать). Если Custom Tabs не поддержаны (нет совместимого браузера) — обычный
 * ACTION_VIEW, как было.
 */
private fun openConsentPage(context: android.content.Context, url: String) {
    val uri = Uri.parse(url)
    runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(context, uri)
    }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}

/**
 * Общая карточка облачного хранилища через OAuth-сервер. Поддерживает ОБА сценария завершения
 * согласия, выбирая их по флагу `manual` из ответа сервера:
 *  - callback (manual=false): провайдер редиректит на сервер, приложение поллит `take` по `state`;
 *  - ручной код (manual=true): пользователь копирует код со страницы провайдера, `exchange`.
 *
 * Флаг раньше игнорировался: карточка всегда шла в поллинг, а Яндекс Диск имел ОТДЕЛЬНУЮ копию
 * карточки, всегда просившую код. Теперь сценарий диктует сервер — когда у провайдера настроят
 * redirect_uri на сервер, ручной ввод исчезнет сам, без правки приложения.
 *
 * [connectedExtra] — слот для настроек, специфичных для коннектора (у Яндекс Диска — режим доступа
 * облачной модели к файлам).
 */
@Composable
private fun OAuthStorageCard(
    title: String,
    provider: String,
    about: String,
    isConnected: () -> Boolean,
    onSaved: (access: String, refresh: String?) -> Unit,
    onClear: () -> Unit,
    connectedExtra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(isConnected()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var manualCode by remember { mutableStateOf<String?>(null) } // не null — сервер требует ручной код

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudUpload, null, tint = P.Accent, modifier = Modifier.size(28.dp))
            Text("  $title", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(about, color = P.TextSoft, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        if (connected) {
            Text("Подключён ✓", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            connectedExtra()
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp).background(P.Bg, RoundedCornerShape(12.dp))
                    .clickable { onClear(); connected = false; manualCode = null; status = null },
                contentAlignment = Alignment.Center,
            ) { Text("Отключить", color = P.Accent, fontSize = 15.sp) }
        } else {
            ActionBtn(if (busy) "Подключаю…" else "Подключить $title", filled = true) {
                if (busy) return@ActionBtn
                busy = true; status = null
                scope.launch {
                    ru.aiagent.app.integrations.OAuthBroker.start(context, provider)
                        .onSuccess { info ->
                            // state берём из ссылки согласия — по нему сервер отдаст токен в take().
                            val state = runCatching { Uri.parse(info.authUrl).getQueryParameter("state") }.getOrNull().orEmpty()
                            openConsentPage(context, info.authUrl)
                            if (info.manual) {
                                // redirect_uri провайдера фиксированный — вернуть код может только пользователь.
                                manualCode = ""
                                status = "Разреши доступ на странице провайдера, скопируй код и вставь ниже."
                                return@onSuccess
                            }
                            status = "Разреши доступ на открывшейся странице — подключение завершится автоматически…"
                            // Поллинг ~2с до успеха или таймаута ~2 мин.
                            var got = false
                            for (attempt in 0 until 60) {
                                kotlinx.coroutines.delay(2000)
                                val r = ru.aiagent.app.integrations.OAuthBroker.take(context, provider, state)
                                val tok = r.getOrNull()
                                if (tok != null) {
                                    onSaved(tok.access, tok.refresh.takeIf { it.isNotBlank() })
                                    connected = true; status = null; got = true
                                    break
                                }
                            }
                            if (!got && !connected) status = "Не дождались подтверждения. Попробуйте ещё раз."
                        }
                        .onFailure { status = oauthError(it) }
                    busy = false
                }
            }
            manualCode?.let { codeVal ->
                Spacer(Modifier.height(8.dp))
                IntegrationField(codeVal, { manualCode = it; status = null }, "Код со страницы провайдера", KeyboardType.Number)
                Spacer(Modifier.height(8.dp))
                ActionBtn(if (busy) "Проверяю…" else "Готово", filled = true) {
                    if (busy || codeVal.isBlank()) return@ActionBtn
                    busy = true; status = null
                    scope.launch {
                        ru.aiagent.app.integrations.OAuthBroker.exchange(context, provider, codeVal)
                            .onSuccess { tok ->
                                onSaved(tok.access, tok.refresh.takeIf { it.isNotBlank() })
                                connected = true; manualCode = null; status = null
                            }
                            .onFailure { status = "Обмен кода: ${it.message?.take(80)}" }
                        busy = false
                    }
                }
            }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
        }
    }
}

/** Google Диск — OAuth через сервер (callback-flow). Токен в Keystore; файлы идут напрямую в Google (§7). */
@Composable
private fun GoogleDriveCard() {
    val context = LocalContext.current
    OAuthStorageCard(
        title = "Google Диск",
        provider = "gdrive",
        about = "Агент читает документы с твоего Google Диска и сохраняет результаты обратно. " +
            "Файлы идут напрямую с телефона в Google — через наш сервер не проходят.",
        isConnected = { ru.aiagent.app.integrations.DriveAuth.isConnected(context) },
        onSaved = { access, refresh -> ru.aiagent.app.integrations.DriveAuth.save(context, access, refresh) },
        onClear = { ru.aiagent.app.integrations.DriveAuth.clear(context) },
    )
}

/** Dropbox — OAuth через сервер (callback-flow). Токен в Keystore; файлы идут напрямую в Dropbox (§7). */
@Composable
private fun DropboxCard() {
    val context = LocalContext.current
    OAuthStorageCard(
        title = "Dropbox",
        provider = "dropbox",
        about = "Агент читает документы из твоего Dropbox и сохраняет результаты обратно. " +
            "Файлы идут напрямую с телефона в Dropbox — через наш сервер не проходят.",
        isConnected = { ru.aiagent.app.integrations.DropboxAuth.isConnected(context) },
        onSaved = { access, refresh -> ru.aiagent.app.integrations.DropboxAuth.save(context, access, refresh) },
        onClear = { ru.aiagent.app.integrations.DropboxAuth.clear(context) },
    )
}

/** OneDrive — OAuth через сервер (callback-flow). Токен в Keystore; файлы идут напрямую в OneDrive (§7). */
@Composable
private fun OneDriveCard() {
    val context = LocalContext.current
    OAuthStorageCard(
        title = "OneDrive",
        provider = "onedrive",
        about = "Агент читает документы из твоего OneDrive и сохраняет результаты обратно. " +
            "Файлы идут напрямую с телефона в OneDrive — через наш сервер не проходят.",
        isConnected = { ru.aiagent.app.integrations.OneDriveAuth.isConnected(context) },
        onSaved = { access, refresh -> ru.aiagent.app.integrations.OneDriveAuth.save(context, access, refresh) },
        onClear = { ru.aiagent.app.integrations.OneDriveAuth.clear(context) },
    )
}

/** Discord — по bot-токену (без OAuth). Токен хранится в Keystore, не покидает устройство. */
@Composable
private fun DiscordCard() {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(ru.aiagent.app.integrations.DiscordAuth.isConnected(context)) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Code, null, tint = P.Accent, modifier = Modifier.size(28.dp))
            Text("  Discord", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Агент через твоего бота смотрит серверы и каналы, читает сообщения (с подтверждением) и " +
                "отправляет сообщения от имени бота. Токен хранится только на телефоне.",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        if (connected) {
            Text("Подключён ✓", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp).background(P.Bg, RoundedCornerShape(12.dp))
                    .clickable {
                        ru.aiagent.app.integrations.DiscordAuth.clear(context)
                        connected = false; token = ""; status = null
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Отключить", color = P.Accent, fontSize = 15.sp) }
        } else {
            IntegrationField(token, { token = it; status = null }, "Bot Token", KeyboardType.Password, isPassword = true)
            Text(
                "Создайте бота на Discord Developer Portal, скопируйте Bot Token.",
                color = P.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            ActionBtn("Сохранить", filled = true) {
                if (token.isBlank()) { status = "Вставьте bot-токен"; return@ActionBtn }
                ru.aiagent.app.integrations.DiscordAuth.save(context, token.trim())
                connected = true; status = null
            }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
        }
    }
}

/** Telegram — через bot-токен от @BotFather (Bot API, без api_id/TDLib). Токен только на телефоне. */
@Composable
private fun TelegramCard() {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(ru.aiagent.app.integrations.TelegramAuth.isConnected(context)) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Send, null, tint = P.Accent, modifier = Modifier.size(28.dp))
            Text("  Telegram", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Агент через твоего бота отправляет сообщения и читает то, что пишут боту (с подтверждением). " +
                "Личную переписку бот не видит. Токен хранится только на телефоне.",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        if (connected) {
            Text("Подключён ✓", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp).background(P.Bg, RoundedCornerShape(12.dp))
                    .clickable {
                        ru.aiagent.app.integrations.TelegramAuth.clear(context)
                        connected = false; token = ""; status = null
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Отключить", color = P.Accent, fontSize = 15.sp) }
        } else {
            IntegrationField(token, { token = it; status = null }, "Bot Token", KeyboardType.Password, isPassword = true)
            Text(
                "В Telegram напишите @BotFather → /newbot → скопируйте токен вида 123456:ABC...",
                color = P.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            ActionBtn("Сохранить", filled = true) {
                if (token.isBlank()) { status = "Вставьте bot-токен"; return@ActionBtn }
                ru.aiagent.app.integrations.TelegramAuth.save(context, token.trim())
                connected = true; status = null
            }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
        }
    }
}

/**
 * Telegram ЮЗЕР-клиент (tg_*) — свой аккаунт через официальный TDLib. Отдельно от бот-карточки выше:
 * тут api_id/api_hash с my.telegram.org (не бот-токен). Нужен ещё скачиваемый пак TDLib (Инструменты).
 */
@Composable
private fun TelegramUserCard() {
    val context = LocalContext.current
    var apiId by remember { mutableStateOf(ru.aiagent.app.integrations.TelegramUserAuth.apiId(context)?.toString() ?: "") }
    var apiHash by remember { mutableStateOf(ru.aiagent.app.integrations.TelegramUserAuth.apiHash(context) ?: "") }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Send, null, tint = P.Accent, modifier = Modifier.size(28.dp))
            Text("  Telegram (аккаунт)", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Вход СВОИМ номером через официальный TDLib: агент читает и пишет твои личные чаты (tg_*). " +
                "api_id/api_hash — с my.telegram.org (не бот-токен). Нужен ещё пак TDLib в «Инструментах». " +
                "Коды входа Госуслуг/банков агенту не показываются (фильтр).",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        IntegrationField(apiId, { apiId = it.filter { c -> c.isDigit() }; status = null }, "api_id (число)", KeyboardType.Number)
        Spacer(Modifier.height(8.dp))
        IntegrationField(apiHash, { apiHash = it; status = null }, "api_hash", KeyboardType.Password, isPassword = true)
        Text(
            "Получить: my.telegram.org → API development tools → создать приложение (short name — 5–32 латиницы/цифр, URL пустым).",
            color = P.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        ActionBtn("Сохранить", filled = true) {
            val id = apiId.trim().toIntOrNull()
            if (id == null || id <= 0) { status = "api_id — это число с my.telegram.org"; return@ActionBtn }
            if (apiHash.isBlank()) { status = "Вставьте api_hash"; return@ActionBtn }
            ru.aiagent.app.integrations.TelegramUserAuth.saveApiCredentials(context, id, apiHash.trim())
            status = "Сохранено. Дальше вход из чата: tg_login {phone}."
        }
        status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
    }
}

/**
 * Автосинк устройств (E2E). С ключом от ПАРОЛЯ АККАУНТА: на другом устройстве под тем же аккаунтом тот же
 * ключ, поэтому отдельный синк-пароль не нужен. Автоматически уезжают/приезжают настройки облака и ключи,
 * интеграции, тайны, SSH и история чатов. Здесь — статус и ручная кнопка «синхронизировать сейчас».
 */
@Composable
private fun SyncCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn = remember { ru.aiagent.app.cloud.Account.isLoggedIn(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Text("Автосинк устройств (E2E)", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Настройки облака и ключи, интеграции, тайны, SSH и история чатов автоматически " +
                "синхронизируются между устройствами под твоим аккаунтом. Шифрование на устройстве " +
                "ключом от пароля аккаунта — сервер содержимое не видит. Забыл пароль — синк не прочитать.",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        if (!loggedIn) {
            Text("Нужен вход в аккаунт (вкладка «Аккаунт»).", color = P.Accent, fontSize = 14.sp)
            return@Column
        }
        val active = ru.aiagent.app.cloud.AutoSync.enabled(context)
        Text(
            if (active) "Автосинк включён (ключ от пароля аккаунта)." else "Пароль аккаунта не сохранён на этом устройстве — войдите с паролем, чтобы включить автосинк.",
            color = if (active) P.Accent else P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(42.dp).background(P.Bg, RoundedCornerShape(12.dp)).clickable(enabled = !busy) {
                busy = true; status = "синхронизирую…"
                scope.launch {
                    ru.aiagent.app.cloud.AutoSync.syncNow(context)
                    status = "готово"
                    busy = false
                }
            },
            contentAlignment = Alignment.Center,
        ) { Text("Синхронизировать сейчас", color = P.Accent, fontSize = 14.sp) }
        status?.let { Spacer(Modifier.height(10.dp)); Text(it, color = P.TextSoft, fontSize = 13.sp) }
    }
}

@Composable
private fun TelegramAutomationCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn = remember { ru.aiagent.app.cloud.Account.isLoggedIn(context) }
    var token by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("both") }
    var model by remember { mutableStateOf("") }
    var maxDayRub by remember { mutableStateOf("50") }
    var maxAgentSteps by remember { mutableStateOf("10") }
    var models by remember { mutableStateOf<List<ru.aiagent.app.cloud.CloudModelInfo>>(emptyList()) }
    var bots by remember { mutableStateOf<List<ru.aiagent.app.cloud.Account.TgBotView>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            ru.aiagent.app.cloud.Account.tgBots(context)
                .onSuccess { bots = it }
                .onFailure { status = it.message }
        }
    }

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Send, null, tint = P.Accent, modifier = Modifier.size(28.dp))
            Text("  Автоматизация Telegram (ИИ-бот)", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Свой бот от @BotFather висит на нашем сервере 24/7 и сам отвечает. Он агентный: помнит заметки, " +
                "пишет в другие чаты, ставит себе напоминания, может считать/компилировать через облачную задачу. " +
                "Обычный режим — в чатах с ботом; бизнес — в твоих личных чатах (Telegram → Настройки → Telegram " +
                "Business → Чат-боты; нужен Premium, охват чатов выбираешь там же). Внимание: в бизнес-режиме " +
                "содержимое выбранных чатов уходит на сервер и в облачную модель.",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        if (!loggedIn) {
            Text("Нужен вход в аккаунт (вкладка «Аккаунт»).", color = P.Accent, fontSize = 14.sp)
            return@Column
        }

        IntegrationField(token, { token = it; status = null }, "Токен бота (@BotFather)", KeyboardType.Password, isPassword = true)
        Spacer(Modifier.height(8.dp))
        IntegrationField(instructions, { instructions = it }, "Инструкция боту (как отвечать)", KeyboardType.Text)
        Spacer(Modifier.height(8.dp))
        IntegrationField(name, { name = it }, "Имя бота (в профиле)", KeyboardType.Text)
        Spacer(Modifier.height(8.dp))
        IntegrationField(description, { description = it }, "Описание бота (в профиле)", KeyboardType.Text)
        Spacer(Modifier.height(8.dp))
        IntegrationField(greeting, { greeting = it }, "Приветствие на /start (обычный режим)", KeyboardType.Text)
        Spacer(Modifier.height(8.dp))
        IntegrationField(model, { model = it; status = null }, "Модель (выбери ниже или впиши id)", KeyboardType.Text)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(42.dp).background(P.Bg, RoundedCornerShape(12.dp))
                .clickable {
                    scope.launch {
                        runCatching { ru.aiagent.app.cloud.CloudModels.fetchAll(context) }
                            .onSuccess { models = it; if (it.isEmpty()) status = "Модели не загрузились" }
                            .onFailure { status = it.message }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text(if (models.isEmpty()) "Загрузить список моделей" else "Модели загружены (${models.size})", color = P.Accent, fontSize = 14.sp) }
        if (models.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            var modelSearch by remember { mutableStateOf("") }
            IntegrationField(modelSearch, { modelSearch = it }, "Поиск модели...", KeyboardType.Text)
            Spacer(Modifier.height(4.dp))
            val filtered = if (modelSearch.isBlank()) models else models.filter { it.id.contains(modelSearch, ignoreCase = true) }
            filtered.take(40).forEach { mi ->
                Box(
                    Modifier.fillMaxWidth().clickable { model = mi.id; status = "Модель: ${mi.id}" }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) { Text(mi.id, color = if (model == mi.id) P.Accent else P.Text, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        IntegrationField(maxDayRub, { maxDayRub = it.filter { c -> c.isDigit() } }, "Потолок трат в день, ₽", KeyboardType.Number)
        Spacer(Modifier.height(8.dp))
        IntegrationField(maxAgentSteps, { maxAgentSteps = it.filter { c -> c.isDigit() } }, "Лимит шагов агента (0=10)", KeyboardType.Number)
        Spacer(Modifier.height(10.dp))

        Row {
            listOf("both" to "оба", "business" to "бизнес", "regular" to "обычный").forEach { (m, label) ->
                val sel = mode == m
                Box(
                    Modifier.padding(end = 8.dp).background(if (sel) P.Accent else P.Bg, RoundedCornerShape(10.dp))
                        .clickable { mode = m }.padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (sel) P.Bg else P.Text, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))

        ActionBtn(if (busy) "…" else "Добавить бота", filled = true) {
            if (busy) return@ActionBtn
            if (token.isBlank()) { status = "Вставьте токен бота"; return@ActionBtn }
            if (model.isBlank()) { status = "Укажите id модели (вкладка «Модели»)"; return@ActionBtn }
            busy = true; status = "Регистрирую…"
            scope.launch {
                ru.aiagent.app.cloud.Account.registerTgBot(context, token.trim(), instructions.trim(), mode, model.trim(), name.trim(), description.trim(), greeting.trim(), maxDayRub.toLongOrNull() ?: 50L, maxAgentSteps.toIntOrNull() ?: 10)
                    .onSuccess { token = ""; status = "Бот @${it.botUsername} подключён и запущен."; refresh() }
                    .onFailure { status = it.message }
                busy = false
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(42.dp).background(P.Bg, RoundedCornerShape(12.dp)).clickable { refresh() },
            contentAlignment = Alignment.Center,
        ) { Text("Обновить список", color = P.Accent, fontSize = 14.sp) }

        status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }

        bots.forEach { b ->
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().background(P.Bg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("@${b.botUsername}", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "режим: ${b.mode} · ${if (b.enabled) "включён" else "выключен"} · бизнес-подключений: ${b.businessConnections} · шагов агента: ${b.maxAgentSteps}",
                    color = P.TextSoft, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Box(
                        Modifier.padding(end = 8.dp).background(P.Surface, RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    ru.aiagent.app.cloud.Account.setTgBotEnabled(context, b.id, !b.enabled)
                                        .onSuccess { status = "@${it.botUsername}: ${if (it.enabled) "включён" else "выключен"}"; refresh() }
                                        .onFailure { status = it.message }
                                }
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(if (b.enabled) "выключить" else "включить", color = P.Accent, fontSize = 13.sp) }
                    Box(
                        Modifier.padding(end = 8.dp).background(P.Surface, RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    ru.aiagent.app.cloud.Account.restartTgBot(context, b.id)
                                        .onSuccess { status = "@${b.botUsername} перезапущен"; refresh() }
                                        .onFailure { status = it.message }
                                }
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("перезапуск", color = P.Accent, fontSize = 13.sp) }
                    Box(
                        Modifier.background(P.Surface, RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    ru.aiagent.app.cloud.Account.deleteTgBot(context, b.id)
                                        .onSuccess { status = "@${b.botUsername} удалён."; refresh() }
                                        .onFailure { status = it.message }
                                }
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("удалить", color = P.Accent, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Text("Шаги агента:", color = P.TextSoft, fontSize = 11.sp)
                Row {
                    listOf(10, 20, 30).forEach { n ->
                        val isSel = b.maxAgentSteps == n
                        Box(
                            Modifier.padding(end = 6.dp).background(if (isSel) P.Accent else P.Surface, RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        ru.aiagent.app.cloud.Account.updateTgBot(context, b.id, maxAgentSteps = n)
                                            .onSuccess { status = "@${it.botUsername}: шагов → $n"; refresh() }
                                            .onFailure { status = it.message }
                                    }
                                }.padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text("$n", color = if (isSel) P.Bg else P.Text, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

/**
 * Фильтр кодов (для Telegram). Слой 1 (маскировка кодов входа Госуслуг/банков в тексте)
 * всегда включён и не выключается. Здесь — тумблер Слоя 2 (подавление тела у сервисных аккаунтов) и
 * редактируемый список паттернов имён (regex-альтернативы через |).
 */
@Composable
private fun MessengerFilterCard() {
    val context = LocalContext.current
    var suppress by remember { mutableStateOf(ru.aiagent.app.integrations.MessengerFilterSettings.suppressSensitive(context)) }
    var senders by remember { mutableStateOf(ru.aiagent.app.integrations.MessengerFilterSettings.sensitiveSenders(context)) }

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Text("Фильтр кодов (Telegram)", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Коды входа Госуслуг/банков не показываются агенту (и не уходят в облако). Маскировка кодов " +
                "включена всегда. Ниже — подавление ЦЕЛОГО сообщения от сервисных аккаунтов и список их имён.",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Подавлять сервисные аккаунты", color = P.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = suppress,
                onCheckedChange = { suppress = it; ru.aiagent.app.integrations.MessengerFilterSettings.setSuppressSensitive(context, it) },
            )
        }
        Spacer(Modifier.height(10.dp))
        IntegrationField(
            senders,
            { senders = it; ru.aiagent.app.integrations.MessengerFilterSettings.setSensitiveSenders(context, it) },
            "Паттерны имён (через |)", KeyboardType.Text,
        )
        Text(
            "Напр.: Госуслуг|Сбер|ВТБ|ФНС|\\bбанк\\b — совпадение по имени чата/отправителя подавляет тело.",
            color = P.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Яндекс Диск — REST по OAuth (S10). Токен в Keystore; файлы Диска на сервер не уходят (§7).
 * Отличается от прочих хранилищ только тумблером режима доступа облачной модели к файлам —
 * сам OAuth-флоу общий ([OAuthStorageCard]); ручной код показывается, если сервер вернул manual.
 */
@Composable
private fun DiskCard() {
    val context = LocalContext.current
    OAuthStorageCard(
        title = "Яндекс Диск",
        provider = "yandexdisk",
        about = "Агент читает документы с твоего Диска и сохраняет результаты обратно. Файлы идут напрямую " +
            "с телефона в Яндекс — через наш сервер не проходят.",
        isConnected = { ru.aiagent.app.integrations.DiskAuth.isConnected(context) },
        onSaved = { access, refresh -> ru.aiagent.app.integrations.DiskAuth.save(context, access, refresh) },
        onClear = { ru.aiagent.app.integrations.DiskAuth.clear(context) },
    ) {
        var diskMode by remember { mutableStateOf(ru.aiagent.app.AppSettings.diskCloudMode(context)) }
        CloudModeToggle(
            "Диск в облаке", "Как облачная модель работает с файлами Диска. Локальная — всегда.",
            listOf(
                Triple("local", "Только локально", "Облаку Диск недоступен (строгий §7)."),
                Triple("hybrid", "Через локальную модель", "Содержимое файла читает локальная модель, облаку — ответ."),
                Triple("direct", "Напрямую", "Файлы уходят провайдеру."),
            ), diskMode,
        ) { diskMode = it; ru.aiagent.app.AppSettings.setDiskCloudMode(context, it) }
    }
}

/** Переиспользуемый тумблер режима доступа облачной модели к личной категории (local/hybrid/direct). */
@Composable
private fun CloudModeToggle(
    title: String, subtitle: String,
    options: List<Triple<String, String, String>>, // key, label, desc
    current: String, onSelect: (String) -> Unit,
) {
    Text(title, color = P.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Text(subtitle, color = P.TextSoft, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    options.forEach { (key, label, desc) ->
        val sel = current == key
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(key) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(if (sel) "◉" else "○", color = if (sel) P.Accent else P.TextSoft, fontSize = 15.sp, modifier = Modifier.padding(top = 1.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(label, color = if (sel) P.Accent else P.Text, fontSize = 14.sp)
                Text(desc, color = P.TextSoft, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Тумблер фоновой индексации вложений: OCR сканов/документов в фоне (Wi-Fi + не разряжен) и
 * зашифрованный локальный индекс для мгновенного поиска по содержимому. Данные не покидают
 * устройство (§7), но это персональные документы at-rest — поэтому по умолчанию ВЫКЛ.
 */
@Composable
private fun MailIndexToggle() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(ru.aiagent.app.AppSettings.emailIndexEnabled(context)) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text("Фоновый поиск по вложениям", color = P.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "Распознаёт сканы/документы во вложениях в фоне и шифрует локальный индекс — поиск по " +
                    "содержимому мгновенный. Ничего не уходит на сервер. Старое сжимается и удаляется.",
                color = P.TextSoft, fontSize = 12.sp,
            )
        }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = {
                on = it
                ru.aiagent.app.AppSettings.setEmailIndexEnabled(context, it)
                if (it) ru.aiagent.app.integrations.EmailIndexer.schedule(context)
                else ru.aiagent.app.integrations.EmailIndexer.cancel(context)
            },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = P.Accent, checkedTrackColor = P.Accent.copy(alpha = 0.4f)),
        )
    }
}

/** Календарь — локально через CalendarProvider (S10). Нужен доступ READ/WRITE_CALENDAR. */
@Composable
private fun CalendarCard() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ru.aiagent.app.integrations.CalendarClient.canRead(context))
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = ru.aiagent.app.integrations.CalendarClient.canRead(context) }

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Event, null, tint = P.Accent, modifier = Modifier.size(28.dp))
            Text("  Календарь", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Агент видит твои ближайшие события, ищет встречи и создаёт события — локально, " +
                "через системный календарь. На сервер ничего не уходит.",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        if (granted) {
            Text("Доступ выдан ✓", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            var calMode by remember { mutableStateOf(ru.aiagent.app.AppSettings.calendarCloudMode(context)) }
            CloudModeToggle(
                "Календарь в облаке", "Как облачная модель работает с событиями. Локальная — всегда.",
                listOf(
                    Triple("local", "Только локально", "Облаку календарь недоступен (строгий §7)."),
                    Triple("hybrid", "Через локальную модель", "События читает локальная модель, облаку — ответ."),
                    Triple("direct", "Напрямую", "События уходят провайдеру."),
                ), calMode,
            ) { calMode = it; ru.aiagent.app.AppSettings.setCalendarCloudMode(context, it) }
        } else {
            ActionBtn("Разрешить доступ к календарю", filled = true) {
                launcher.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
            }
        }
    }
}

/**
 * Почта — локально по паролю приложения (S10). Ничего не уходит на сервер: агент
 * ищет письма прямо на телефоне. Пароль приложения генерируется в настройках почты.
 */
@Composable
private fun MailCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = listOf("yandex" to "Яндекс", "mailru" to "Mail.ru", "gmail" to "Gmail")

    var accounts by remember { mutableStateOf(ru.aiagent.app.integrations.MailAuth.accounts(context)) }
    var active by remember { mutableStateOf(ru.aiagent.app.integrations.MailAuth.account(context)?.email) }
    var adding by remember { mutableStateOf(false) }
    var mailMode by remember { mutableStateOf(ru.aiagent.app.AppSettings.mailCloudMode(context)) }
    var provider by remember { mutableStateOf("yandex") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        accounts = ru.aiagent.app.integrations.MailAuth.accounts(context)
        active = ru.aiagent.app.integrations.MailAuth.account(context)?.email
    }
    val diskConnected = ru.aiagent.app.integrations.DiskAuth.isConnected(context)

    Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Email, null, tint = P.Accent, modifier = Modifier.size(28.dp))
            Text("  Почта", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Агент найдёт нужное письмо по смыслу — прямо на телефоне. Можно подключить несколько ящиков " +
                "(Яндекс, Gmail, рабочая почта) и переключаться между ними.",
            color = P.TextSoft, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        if (accounts.isNotEmpty() && !adding) {
            // Список подключённых ящиков: активный отмечен, тап — переключить, ✕ — удалить.
            accounts.forEach { a ->
                val sel = a.email == active
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable { ru.aiagent.app.integrations.MailAuth.setActive(context, a.email); refresh() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (sel) "●" else "○", color = if (sel) P.Accent else P.TextSoft, fontSize = 16.sp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(a.email, color = if (sel) P.Accent else P.Text, fontSize = 15.sp,
                            fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                        Text(a.provider, color = P.TextSoft, fontSize = 11.sp)
                    }
                    Text("✕", color = P.TextSoft, fontSize = 15.sp, modifier = Modifier
                        .clickable {
                            ru.aiagent.app.integrations.MailAuth.remove(context, a.email); refresh()
                            if (accounts.isEmpty()) {
                                ru.aiagent.app.integrations.EmailIndexer.cancel(context)
                                ru.aiagent.app.AppSettings.setEmailIndexEnabled(context, false)
                            }
                        }.padding(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("+ Добавить ещё почту", color = P.Accent, fontSize = 14.sp,
                modifier = Modifier.clickable { adding = true; email = ""; password = ""; provider = "yandex"; status = null }.padding(vertical = 6.dp))

            // Тумблер доступа облачной модели к почте (как у файлов/RAG).
            Spacer(Modifier.height(14.dp))
            Text("Почта в облаке", color = P.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("Как облачная модель работает с письмами. Локальная — всегда видит.", color = P.TextSoft, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            listOf(
                "local" to ("Только локально" to "Облаку почта недоступна (строгий §7)."),
                "hybrid" to ("Через локальную модель" to "Тело письма не уходит: локальная модель читает и отдаёт облаку ответ."),
                "direct" to ("Напрямую" to "Облачная модель получает сами письма (уходят провайдеру)."),
            ).forEach { (key, td) ->
                val sel = mailMode == key
                Row(
                    Modifier.fillMaxWidth().clickable { mailMode = key; ru.aiagent.app.AppSettings.setMailCloudMode(context, key) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(if (sel) "◉" else "○", color = if (sel) P.Accent else P.TextSoft, fontSize = 15.sp, modifier = Modifier.padding(top = 1.dp))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(td.first, color = if (sel) P.Accent else P.Text, fontSize = 14.sp)
                        Text(td.second, color = P.TextSoft, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            MailIndexToggle()

            // Подсказка про Диск: почта Яндекса почти всегда идёт с Я.Диском.
            if (accounts.any { it.provider == "yandex" } && !diskConnected) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().background(P.Bg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text("💡 У Яндекса есть и Диск", color = P.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Подключи Яндекс.Диск (раздел «Яндекс Диск» на этом экране) — агент сможет работать и с файлами того же аккаунта.",
                        color = P.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        } else {
            if (adding) {
                Text("← к списку", color = P.Accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { adding = false; status = null }.padding(bottom = 8.dp))
            }
            // Выбор провайдера
            Row {
                providers.forEach { (id, title) ->
                    val sel = provider == id
                    Box(
                        Modifier.padding(end = 8.dp).height(38.dp)
                            .background(if (sel) P.Accent else P.Bg, RoundedCornerShape(10.dp))
                            .clickable { provider = id }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(title, color = if (sel) P.Bg else P.Text, fontSize = 13.sp) }
                }
            }
            // --- Быстрый вход через OAuth (для Яндекса — в один тап + код со страницы) ---
            var oauthCode by remember { mutableStateOf<String?>(null) } // null = OAuth не начат
            if (provider == "yandex") {
                Spacer(Modifier.height(6.dp))
                IntegrationField(email, { email = it; status = null }, "Твой Яндекс-адрес (для входа)", KeyboardType.Email)
                Spacer(Modifier.height(8.dp))
                ActionBtn(if (busy) "Открываю…" else "Войти через Яндекс", filled = true) {
                    if (busy) return@ActionBtn
                    if (email.isBlank()) { status = "Впиши свой Яндекс-адрес выше"; return@ActionBtn }
                    busy = true; status = null
                    scope.launch {
                        ru.aiagent.app.integrations.OAuthBroker.start(context, "yandex")
                            .onSuccess { info ->
                                openConsentPage(context, info.authUrl)
                                oauthCode = "" // показать поле ввода кода
                                status = "Разреши доступ на странице Яндекса, скопируй код и вставь ниже."
                            }
                            .onFailure { status = oauthError(it) }
                        busy = false
                    }
                }
                oauthCode?.let { codeVal ->
                    Spacer(Modifier.height(8.dp))
                    IntegrationField(codeVal, { oauthCode = it; status = null }, "Код со страницы Яндекса", KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    ActionBtn(if (busy) "Проверяю…" else "Готово", filled = true) {
                        if (busy || codeVal.isBlank()) return@ActionBtn
                        busy = true; status = null
                        scope.launch {
                            ru.aiagent.app.integrations.OAuthBroker.exchange(context, "yandex", codeVal)
                                .onSuccess { tok ->
                                    val host = tok.imapHost.ifBlank { ru.aiagent.app.integrations.MailClient.imapHost("yandex") }
                                    val mail = tok.email.ifBlank { email.trim() }
                                    ru.aiagent.app.integrations.MailClient.testLogin(host, mail, tok.access, oauth = true)
                                        .onSuccess {
                                            ru.aiagent.app.integrations.MailAuth.save(context, "yandex", mail, tok.access, host, oauth = true, refresh = tok.refresh.takeIf { it.isNotBlank() })
                                            refresh(); adding = false; oauthCode = null; email = ""; status = null
                                        }
                                        .onFailure { status = imapError(it) }
                                }
                                .onFailure { status = "Обмен кода: ${it.message?.take(80)}" }
                            busy = false
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("или вручную по паролю приложения:", color = P.TextSoft, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            IntegrationField(email, { email = it; status = null }, "Email", KeyboardType.Email)
            Spacer(Modifier.height(8.dp))
            IntegrationField(password, { password = it; status = null }, "Пароль приложения", KeyboardType.Password, isPassword = true)

            // Пошаговая подсказка: как получить пароль приложения (не обычный пароль!).
            AppPasswordHelp(provider)

            Spacer(Modifier.height(12.dp))
            ActionBtn(if (busy) "Проверяю…" else "Подключить", filled = true) {
                if (busy || email.isBlank() || password.isBlank()) return@ActionBtn
                busy = true; status = null
                scope.launch {
                    val host = ru.aiagent.app.integrations.MailClient.imapHost(provider)
                    ru.aiagent.app.integrations.MailClient.testLogin(host, email.trim(), password.trim(), oauth = false)
                        .onSuccess {
                            ru.aiagent.app.integrations.MailAuth.save(context, provider, email.trim(), password.trim(), host, oauth = false)
                            refresh(); adding = false; email = ""; password = ""; status = null
                        }
                        .onFailure { status = imapError(it) }
                    busy = false
                }
            }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
        }
    }
}

private data class PwGuide(val intro: String, val url: String, val steps: List<String>)

private fun appPasswordGuide(provider: String): PwGuide = when (provider) {
    "yandex" -> PwGuide(
        intro = "Это НЕ обычный пароль от почты — ты создашь отдельный ключ только для приложения. " +
            "Свой пароль вводить не нужно.",
        url = "https://id.yandex.ru/security/app-passwords",
        steps = listOf(
            "Открой страницу паролей приложений (кнопка ниже) — ты уже залогинен",
            "Нажми «Создать пароль приложения» → выбери тип «Почта»",
            "Придумай название (например «Plus AI») → Яндекс покажет пароль из 16 букв",
            "Скопируй его и вставь в поле «Пароль приложения» выше",
            "Если войти не удаётся: Почта → Настройки → «Почтовые программы» → включи доступ по IMAP",
        ),
    )
    "mailru" -> PwGuide(
        intro = "Это отдельный ключ для приложения, а не пароль от ящика.",
        url = "https://account.mail.ru/user/2-step-auth/passwords/",
        steps = listOf(
            "Открой страницу «Пароли для внешних приложений» (кнопка ниже)",
            "Нажми «Добавить» → назови «Plus AI»",
            "Скопируй показанный пароль и вставь в поле выше",
            "Может потребоваться включить двухфакторную защиту — Mail.ru подскажет",
        ),
    )
    else -> PwGuide(
        intro = "Для Gmail нужен включённый двухэтапный вход (2FA) — иначе пункт не появится.",
        url = "https://myaccount.google.com/apppasswords",
        steps = listOf(
            "Сначала включи двухэтапный вход: myaccount.google.com → Безопасность",
            "Открой «Пароли приложений» (кнопка ниже)",
            "Введи название «Plus AI» → нажми «Создать»",
            "Скопируй пароль из 16 символов (без пробелов) и вставь в поле выше",
        ),
    )
}

@Composable
private fun AppPasswordHelp(provider: String) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val g = appPasswordGuide(provider)

    Column(Modifier.padding(top = 8.dp)) {
        Text(
            if (open) "▾ Как получить пароль приложения" else "▸ Как получить пароль приложения",
            color = P.Accent, fontSize = 13.sp,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) {
            Text(g.intro, color = P.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            g.steps.forEachIndexed { i, s ->
                Row(Modifier.padding(bottom = 6.dp)) {
                    Text("${i + 1}. ", color = P.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(s, color = P.Text, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            ActionBtn("Открыть страницу паролей", filled = false) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.url))) }
            }
        }
    }
}

@Composable
private fun IntegrationField(
    value: String, onChange: (String) -> Unit, label: String,
    keyboard: KeyboardType, isPassword: Boolean = false,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = P.TextSoft) }, singleLine = true,
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = P.Text, unfocusedTextColor = P.Text,
            focusedBorderColor = P.Accent, unfocusedBorderColor = P.Line, cursorColor = P.Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Понятное сообщение по ошибке OAuth. Сервер-брокер требует входа в аккаунт Plus AI (привязка flow
 *  к аккаунту — защита от кражи state), и отвечает «войдите в аккаунт». В контексте «Войти через Яндекс»
 *  это сбивало с толку — поясняем, что сперва нужен вход в аккаунт приложения (меню сверху). */
private fun oauthError(t: Throwable): String {
    val m = t.message.orEmpty()
    return when {
        m.contains("войдите", true) || m.contains("401") ->
            "Сначала войдите в аккаунт Plus AI — в боковом меню сверху. Он нужен, чтобы безопасно подключить провайдера."
        m.contains("не настроен", true) || m.contains("not configured", true) || m.contains("unknown provider", true) ->
            "Провайдер пока не настроен на сервере — владелец ещё не прописал ключи для него."
        else -> "OAuth: ${m.take(80)}"
    }
}

/** Понятное сообщение по отказу IMAP. Частая причина у Яндекса/Mail.ru — IMAP ВЫКЛЮЧЕН в самом ящике
 *  (сервер отвечает AUTHENTICATIONFAILED «invalid credentials or IMAP is disabled»), а не проблема кода. */
private fun imapError(t: Throwable): String {
    val m = t.message.orEmpty()
    return if (m.contains("AUTHENTICATIONFAILED", true) || m.contains("IMAP is disabled", true) ||
        m.contains("invalid credentials", true))
        "Почта не пустила по IMAP. Включите IMAP в ящике: Яндекс.Почта → ⚙ Все настройки → " +
            "«Почтовые программы» → разрешить доступ по IMAP (и по OAuth-токену). Затем повторите."
    else "IMAP: ${m.take(90)}"
}

@Composable
private fun ActionBtn(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).fillMaxWidth()
            .background(if (filled) P.Accent else P.Bg, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) P.Bg else P.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
