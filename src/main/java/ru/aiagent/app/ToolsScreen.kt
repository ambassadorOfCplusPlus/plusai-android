package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Геттеры (не val!): P.* реактивны на смену темы/акцента — читаем на каждой рекомпозиции.
private val Cream: Color get() = ru.aiagent.app.ui.P.Bg
private val Ink: Color get() = ru.aiagent.app.ui.P.Text
private val InkSoft: Color get() = ru.aiagent.app.ui.P.TextSoft
private val Coral: Color get() = ru.aiagent.app.ui.P.Accent
private val Card: Color get() = ru.aiagent.app.ui.P.Surface
private val Line: Color get() = ru.aiagent.app.ui.P.Line

/** Категория возможностей агента: иконка, цвет, что умеет, статус. */
private data class Capability(
    val icon: ImageVector, val color: Color, val title: String, val items: String, val ready: Boolean = true,
)

private val CAPABILITIES = listOf(
    Capability(Icons.Outlined.Description, Color(0xFF4C7EF3), "Документы",
        "Читает PDF, Word, Excel, txt/csv/md · создаёт текстовые документы"),
    Capability(Icons.Outlined.Slideshow, Color(0xFFE8622D), "Офис",
        "Создаёт презентации PowerPoint (.pptx), документы Word (.docx), таблицы Excel (.xlsx)"),
    Capability(Icons.Outlined.MenuBook, Color(0xFF7C5CFF), "База знаний (RAG)",
        "Индексирует твои документы и отвечает по ним — семантический поиск, всё локально"),
    Capability(Icons.Outlined.Calculate, Color(0xFF2AA876), "Утилиты",
        "Калькулятор · дата/время («сколько дней до…») · конвертер величин · текст (счёт слов, base64, хеши)"),
    Capability(Icons.Outlined.Search, Color(0xFF00A3A3), "Поиск и файлы",
        "Поиск по файлам телефона · чтение/создание/список файлов (с твоего разрешения — за пределами папки)"),
    Capability(Icons.Outlined.Visibility, Color(0xFFD94F8A), "Зрение",
        "Описывает изображения (SmolVLM на устройстве) — что на фото, распознавание"),
    Capability(Icons.Outlined.Alarm, Color(0xFFF0A020), "Продуктивность",
        "Событие в календарь · будильник и таймер · открыть ссылку · поделиться текстом"),
    Capability(Icons.Outlined.PhoneAndroid, Color(0xFF6BA644), "Управление телефоном",
        "Читает экран, нажимает, свайпает, навигация (нужна служба Accessibility)"),
    Capability(Icons.Outlined.Code, Color(0xFF3776AB), "Код",
        "Python (pandas, matplotlib) и Java выполняются на устройстве · C++/Node — далее"),
    Capability(Icons.Outlined.Cloud, Color(0xFF5A8DEE), "Облако",
        "Доступ к ~400 моделям через RouterAI · оркестратор план→исполнение→оценка (нужен ключ)"),
)

@Composable
fun ToolsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    // Тумблеры инструментов и модули переехали сюда из Настроек — здесь им место.
    val catalog = remember { AgentChatEngine.toolCatalog(context) }
    var disabled by remember { mutableStateOf(AppSettings.disabledTools(context)) }
    val fileAccess = remember { AppSettings.fileAccessEnabled(context) }
    val expandedCats = remember { mutableStateMapOf<String, Boolean>() }
    val groups = remember(catalog) { catalog.groupBy { it.id.substringBefore("_") } }
    val activeCount = catalog.count { it.id !in disabled && (!it.usesFiles || fileAccess) }

    Column(modifier = Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад", tint = Ink,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp).size(24.dp))
            Text("Инструменты", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        HorizontalDivider(color = Line)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Что умеет агент в режиме «Агент». Инструменты подбираются под задачу автоматически " +
                        "(слабым локальным моделям даётся меньше, облачным — все).",
                    color = InkSoft, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            // Инструменты по категориям с раскрытием
            groups.forEach { (cat, tools) ->
                val expanded = expandedCats.getOrPut(cat) { false }
                item {
                    Row(Modifier.fillMaxWidth().clickable { expandedCats[cat] = !expanded }.padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (expanded) "▾" else "▸", color = Coral, fontSize = 14.sp)
                        Text("  $cat (${tools.count { it.id !in disabled }})", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
                if (expanded) tools.forEach { tool ->
                    item {
                        val on = tool.id !in disabled
                        val ov = tool.usesFiles && !fileAccess
                        val a = if (ov) 0.45f else 1f
                        Row(Modifier.fillMaxWidth().clickable(enabled = !ov) { AppSettings.setToolEnabled(context, tool.id, !on); disabled = AppSettings.disabledTools(context) }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tool.id, color = Ink.copy(alpha = a), fontSize = 14.sp); if (tool.description.isNotBlank()) Text(tool.description.take(80), color = InkSoft.copy(alpha = a), fontSize = 11.sp) }
                            Switch(checked = on && !ov, enabled = !ov, onCheckedChange = { AppSettings.setToolEnabled(context, tool.id, it); disabled = AppSettings.disabledTools(context) }, colors = SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = Coral.copy(alpha = 0.4f)))
                        }
                    }
                }
            }
            item {
                Text(
                    "Инструменты интеграций (GitHub, почта, календарь) появляются после подключения аккаунта. " +
                        "Доступ к файлам включается в Настройках.",
                    color = InkSoft, fontSize = 12.sp,
                )
            }

            // Модули (языки исполнения кода): выключаемые + скачиваемые паки. Базовый APK лёгкий.
            item {
                Section("Модули (языки исполнения кода)") {
                    Text(
                        "Не нужен язык — выключи, чтобы модель не путалась в лишних инструментах. Тяжёлые/нативные " +
                            "(C, TypeScript, Kotlin) качаются паком, не в APK; часть — только на десктопе.",
                        color = InkSoft, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    for (m in ru.aiagent.app.code.CodeModules.all) ModuleRow(context, m)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = InkSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp))
        Column(
            modifier = Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(4.dp),
        ) { content() }
    }
}

/** Строка модуля-языка: вкл/выкл (не грузит модель лишними тулзами) + скачать/удалить для паков. */
@Composable
private fun ModuleRow(context: android.content.Context, m: ru.aiagent.app.code.CodeModules.Module) {
    val scope = rememberCoroutineScope()
    val downloadable = m.kind == ru.aiagent.app.code.CodeModules.Kind.NATIVE_PACK ||
        m.kind == ru.aiagent.app.code.CodeModules.Kind.ASSET_PACK ||
        m.kind == ru.aiagent.app.code.CodeModules.Kind.TOOLCHAIN_PACK
    val desktopOnly = m.kind == ru.aiagent.app.code.CodeModules.Kind.DESKTOP_ONLY
    var enabled by remember { mutableStateOf(ru.aiagent.app.code.CodeModules.enabled(context, m.id)) }
    var installed by remember { mutableStateOf(ru.aiagent.app.code.CodeModules.available(context, m.id)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, color = if (desktopOnly) InkSoft else Ink, fontSize = 15.sp)
            val sub = status ?: when {
                desktopOnly -> "Только на десктопе Фазы 2 (на телефоне exec запрещён)"
                downloadable && !installed -> "≈${m.approxMb} МБ — скачать, чтобы включить"
                enabled -> "Включён" + (if (m.toolIds.isNotEmpty()) " · ${m.toolIds.joinToString(", ")}" else "")
                else -> "Выключен"
            }
            Text(sub, color = InkSoft, fontSize = 12.sp)
        }
        when {
            desktopOnly -> Text("десктоп", color = InkSoft, fontSize = 13.sp)
            downloadable && !installed -> Text(
                if (busy) "…" else "Скачать",
                color = Coral, fontSize = 14.sp,
                modifier = Modifier.clickable(enabled = !busy) {
                    busy = true; status = "Скачиваю…"
                    scope.launch {
                        val pack = ru.aiagent.app.code.RuntimePackManager.known.first { it.id == m.id }
                        ru.aiagent.app.code.RuntimePackManager.install(context, pack) { pct ->
                            status = if (pct < 0) "Скачиваю…" else "Скачиваю… $pct%"
                        }
                            .onSuccess {
                                installed = true; status = null; enabled = true
                                ru.aiagent.app.AppSettings.setModuleEnabled(context, m.id, true)
                            }
                            .onFailure { status = "Ошибка: ${it.message?.take(50)}" }
                        busy = false
                    }
                },
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (enabled) "Выкл" else "Вкл",
                    color = Coral, fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        enabled = !enabled
                        ru.aiagent.app.AppSettings.setModuleEnabled(context, m.id, enabled)
                    },
                )
                if (downloadable) Text(
                    "Удалить", color = InkSoft, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp).clickable {
                        ru.aiagent.app.code.RuntimePackManager.remove(context, m.id); installed = false; status = null
                    },
                )
            }
        }
    }
}
