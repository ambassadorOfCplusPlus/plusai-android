package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.aiagent.data.ModelDownloader
import java.io.File

// Геттеры (не val!): P.* реактивны на смену темы/акцента — читаем на каждой рекомпозиции.
private val Cream: Color get() = ru.aiagent.app.ui.P.Bg
private val Ink: Color get() = ru.aiagent.app.ui.P.Text
private val InkSoft: Color get() = ru.aiagent.app.ui.P.TextSoft
private val Coral: Color get() = ru.aiagent.app.ui.P.Accent

/** Бейдж «СКИДКА» для моделей скидочного пула (бесплатный апстрим на прокси владельца). */
@Composable
fun DiscountBadge() {
    Text(
        "СКИДКА", color = Cream, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(Coral, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
private val Card: Color get() = ru.aiagent.app.ui.P.Surface
private val Line: Color get() = ru.aiagent.app.ui.P.Line

/** Модель каталога для загрузки (реальные ungated gguf с HuggingFace). sha256 — LFS oid. */
private data class CatalogModel(
    val name: String, val file: String, val url: String, val mb: Int, val note: String, val sha256: String = "",
    val category: String = "chat",
    val preinstalled: Boolean = false,
)

private val CATALOG = listOf(
    CatalogModel(
        "Qwen2.5 0.5B", "qwen2.5-0.5b-q4.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        400, "Самая лёгкая: для слабых телефонов, простые задачи",
        "6eb923e7d26e9cea28811e1a8e852009b21242fb157b26149d3b188f3a8c8653",
    ),
    CatalogModel(
        "Llama 3.2 1B", "llama3.2-1b-q4.gguf",
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        810, "Лёгкая, шустрая; средний уровень",
        "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
    ),
    CatalogModel(
        "Qwen2.5 1.5B", "qwen2.5-1.5b-q4.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        940, "Компромисс скорость/качество; ~4 ГБ ОЗУ",
        "1adf0b11065d8ad2e8123ea110d1ec956dab4ab038eab665614adba04b6c3370",
    ),
    CatalogModel(
        "SmolLM2 1.7B", "smollm2-1.7b-q4.gguf",
        "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
        1006, "Шустрая, хороша для простого чата и инструкций",
        "77665ea4815999596525c636fbeb56ba8b080b46ae85efef4f0d986a139834d7",
    ),
    CatalogModel(
        "Gemma 2 2B it", "gemma2-2b-it-q4.gguf",
        "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
        1629, "Сильная 2B от Google; ~5 ГБ ОЗУ",
        "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787",
    ),
    CatalogModel(
        "Qwen2.5 3B", "qwen2.5-3b-q4.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        1930, "Точнее, но требует ~6 ГБ ОЗУ",
        "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94",
    ),
    CatalogModel(
        "Llama 3.2 3B", "llama3.2-3b-q4.gguf",
        "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        1925, "Сильная 3B от Meta; ~6 ГБ ОЗУ",
        "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
    ),
    CatalogModel(
        "Phi-3.5 mini", "phi3.5-mini-q4.gguf",
        "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
        2282, "Сильная в рассуждениях/коде; ~7 ГБ ОЗУ",
        "e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5",
    ),
    CatalogModel(
        "Gemma 4 E2B", "gemma4-e2b-q4.gguf",
        "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
        1600, "Gemma 4 от Google; 2B для агентов",
        "",
    ),
    CatalogModel(
        "Phi-4 Mini", "phi4-mini-q4.gguf",
        "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
        2400, "Phi-4 Mini от Microsoft; сильная в коде",
        "",
    ),
    CatalogModel(
        "SmolVLM2 500M (зрение)", "smolvlm2-500m-q8.gguf",
        "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
        1100, "Описание фото/видео. Нужен mmproj рядом",
        "",
        category = "service",
    ),
    CatalogModel(
        "bge-m3 (для RAG)", "bge-m3.gguf",
        "https://huggingface.co/gpustack/bge-m3-GGUF/resolve/main/bge-m3-Q8_0.gguf",
        605, "Эмбеддер для поиска по документам. Не чат-модель",
        "",
        category = "service",
    ),
    CatalogModel(
        "Whisper Tiny", "ggml-tiny.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        75, "Распознавание речи (русский/английский). Авто-загрузка",
        "",
        category = "service", preinstalled = true,
    ),
    CatalogModel(
        "Tesseract OCR (rus+eng)", "rus.traineddata",
        "https://github.com/tesseract-ocr/tessdata_best/raw/4.1.0/rus.traineddata",
        25, "Распознавание текста с фото/сканов. Вшит в APK",
        "",
        category = "service", preinstalled = true,
    ),
)

@Composable
fun ModelsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val modelsDir = remember { File(context.filesDir, "models").apply { mkdirs() } }
    var installed by remember { mutableStateOf(listInstalled(modelsDir)) }
    // Прогресс/ошибки — из синглтона, а не локального scope: загрузка переживает уход с экрана (U2/U3).
    val progress = ru.aiagent.app.data.ModelDownloadManager.progress
    val downloadErrors = ru.aiagent.app.data.ModelDownloadManager.errors
    androidx.compose.runtime.LaunchedEffect(ru.aiagent.app.data.ModelDownloadManager.completed) {
        installed = listInstalled(modelsDir) // обновляем список, когда загрузка завершилась
    }
    val cloudReady = remember { ru.aiagent.app.cloud.CloudEngine.isConfigured(context) }
    var cloudList by remember { mutableStateOf<List<ru.aiagent.app.cloud.CloudModelInfo>>(emptyList()) }
    var cloudQuery by remember { mutableStateOf("") }
    val chatModels = remember { CATALOG.filter { it.category == "chat" } }
    val serviceModels = remember { CATALOG.filter { it.category == "service" } } // поиск по полному облачному каталогу
    androidx.compose.runtime.LaunchedEffect(cloudReady) {
        if (cloudReady) cloudList = runCatching { ru.aiagent.app.cloud.CloudModels.fetchAll(context) }.getOrDefault(emptyList())
    }

    Column(modifier = Modifier.fillMaxSize().background(Cream).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад", tint = Ink,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Text("Модели", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        HorizontalDivider(color = Line)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Label("Установленные (${installed.size})") }
            items(installed.size) { i ->
                val f = installed[i]
                Card {
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension, color = Ink, fontSize = 15.sp)
                        Text("${f.length() / (1024 * 1024)} МБ", color = InkSoft, fontSize = 12.sp)
                    }
                    Text("Удалить", color = InkSoft, fontSize = 12.sp, modifier = Modifier.clickable {
                        f.delete(); installed = listInstalled(modelsDir)
                    })
                }
            }

            // Облачные модели (RouterAI) — полный каталог с поиском. Не скачиваются, выбираются в чате.
            item { Label("☁ Облачные (RouterAI)") }
            if (!cloudReady) {
                item {
                    Text(
                        "Добавьте ключ RouterAI в Настройки → Облако — и здесь появится полный каталог " +
                            "(~300 моделей: DeepSeek, GPT, Claude, Gemini, Qwen…). Выбираются в шапке чата.",
                        color = InkSoft, fontSize = 12.sp,
                    )
                }
            } else if (cloudList.isEmpty()) {
                item { Text("Загружаю каталог…", color = InkSoft, fontSize = 12.sp) }
            } else {
                item {
                    OutlinedTextField(
                        value = cloudQuery, onValueChange = { cloudQuery = it }, singleLine = true,
                        placeholder = { Text("Поиск по названию или id (gpt, claude, deepseek…)", color = InkSoft) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Coral, unfocusedBorderColor = Line,
                            focusedTextColor = Ink, unfocusedTextColor = Ink),
                    )
                }
                val q = cloudQuery.trim()
                val filtered = if (q.isBlank()) cloudList
                else cloudList.filter { ru.aiagent.app.cloud.modelMatchesQuery(it, q) }
                item {
                    Text("Каталог: ${cloudList.size} · показано ${filtered.size}. Цена ₽ за 1М токенов (вход/выход). Выбор — в шапке чата.",
                        color = InkSoft, fontSize = 12.sp)
                }
                items(filtered.size) { i ->
                    val m = filtered[i]
                    Card {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, color = Ink, fontSize = 14.sp, maxLines = 1,
                                    modifier = Modifier.weight(1f, fill = false))
                                if (m.freePool) DiscountBadge()
                            }
                            Text(m.id, color = InkSoft, fontSize = 11.sp, maxLines = 1)
                        }
                        Text("%.0f/%.0f ₽".format(m.priceIn, m.priceOut), color = Coral, fontSize = 12.sp)
                    }
                }
            }

            item { Label("Локальные чат-модели") }
            items(chatModels.size) { i ->
                val m = chatModels[i]
                val exists = File(modelsDir, m.file).exists()
                val pct = progress[m.file]
                Card {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Ink, fontSize = 15.sp)
                        Text("${m.mb} МБ · ${m.note}", color = InkSoft, fontSize = 12.sp)
                        if (pct != null && pct in 0..99) {
                            LinearProgressIndicator(progress = { pct / 100f }, color = Coral, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        downloadErrors[m.file]?.let { msg -> Text("⚠ $msg", color = Coral, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    when {
                        exists -> Text("✓", color = Coral, fontSize = 16.sp)
                        pct != null -> Text("$pct%", color = Coral, fontSize = 13.sp)
                        else -> Text("Скачать", color = Coral, fontSize = 13.sp, modifier = Modifier.clickable {
                            ru.aiagent.app.data.ModelDownloadManager.start(m.url, File(modelsDir, m.file), m.sha256)
                        })
                    }
                }
            }
                if (serviceModels.isNotEmpty()) {
                item { Label("Служебные") }
                items(serviceModels.size) { i ->
                    val m = serviceModels[i]
                    val exists = m.preinstalled || File(modelsDir, m.file).exists()
                    val pct = progress[m.file]
                    Card {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = Ink, fontSize = 15.sp)
                            Text("${m.mb} МБ · ${m.note}", color = InkSoft, fontSize = 12.sp)
                            if (pct != null && pct in 0..99) { LinearProgressIndicator(progress = { pct / 100f }, color = Coral, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
                        }
                        when {
                            exists -> Text("✓", color = Coral, fontSize = 16.sp)
                            pct != null -> Text("$pct%", color = Coral, fontSize = 13.sp)
                            m.preinstalled -> Text("Авто", color = InkSoft, fontSize = 12.sp)
                            m.url.isEmpty() -> Text("Вручную", color = InkSoft, fontSize = 12.sp)
                            else -> Text("Скачать", color = Coral, fontSize = 13.sp, modifier = Modifier.clickable { ru.aiagent.app.data.ModelDownloadManager.start(m.url, File(modelsDir, m.file), m.sha256) })
                        }
                    }
                }
            }
            item {
                Text(
                    "Загрузка идёт в фоне. Модель появится в выборе на экране чата. " +
                        "Каталог — открытые gguf с HuggingFace; можно добавить свои.",
                    color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, color = InkSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun listInstalled(dir: File): List<File> =
    dir.listFiles { f -> f.extension == "gguf" || f.extension == "task" }?.sortedBy { it.name } ?: emptyList()
