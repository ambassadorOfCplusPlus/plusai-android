package ru.aiagent.app.cloud

import android.content.Context
import ru.aiagent.core.agent.ModelClass
import ru.aiagent.core.agent.ToolDialect

/**
 * Выбор РОДНОГО формата вызова инструментов под конкретную облачную модель (по каталогу RouterAI):
 *  - [native] — модель умеет нативный function-calling (OpenAI-`tools`; RouterAI сам транслирует в
 *    провайдерский формат Anthropic/Gemini/… ) → идём через [CloudFunctionAgent];
 *  - иначе — текстовый протокол в [dialect] семейства (Qwen/ChatML→Hermes, Mistral→[TOOL_CALLS],
 *    Llama→JSON parameters, прочее→generic).
 */
object CloudToolFormat {
    data class Decision(
        val native: Boolean,
        val dialect: ToolDialect,
        val reasoning: Boolean = false,
        val structured: Boolean = false,
        val reasoningEffort: Boolean = false,
        // Размер окна контекста модели (токены, 0 = неизвестно) — для пер-модельного лимита транскрипта
        // (не упереться в context_length_exceeded у малых окон).
        val contextLength: Int = 0,
    )

    suspend fun of(context: Context, modelId: String): Decision {
        val info = CloudModels.infoOf(context, modelId)
        val dialect = ToolDialect.classify(info?.tokenizer, info?.instructType, modelId)
        // Каталог знает модель → его сигнал supported_parameters авторитетен. Каталога нет
        // (офлайн/не загрузился) → оптимистично для известных провайдеров с нативным FC.
        val native = info?.supportsTools ?: nativeByHeuristic(modelId)
        // max_tokens НЕ шлём из приложения: RouterAI не раскрывает реальный лимит ВЫВОДА модели
        // (context_length — это всё окно вход+выход, не output-cap), а фиксированное значение либо режет
        // длинные/reasoning-ответы, либо 400-ит «max_tokens exceeds» у части провайдеров (этот 400 не
        // содержит tool/schema → нет отката → рвётся каждый запрос). Прокси/провайдер сам ставит
        // безопасный дефолт. Поле оставлено (maxTokensParam=null → post() не отправляет).
        return Decision(
            native = native,
            dialect = dialect,
            reasoning = info?.supportsReasoning ?: false,
            structured = info?.supportsStructured ?: false,
            reasoningEffort = info?.supportsReasoningEffort ?: false,
            contextLength = info?.contextLength ?: 0,
        )
    }

    /** Запасной признак нативного FC по префиксу id, когда каталог недоступен. */
    private fun nativeByHeuristic(modelId: String): Boolean {
        val i = modelId.lowercase()
        // Скидочный пул (id с ":free", OpenRouter) сервер намеренно отдаёт БЕЗ supports_tools → текстовый
        // протокол. Оффлайн/до загрузки каталога id вида "deepseek/…:free" совпал бы с префиксом ниже и
        // ошибочно выбрал бы native FC (протокол вызова мог бы протечь в ответ, §0/§7). Суффикс ":free" ловим.
        if (i.endsWith(":free")) return false
        return listOf("openai/", "anthropic/", "google/gemini", "deepseek/", "qwen/", "mistralai/", "x-ai/")
            .any { i.startsWith(it) }
    }
}

// L8 для облака: у облачных моделей нет бенч-балла (как у локалок), поэтому класс силы оцениваем ЭВРИСТИКОЙ по
// id — чтобы слабая облачная модель (в т.ч. дешёвая из скидочного пула) НЕ получала полный опасный набор тулов
// (иначе в bypass она могла бы исполнить DANGEROUS без подтверждения). Флагманы → STRONG (полный набор),
// явно мелкие → WEAK (только SAFE), средние/неизвестные → STANDARD (без DANGEROUS — консервативно безопасно;
// узнаваемые сильные модели пользователя, напр. sonnet/gpt-5/deepseek, получают полный доступ).
private val FLAGSHIP = listOf(
    "opus", "sonnet", "claude-4", "claude-5", "gpt-5", "gpt-4o", "gpt-4.1", "o1", "o3",
    "deepseek-v", "deepseek-r1", "deepseek-chat", "qwen-max", "qwen2.5-max", "qwen3-235", "qwen3-480",
    "llama-4-maverick", "grok", "gemini-2.5-pro", "gemini-2.0-pro", "mistral-large", "command-a", "command-r-plus",
)
// «Дешёвый/быстрый» ярус. `flash` тоже здесь: иначе `deepseek/deepseek-v4-flash` (дефолтный исполнитель
// оркестратора!) не совпадал ни с размером, ни с SMALL и доезжал до FLAGSHIP-токена `deepseek-v`.
// Сверяем ПО ГРАНИЦЕ СЛОВА, а не подстрокой: "mini" сидит внутри "ge-mini-", и подстрочная проверка
// записывала в слабые ВСЕ модели gemini (включая gemini-2.5-pro). Размерные токены — только одиночная
// цифра 1–8 перед 'b', иначе "-70b"/"-32b" ложно матчились бы началом "-7b"/"-3b".
private val SMALL_RE = Regex("""(?:^|[^a-z])(?:nano|mini|tiny|flash|lite)(?:[^a-z]|$)|-[1-8]b(?![0-9])""")
private val SIZE_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*b\\b")

fun cloudModelClass(modelId: String): ModelClass {
    val id = modelId.lowercase()
    // ПОРЯДОК КРИТИЧЕН: сначала дешёвый ярус, потом флагманы. Токены флагманов — ПРЕФИКСЫ своих же
    // мелких вариантов ("gpt-4o-mini" ⊃ "gpt-4o", "gpt-5-nano" ⊃ "gpt-5",
    // "deepseek-r1-distill-llama-8b" ⊃ "deepseek-r1"), поэтому проверка FLAGSHIP первой возвращала
    // STRONG для слабых моделей и выдавала им ПОЛНЫЙ опасный набор (в bypass — без подтверждения),
    // полностью обнуляя L8. Родственный stepBudget() по той же причине проверяет mini/nano/flash первым.
    if (SMALL_RE.containsMatchIn(id)) return ModelClass.WEAK
    if (FLAGSHIP.any { id.contains(it) }) return ModelClass.STRONG
    // Размер параметров из id (…70b…): >=70B → STRONG, 15–70 → STANDARD, <15 → WEAK.
    val sz = SIZE_RE.findAll(id).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
    if (sz != null) return when {
        sz < 15 -> ModelClass.WEAK
        sz < 70 -> ModelClass.STANDARD
        else -> ModelClass.STRONG
    }
    return ModelClass.STANDARD // неизвестно — без DANGEROUS (лучше, чем полный набор непроверенной модели)
}
