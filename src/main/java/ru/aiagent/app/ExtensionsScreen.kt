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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.ext.ExtManifest
import ru.aiagent.app.ext.ExtensionManager
import ru.aiagent.app.ui.P
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Геттеры (не val!): P.* реактивны на смену темы/акцента — читаем на каждой рекомпозиции.
private val Cream: Color get() = P.Bg
private val Ink: Color get() = P.Text
private val InkSoft: Color get() = P.TextSoft
private val Coral: Color get() = P.Accent
private val Card: Color get() = P.Surface
private val Line: Color get() = P.Line

private const val CATALOG_URL = "https://api.plus-ai.ru/v1/extensions"
private const val SUBMIT_URL = "https://api.plus-ai.ru/v1/extensions/submit"

/** Возможности, которые пользователь включает расширению тумблерами. */
private val CAPS = listOf(
    "camera" to "Камера",
    "audio" to "Микрофон (аудио)",
    "interactive" to "Интерактив",
    "network" to "Сеть",
)

/** Встроенные Python-стеки — уже в APK через Chaquopy, не требуют установки. */
private data class BuiltinStack(
    val id: String, val name: String, val desc: String, val tools: String,
)

private val BUILTIN_STACKS = listOf(
    BuiltinStack("vyshmat", "Вышмат", "numpy, scipy, sympy, matplotlib, pandas — математика и научные вычисления", "run_python"),
    BuiltinStack("aiarch", "Архитектуры ИИ", "torch, einops, torchvision — создание и эксперименты с нейросетями (десктоп-пак aiarch)", "run_python"),
    BuiltinStack("aitrain", "Обучение ИИ", "transformers, peft, accelerate — дообучение и файнтюнинг моделей (десктоп-пак aitrain)", "run_python"),
)

/** Позиция каталога магазина расширений (с сервера). */
private data class StoreItem(
    val id: String, val name: String, val author: String, val description: String,
    val lang: String, val url: String, val sha256: String,
    val tools: List<String>, val permissions: List<String>,
)

/**
 * Экран «Расширения» — «плюсик»: магазин + управление сторонними инструментами (Python-раскладка
 * платформы расширений). Пользователь ставит расширение и явно ВКЛЮЧАЕТ ему возможности
 * (камера/аудио/интерактив/сеть) тумблерами. Установка: из магазина, из файла (SAF) или заявка на
 * публикацию своего. Реестр агента подхватывает установленные расширения (см. extensionTools).
 */
@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(ExtensionManager.installed(context)) }
    val prefs = remember { context.getSharedPreferences("plusai_ext", android.content.Context.MODE_PRIVATE) }
    var disabledStacks by remember { mutableStateOf(prefs.getStringSet("disabled_stacks", emptySet()) ?: emptySet()) }
    fun refresh() { installed = ExtensionManager.installed(context) }

    // Магазин
    var catalog by remember { mutableStateOf<List<StoreItem>>(emptyList()) }
    var catalogStatus by remember { mutableStateOf<String?>(null) }
    var catalogBusy by remember { mutableStateOf(false) }

    // Установка из файла (SAF): zip → временный файл → installFromZip.
    var fileStatus by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        fileStatus = "Устанавливаю…"
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "ext_import_${System.nanoTime()}.zip")
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    val r = ExtensionManager.installFromZip(context, tmp)
                    tmp.delete()
                    r.getOrThrow()
                }
            }
            res.onSuccess { fileStatus = "Установлено: ${it.name}"; refresh() }
                .onFailure { fileStatus = "Ошибка: ${it.message?.take(120)}" }
        }
    }

    Column(Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад", tint = Ink,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp).size(24.dp))
            Text("Расширения", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        HorizontalDivider(color = Line)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Text(
                    "Расширения добавляют агенту новые инструменты. Ставь их из магазина или файла и явно " +
                        "включай нужные возможности — сторонний код не получит камеру, микрофон, интерактив " +
                        "или сеть, пока ты сам не разрешишь.",
                    color = InkSoft, fontSize = 12.5.sp,
                )
            }

            // 1. Встроенные Python-стеки (уже в APK, всегда доступны через run_python)
            item {
                Section("Встроенные (${BUILTIN_STACKS.size})") {
                    BUILTIN_STACKS.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = Line)
                        val on = s.id !in disabledStacks
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, color = Ink.copy(alpha = if (on) 1f else 0.5f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(s.desc, color = InkSoft, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                                Row(Modifier.padding(top = 6.dp)) {
                                    Text("Python", color = Coral.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.background(Coral.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(if (on) "Встроено ✓" else "Выключено", color = InkSoft, fontSize = 12.sp)
                                }
                            }
                            Switch(checked = on, onCheckedChange = {
                                val ns = disabledStacks.toMutableSet()
                                if (on) ns.add(s.id) else ns.remove(s.id)
                                disabledStacks = ns; prefs.edit().putStringSet("disabled_stacks", ns).apply()
                            }, colors = SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = Coral.copy(alpha = 0.4f)))
                        }
                    }
                }
            }

            // 2. Установленные
            item {
                Section("Установленные (${installed.size})") {
                    if (installed.isEmpty()) {
                        Text("Пока ничего не установлено.", color = InkSoft, fontSize = 13.sp,
                            modifier = Modifier.padding(14.dp))
                    } else {
                        installed.forEachIndexed { i, mf ->
                            if (i > 0) HorizontalDivider(color = Line)
                            InstalledCard(mf, context) { ExtensionManager.remove(context, mf.id); refresh() }
                        }
                    }
                }
            }

            // 3. Магазин
            item {
                Section("Магазин") {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Каталог расширений с сервера.", color = InkSoft, fontSize = 12.5.sp,
                            modifier = Modifier.weight(1f))
                        Text(
                            if (catalogBusy) "…" else "Обновить каталог", color = Coral, fontSize = 14.sp,
                            modifier = Modifier.clickable(enabled = !catalogBusy) {
                                catalogBusy = true; catalogStatus = null
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { runCatching { fetchCatalog() } }
                                    res.onSuccess { catalog = it; catalogStatus = if (it.isEmpty()) "Каталог пуст" else null }
                                        .onFailure { catalog = emptyList(); catalogStatus = "Каталог недоступен" }
                                    catalogBusy = false
                                }
                            },
                        )
                    }
                    catalogStatus?.let {
                        Text(it, color = InkSoft, fontSize = 12.5.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    }
                    val installedIds = installed.map { it.id }.toSet()
                    catalog.forEach { item ->
                        HorizontalDivider(color = Line)
                        StoreCard(item, already = item.id in installedIds) { onResult ->
                            scope.launch {
                                val res = installFromStore(context, item)
                                if (res.isSuccess) refresh()
                                onResult(res)
                            }
                        }
                    }
                }
            }

            // 4. Установить из файла
            item {
                Section("Установить из файла") {
                    Column(Modifier.padding(14.dp)) {
                        Text("Выбери ZIP-архив расширения (с manifest.json внутри).",
                            color = InkSoft, fontSize = 12.5.sp)
                        Spacer(Modifier.height(10.dp))
                        ActionButton("Выбрать файл (.zip)", filled = true) {
                            fileStatus = null
                            runCatching { filePicker.launch(arrayOf("application/zip")) }
                        }
                        fileStatus?.let {
                            Spacer(Modifier.height(8.dp)); Text(it, color = Coral, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 5. Опубликовать своё
            item { PublishSection() }
        }
    }
}

/** Карточка установленного расширения: метаданные, инструменты, тумблеры возможностей, «Удалить». */
@Composable
private fun InstalledCard(mf: ExtManifest, context: android.content.Context, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mf.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${mf.author} · v${mf.version} · ${mf.lang}", color = InkSoft, fontSize = 12.sp)
            }
            Text("Удалить", color = Coral, fontSize = 13.sp, modifier = Modifier.clickable { onRemove() })
        }
        if (mf.tools.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Инструменты:", color = InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            mf.tools.forEach { t ->
                Text("• ${t.id} — ${t.schema}", color = Ink, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Возможности:", color = InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        CAPS.forEach { (cap, label) ->
            var on by remember(mf.id, cap) { mutableStateOf(AppSettings.extCapEnabled(context, mf.id, cap)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = { on = it; AppSettings.setExtCap(context, mf.id, cap, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = Coral.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

/** Карточка позиции магазина: метаданные + «Установить» (или «Установлено»).
 *  onInstall получает колбэк результата: карточка ОБЯЗАНА снять «Устанавливаю…» в любом исходе
 *  (иначе при ошибке зависает навсегда) и показать текст ошибки. */
@Composable
private fun StoreCard(item: StoreItem, already: Boolean, onInstall: ((Result<ExtManifest>) -> Unit) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${item.author} · ${item.lang}", color = InkSoft, fontSize = 12.sp)
            }
            when {
                already -> Text("Установлено ✓", color = InkSoft, fontSize = 13.sp)
                busy -> Text("Устанавливаю…", color = InkSoft, fontSize = 13.sp)
                else -> Text("Установить", color = Coral, fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        busy = true; error = null
                        onInstall { res ->
                            busy = false
                            error = res.exceptionOrNull()?.message?.take(120)
                        }
                    })
            }
        }
        if (item.description.isNotBlank()) {
            Text(item.description, color = InkSoft, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (item.permissions.isNotEmpty()) {
            Text("Запрашивает: ${item.permissions.joinToString(", ")}", color = InkSoft, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        error?.let {
            Text("Ошибка установки: $it", color = Coral, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Раздел «Опубликовать своё»: форма заявки + ссылка на документацию для разработчиков. */
@Composable
private fun PublishSection() {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Section("Опубликовать своё") {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Сделал полезное расширение? Отправь заявку — после ревью владельцем оно появится в магазине.",
                color = InkSoft, fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(10.dp))
            ExtField(name, { name = it; status = null }, "Название расширения")
            Spacer(Modifier.height(8.dp))
            ExtField(author, { author = it; status = null }, "Автор (имя / никнейм)")
            Spacer(Modifier.height(8.dp))
            ExtField(contact, { contact = it; status = null }, "Контакт (email / Telegram)", KeyboardType.Email)
            Spacer(Modifier.height(8.dp))
            ExtField(description, { description = it; status = null }, "Что делает", singleLine = false)
            Spacer(Modifier.height(8.dp))
            ExtField(sources, { sources = it; status = null }, "Ссылка на исходники")
            Spacer(Modifier.height(12.dp))
            ActionButton(if (busy) "Отправляю…" else "Отправить заявку", filled = true) {
                if (busy) return@ActionButton
                if (name.isBlank() || contact.isBlank()) { status = "Заполни название и контакт"; return@ActionButton }
                busy = true; status = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching { submitExtension(name, author, contact, description, sources) }
                    }
                    res.onSuccess { status = "Заявка отправлена — спасибо!" }
                        .onFailure { status = "Не удалось отправить: ${it.message?.take(100)}" }
                    busy = false
                }
            }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Coral, fontSize = 13.sp) }
            Spacer(Modifier.height(10.dp))
            Text(
                "Документация для разработчиков — см. docs/EXTENSION_SDK.md в репозитории проекта: " +
                    "формат manifest.json, объявление инструментов и запрашиваемые возможности.",
                color = InkSoft, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ExtField(
    value: String, onChange: (String) -> Unit, label: String,
    keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = InkSoft) }, singleLine = singleLine,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedBorderColor = Coral, unfocusedBorderColor = Line, cursorColor = Coral,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(46.dp).fillMaxWidth()
            .background(if (filled) Coral else Cream, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Cream else Coral, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = InkSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp))
        Column(
            modifier = Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(4.dp),
        ) { content() }
    }
}

// --- Сеть (вне главного потока — вызывается из withContext(Dispatchers.IO)) ---

private fun fetchCatalog(): List<StoreItem> {
    val conn = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
        setRequestProperty("Accept", "application/json")
    }
    conn.use {
        val code = it.responseCode
        if (code !in 200..299) error("HTTP $code")
        val body = it.inputStream.bufferedReader().readText()
        val arr = JSONObject(body).optJSONArray("extensions") ?: JSONArray()
        // Разбор КАЖДОЙ записи изолирован: одна битая позиция (нет id и т.п.) не должна ронять весь
        // каталог — пропускаем её (mapNotNull), а не бросаем на всём списке.
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) return@runCatching null
                StoreItem(
                    id = id,
                    name = o.optString("name", id),
                    author = o.optString("author", "—"),
                    description = o.optString("description", ""),
                    lang = o.optString("lang", "python"),
                    url = o.optString("url", ""),
                    sha256 = o.optString("sha256", ""),
                    tools = o.optJSONArray("tools").toToolNames(),
                    permissions = o.optJSONArray("permissions").toStringList(),
                )
            }.getOrNull()
        }
    }
}

/** Скачать zip позиции магазина во временный файл, СВЕРИТЬ SHA-256 с каталогом и установить.
 *  Возвращает Result — вызывающая карточка снимает «Устанавливаю…» и показывает ошибку в любом исходе. */
private suspend fun installFromStore(
    context: android.content.Context, item: StoreItem,
): Result<ExtManifest> = withContext(Dispatchers.IO) {
    runCatching {
        if (item.url.isBlank()) error("нет ссылки на архив")
        // Только https: без TLS zip можно подменить по пути (а он идёт в исполнение расширения).
        if (!item.url.startsWith("https://")) error("небезопасная ссылка (нужен https)")
        // Fail-closed: каталог обязан задавать sha256. Нет хэша → не ставим (обещанная сверка на сайте/в CLAUDE.md).
        val expected = item.sha256.trim().lowercase()
        if (expected.isBlank()) error("в каталоге нет SHA-256 — установка запрещена")
        val tmp = File(context.cacheDir, "ext_dl_${item.id}_${System.nanoTime()}.zip")
        try {
            val conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 30_000
            }
            conn.use { c ->
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            // Считаем хэш скачанного zip и сверяем ДО установки: несовпадение → подмена/повреждение, не ставим.
            val actual = sha256Hex(tmp)
            if (actual != expected) error("SHA-256 не совпал (архив повреждён или подменён)")
            ExtensionManager.installFromZip(context, tmp).getOrThrow()
        } finally {
            tmp.delete()
        }
    }
}

// Тело заявки должно совпадать с серверным extSubmission (name/author/contact/description/source_url),
// иначе сервер отвечает 400. Раньше слали "sources" — сервер ждёт "source_url".
private fun submitExtension(name: String, author: String, contact: String, description: String, sourceUrl: String) {
    val payload = JSONObject()
        .put("name", name).put("author", author).put("contact", contact)
        .put("description", description).put("source_url", sourceUrl)
        .toString()
    val conn = (URL(SUBMIT_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 15_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
    }
    conn.use {
        it.outputStream.use { os -> os.write(payload.toByteArray()) }
        val code = it.responseCode
        if (code !in 200..299) error("HTTP $code")
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}

/** tools в каталоге — массив ОБЪЕКТОВ {id,name,schema}, а не строк. optString(i) на объекте вернул бы
 *  JSON-строку целиком (мусор в UI) — поэтому достаём name (или id) из объекта. Фолбэк на строку —
 *  на случай старого формата. Битую позицию пропускаем. */
private fun JSONArray?.toToolNames(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        runCatching {
            val o = optJSONObject(i)
            if (o != null) o.optString("name").ifBlank { o.optString("id") } else optString(i)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** SHA-256 файла в hex (lowercase), стримом — тот же подход, что в RuntimePackManager.sha256Hex.
 *  Нужен для сверки скачанного zip расширения с хэшем из каталога (защита от подмены). */
private fun sha256Hex(f: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Автозакрытие HttpURLConnection в use { } (disconnect в finally). */
private inline fun <R> HttpURLConnection.use(block: (HttpURLConnection) -> R): R =
    try { block(this) } finally { disconnect() }
