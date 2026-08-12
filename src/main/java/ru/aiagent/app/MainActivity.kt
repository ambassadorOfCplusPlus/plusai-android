package ru.aiagent.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.aiagent.core.inference.Accelerator
import ru.aiagent.core.inference.LlamaCppBackend
import ru.aiagent.data.DeviceProfiler
import java.io.File

private const val TAG = "aiagent-bench"

/**
 * Основной экран — чат с локальной моделью (S9-lite).
 * Dev-бенчмарк S1 остаётся доступен: `adb shell am start -n ru.aiagent.app/.MainActivity --ez bench true`.
 */
class MainActivity : ComponentActivity() {
    // SAF-выбор файла (инструмент file_picker): регистрируем ДО RESUMED, запускаем из onResume при pending.
    private val openDocLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            ru.aiagent.app.utils.FilePickerBridge.deliver(uri)
        }

    // Съёмка фото системной камерой (инструмент camera_capture): контракт TakePicture принимает Uri
    // назначения (наш файл через FileProvider) и возвращает Boolean — снят ли кадр. Регистрируем ДО
    // RESUMED, запускаем из onResume при pending.
    private val takePictureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
            ru.aiagent.app.utils.CameraBridge.deliver(success)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // U6: прогреваем зашифрованное хранилище (первый доступ строит MasterKey через Keystore +
        // диск — дорого) вне main-потока, ДО композиции. Дальше SecureKeys отдаёт кэш, и чтения
        // секретов на экранах (Account/Settings/Models/Chat) не бьют по главному потоку.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ru.aiagent.app.cloud.SecureKeys.get(applicationContext) }
            // Сейв тайн {{secret:имя}} (паритет CLI plusai secret): загружаем зашифрованный список
            // и подключаем в петлю агента (SecretGate) — тоже вне main-потока (Keystore/диск).
            runCatching { SecretStore.load(applicationContext) }
            // Автосинк: при старте тихо забираем с сервера всё новое (настройки, ключи, беседы).
            runCatching { ru.aiagent.app.cloud.AutoSync.pullOnStart(applicationContext) }
            // Интеграция MAX убрана из продукта. Затираем ЛЮБЫЕ её следы, оставшиеся на устройстве от
            // прежних версий: зашифрованный токен сессии/промежуточные токены + несекретный персист
            // (deviceId/userId/маска номера). Идемпотентно — на чистой установке no-op.
            runCatching {
                ru.aiagent.app.cloud.SecureKeys.get(applicationContext).edit()
                    .remove("max_login_token").remove("max_pending_auth").remove("max_pw_track").apply()
                applicationContext.getSharedPreferences("plusai_max", MODE_PRIVATE).edit().clear().apply()
            }
        }
        // REMOTE-XDEV: если пользователь включил «Управление с ПК» и вошёл в аккаунт — поднимаем
        // foreground-хост, чтобы телефон был достижим с ПК сразу после запуска приложения.
        runCatching { ru.aiagent.app.remote.RemoteAgentService.sync(applicationContext) }
        // Оформление (редизайн): восстанавливаем выбранные тему и акцент ДО композиции, чтобы
        // первый кадр рисовался уже в нужной палитре (без вспышки тёмной темы у светлого юзера).
        ru.aiagent.app.ui.P.mode =
            if (AppSettings.lightTheme(this)) ru.aiagent.app.ui.ThemeMode.LIGHT else ru.aiagent.app.ui.ThemeMode.DARK
        ru.aiagent.app.ui.P.accent =
            runCatching { ru.aiagent.app.ui.AccentChoice.valueOf(AppSettings.accent(this)) }
                .getOrDefault(ru.aiagent.app.ui.AccentChoice.CORAL)
        // Edge-to-edge: система НЕ панит окно сама, отступ под клавиатуру даёт imePadding()
        // ровно один раз (иначе двойной сдвиг — «экран улетает вдвое»).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Debug-консоль (localhost, только debug-сборка — в релизе это no-op заглушка): позволяет
        // отлаживать UI с ПК текстом вместо скринов. adb forward tcp:8099 tcp:8099.
        ru.aiagent.app.debug.DebugHooks.onActivityCreate(this)
        // Dev-бенч и code-self-test доступны ТОЛЬКО в debug — иначе сторонний app мог бы
        // дёрнуть исполнение кода через intent на exported MainActivity.
        val benchMode = BuildConfig.DEBUG && intent.getBooleanExtra("bench", false)
        setContent {
            // Статус-бар следует за темой: светлые иконки на тёмной, тёмные — на светлой. Реактивно
            // на P.mode, чтобы переключение «Оформление» в настройках сразу перекрашивало иконки.
            LaunchedEffect(ru.aiagent.app.ui.P.mode) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = (ru.aiagent.app.ui.P.mode == ru.aiagent.app.ui.ThemeMode.LIGHT)
            }
            val scheme =
                if (ru.aiagent.app.ui.P.mode == ru.aiagent.app.ui.ThemeMode.LIGHT) {
                    androidx.compose.material3.lightColorScheme(
                        primary = ru.aiagent.app.ui.P.Accent,
                        background = ru.aiagent.app.ui.P.Bg,
                        surface = ru.aiagent.app.ui.P.Surface,
                        onSurface = ru.aiagent.app.ui.P.Text,
                    )
                } else {
                    androidx.compose.material3.darkColorScheme(
                        primary = ru.aiagent.app.ui.P.Accent,
                        background = ru.aiagent.app.ui.P.Bg,
                        surface = ru.aiagent.app.ui.P.Surface,
                        onSurface = ru.aiagent.app.ui.P.Text,
                    )
                }
            MaterialTheme(colorScheme = scheme) {
                Surface(color = ru.aiagent.app.ui.P.Bg, modifier = Modifier.fillMaxSize()) {
                    if (benchMode) {
                        // Один бэкенд за запуск (иначе троттлинг после CPU искажает GPU/NPU).
                        // adb ... --ez bench true --es backend cpu|vulkan|mp_gpu|mp_npu
                        BenchScreen(intent.getStringExtra("backend") ?: "cpu", intent.getStringExtra("key") ?: "")
                    } else {
                        // Онбординг — один раз при первом запуске (DoD §3.16).
                        var onboarded by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(AppSettings.onboardingDone(this))
                        }
                        if (!onboarded) {
                            OnboardingScreen(onDone = { AppSettings.setOnboardingDone(this); onboarded = true })
                        } else {
                            AppShell()
                        }
                    }
                }
            }
        }
    }

    // NFC foreground reader mode: включаем ТОЛЬКО когда инструмент (nfc_read/nfc_write) ждёт
    // метку (NfcBridge.hasPending()). Тогда система отдаёт теги нам, пока экран на переднем плане.
    // Метку читает/пишет NfcBridge.onTag (на потоке NFC). Дополняет существующий жизненный цикл.
    override fun onResume() {
        super.onResume()
        if (ru.aiagent.app.utils.NfcBridge.hasPending()) {
            runCatching {
                android.nfc.NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
                    this,
                    { tag -> ru.aiagent.app.utils.NfcBridge.onTag(tag) },
                    android.nfc.NfcAdapter.FLAG_READER_NFC_A or
                        android.nfc.NfcAdapter.FLAG_READER_NFC_B or
                        android.nfc.NfcAdapter.FLAG_READER_NFC_F or
                        android.nfc.NfcAdapter.FLAG_READER_NFC_V or
                        android.nfc.NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
            }
        }
        // file_picker: если инструмент ждёт выбор файла и мы ещё не показывали диалог — запускаем SAF.
        if (ru.aiagent.app.utils.FilePickerBridge.consumeLaunch()) {
            runCatching { openDocLauncher.launch(ru.aiagent.app.utils.FilePickerBridge.pendingTypes()) }
                .onFailure { ru.aiagent.app.utils.FilePickerBridge.deliver(null) }
        }
        // camera_capture: если инструмент ждёт кадр и мы ещё не запускали камеру — открываем её на наш файл.
        if (ru.aiagent.app.utils.CameraBridge.hasPending() && ru.aiagent.app.utils.CameraBridge.consumeLaunch()) {
            val uri = ru.aiagent.app.utils.CameraBridge.pendingUri(this)
            if (uri == null) {
                ru.aiagent.app.utils.CameraBridge.deliver(false)
            } else {
                runCatching { takePictureLauncher.launch(uri) }
                    .onFailure { ru.aiagent.app.utils.CameraBridge.deliver(false) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Reader mode держится только на переднем плане; уходим в фон — снимаем.
        runCatching {
            android.nfc.NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        }
    }

    // Под нехваткой памяти выгружаем RAG-эмбеддер (~600 МБ), чтобы не убило процесс.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            lifecycleScope.launch { runCatching { ru.aiagent.app.rag.RagEngine.unload() } }
        }
    }
}

@Composable
private fun BenchScreen(backend: String, cloudKey: String = "") {
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("Готовлю замер ($backend)…") }
    var log by remember { mutableStateOf("") }

    fun append(line: String) {
        Log.i(TAG, line)
        log += line + "\n"
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val profile = DeviceProfiler.profile(context)
            append("DEVICE: ${profile.chipset} | RAM ${profile.ramMb} MB | vulkan=${profile.hasVulkan} nnapi=${profile.hasNnapi}")
            append("THERMAL_BEFORE: ${thermalStatus(context)}")

            val modelsDir = File(context.filesDir, "models")
            try {
                when (backend) {
                    "cpu" -> runLlama(context, modelsDir, Accelerator.CPU, ::append) { status = it }
                    "vulkan" -> runLlama(context, modelsDir, Accelerator.GPU_VULKAN, ::append) { status = it }
                    "mp_gpu" -> runMediaPipe(context, modelsDir, Accelerator.GPU_OPENCL, ::append) { status = it }
                    "mp_npu" -> runMediaPipe(context, modelsDir, Accelerator.NPU_NNAPI, ::append) { status = it }
                    "rag" -> runRagSelfTest(modelsDir, ::append) { status = it }
                    "agent" -> runAgentSelfTest(context, ::append) { status = it }
                    "code" -> runCodeSelfTest(context, ::append) { status = it }
                    "vision" -> runVisionSelfTest(context, modelsDir, ::append) { status = it }
                    "cloud" -> runCloudSelfTest(cloudKey, ::append) { status = it }
                    "files" -> runFilesSelfTest(context, ::append) { status = it }
                    "c" -> runCSelfTest(context, ::append) { status = it }
                    "tools" -> runToolsSelfTest(context, ::append) { status = it }
                    "git" -> runGitSmokeTest(context, ::append) { status = it } // debug-only смоук JGit-пака
                    "cpack" -> runCPackSelfTest(context, ::append) { status = it }
                    "compact" -> runCompactSelfTest(context, ::append) { status = it }
                    else -> append("BENCH_ERROR: неизвестный бэкенд '$backend'")
                }
            } catch (t: Throwable) {
                append("BENCH_ERROR[$backend]: ${t.message?.take(200)}")
                status = "Ошибка: ${t.message?.take(80)}"
            }
            append("THERMAL_AFTER: ${thermalStatus(context)}")
            append("BENCH_DONE")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Plus AI — bench: $backend", style = MaterialTheme.typography.headlineMedium)
        Text(status, style = MaterialTheme.typography.bodyLarge)
        Text(log, style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun runLlama(
    context: android.content.Context,
    modelsDir: File,
    acc: Accelerator,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    // Только чат-модели: исключаем эмбеддинги и vision/проекторы (иначе бенч берёт mmproj).
    val nonChat = listOf("bge", "e5", "embed", "gte", "minilm", "mmproj", "smolvlm", "vlm", "vision")
    val gguf = modelsDir.listFiles { f ->
        f.extension == "gguf" && nonChat.none { f.name.contains(it, true) }
    }?.minByOrNull { it.length() }
    if (gguf == null) { append("NO_MODEL: нет чат-gguf в ${modelsDir.absolutePath}"); return }
    append("MODEL: ${gguf.name} (${gguf.length() / (1024 * 1024)} MB)")
    setStatus("Бенчмарк $acc…")
    val engine = LlamaCppBackend(maxTokens = 96)
    val loadStart = System.currentTimeMillis()
    val model = engine.loadModel(gguf.absolutePath, acc)
    append("LOAD_MS[$acc]: ${System.currentTimeMillis() - loadStart}")
    val r = engine.benchmark(model, prompt = "Кратко объясни, что такое языковая модель.")
    model.close()
    append("BENCH_RESULT: %.2f tok/s | first token %d ms | %s".format(r.tokensPerSecond, r.firstTokenLatencyMs, acc))
    setStatus("Готово ✅  %.1f tok/s ($acc)".format(r.tokensPerSecond))
}

private suspend fun runMediaPipe(
    context: android.content.Context,
    modelsDir: File,
    acc: Accelerator,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    val mp = modelsDir.listFiles { f -> f.extension in setOf("task", "litertlm") }?.minByOrNull { it.length() }
    if (mp == null) { append("MP_MODEL: нет .task/.litertlm"); return }
    append("MP_MODEL: ${mp.name} (${mp.length() / (1024 * 1024)} MB)")
    setStatus("MediaPipe $acc…")
    val engine = ru.aiagent.core.inference.MediaPipeBackend(context, maxTokens = 96)
    val loadStart = System.currentTimeMillis()
    val model = engine.loadModel(mp.absolutePath, acc)
    append("LOAD_MS[MP_$acc]: ${System.currentTimeMillis() - loadStart}")
    val r = engine.benchmark(model, prompt = "Кратко объясни, что такое языковая модель.")
    model.close()
    append("BENCH_RESULT: %.2f tok/s | first token %d ms | MP_%s".format(r.tokensPerSecond, r.firstTokenLatencyMs, acc))
    setStatus("Готово ✅  %.1f tok/s (MP_$acc)".format(r.tokensPerSecond))
}

/**
 * S8: сквозной самотест RAG на устройстве — грузит эмбеддинг-модель (e5), индексирует
 * два коротких документа, ищет по запросу и логирует top-чанк + косинусы. Доказывает
 * работу локального RAG (всё оффлайн).
 */
private suspend fun runRagSelfTest(
    modelsDir: File,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    val e5 = modelsDir.listFiles { f ->
        f.extension == "gguf" && listOf("bge", "e5", "embed").any { f.name.contains(it, true) }
    }?.firstOrNull()
    if (e5 == null) { append("RAG: нет эмбеддинг-модели (*bge*/*e5*.gguf) в ${modelsDir.absolutePath}"); return }
    append("RAG_MODEL: ${e5.name} (${e5.length() / (1024 * 1024)} MB)")
    // bge-m3 не требует префиксов query:/passage: (в отличие от e5).
    val usePrefix = e5.name.contains("e5", true)
    fun q(s: String) = if (usePrefix) "query: $s" else s
    fun p(s: String) = if (usePrefix) "passage: $s" else s
    setStatus("RAG: гружу эмбеддер…")

    val t0 = System.currentTimeMillis()
    val model = ru.aiagent.core.inference.EmbeddingModel.load(e5.absolutePath)
    append("RAG_LOAD_MS: ${System.currentTimeMillis() - t0}")

    val cos = ru.aiagent.core.inference.EmbeddingModel::cosine

    // Диагностика: детерминизм, различимость тем, ранжирование запросов.
    val eW1 = model.embed(p("На складе 262 позиции товаров. Самый дорогой товар — ноутбук за 54990 рублей."))
    val eW2 = model.embed(p("На складе 262 позиции товаров. Самый дорогой товар — ноутбук за 54990 рублей."))
    val eWeather = model.embed(p("Завтра в Москве дождь и похолодание до 12 градусов. Возьмите зонт."))
    append("RAG_DIAG_selfcos: %.3f (тот же текст, ждём ~1.0)".format(cos(eW1, eW2)))
    append("RAG_DIAG_crosscos: %.3f (склад vs погода, ждём НИЗКО)".format(cos(eW1, eWeather)))

    for (query in listOf("какой самый дорогой товар на складе", "какая завтра погода, брать ли зонт")) {
        val eq = model.embed(q(query))
        val sW = cos(eq, eW1)
        val sWeather = cos(eq, eWeather)
        val win = if (sW > sWeather) "СКЛАД" else "ПОГОДА"
        append("RAG_Q: «${query.take(28)}» → склад=%.3f погода=%.3f → $win".format(sW, sWeather))
    }
    model.close()
    setStatus("RAG-диагностика в логе")
}

/**
 * Самотест агентного цикла: просит модель прочитать workspace/notes.txt через инструмент.
 * Логирует список инструментов и все события (вызовы/наблюдения/ответ). Подтверждение — авто.
 */
private suspend fun runAgentSelfTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    val tools = AgentChatEngine.toolsFor(context).map { it.id }
    append("AGENT_TOOLS: ${tools.joinToString(", ")}")
    setStatus("Агент думает…")
    val query = "Прочитай файл notes.txt в рабочей папке и скажи, какой самый дорогой товар."
    append("AGENT_QUERY: $query")
    var toolCalled = false
    AgentChatEngine.run(context, query, "", ru.aiagent.core.agent.AutonomyMode.BYPASS, { true }).collect { ev ->
        when (ev) {
            is ru.aiagent.core.agent.AgentEvent.ToolCall -> {
                toolCalled = true
                append("AGENT_TOOLCALL: ${ev.toolId} ${ev.argsJson.take(80)}")
            }
            is ru.aiagent.core.agent.AgentEvent.ToolObservation ->
                append("AGENT_OBS: ${ev.result::class.simpleName} ${obsText(ev.result).take(90)}")
            is ru.aiagent.core.agent.AgentEvent.EscalationSuggested ->
                append("AGENT_ESCALATE: ${ev.reason.take(80)}")
            is ru.aiagent.core.agent.AgentEvent.Answer ->
                append("AGENT_ANSWER: ${ev.text.take(160)}")
            else -> {}
        }
    }
    append("AGENT_RESULT: ${if (toolCalled) "инструмент ВЫЗВАН" else "инструмент НЕ вызван (модель ответила текстом)"}")
    setStatus("Агент-тест в логе")
}

/** Самотест доступа к файлам: read_file по абсолютному пути вне рабочей папки. */
/** Самотест СКАЧИВАНИЯ пака C с сервера: install → распаковка → System.load → run_c. */
private suspend fun runCPackSelfTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    setStatus("C-pack…")
    val pack = ru.aiagent.app.code.RuntimePackManager.known.first { it.id == "c" }
    append("CPACK: скачиваю ${pack.url}")
    val r = ru.aiagent.app.code.RuntimePackManager.install(context, pack)
    append("CPACK_INSTALL: ${if (r.isSuccess) "OK" else "FAIL ${r.exceptionOrNull()?.message?.take(120)}"}")
    append("CPACK_READY: ${ru.aiagent.app.code.RuntimePackManager.isInstalled(context, "c")}")
    if (ru.aiagent.app.code.CInterp.ensureLoaded(context)) {
        val out = runCatching {
            ru.aiagent.app.code.CInterp.run("#include <stdio.h>\nint main(){printf(\"pack-ok %d\",21*2);return 0;}")
        }.getOrElse { "EXC ${it.message}" }
        append("CPACK_RUN: $out")
    } else {
        append("CPACK: .so не загрузилась")
    }
    append("BENCH_DONE")
    setStatus("C-pack тест в логе")
}

/** Сквозной прогон инструментов: вызывает каждый напрямую + OCR end-to-end + tool-RAG. */
/** Смоук-тест JGit-пака: авто-скачивание пака → clone/status/log публичного репо через DexClassLoader. */
private suspend fun runGitSmokeTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    setStatus("Git…")
    // 1) пак: ensure авто-качает jgit.zip и грузит GitBackendImpl рефлексией через DexClassLoader.
    val backend = ru.aiagent.app.integrations.GitBackends.ensure(context)
    if (backend == null) { append("GIT_PACK: ensure=NULL (пак не скачался/не загрузился)"); return }
    append("GIT_PACK: backend загружен = ${backend.javaClass.name}")
    // 2) clone маленького публичного репо в кэш (без токена — публичный HTTPS).
    val dir = java.io.File(context.cacheDir, "gitsmoke_${System.currentTimeMillis()}")
    dir.deleteRecursively()
    val url = "https://github.com/octocat/Hello-World.git"
    val cl = runCatching { backend.clone(url, dir.absolutePath, null, null); "ok" }
        .getOrElse { e ->
            val cause = e.cause
            val frame = e.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
            "EXC ${e.javaClass.name} msg=${e.message} cause=${cause?.javaClass?.name}:${cause?.message} at=$frame"
        }
    append("GIT_CLONE: ${cl.take(300)}")
    val cloned = java.io.File(dir, "README").exists() || (dir.list()?.isNotEmpty() == true)
    append("GIT_CLONE_FILES: exists=$cloned files=${dir.list()?.joinToString(",")?.take(80)}")
    // 3) status + log на склонированном.
    if (cloned) {
        val st = runCatching { backend.status(dir.absolutePath) }.getOrElse { "EXC ${it.message}" }
        append("GIT_STATUS: ${st.replace("\n", " ").take(120)}")
        val lg = runCatching { backend.log(dir.absolutePath, 3) }.getOrElse { "EXC ${it.message}" }
        append("GIT_LOG: ${lg.replace("\n", " ").take(160)}")
    }
    dir.deleteRecursively()
    append("GIT_SMOKE: ${if (cloned) "PASS ✓" else "FAIL ✗"}")
}

private suspend fun runToolsSelfTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    setStatus("Инструменты…")
    val cat = ru.aiagent.app.AgentChatEngine.toolCatalog(context).associateBy { it.id }
    append("TOOLS_CATALOG: ${cat.size} шт: ${cat.keys.joinToString(",")}")
    val samples = listOf(
        "run_javascript" to """{"code":"print(6*7)"}""",
        "run_lua" to """{"code":"print(6*7)"}""",
        "run_sql" to """{"sql":"CREATE TABLE t(a INT); INSERT INTO t VALUES(3),(4); SELECT sum(a) s FROM t;"}""",
        "repl_eval" to """{"expr":"y=10; y*2"}""",
        "run_java" to """{"code":"System.out.println(6*7);"}""",
        "run_python" to """{"code":"print(6*7)"}""",
        "read_logs" to """{"lines":10}""",
        "datetime" to """{}""",
        "web_search" to """{"query":"погода"}""",
        "run_c" to """{"code":"#include <stdio.h>\nint main(){printf(\"%d\",6*7);return 0;}"}""",
    )
    for ((id, args) in samples) {
        val t = cat[id]
        if (t == null) { append("TOOL[$id]: нет в каталоге"); continue }
        val r = runCatching { kotlinx.coroutines.withTimeout(35000) { t.invoke(args) } }
            .getOrElse { ru.aiagent.core.agent.ToolResult.Failure("EXC ${it.message}") }
        val ok = if (r is ru.aiagent.core.agent.ToolResult.Success) "OK" else "FAIL"
        append("TOOL[$id] $ok: ${obsText(r).replace("\n", " ").take(110)}")
    }
    // OCR end-to-end: рисуем текст в bitmap → распознаём.
    runCatching {
        val bmp = android.graphics.Bitmap.createBitmap(480, 140, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp); cv.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 64f; isAntiAlias = true }
        cv.drawText("Privet 123", 20f, 90f, p)
        val f = java.io.File(context.cacheDir, "ocr_test.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val txt = ru.aiagent.app.ocr.OcrEngine.extract(context, f); f.delete()
        append("OCR_TEST: '${txt.replace("\n", " ").trim().take(60)}'")
    }.onFailure { append("OCR_TEST FAIL: ${it.message}") }
    // tool-RAG: релевантный отбор + СТРЕСС длинным запросом (как чат с большой историей — ронял embed).
    runCatching {
        val exec = ru.aiagent.app.AgentChatEngine.toolsFor(context)
        val picked = ru.aiagent.app.AgentChatEngine.selectRelevant(context, "посчитай сумму чисел в таблице SQL", exec, 6)
        append("TOOLRAG(${exec.size}→6): ${picked.joinToString(",") { it.id }}")
        // длинный запрос (~16000 симв.) не должен ронять эмбеддер (ggml_abort fix):
        val longQ = "проверь все инструменты и историю диалога ".repeat(400)
        val p2 = ru.aiagent.app.AgentChatEngine.selectRelevant(context, longQ, exec, 6)
        append("TOOLRAG_LONG(${longQ.length} симв.): ${p2.joinToString(",") { it.id }} — НЕ УПАЛО ✓")
    }.onFailure { append("TOOLRAG FAIL: ${it.message}") }
    append("BENCH_DONE")
    setStatus("Тест инструментов в логе")
}

/** Самотест авто-compact: сжимает длинную синтетическую историю через ChatEngine.summarize. */
private suspend fun runCompactSelfTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    setStatus("Compact…")
    val hist = (1..12).flatMap { i ->
        listOf(
            ru.aiagent.core.inference.ChatMessage(ru.aiagent.core.inference.ChatRole.USER, "Вопрос $i: расскажи про пункт $i плана проекта, детали и решения."),
            ru.aiagent.core.inference.ChatMessage(ru.aiagent.core.inference.ChatRole.ASSISTANT, "Ответ $i: по пункту $i приняли решение сделать X, важная деталь — Y, осталось Z."),
        )
    }
    append("COMPACT_IN: ${hist.size} реплик, ${hist.sumOf { it.text.length }} симв.")
    val summary = runCatching { ru.aiagent.app.ChatEngine.summarize(context, hist) }.getOrElse { "EXC: ${it.message}" }
    append("COMPACT_OUT (${summary.length} симв.): ${summary.replace("\n", " ").take(220)}")
    append("BENCH_DONE")
    setStatus("Compact-тест в логе")
}

/** Самотест интерпретатора C (пак picoc): грузит libcpico.so и гоняет программу с циклом. */
private suspend fun runCSelfTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    setStatus("C…")
    val so = ru.aiagent.app.code.CInterp.soFile(context)
    append("CINTERP_SO: ${so.absolutePath} exists=${so.exists()} size=${so.length()}")
    if (!ru.aiagent.app.code.CInterp.ensureLoaded(context)) {
        append("CINTERP: не загрузилась (пак не установлен?)"); append("BENCH_DONE"); return
    }
    val code = "#include <stdio.h>\nint main(){int s=0;for(int i=1;i<=5;i++)s+=i;printf(\"sum=%d\\n\",s);return 0;}"
    val out = runCatching { ru.aiagent.app.code.CInterp.run(code) }.getOrElse { "EXC: ${it.message}" }
    append("CINTERP_OUT: ${out.take(300)}")
    append("BENCH_DONE")
    setStatus("C-тест в логе")
}

private suspend fun runFilesSelfTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    setStatus("Файлы…")
    val mgr = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
        android.os.Environment.isExternalStorageManager()
    append("FILES_MANAGER: $mgr")
    // Проба возможности чтения файлов — берём из полного каталога, а не из allTools(),
    // чтобы диагностика не зависела от пользовательских тумблеров (доступ к файлам / выкл. инструментов).
    val readFile = AgentChatEngine.toolCatalog(context).firstOrNull { it.id == "read_file" }
    if (readFile == null) { append("FILES: нет read_file"); append("BENCH_DONE"); return }
    val r = readFile.invoke("""{"path":"/storage/emulated/0/Download/plusai_test.txt"}""")
    append("FILES_READ: ${obsText(r).take(200)}")
    val ls = AgentChatEngine.toolCatalog(context).firstOrNull { it.id == "list_files" }
        ?.invoke("""{"path":"/storage/emulated/0/Download"}""")
    append("FILES_LIST: ${ls?.let { obsText(it).take(160) }}")
    append("BENCH_DONE")
    setStatus("Файлы-тест в логе")
}

/** Самотест облака: DeepSeek V4 Flash через OpenRouter (ключ — из intent, не хранится). */
private suspend fun runCloudSelfTest(
    key: String,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    if (key.isBlank()) { append("CLOUD: нет ключа (--es key sk-...)"); return }
    setStatus("Облако…")
    val cfg = ru.aiagent.core.cloud.CloudConfig(
        route = ru.aiagent.core.cloud.CloudRoute.Byok(ru.aiagent.core.cloud.CloudProvider.DEEPSEEK),
        apiKey = key,
    )
    val client = ru.aiagent.core.cloud.CloudClients.of(ru.aiagent.core.cloud.CloudProvider.DEEPSEEK)
    val sb = StringBuilder()
    val t0 = System.currentTimeMillis()
    try {
        client.generate("Ответь одним предложением: чем ты можешь помочь?", cfg).collect { sb.append(it) }
        append("CLOUD_MS: ${System.currentTimeMillis() - t0}")
        append("CLOUD_ANSWER: ${sb.toString().take(300)}")
    } catch (t: Throwable) {
        append("CLOUD_ERROR: ${t.message?.take(300)}")
    }
    append("BENCH_DONE")
    setStatus("Облако-тест в логе")
}

/** Самотест зрения: SmolVLM + mmproj описывает картинку из files/models/test.jpg. */
private suspend fun runVisionSelfTest(
    context: android.content.Context,
    modelsDir: java.io.File,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    val model = modelsDir.listFiles { f ->
        f.extension == "gguf" && !f.name.startsWith("mmproj", true) &&
            listOf("vlm", "vision", "smolvlm").any { f.name.contains(it, true) }
    }?.firstOrNull()
    val mmproj = modelsDir.listFiles { f -> f.name.startsWith("mmproj", true) && f.extension == "gguf" }?.firstOrNull()
    val image = java.io.File(modelsDir, "test.jpg")
    if (model == null || mmproj == null) { append("VISION: нет модели/mmproj"); return }
    if (!image.exists()) { append("VISION: нет ${image.absolutePath}"); return }
    append("VISION_MODEL: ${model.name} + ${mmproj.name}")
    setStatus("Загружаю vision…")
    val t0 = System.currentTimeMillis()
    val vm = ru.aiagent.core.inference.VisionModel.load(model.absolutePath, mmproj.absolutePath)
    append("VISION_LOAD_MS: ${System.currentTimeMillis() - t0}")
    setStatus("Описываю…")
    val te = System.currentTimeMillis()
    val desc = vm.describe(image.absolutePath, "Describe this image in detail.")
    vm.close()
    append("VISION_MS: ${System.currentTimeMillis() - te}")
    append("VISION_DESC: ${desc.take(300)}")
    append("BENCH_DONE")
    setStatus("Vision-тест в логе")
}

/** Самотест исполнения кода: Python (pandas) и Java (BeanShell) прямо на устройстве. */
private suspend fun runCodeSelfTest(
    context: android.content.Context,
    append: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    val ws = java.io.File(context.filesDir, "workspace").apply { mkdirs() }.absolutePath
    setStatus("Python…")
    val py = ru.aiagent.app.python.RunPythonTool(context, ws).invoke(
        """{"code":"import pandas as pd\ndf=pd.DataFrame({'x':[1,2,3]})\nprint('sum', int(df.x.sum()))"}""",
    )
    append("PY_RESULT: ${obsText(py).take(160)}")

    setStatus("Java…")
    val java = ru.aiagent.app.code.RunJavaTool(context).invoke(
        """{"code":"int s=0; for(int i=1;i<=10;i++) s+=i; System.out.println(\"java sum \"+s);"}""",
    )
    append("JAVA_RESULT: ${obsText(java).take(160)}")

    setStatus("Офис…")
    val pptx = ru.aiagent.app.office.CreatePresentationTool(context, ws).invoke(
        """{"path":"test.pptx","title":"Тест","slides":[{"title":"Слайд","bullets":["раз","два"]}]}""",
    )
    append("PPTX_RESULT: ${obsText(pptx).take(160)}")
    val docx = ru.aiagent.app.office.CreateWordTool(context, ws).invoke(
        """{"path":"test.docx","title":"Тест","sections":[{"heading":"Раздел","text":"абзац"}]}""",
    )
    append("DOCX_RESULT: ${obsText(docx).take(160)}")
    val calc = ru.aiagent.app.office.CalculateTool().invoke("""{"expr":"(17*23)+5/2"}""")
    append("CALC_RESULT: ${obsText(calc).take(120)}")
    append("BENCH_DONE")
    setStatus("Код-тест в логе")
}

private fun obsText(r: ru.aiagent.core.agent.ToolResult): String = when (r) {
    is ru.aiagent.core.agent.ToolResult.Success -> r.outputJson
    is ru.aiagent.core.agent.ToolResult.Failure -> r.message
    else -> ""
}

/** Тепловой статус устройства (0 = норма … 6 = shutdown) — для контроля троттлинга. */
private fun thermalStatus(context: android.content.Context): String {
    return try {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        "status=${pm.currentThermalStatus}"
    } catch (t: Throwable) {
        "n/a"
    }
}
