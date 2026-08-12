package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.aiagent.app.data.ConversationStore
import ru.aiagent.app.ui.P

/**
 * Корневая оболочка со выдвижным меню (как ChatGPT/DeepSeek/Qwen):
 * чаты по датам + Модели/Инструменты/Настройки. Без нижнего таб-бара.
 */
@Composable
fun AppShell() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val store = remember { ConversationStore(context) }

    var route by remember { mutableStateOf("chat") }        // chat|models|tools|settings
    // Публикуем текущий экран в debug-консоль (в релизе — no-op). Чтобы «в какой ты вкладке» было видно.
    androidx.compose.runtime.LaunchedEffect(route) { ru.aiagent.app.debug.DebugHooks.publishRoute(route) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var historyReload by remember { mutableStateOf(0) }       // триггер обновления списка
    // Монотонный счётчик «сброса чата»: меняется на каждый «новый чат»/открытие,
    // чтобы очистка сработала даже при null→null (иначе новый чат не создаётся).
    var chatEpoch by remember { mutableStateOf(0) }
    // Лейбл аккаунта грузим ВНЕ главного потока (Account.current читает EncryptedSharedPreferences
    // + первый раз строит MasterKey через Keystore — это диск/крипто, нельзя в композиции).
    var accountLabel by remember { mutableStateOf("Войти в аккаунт") }

    fun open() = scope.launch { drawer.open() }
    fun close() = scope.launch { drawer.close() }

    // Список диалогов обновляем каждый раз при открытии меню (чтобы свежий чат был виден).
    androidx.compose.runtime.LaunchedEffect(drawer.currentValue) {
        if (drawer.currentValue == DrawerValue.Open) historyReload++
    }
    androidx.compose.runtime.LaunchedEffect(historyReload) {
        accountLabel = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ru.aiagent.app.cloud.Account.current(context)?.login?.takeIf { it.isNotBlank() } ?: "Войти в аккаунт"
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = P.Bg,
                modifier = Modifier.fillMaxSize(0.84f),
            ) {
                DrawerContent(
                    reloadKey = historyReload,
                    pcActive = route == "pc",
                    onNewChat = { conversationId = null; chatEpoch++; route = "chat"; close() },
                    onOpenChat = { id -> conversationId = id; chatEpoch++; route = "chat"; close() },
                    onDelete = { id -> store.delete(id); historyReload++ },
                    onRoute = { r -> route = r; close() },
                    store = store,
                    accountLabel = accountLabel,
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().background(P.Bg)) {
            // §7/безопасность: если Keystore недоступен, секреты пишутся НЕ зашифрованными —
            // раньше флаг ставился, но нигде не читался; теперь предупреждаем пользователя.
            if (ru.aiagent.app.cloud.SecureKeys.usingInsecureFallback) {
                androidx.compose.material3.Text(
                    "⚠ Защищённое хранилище недоступно — токены и ключи НЕ шифруются. Не вводите чувствительные ключи на этом устройстве.",
                    color = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color(0xFFF0C060))
                        .padding(10.dp),
                )
            }
            when (route) {
                // Под-экраны, открываемые ИЗ Настроек, возвращают в Настройки (а не на главный экран).
                "account" -> AccountScreen(onBack = { route = "chat" }, onChanged = { historyReload++ })
                "models" -> ModelsScreen(onBack = { route = "settings" })
                "pc" -> PcScreen(onBack = { route = "chat" })
                "tools" -> ToolsScreen(onBack = { route = "settings" })
                "extensions" -> ExtensionsScreen(onBack = { route = "settings" })
                "integrations" -> IntegrationsScreen(onBack = { route = "settings" })
                "mcp" -> McpServersScreen(onBack = { route = "settings" })
                "byokpro" -> BYOKProScreen(onBack = { route = "settings" })
                "history" -> HistoryScreen(onBack = { route = "settings" })
                "cloudtask" -> CloudTaskScreen(onBack = { route = "chat" })
                "settings" -> SettingsScreen(onBack = { route = "chat" }, onNavigate = { r -> route = r })
                else -> ChatScreen(
                    conversationId = conversationId,
                    resetKey = chatEpoch,
                    onSettings = { route = "settings" },
                    onNewChat = { conversationId = null; chatEpoch++; historyReload++ },
                    onMenu = { open() },
                    onOpenPc = { route = "pc" },
                )
            }
        }
    }
}

@Composable
private fun DrawerContent(
    reloadKey: Int,
    pcActive: Boolean,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRoute: (String) -> Unit,
    store: ConversationStore,
    accountLabel: String,
) {
    // Список диалогов читаем/парсим с диска ВНЕ главного потока (иначе джанк/ANR при многих чатах).
    val items by androidx.compose.runtime.produceState(initialValue = emptyList<ru.aiagent.app.data.ConversationMeta>(), reloadKey) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.list() }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .clickable { onRoute("account") }
                .padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AccountCircle, null, tint = P.Accent, modifier = Modifier.size(26.dp))
            Text("   $accountLabel", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(color = P.Line)

        // Новый чат
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clickable { onNewChat() }
                .background(P.Surface, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, null, tint = P.Text, modifier = Modifier.size(20.dp))
            Text("  Новый чат", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }

        // Диалоги (группировка появится при накоплении; пока — по свежести)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (items.isEmpty()) {
                item { Text("Диалогов пока нет", color = P.TextSoft, fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)) }
            }
            items(items, key = { it.id }) { c ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenChat(c.id) }.padding(vertical = 11.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.title, color = P.Text, fontSize = 14.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Outlined.Close, contentDescription = "Удалить диалог", tint = P.TextSoft,
                        modifier = Modifier.clickable { onDelete(c.id) }.padding(start = 8.dp).size(18.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = P.Line)
        // Облачная задача (Фаза 3): выполнение на сервере под аккаунтом (с файлами), ответ приходит сюда.
        DrawerLink(Icons.Outlined.Cloud, "Облачная задача") { onRoute("cloudtask") }
        // Остальные разделы (Модели/Инструменты/История/BYOK/Интеграции) переехали в Настройки (правка F).
        DrawerLink(Icons.Outlined.Settings, "Настройки") { onRoute("settings") }
    }
}

/** Сегментированный переключатель контекста агента: Телефон ⇄ ПК. */
@Composable
private fun DeviceToggle(pcActive: Boolean, onPhone: () -> Unit, onPc: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .background(P.Surface, RoundedCornerShape(14.dp)).padding(4.dp),
    ) {
        SegChip("Телефон", selected = !pcActive, modifier = Modifier.weight(1f), onClick = onPhone)
        SegChip("ПК", selected = pcActive, modifier = Modifier.weight(1f), onClick = onPc)
    }
}

@Composable
private fun SegChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) P.Accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(11.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) P.Bg else P.Text, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun DrawerLink(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = P.TextSoft, modifier = Modifier.size(20.dp))
        Text("   $title", color = P.Text, fontSize = 15.sp)
    }
}
