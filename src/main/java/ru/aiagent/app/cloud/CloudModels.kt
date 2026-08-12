package ru.aiagent.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Модель из живого каталога RouterAI: цены — ₽ за 1M токенов (pricing × 1e6).
 * [supportsTools] — есть ли нативный function-calling (RouterAI сам транслирует OpenAI-`tools`
 * в провайдерский формат); иначе используем текстовый протокол в диалекте [tokenizer]/[instructType].
 */
data class CloudModelInfo(
    val id: String,
    val name: String,
    val priceIn: Double,
    val priceOut: Double,
    val supportsTools: Boolean = false,
    val tokenizer: String? = null,
    val instructType: String? = null,
    // «Думающая» модель (можно явно включить reasoning и держать мысли вне ответа).
    val supportsReasoning: Boolean = false,
    // structured_outputs — можно строгую схему ответа/аргументов.
    val supportsStructured: Boolean = false,
    // reasoning_effort — можно задать глубину размышления (low/medium/high).
    val supportsReasoningEffort: Boolean = false,
    // Максимум токенов ответа: max_completion_tokens (новее) приоритетнее max_tokens.
    val supportsMaxTokens: Boolean = false,
    val supportsMaxCompletionTokens: Boolean = false,
    // Размер контекста модели (токены) — для разумного лимита ответа и обрезки истории.
    val contextLength: Int = 0,
    // Модель «скидочного пула» (бесплатный апстрим на прокси владельца): бейдж «СКИДКА», поднимается вверх.
    val freePool: Boolean = false,
    // Провайдер скидочного пула (groq|openrouter|cerebras|gemini|github) — для инфо в UI.
    val provider: String? = null,
)

/**
 * Нечёткий поиск модели: все слова запроса (в ЛЮБОМ порядке) должны найтись в id+name, с игнором
 * разделителей `-_/пробел`. Так «gpt 4o mini» находит «gpt-4o-mini», «claude opus» — «claude-3-opus»,
 * «квен 32» — не найдёт (латиница), но «qwen 32b» найдёт. Раньше был одиночный substring — приходилось
 * вписывать точное название.
 */
fun modelMatchesQuery(m: CloudModelInfo, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val hay = "${m.name} ${m.id}".lowercase()
    val haySquish = hay.replace(Regex("[-_/\\s]"), "")
    return q.split(Regex("\\s+")).all { tok ->
        hay.contains(tok) || haySquish.contains(tok.replace(Regex("[-_/\\s]"), ""))
    }
}

/**
 * Живой каталог RouterAI (routerai.ru/api/v1/models — публичный, ~400 моделей).
 * Кэшируется в памяти; сортировка по цене выхода (дешёвые сверху).
 */
object CloudModels {
    @Volatile private var cache: List<CloudModelInfo>? = null
    // Отдельный кэш для image-моделей: у них другой фильтр (output_modalities ⊇ "image") и другой
    // разбор (цена «за картинку», а не за токен), мешать их с текстовым каталогом нельзя.
    @Volatile private var imageCache: List<CloudModelInfo>? = null
    private val fetchMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Сбросить кэш каталога — ОБЯЗАТЕЛЬНО при смене аккаунта/маршрута (логин/логаут/BYOK/свой endpoint).
     * Иначе процесс-глобальный кэш отдал бы каталог прошлого маршрута: например СЫРЫЕ (без наценки) цены
     * routerai, закэшированные пока пользователь был не залогинен (BYOK/custom → ownerUrl==null), продолжили
     * бы показываться ПОСЛЕ логина — прямая утечка скрытой маржи (§3.13.1), которую fetchAll обязан скрывать.
     */
    fun invalidate() {
        cache = null
        imageCache = null
    }

    /**
     * Каталог: СНАЧАЛА через сервер владельца (/v1/models — цены уже с наценкой pay-as-you-go,
     * маржа §3.13.1 не раскрывается), при недоступности — RouterAI напрямую (сырые цены, fallback).
     * Кэшируем только наценённый (серверный) результат, чтобы не показать сырые цены после входа.
     * При включённом free-тире Zen каталог = фиксированный список анонимных free-моделей (без сети).
     */
    suspend fun fetchAll(context: android.content.Context): List<CloudModelInfo> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }
        // Анонимный free-тир: каталог фиксирован (без сети), каждая модель бесплатна (0 ₽).
        // Остальные -free-модели Zen требуют OAuth-входа — на Android тир анонимный (как на десктопе).
        if (CloudEngine.zenEnabled(context)) return@withContext zenModels()
        // Под мьютексом с повторной проверкой: два параллельных вызова (пикер + шапка) иначе
        // делают два сетевых запроса и пишут кэш дважды.
        fetchMutex.withLock {
            cache?.let { return@withLock it }
            val sess = Account.current(context)
            val owner = sess?.url?.trimEnd('/')
            val ownerUrl = owner?.let { "$it/v1/models" }
            var result = emptyList<CloudModelInfo>()
            // Залогинен → каталог берём ТОЛЬКО у владельца (с наценкой). НЕ откатываемся на сырой
            // routerai даже если владелец недоступен: иначе при 401/сбое показали бы немаркированные
            // цены = утечка скрытой маржи (§3.13.1). Не залогинен (BYOK) → берём routerai напрямую.
            val urls = if (ownerUrl != null) listOf(ownerUrl) else listOf("https://routerai.ru/api/v1/models")
            for (u in urls) {
                // Токен аккаунта шлём ТОЛЬКО на сервер владельца. На сторонний routerai — не отправляем.
                val token = if (u == ownerUrl) sess?.token else null
                val list = runCatching { fetchFrom(u, token) }.getOrNull()
                if (!list.isNullOrEmpty()) {
                    // Кэшируем каталог владельца (с наценкой) всегда; сырой routerai — ТОЛЬКО когда
                    // владельца нет (BYOK/не залогинен). Иначе при временно недоступном сервере
                    // владельца закэшировали бы немаркированные цены и раскрыли маржу (§3.13.1). Кэш
                    // для BYOK нужен: CloudToolFormat.of на горячем пути иначе тянул бы весь каталог
                    // на каждое сообщение.
                    if (u == ownerUrl || ownerUrl == null) cache = list
                    result = list
                    break
                }
            }
            // DeepSeek V4 Flash — бесплатный (OpenRouter Zen) и платный (через RouterAI)
            result = listOf(CloudModelInfo(
                id = "deepseek/deepseek-v4-flash",
                name = "DeepSeek V4 Flash (бесплатно)",
                priceIn = 0.0, priceOut = 0.0,
                supportsTools = true, freePool = true, provider = "openrouter",
            ), CloudModelInfo(
                id = "deepseek/deepseek-v4-flash",
                name = "DeepSeek V4 Flash (платный)",
                priceIn = 7.0, priceOut = 21.0,
                supportsTools = true,
            )) + result
            result
        }
    }

    /**
     * Каталог IMAGE-моделей (генерация картинок по тексту): те же источники, что и [fetchAll]
     * (сервер владельца с наценкой → иначе routerai для BYOK), но берём модели, у которых
     * output_modalities содержит "image" (см. [parseImageModel]). Кэшируется отдельно ([imageCache]).
     * Нужен пикеру модели генерации картинок (generate_image включается пользователем в настройках).
     */
    suspend fun fetchImageModels(context: android.content.Context): List<CloudModelInfo> = withContext(Dispatchers.IO) {
        imageCache?.let { return@withContext it }
        // Free-тир Zen — только текстовые модели (генерации картинок нет).
        if (CloudEngine.zenEnabled(context)) return@withContext emptyList()
        fetchMutex.withLock {
            imageCache?.let { return@withLock it }
            val sess = Account.current(context)
            val owner = sess?.url?.trimEnd('/')
            val ownerUrl = owner?.let { "$it/v1/models" }
            var result = emptyList<CloudModelInfo>()
            val urls = if (ownerUrl != null) listOf(ownerUrl) else listOf("https://routerai.ru/api/v1/models")
            for (u in urls) {
                val token = if (u == ownerUrl) sess?.token else null
                val list = runCatching { fetchFrom(u, token, ::parseImageModel) }.getOrNull()
                if (!list.isNullOrEmpty()) {
                    if (u == ownerUrl || ownerUrl == null) imageCache = list
                    result = list
                    break
                }
            }
            result
        }
    }

    /** Инфо о конкретной модели из каталога (для выбора формата инструментов). null — нет в каталоге. */
    suspend fun infoOf(context: android.content.Context, id: String): CloudModelInfo? =
        runCatching { fetchAll(context).firstOrNull { it.id == id } }.getOrNull()

    /**
     * Фиксированный каталог анонимного free-тира (без сетевого запроса). Модели работают
     * без ключа (Bearer не нужен). deepseek-v4-flash-free: контекст 200K, вывод 128K;
     * longcat-2.0-free: контекст не публикуется (0 = «неизвестно»).
     * Нативный function-calling поддерживается (проверено: finish_reason=tool_calls анонимным запросом).
     */
    private fun zenModels(): List<CloudModelInfo> = listOf(
        CloudModelInfo(
            id = "deepseek-v4-flash-free", name = "DeepSeek V4 Flash (free)",
            priceIn = 0.0, priceOut = 0.0,
            supportsTools = true, contextLength = 200_000,
        ),
        CloudModelInfo(
            id = "longcat-2.0-free", name = "LongCat 2.0 (free)",
            priceIn = 0.0, priceOut = 0.0,
            supportsTools = true, contextLength = 0,
        ),
    )

    private fun fetchFrom(
        url: String,
        token: String? = null,
        parse: (JSONObject) -> CloudModelInfo? = ::parseModel,
    ): List<CloudModelInfo> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            // Проверяем код: на non-2xx getInputStream бросает непрозрачный IOException, а errorStream
            // не осушается (утечка соединения) и 401 не отличить от сбоя сети. Явно осушаем и выходим.
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                return emptyList()
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("data")
            // runCatching на КАЖДУЮ запись: одна битая строка (нет id / поменялся тип поля) не должна
            // ронять разбор ВСЕГО каталога — иначе список моделей у пользователя внезапно пуст.
            return (0 until arr.length())
                .mapNotNull { i -> runCatching { parse(arr.getJSONObject(i)) }.getOrNull() }
                // Скидочный пул — вверх (сервер их prepend'ит, но сортировка по цене иначе перемешала бы),
                // дальше — по цене выхода (дешёвые сверху).
                .sortedWith(compareByDescending<CloudModelInfo> { it.freePool }.thenBy { it.priceOut })
        } finally {
            conn.disconnect()
        }
    }

    /** Разбор одной записи каталога RouterAI → CloudModelInfo (null — не текст-чат-модель/битая). */
    private fun parseModel(o: JSONObject): CloudModelInfo? {
        // Только текст-в-текст чат-модели: исключаем генераторы картинок/аудио и эмбеддеры.
        val out = o.optJSONObject("architecture")?.optJSONArray("output_modalities")
        val outputsText = out == null || (0 until out.length()).any { out.getString(it).equals("text", true) }
        val id = o.getString("id")
        val p = o.optJSONObject("pricing")
        val priceOut = (p?.optDouble("completion", 0.0) ?: 0.0) * 1_000_000
        if (!outputsText || priceOut <= 0.0 || id.contains("embed", true) || id.contains("/gte", true)) return null
        val arch = o.optJSONObject("architecture")
        // supported_parameters содержит "tools", когда модель умеет нативный function-calling.
        val sp = o.optJSONArray("supported_parameters")
        val spSet = if (sp == null) emptySet() else (0 until sp.length()).map { sp.getString(it).lowercase() }.toSet()
        return CloudModelInfo(
            id = id,
            // Всюду isNull-гард: Android optString при JSON null отдаёт строку "null", а не fallback —
            // отсюда брались имя «null», бейдж провайдера «null» и мусорный tokenizer, по которому
            // ToolDialect.classify выбирал неверный текстовый диалект.
            name = if (o.isNull("name")) id else o.optString("name", id),
            priceIn = (p?.optDouble("prompt", 0.0) ?: 0.0) * 1_000_000,
            priceOut = priceOut,
            // free-модели пула не отдают supported_parameters → читаем и прямой boolean supports_tools.
            supportsTools = "tools" in spSet || o.optBoolean("supports_tools", false),
            tokenizer = arch?.let { if (it.isNull("tokenizer")) null else it.optString("tokenizer") }?.takeIf { it.isNotBlank() },
            instructType = arch?.let { if (it.isNull("instruct_type")) null else it.optString("instruct_type") }?.takeIf { it.isNotBlank() },
            supportsReasoning = "reasoning" in spSet || "include_reasoning" in spSet,
            supportsStructured = "structured_outputs" in spSet,
            supportsReasoningEffort = "reasoning_effort" in spSet,
            supportsMaxTokens = "max_tokens" in spSet,
            supportsMaxCompletionTokens = "max_completion_tokens" in spSet,
            contextLength = o.optInt("context_length", 0),
            freePool = o.optBoolean("free_pool", false),
            provider = (if (o.isNull("provider")) "" else o.optString("provider", "")).takeIf { it.isNotBlank() },
        )
    }

    /**
     * Разбор записи каталога → image-модель (генерация картинок). Берём ТОЛЬКО модели, у которых
     * output_modalities содержит "image" (мультимодальные image+text возвращают картинку прямо в
     * /v1/chat/completions). Цену картинки RouterAI обычно кладёт в pricing.completion (за выход) —
     * оставляем как есть для сортировки (у части моделей может быть 0, тогда просто вверху списка);
     * на цене НЕ фильтруем (в отличие от текстового parseModel), иначе бесплатные/иначе-тарифицируемые
     * image-модели выпали бы. Эмбеддеры исключаем на всякий случай.
     */
    private fun parseImageModel(o: JSONObject): CloudModelInfo? {
        val out = o.optJSONObject("architecture")?.optJSONArray("output_modalities") ?: return null
        val outputsImage = (0 until out.length()).any { out.getString(it).equals("image", true) }
        if (!outputsImage) return null
        val id = o.getString("id")
        if (id.contains("embed", true)) return null
        val p = o.optJSONObject("pricing")
        return CloudModelInfo(
            id = id,
            name = o.optString("name", id),
            priceIn = (p?.optDouble("prompt", 0.0) ?: 0.0) * 1_000_000,
            priceOut = (p?.optDouble("completion", 0.0) ?: 0.0) * 1_000_000,
            contextLength = o.optInt("context_length", 0),
        )
    }
}
