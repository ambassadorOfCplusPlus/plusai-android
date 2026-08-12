package ru.aiagent.app.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.aiagent.core.inference.EmbeddingModel
import ru.aiagent.core.rag.Embedder
import ru.aiagent.core.rag.LocalRagIndex
import ru.aiagent.core.rag.ChunkCipher
import ru.aiagent.core.rag.RagChunk
import ru.aiagent.core.rag.SqliteVectorStore
import ru.aiagent.app.security.CryptoBox
import java.io.File

/**
 * Шифратор текста чанков RAG для [SqliteVectorStore] — AES-GCM через Android Keystore ([CryptoBox]),
 * тот же механизм, что у индекса почты. Base64, потому что колонка chunk — TEXT.
 */
private object RagChunkCipher : ChunkCipher {
    override fun encrypt(plain: String): String =
        android.util.Base64.encodeToString(CryptoBox.encrypt(plain.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)

    override fun decrypt(stored: String): String =
        String(CryptoBox.decrypt(android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)), Charsets.UTF_8)
}

/**
 * Локальный RAG в приложении (S8, подключение к чату): эмбеддер bge-m3 + SQLite-индекс.
 * Всё на устройстве (инвариант приватности ТЗ §7). Эмбеддер грузится лениво и держится;
 * доступ сериализован (один нативный контекст не потокобезопасен).
 */
object RagEngine {
    private const val TAG = "aiagent-rag"
    private val mutex = Mutex()
    private var model: EmbeddingModel? = null
    private var index: LocalRagIndex? = null
    // Кэш эмбеддингов СТАТИЧНЫХ строк (описания инструментов для tool-RAG): ключ = текст.
    // Инвалидируется при смене/выгрузке эмбеддера. Динамику (почта) сюда не кладём.
    private val embedCache = HashMap<String, FloatArray>()

    /** Есть ли установленная эмбеддинг-модель (иначе RAG недоступен). */
    // ДЕТЕРМИНИРОВАННЫЙ выбор: listFiles даёт неупорядоченный список, а имя файла = embedderId для
    // ensureModel. При двух эмбеддерах (bge + e5) firstOrNull флипал бы между запусками → каждый флип
    // выглядит как смена модели → ensureModel стирал бы весь RAG-индекс. Стабилизируем: bge в приоритете,
    // затем по алфавиту.
    fun embeddingModelFile(context: Context): File? =
        File(context.filesDir, "models").listFiles { f ->
            f.extension == "gguf" && listOf("bge", "e5", "embed", "gte").any { f.name.contains(it, true) }
        }?.sortedWith(compareByDescending<File> { it.name.contains("bge", true) }.thenBy { it.name.lowercase() })
            ?.firstOrNull()

    private suspend fun ready(context: Context): LocalRagIndex? = mutex.withLock {
        index?.let { return it }
        val file = embeddingModelFile(context) ?: return null
        val m: EmbeddingModel
        try {
            m = EmbeddingModel.load(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "RAG: не удалось загрузить эмбеддинг-модель ${file.name}: ${e.message}", e)
            error("эмбеддинг-модель ${file.name} найдена, но не загрузилась: ${e.message?.take(200)}")
        }
        // §7: текст чанков шифруем на устройстве (AES-GCM, ключ в Keystore). Вектор — открытым
        // (нужен для поиска, исходный текст по нему не восстановить). Старые открытые базы читаются
        // как есть (миграция в SqliteVectorStore), при переиндексации перезапишутся зашифрованными.
        val store = SqliteVectorStore(context, RagChunkCipher)
        val embedder = Embedder { m.embed(it) }
        val usePrefix = file.name.contains("e5", true) // bge-m3 — без префиксов
        // Проактивная сверка модели ПРИ ЗАГРУЗКЕ: если индекс собран другой моделью (иная размерность
        // ИЛИ иное имя gguf), чистим его здесь — до первого поиска. Иначе смена на модель той же
        // размерности вернула бы правдоподобный мусор (векторы несопоставимы). Размерность узнаём одним
        // пробным эмбеддингом (модель всё равно загружена). Пустой вектор (сбой) → сверку пропускаем.
        val probe = runCatching { m.embed("x") }.getOrDefault(floatArrayOf())
        if (probe.isNotEmpty()) {
            val wiped = store.ensureModel(probe.size, file.name)
            if (wiped) Log.i(TAG, "RAG: сменилась эмбеддинг-модель → индекс сброшен, нужен реиндекс")
        }
        val idx = LocalRagIndex(embedder, store, usePrefix, file.name)
        model = m; index = idx
        embedCache.clear() // новый эмбеддер → старые векторы описаний недействительны (размерность/модель)
        Log.i(TAG, "RAG готов на ${file.name} (prefix=$usePrefix)")
        idx
    }

    /** Проиндексировать текст документа под ключом uri (обычно имя файла). Тотальный Result. */
    suspend fun indexText(context: Context, uri: String, text: String): Result<Int> = runCatching {
        ready(context) ?: error("эмбеддинг-модель не установлена")
        // Берём АКТУАЛЬНЫЙ index под локом: если unload() освободил модель в зазоре между
        // ready() и этим блоком — index станет null, и мы не дёрнем освобождённый нативный хендл.
        mutex.withLock {
            val idx = index ?: error("эмбеддер выгружен")
            idx.indexDocument(uri, text); idx.lastSkippedChunks
        }
    }

    /** Найти релевантные фрагменты по запросу. Никогда не бросает (пустой список при сбое). */
    suspend fun search(context: Context, query: String, topK: Int = 5): List<RagChunk> =
        runCatching {
            ready(context) ?: return emptyList()
            mutex.withLock { (index ?: return@withLock emptyList()).search(query, topK) }
        }.getOrDefault(emptyList())

    suspend fun removeDocument(context: Context, uri: String) {
        runCatching { ready(context) }.getOrNull() ?: return
        mutex.withLock { index?.removeDocument(uri) }
    }

    /** Выгрузить эмбеддер (освободить ~600 МБ) — вызывается под нехваткой памяти. */
    suspend fun unload() = mutex.withLock {
        runCatching { model?.close() }
        runCatching { index?.close() } // закрыть SQLite-коннект store (иначе утечка на unload→reload)
        model = null; index = null
        embedCache.clear()
    }

    /**
     * Эфемерное семантическое ранжирование: эмбеддит запрос и кандидатов ЛОКАЛЬНО и
     * возвращает индексы top-K по косинусу — БЕЗ сохранения (для поиска по почте:
     * письма нигде не индексируются, только сравниваются в памяти и тут же забываются).
     * Требует установленной эмбеддинг-модели; иначе — пустой список.
     */
    suspend fun rankBySimilarity(context: Context, query: String, candidates: List<String>, topK: Int): List<Int> =
        runCatching {
            if (candidates.isEmpty()) return emptyList()
            ready(context) ?: return emptyList() // гарантируем загруженную модель
            mutex.withLock {
                val m = model ?: return emptyList()
                val q = m.embed(query)
                if (q.isEmpty()) return emptyList()
                candidates.indices
                    .map { i -> i to cosine(q, m.embed(candidates[i])) }
                    .sortedByDescending { it.second }
                    .take(topK.coerceAtLeast(1))
                    .map { it.first }
            }
        }.getOrDefault(emptyList())

    /**
     * Как [rankBySimilarity], но кандидаты СТАТИЧНЫ (описания инструментов) — их эмбеддинги
     * кэшируются, поэтому за ход эмбеддится только запрос, а не ~40 описаний. Кэш живёт до
     * смены/выгрузки эмбеддера. Не использовать для динамики (почта/документы) — засорит кэш.
     */
    suspend fun rankCached(context: Context, query: String, candidates: List<String>, topK: Int): List<Int> =
        runCatching {
            if (candidates.isEmpty()) return emptyList()
            ready(context) ?: return emptyList()
            mutex.withLock {
                val m = model ?: return emptyList()
                val q = m.embed(query)
                if (q.isEmpty()) return emptyList()
                candidates.indices
                    .map { i -> i to cosine(q, cachedEmbed(m, candidates[i])) }
                    .sortedByDescending { it.second }
                    .take(topK.coerceAtLeast(1))
                    .map { it.first }
            }
        }.getOrDefault(emptyList())

    /** Публичный эмбеддинг строки (для индекса вложений почты). Пустой массив при недоступности. */
    suspend fun embed(context: Context, text: String): FloatArray =
        runCatching {
            ready(context) ?: return floatArrayOf()
            mutex.withLock { model?.embed(text) ?: floatArrayOf() }
        }.getOrDefault(floatArrayOf())

    /** Косинус двух векторов (публично — для внешних индексов). */
    fun cosineSim(a: FloatArray, b: FloatArray): Float = cosine(a, b)

    /** Эмбеддинг строки с кэшом (под mutex). Пустые (сбой) не кэшируем. */
    private fun cachedEmbed(m: EmbeddingModel, text: String): FloatArray {
        embedCache[text]?.let { return it }
        val v = m.embed(text)
        if (v.isNotEmpty()) embedCache[text] = v
        return v
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return -1f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (d == 0f) -1f else dot / d
    }
}
