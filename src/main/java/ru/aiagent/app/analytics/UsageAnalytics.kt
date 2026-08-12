package ru.aiagent.app.analytics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.aiagent.app.cloud.Account
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First-party аналитика «задача↔инструмент» (§7-safe) — Android-порт C# UsageStats. На устройстве
 * классифицируем задачу в фикс-категорию (А по инструментам + Б по тексту) и копим матрицу; наружу
 * (владельцу на сервер) уходят ТОЛЬКО анонимные агрегаты (имена фикс-категорий/инструментов + числа),
 * никогда текст задач/файлов/RAG. Категории ОБЯЗАНЫ совпадать с C# — иначе агрегаты не сольются.
 */
object UsageClassifier {
    const val OTHER = "other"

    // Порядок = приоритет при равенстве очков (совпадает с C#).
    val CATEGORIES = listOf(
        "coding", "math", "data", "writing", "research", "sysadmin",
        "personal", "documents", "media", "security", "automation", OTHER,
    )

    private val TOOL_HINTS = listOf(
        "cas" to "math", "calc" to "math", "sympy" to "math",
        "lsp_" to "coding", "run_code" to "coding", "run_python" to "coding", "compile" to "coding",
        "grep" to "coding", "edit_file" to "coding", "write_file" to "coding", "read_file" to "coding",
        "ssh" to "sysadmin", "run_shell" to "sysadmin", "run_background" to "sysadmin", "powershell" to "sysadmin",
        "email" to "personal", "mail" to "personal", "calendar" to "personal", "contact" to "personal",
        "web_search" to "research", "fetch_url" to "research", "web_render" to "research", "wikipedia" to "research",
        "search_knowledge" to "research", "rag" to "research",
        "document" to "documents", "pdf" to "documents", "docx" to "documents", "office" to "documents",
        "ocr" to "media", "image" to "media", "audio" to "media", "video" to "media",
        "http" to "automation", "background" to "automation", "cron" to "automation",
    )

    private val TEXT_HINTS = listOf(
        "код" to "coding", "программ" to "coding", "функци" to "coding", "баг" to "coding", "рефактор" to "coding",
        "python" to "coding", "java" to "coding", "c++" to "coding", "компил" to "coding", "class " to "coding",
        "интеграл" to "math", "уравнени" to "math", "матриц" to "math", "производн" to "math", "вычисли" to "math",
        "посчита" to "math", "формул" to "math",
        "таблиц" to "data", "csv" to "data", "данны" to "data", "график" to "data", "статист" to "data", "dataset" to "data",
        "напиши текст" to "writing", "статья" to "writing", "перевед" to "writing", "редактир" to "writing",
        "сочини" to "writing", "письмо" to "personal", "почт" to "personal", "встреч" to "personal", "календар" to "personal",
        "найди" to "research", "изучи" to "research", "исследуй" to "research", "что такое" to "research", "сравни" to "research",
        "сервер" to "sysadmin", "nginx" to "sysadmin", "деплой" to "sysadmin", "docker" to "sysadmin", "настрой" to "sysadmin",
        "linux" to "sysadmin",
        "pdf" to "documents", "документ" to "documents", "договор" to "documents", "word" to "documents", "excel" to "documents",
        "картинк" to "media", "изображ" to "media", "фото" to "media", "видео" to "media", "аудио" to "media",
        "реверс" to "security", "уязвим" to "security", "эксплойт" to "security", "безопас" to "security", "malware" to "security",
        "скрипт" to "automation", "автоматизир" to "automation", "бот" to "automation", "cron" to "automation",
    )

    /** Классифицировать задачу по тексту (Б, вес 2) + инструментам (А, вес 3). Порт C#.Classify. */
    fun classify(taskText: String?, tools: Collection<String>?): String {
        val score = HashMap<String, Int>()
        fun add(cat: String, w: Int) { score[cat] = (score[cat] ?: 0) + w }

        val text = (taskText ?: "").lowercase()
        for ((kw, cat) in TEXT_HINTS) if (text.contains(kw)) add(cat, 2)
        if (tools != null) for (t in tools) {
            val n = t.lowercase()
            for ((sub, cat) in TOOL_HINTS) if (n.contains(sub)) add(cat, 3)
        }
        if (score.isEmpty()) return OTHER
        val best = score.values.max()
        return CATEGORIES.first { (score[it] ?: 0) == best }
    }
}

/** Локальные агрегаты-буфер (persist в JSON). Только числа + имена — никакого содержимого задач. */
class UsageStore {
    val categories = HashMap<String, Int>()
    val tools = HashMap<String, Int>()
    val packs = HashMap<String, Int>()
    val pairs = HashMap<String, Int>()

    fun record(taskText: String?, toolsUsed: Collection<String>, packsUsed: Collection<String>? = null) {
        val cat = UsageClassifier.classify(taskText, toolsUsed)
        bump(categories, cat)
        for (t in toolsUsed.toSet()) { bump(tools, t); bump(pairs, "$cat|$t") }
        packsUsed?.toSet()?.forEach { bump(packs, it) }
    }

    fun isEmpty() = categories.isEmpty() && tools.isEmpty()

    /** Вычесть отправленный снимок (после успешной отправки), не теряя добавленное позже. */
    fun subtract(sent: UsageStore) {
        sub(categories, sent.categories); sub(tools, sent.tools); sub(packs, sent.packs); sub(pairs, sent.pairs)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("categories", mapJson(categories)); put("tools", mapJson(tools))
        put("packs", mapJson(packs)); put("pairs", mapJson(pairs))
    }

    fun save(file: File) = runCatching {
        file.parentFile?.mkdirs(); file.writeText(toJson().toString())
    }.let { }

    companion object {
        private fun bump(d: HashMap<String, Int>, k: String) { d[k] = (d[k] ?: 0) + 1 }
        private fun sub(d: HashMap<String, Int>, s: Map<String, Int>) {
            for ((k, v) in s) { val left = (d[k] ?: 0) - v; if (left > 0) d[k] = left else d.remove(k) }
        }
        private fun mapJson(m: Map<String, Int>) = JSONObject().apply { for ((k, v) in m) put(k, v) }
        private fun readMap(o: JSONObject?, dst: HashMap<String, Int>) {
            o ?: return; for (k in o.keys()) dst[k] = o.optInt(k)
        }

        fun load(file: File): UsageStore {
            val s = UsageStore()
            runCatching {
                if (file.exists()) {
                    val o = JSONObject(file.readText())
                    readMap(o.optJSONObject("categories"), s.categories)
                    readMap(o.optJSONObject("tools"), s.tools)
                    readMap(o.optJSONObject("packs"), s.packs)
                    readMap(o.optJSONObject("pairs"), s.pairs)
                }
            }
            return s
        }
    }
}

/**
 * Сбор + анонимная отправка. Вызывается по завершении задачи. Копит дельту в usage-pending.json, шлёт
 * агрегаты на POST /v1/usage/report (Bearer, сервер user-id не хранит) и при успехе вычитает отправленное.
 * Одна отправка за раз (guard) — иначе перекрывающиеся отправки дважды учли бы буфер. Fire-and-forget:
 * телеметрия НИКОГДА не блокирует агента.
 */
object UsageAnalytics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()
    private val sending = AtomicBoolean(false)

    private fun pendingFile(context: Context) = File(context.filesDir, "usage-pending.json")

    // Согласие — через лицензионное соглашение при регистрации (анонимно, без содержимого), поэтому
    // отдельного тумблера нет.
    fun recordAndSend(context: Context, task: String?, tools: Collection<String>) {
        if (tools.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            val file = pendingFile(app)
            synchronized(fileLock) {
                val pending = UsageStore.load(file)
                pending.record(task, tools)
                pending.save(file)
            }
            trySend(app, file)
        }
    }

    private fun trySend(context: Context, file: File) {
        val sess = Account.current(context) ?: return          // не вошёл — копим до входа
        if (!sending.compareAndSet(false, true)) return        // уже идёт отправка — эта партия уйдёт позже
        try {
            val snap = synchronized(fileLock) { UsageStore.load(file) }
            if (snap.isEmpty()) return
            if (post(sess, snap.toJson().toString())) {
                synchronized(fileLock) {
                    val cur = UsageStore.load(file)
                    cur.subtract(snap)
                    cur.save(file)
                }
            }
        } catch (_: Exception) {
            // офлайн/ошибка — буфер остаётся, уйдёт в следующий раз
        } finally {
            sending.set(false)
        }
    }

    private fun post(sess: Account.Session, body: String): Boolean {
        val url = URL(sess.url.trimEnd('/') + "/v1/usage/report")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + sess.token)
            connectTimeout = 8000; readTimeout = 8000
        }
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }
}
