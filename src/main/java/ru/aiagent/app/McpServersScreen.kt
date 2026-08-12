package ru.aiagent.app

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
import androidx.compose.material.icons.outlined.Delete
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.aiagent.app.integrations.mcp.McpAuth
import ru.aiagent.app.integrations.mcp.McpCache
import ru.aiagent.app.integrations.mcp.McpClient
import ru.aiagent.app.integrations.mcp.McpServer
import ru.aiagent.app.ui.P

/**
 * Экран «MCP-серверы» — внешние наборы инструментов (Model Context Protocol) по URL. Как «Свой API», но для
 * ИНСТРУМЕНТОВ: подключил свой MCP-сервер → его тулы доступны агенту (сторонние → гейтятся как расширения:
 * подтверждение + только локальная модель). «Проверить и подключить» обнаруживает список тулов (tools/list)
 * и кэширует его; в чат тулы попадают из кэша (без сети на каждом сообщении).
 */
@Composable
fun McpServersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // (сервер, число закэшированных тулов) — читаем с диска (EncryptedSharedPreferences + кэш) на IO, ОДИН раз
    // и по reload(), а не в композиции на каждой рекомпозиции/нажатии клавиши (иначе крипто-дешифр на UI-потоке).
    var servers by remember { mutableStateOf<List<Pair<McpServer, Int>>>(emptyList()) }
    fun reload() = scope.launch {
        val loaded = withContext(Dispatchers.IO) { McpAuth.servers(context).map { it to McpCache.tools(context, it.name).size } }
        servers = loaded
    }
    LaunchedEffect(Unit) { reload() }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = P.Text, unfocusedTextColor = P.Text,
        focusedBorderColor = P.Accent, unfocusedBorderColor = P.Line, cursorColor = P.Accent,
    )

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "назад", tint = P.Text,
                modifier = Modifier.size(38.dp).clickable { onBack() }.padding(6.dp))
            Text("MCP-серверы — внешние инструменты", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(
                "Подключи внешний MCP-сервер (URL) — его инструменты станут доступны агенту. Сторонний сервер: " +
                    "тулы требуют подтверждения и работают с ЛОКАЛЬНОЙ моделью (§7 — результат не уходит облаку). " +
                    "Только https (http — лишь для localhost).",
                color = P.TextSoft, fontSize = 13.sp,
            )

            // Список подключённых серверов.
            if (servers.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Подключено", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                for ((s, count) in servers) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp)
                            .background(P.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = P.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("${s.url}  ·  инструментов: $count", color = P.TextSoft, fontSize = 11.sp, maxLines = 1)
                        }
                        Icon(
                            Icons.Outlined.Delete, "удалить", tint = P.Accent,
                            modifier = Modifier.size(34.dp).clickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) { McpAuth.remove(context, s.name) }
                                    status = "«${s.name}» отключён"
                                    reload()
                                }
                            }.padding(6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Добавить сервер", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Название (напр. github)", color = P.TextSoft) },
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("URL (https://…/mcp)", color = P.TextSoft) },
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true,
                label = { Text("Токен (Bearer, можно пусто)", color = P.TextSoft) },
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val canAdd = name.isNotBlank() && url.isNotBlank() && !busy
            Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .background(if (canAdd) P.Accent else P.Line, RoundedCornerShape(12.dp))
                    .clickable(enabled = canAdd) {
                        if (!McpClient.isSafeUrl(url)) {
                            status = "URL должен быть https:// (http:// — только для localhost)"
                            return@clickable
                        }
                        busy = true
                        status = "Подключаюсь и запрашиваю список инструментов…"
                        val server = McpServer(name.trim(), url.trim(), token.trim())
                        scope.launch {
                            McpClient.discover(server)
                                .onSuccess { tools ->
                                    if (tools.isEmpty()) {
                                        status = "Сервер ответил, но не отдал ни одного инструмента"
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            McpAuth.save(context, server)
                                            McpCache.save(context, server.name, tools)
                                        }
                                        status = "«${server.name}» подключён: ${tools.size} инструм. — ${tools.joinToString(", ") { it.name }.take(120)}"
                                        name = ""; url = ""; token = ""
                                        reload()
                                    }
                                }
                                .onFailure { status = "Не удалось: ${it.message?.take(140)}" }
                            busy = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Проверить и подключить", color = P.Bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }

            status?.let { Spacer(Modifier.height(10.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
            Spacer(Modifier.height(16.dp))
            Text(
                "URL и токены хранятся в защищённом хранилище телефона (Keystore). Инструменты MCP — сторонний " +
                    "код: каждый вызов требует подтверждения, результат не отправляется облачной модели.",
                color = P.TextSoft, fontSize = 12.sp,
            )
        }
    }
}
