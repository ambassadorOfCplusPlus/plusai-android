package ru.aiagent.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.aiagent.core.inference.Accelerator
import ru.aiagent.core.inference.ChatMessage
import ru.aiagent.core.inference.ChatRole
import ru.aiagent.core.inference.GemmaChatFormat
import ru.aiagent.core.inference.InferenceBackend
import ru.aiagent.core.inference.LlamaCppBackend
import ru.aiagent.core.inference.LoadedModel
import ru.aiagent.core.inference.MediaPipeBackend
import ru.aiagent.data.BackendSelector
import ru.aiagent.data.DeviceProfiler
import ru.aiagent.data.SocCatalog
import java.io.File

/**
 * Движок чата (S9-lite). Бэкенд выбирается автоматически по чипу через [BackendSelector]
 * (Tensor→CPU, Adreno→MediaPipe GPU и т.д. — решение владельца по замерам S1), с
 * безопасным откатом на llama.cpp CPU, если выбранный движок/модель недоступны.
 */
object ChatEngine {
    private const val TAG = "aiagent-chat"
    private val mutex = Mutex()
    private var model: LoadedModel? = null

    /** Явно выбранная пользователем модель (имя файла); null — авто-подбор. */
    @Volatile
    var selectedModelName: String? = null
        private set

    /** Не-чат модели (эмбеддинги, vision, проекторы) — их НЕЛЬЗЯ грузить как чат-модель. */
    private fun isEmbeddingModel(name: String) =
        listOf("bge", "e5", "embed", "gte", "minilm", "mmproj", "smolvlm", "vlm", "vision")
            .any { name.contains(it, true) }

    /** Красивое имя модели для UI. */
    fun friendlyName(fileName: String): String {
        val n = fileName.substringBeforeLast('.').lowercase()
        return when {
            n.contains("e4b") -> "Gemma E4B"
            n.contains("e2b") -> "Gemma E2B"
            n.contains("gemma3-1b") || n.contains("gemma-3-1b") -> "Gemma 3 1B"
            n.contains("ministral") -> "Ministral 3B"
            n.contains("qwen") && n.contains("3b") -> "Qwen2.5 3B"
            n.contains("qwen") && n.contains("1.5b") -> "Qwen2.5 1.5B"
            n.contains("qwen") && n.contains("0.5b") -> "Qwen2.5 0.5B"
            n.contains("llama") && n.contains("1b") -> "Llama 3.2 1B"
            else -> fileName.substringBeforeLast('.')
        }
    }

    /** Модели, доступные для чата (локальные gguf + MediaPipe .task), кроме эмбеддинг-моделей. */
    fun availableModels(context: Context): List<File> =
        File(context.filesDir, "models")
            .listFiles { f -> (f.extension == "gguf" || f.extension == "task") && !isEmbeddingModel(f.name) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Выбрать модель по имени файла (null — снова авто). Сбрасывает загруженную. */
    suspend fun selectModel(context: Context, fileName: String?) = mutex.withLock {
        if (fileName != selectedModelName) {
            selectedModelName = fileName
            model?.close()
            model = null
        }
    }

    /** Текущее отображаемое имя модели (красивое). */
    fun currentModelLabel(context: Context): String {
        val f = selectedModelName?.let { File(File(context.filesDir, "models"), it) }
            ?: pickAuto(context)
        return f?.let { friendlyName(it.name) } ?: "нет модели"
    }

    private fun pickAuto(context: Context): File? = ggufFile(context) ?: mpTaskFile(context)

    private fun ggufFile(context: Context): File? =
        File(context.filesDir, "models")
            .listFiles { f -> f.extension == "gguf" && !isEmbeddingModel(f.name) }
            ?.minByOrNull { it.length() }

    private fun mpTaskFile(context: Context): File? =
        File(context.filesDir, "models").listFiles { f -> f.extension == "task" }?.minByOrNull { it.length() }

    fun findModelFile(context: Context): File? = pickAuto(context)

    /**
     * Стриминг ответа модели. Мьютекс держится на ВСЮ генерацию: один нативный контекст
     * не потокобезопасен, а [selectModel] не должен закрыть handle во время генерации
     * (иначе use-after-free). Модель грузится лениво при первом вызове.
     */
    /** Небольшой системный промпт простого чата (у Gemma нет system-роли — подмешивается в 1-й ход). */
    const val CHAT_SYSTEM =
        "Ты — Plus AI, дружелюбный и полезный ассистент. Отвечай по-русски, ясно и по делу, " +
            "без лишней воды. Если не знаешь — так и скажи."

    fun reply(context: Context, history: List<ChatMessage>): Flow<String> = flow {
        mutex.withLock {
            val loaded = model ?: load(context).also { model = it }
            val prompt = GemmaChatFormat.format(history, system = CHAT_SYSTEM)
            loaded.generate(prompt).collect { piece -> emit(piece) }
        }
    }

    /** Дописать оборванный ответ (кнопка «Продолжить»): последний ход ассистента открыт. */
    fun continueReply(context: Context, history: List<ChatMessage>): Flow<String> = flow {
        mutex.withLock {
            val loaded = model ?: load(context).also { model = it }
            val prompt = GemmaChatFormat.formatContinue(history, system = CHAT_SYSTEM)
            loaded.generate(prompt).collect { piece -> emit(piece) }
        }
    }

    /** Полная генерация по УЖЕ отформатированному промпту (не-стриминг). */
    suspend fun rawGenerate(context: Context, prompt: String): String = mutex.withLock {
        val loaded = model ?: load(context).also { model = it }
        val sb = StringBuilder()
        loaded.generate(prompt).collect { sb.append(it) }
        sb.toString()
    }

    /**
     * Генерация для агентного цикла: транскрипт оборачиваем в чат-шаблон Gemma.
     * Иначе instruct-модель получает сырой промпт без <start_of_turn> и деградирует
     * (пустые ответы, галлюцинации протокола, утечки §7 — A2).
     */
    suspend fun agentGenerate(context: Context, transcript: String): String =
        rawGenerate(context, GemmaChatFormat.format(listOf(ChatMessage(ChatRole.USER, transcript)), system = ""))

    /**
     * Сжать старую историю диалога в компактное резюме (авто-compact при заполнении контекста —
     * как /compact в Claude Code). Резюме заменит старые реплики, чтобы не переполнять n_ctx.
     */
    suspend fun summarize(context: Context, history: List<ChatMessage>): String {
        val convo = history.joinToString("\n") {
            (if (it.role == ChatRole.USER) "Пользователь: " else "Ассистент: ") + it.text
        }
        val instr = "Сожми диалог ниже в компактное резюме на русском: факты, принятые решения, " +
            "важные детали и незавершённые задачи — тезисно, без воды. Это резюме ЗАМЕНИТ историю, " +
            "поэтому сохрани всё, что нужно для продолжения разговора.\n\nДиалог:\n$convo"
        val prompt = GemmaChatFormat.format(
            listOf(ChatMessage(ChatRole.USER, instr)),
            system = "Ты кратко и точно конспектируешь диалоги.",
        )
        // Гасим возможную утечку спец-токенов чат-шаблона в резюме.
        return rawGenerate(context, prompt).replace(Regex("<[^>]{1,20}>"), "").trim()
    }

    /** Балл бенча текущей модели (для гейта инструментов по классу — L8). */
    fun currentBenchScore(context: Context): Double? {
        val name = currentModelLabel(context).lowercase()
        return when {
            name.contains("e4b") -> 74.7
            name.contains("e2b") -> 61.0
            name.contains("ministral") -> 65.7
            // Размер не должен матчиться внутри большего числа (13b не должно ловиться как 3b):
            // требуем, чтобы перед маркером не было цифры/точки.
            name.contains(Regex("(?<![0-9.])(0\\.[56]b|[0-3]b)")) -> 40.0 // мелкие → WEAK
            // Небенчёная/неизвестная модель — по инварианту §0 не даём ей широкий набор:
            // считаем WEAK, пока её не прогнали по методике и не занесли в реестр.
            else -> 40.0
        }
    }

    private suspend fun load(context: Context): LoadedModel {
        // Явный выбор пользователя имеет приоритет над авто-подбором.
        selectedModelName?.let { name ->
            val f = File(File(context.filesDir, "models"), name)
            if (f.exists()) {
                Log.i(TAG, "MODEL(manual): ${f.name}")
                return loadFile(context, f)
            }
        }
        val profile = DeviceProfiler.profile(context)
        val soc = SocCatalog.classify(profile.chipset, profile.ramMb, currentYear = java.time.Year.now().value)
        val cores = Runtime.getRuntime().availableProcessors()
        val plan = BackendSelector.plan(soc, profile.ramMb, cores)
        Log.i(TAG, "PLAN: ${plan.backendId} | ${plan.rationale}")

        // Пытаемся по плану; при любой проблеме — откат на llama.cpp CPU (везде работает).
        val wantsMediaPipe = plan.backendId.startsWith("mediapipe")
        val task = mpTaskFile(context)
        if (wantsMediaPipe && task != null) {
            runCatching {
                val engine: InferenceBackend = MediaPipeBackend(context, maxTokens = 512)
                return engine.loadModel(task.absolutePath, Accelerator.GPU_OPENCL)
            }.onFailure { Log.w(TAG, "MediaPipe не поднялся (${it.message}), откат на CPU") }
        }

        val gguf = checkNotNull(ggufFile(context)) {
            "Локальная модель не найдена в files/models/ — скачайте её на экране моделей"
        }
        return loadFile(context, gguf)
    }

    /** Грузит конкретный файл нужным движком: .task → MediaPipe, .gguf → llama.cpp CPU. */
    private suspend fun loadFile(context: Context, file: File): LoadedModel {
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(6)
        if (file.extension == "task") {
            return MediaPipeBackend(context, maxTokens = 512)
                .loadModel(file.absolutePath, Accelerator.GPU_OPENCL)
        }
        return LlamaCppBackend(maxTokens = 512, temperature = 0.7f, nThreads = threads)
            .loadModel(file.absolutePath, Accelerator.CPU)
    }
}
