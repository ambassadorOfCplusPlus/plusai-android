package ru.aiagent.app.cloud

import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentEvent
import ru.aiagent.core.agent.AgentLoopSupport
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.advertisedDescription
import ru.aiagent.core.agent.AutonomyMode
import ru.aiagent.core.agent.DangerPolicy
import ru.aiagent.core.agent.GateDecision
import ru.aiagent.core.agent.ToolProtocol
import ru.aiagent.core.agent.ToolResult
import java.net.HttpURLConnection
import java.net.URL

/** Модель/эндпоинт не поддерживает нативный tools — вызывающий откатится на текстовый протокол. */
class ToolsUnsupportedException(msg: String) : Exception(msg)

/**
 * Облачный агент на НАТИВНОМ function-calling (OpenAI-совместимый `tools`/`tool_calls` через прокси
 * владельца → RouterAI). В отличие от текстового [ru.aiagent.core.agent.AgentLoop], большие облачные
 * модели вызывают инструменты штатно — структурным `tool_calls`, ничего не парсим из текста и ничего
 * не утекает в ответ. Для локальных gguf и BYOK-без-прокси остаётся текстовый протокол.
 *
 * ПРИВАТНОСТЬ (§7): сюда передаются только НЕ-приватные инструменты (вызывающий уже отфильтровал
 * privateData) — тело письма/файла в облако не уходит.
 */
object CloudFunctionAgent {
    private const val TOOL_TIMEOUT_MS = 60_000L
    // Общий ВЕРХНИЙ потолок размера диалога (~символы): messages растёт каждый шаг и целиком
    // ре-сериализуется/ре-загружается по радио на КАЖДОМ шаге (O(шаги²) CPU/трафик/деньги), а stepBudget
    // для флагманов — до 1048. Эффективный лимит вычисляется ПОД МОДЕЛЬ (maxChars по contextLength),
    // это лишь его верхняя граница. По достижении — финальная сводка (не рвём пары tool_calls).
    private const val MAX_TRANSCRIPT_CHARS = 150_000

    /**
     * Бюджет шагов агента по размеру/классу модели (большие многошаговые задачи — это НЕ 6 шагов).
     * Малым моделям много шагов ни к чему (теряют нить), крупным — наоборот. Флагманы и очень
     * крупные — максимум. Размер парсим из id/имени ("...-32b..."), иначе считаем «остальными».
     */
    fun stepBudget(model: String): Int {
        val id = model.lowercase()
        // Тиры бюджета шагов (число итераций вызова инструментов): начальные 128 / базовые 256 /
        // крупные 512 / флагманы 1048. Длинные агентные задачи (много инструментов подряд) не должны
        // упираться в мелкий лимит. Верхняя страховка — по РАЗМЕРУ диалога (MAX_TRANSCRIPT_CHARS).
        // Мелкие/быстрые варианты — раньше флагманов, даже если имя похоже (gpt-4o-mini, deepseek-v4-flash).
        if (id.contains("mini") || id.contains("nano") || id.contains("lite")) return 128
        if (id.contains("flash")) return 256
        val flagship = listOf("opus", "gpt-4.1", "gpt-4o", "gpt-5", "/o1", "/o3", "claude-sonnet-4",
            "claude-3.7-sonnet", "gemini-2.5-pro", "gemini-1.5-pro", "deepseek-v4", "deepseek-r1",
            "grok-4", "grok-3", "qwen-max", "mistral-large")
        if (flagship.any { id.contains(it) }) return 1048
        val sizeB = Regex("(\\d+(?:\\.\\d+)?)\\s*b\\b").find(id)?.groupValues?.get(1)?.toDoubleOrNull()
        return when {
            sizeB == null -> 256     // размер неизвестен → «базовые»
            sizeB < 10 -> 128        // начальные (малые)
            sizeB <= 80 -> 256       // базовые (средние)
            sizeB > 200 -> 1048      // флагманы (очень крупные)
            else -> 512              // крупные (большие)
        }
    }

    /** null → FC недоступен (не прокси-режим). Вызывающий тогда идёт по текстовому протоколу. */
    fun endpoint(context: Context): Pair<String, String>? = CloudEngine.proxyEndpoint(context)

    fun run(
        context: Context,
        model: String,
        userMessage: String,
        recentContext: String,
        tools: List<AgentTool>,
        mode: AutonomyMode,
        blockedTools: Set<String>,
        confirm: suspend (String) -> Boolean,
        // «Думающая» модель → включаем reasoning, но с exclude (мысли не в ответе, §7-чистота).
        reasoning: Boolean = false,
        // structured_outputs → строгие схемы аргументов там, где это безопасно (все args required).
        structured: Boolean = false,
        // reasoning_effort — глубина размышления (шлём "medium" когда reasoning вкл и модель умеет).
        reasoningEffort: Boolean = false,
        // Окно контекста модели (токены, 0 = неизвестно) — для пер-модельного лимита размера транскрипта.
        contextLength: Int = 0,
        // Гибрид tool-RAG: какие инструменты ПОКАЗЫВАТЬ модели (ядро + релевантный топ). null → показать все
        // (старое поведение). Исполнить можно любой из [tools]; find_tool динамически дописывает найденные.
        advertisedIds: Set<String>? = null,
    ): Flow<AgentEvent> = flow {
        val (base, token) = endpoint(context) ?: throw ToolsUnsupportedException("не прокси-режим")
        val url = "${base.trimEnd('/')}/v1/chat/completions"
        val maxSteps = stepBudget(model) // адаптивно по размеру модели
        // Потолок размера диалога ПОД КОНКРЕТНУЮ модель: ~3 символа/токен от окна контекста, но не больше
        // общего предела и не меньше разумного минимума. Малые окна (8-32k токенов) не упрутся в
        // context_length_exceeded, крупные — используют бюджет шагов (до 1048) полнее. 0 → консервативно.
        val maxChars = if (contextLength > 0) (contextLength * 3).coerceIn(16_000, MAX_TRANSCRIPT_CHARS) else 100_000
        // Показываем модели ЯДРО+релевантный топ (advertisedIds), а не весь набор — дешевле и точнее (бенч).
        // find_tool потом дописывает найденное. Исполнить можно любой из tools (execMap полный).
        val advertised = if (advertisedIds != null) tools.filter { it.id in advertisedIds }.toMutableList() else tools.toMutableList()
        var toolsJson = buildTools(advertised, structured) // var: strict-откат и разблокировка find_tool пересоберут
        var strictApplied = structured
        val execMap = tools.associateBy { it.id }
        // Пользовательский override температуры (иначе сервер подставит рекомендованную для модели).
        val temperature = ru.aiagent.app.AppSettings.temperatureOverride(context)

        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system").put(
                "content",
                // Гибрид tool-RAG: в tools лежат ЯДРО + самые подходящие под задачу инструменты (НЕ весь набор
                // 200+). Нужного нет → find_tool найдёт его и добавит в набор. Это дешевле и точнее полного списка.
                "Ты — ассистент с инструментами. Когда для задачи нужны данные или действия — вызывай " +
                    "инструменты через function-calling, не выдумывай результат. В tools показаны САМЫЕ подходящие " +
                    "под задачу инструменты — это не весь набор. Если нужного нет, вызови find_tool с описанием " +
                    "задачи: он вернёт подходящие, и ты сможешь их вызвать. " +
                    "Если пользователь спрашивает 'что ты умеешь' или 'какие инструменты' — вызови list_tools " +
                    "чтобы показать полный список. " +
                    "Когда задача решена — ответь текстом." +
                    "\n\n" + DangerPolicy.permissionRules(mode),
            ),
        )
        val userContent = listOf(recentContext, userMessage).filter { it.isNotBlank() }.joinToString("\n\n")
        messages.put(JSONObject().put("role", "user").put("content", userContent))

        val stallGuard = AgentLoopSupport.StallGuard()
        var transcriptChars = userContent.length // грубый счётчик размера диалога (без ре-сериализации)
        for (step in 0 until maxSteps) {
            if (transcriptChars > maxChars) break // контекст/стоимость под потолком (пер-модельно) → к сводке
            val assistant = try {
                post(url, token, model, messages, toolsJson, firstStep = step == 0, reasoning = reasoning, temperature = temperature, reasoningEffort = reasoningEffort)
            } catch (e: ToolsUnsupportedException) {
                // Возможно, провайдер отверг именно strict-СХЕМУ (а tools в принципе умеет). Один раз
                // пересобираем инструменты без strict и повторяем шаг; если снова провал — на текстовый фолбэк.
                if (step == 0 && strictApplied) {
                    strictApplied = false
                    toolsJson = buildTools(advertised, false)
                    try {
                        post(url, token, model, messages, toolsJson, firstStep = true, reasoning = reasoning, temperature = temperature, reasoningEffort = reasoningEffort)
                    } catch (e2: ToolsUnsupportedException) {
                        throw e2 // не в strict дело — модель реально не умеет tools → текстовый протокол
                    } catch (e2: Exception) {
                        emit(AgentEvent.Answer("ошибка облака: ${e2.message?.take(120)}")); return@flow
                    }
                } else {
                    throw e
                }
            } catch (e: Exception) {
                emit(AgentEvent.Answer("ошибка облака: ${e.message?.take(120)}"))
                return@flow
            }
            // «Мышление»: размышление reasoning-модели (reasoning_content / reasoning) — отдельным
            // событием, чтобы UI показал его свёрнутой шторкой (в текст ответа НЕ вливаем, §7).
            val think = assistant.optString("reasoning_content", "")
                .ifBlank { assistant.optString("reasoning", "") }.trim()
            if (think.isNotEmpty()) emit(AgentEvent.Thinking(think))
            val toolCalls = assistant.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                // §0/§7: НЕ показываем сырой content. Пропускаем через ToolProtocol.extract — он срезает
                // <think>/маркеры вызова. Если модель (частое для qwen/deepseek/glm) эмитнула вызов ТЕКСТОМ
                // вместо структурного tool_calls — на первом шаге откатываемся на текстовый протокол (он
                // такие вызовы корректно исполняет), а не показываем протокол и не теряем действие.
                val ex = ru.aiagent.core.agent.ToolProtocol.extract(assistant.optString("content", ""), execMap.keys)
                if (ex.calls.isNotEmpty() && step == 0) {
                    throw ToolsUnsupportedException("модель отдаёт вызовы текстом — на текстовый протокол")
                }
                emit(AgentEvent.Answer(ex.cleanText.ifBlank { "(пустой ответ модели)" }))
                return@flow
            }
            // id может отсутствовать (провайдер шлёт index-based tool_calls). Синтезируем стабильный id
            // ПРЯМО в массив tool_calls ДО вставки assistant — иначе assistant.tool_calls[].id пуст, а
            // tool-ответ ссылается на синтезированный id → рассинхрон, strict-эндпоинт отвергает (400).
            for (k in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(k)
                if (tc.optString("id").isBlank()) tc.put("id", "call_${step}_$k")
            }
            messages.put(assistant) // ассистентское сообщение с tool_calls обязано вернуться в контекст
            transcriptChars += assistant.toString().length // реальный размер: content + аргументы tool_calls + reasoning
            val sig = StringBuilder()
            for (k in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(k)
                val id = tc.optString("id").ifBlank { "call_${step}_$k" } // уже проставлен выше
                val fn = tc.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                // arguments может прийти буквально как JSON null → org.json.optString вернёт строку
                // "null", и JSONObject("null") бросит. Приводим "null"/пусто к "{}". strip null:
                // опциональные аргументы, присланные как null в strict-режиме, → отсутствуют.
                val rawArgs = fn.optString("arguments", "{}")
                val args = stripNullArgs(if (rawArgs.isBlank() || rawArgs == "null") "{}" else rawArgs)
                sig.append(name).append(args).append('|')
                val tool = execMap[name]
                if (tool == null) {
                    messages.put(toolMsg(id, "ошибка — нет инструмента $name"))
                    continue
                }
                emit(AgentEvent.ToolCall(tool.id, args))
                // Единый гейтированный вызов (тот же, что у текстовой петли и code-mode): gate →
                // BLOCK/ASK(подтверждение с декод-аргументами)/ALLOW → invoke под таймаутом.
                val result = AgentLoopSupport.executeGated(tool, args, mode, blockedTools, TOOL_TIMEOUT_MS) { prompt ->
                    confirm(prompt)
                }
                emit(AgentEvent.ToolObservation(tool.id, result))
                // Гибрид tool-RAG: find_tool вернул подходящие — ДИНАМИЧЕСКИ дописываем их схемы в набор,
                // чтобы на следующем шаге модель могла их вызвать (на native-FC вызвать можно только то, что в tools).
                if (tool.id == "find_tool" && result is ToolResult.Success) {
                    val fresh = parseFoundIds(result.outputJson)
                        .mapNotNull { fid -> execMap[fid] }
                        .filter { t -> advertised.none { it.id == t.id } }
                    if (fresh.isNotEmpty()) {
                        advertised.addAll(fresh)
                        toolsJson = buildTools(advertised, strictApplied)
                    }
                }
                val obs = when (result) {
                    is ToolResult.Success -> result.outputJson
                    is ToolResult.Failure -> "ошибка — ${result.message}"
                    is ToolResult.NeedsConfirmation -> "требуется подтверждение"
                }
                val obsMsg = AgentLoopSupport.observation(obs)
                messages.put(toolMsg(id, obsMsg))
                transcriptChars += obsMsg.length
            }
            // Детект зацикливания: тот же набор вызовов ≥3 шагов подряд — модель застряла, завершаем сводкой.
            if (stallGuard.stalled(sig.toString())) break
        }
        // Лимит шагов/застревание: просим финальный ответ БЕЗ инструментов (сводку сделанного),
        // а не тупое «не смог» — большие задачи не обязаны уложиться, но пользователю нужен итог.
        messages.put(JSONObject().put("role", "user").put(
            "content", "Достигнут предел шагов. Дай пользователю финальный ответ по тому, что уже сделано, БЕЗ вызова инструментов.",
        ))
        val wrap = runCatching { post(url, token, model, messages, JSONArray(), firstStep = false, reasoning = reasoning, temperature = temperature, reasoningEffort = reasoningEffort) }.getOrNull()
        // §0/§7: и финальную сводку чистим от think/маркеров перед показом.
        val wrapText = wrap?.optString("content", "")?.let { ru.aiagent.core.agent.ToolProtocol.extract(it).cleanText }
        emit(AgentEvent.Answer(
            wrapText?.ifBlank { null }
                ?: "Задача большая — выполнил максимум шагов ($maxSteps). Скажи, что доделать дальше.",
        ))
    }.flowOn(kotlinx.coroutines.Dispatchers.IO) // тело делает блокирующий HTTP/чтение prefs — объявляем IO,
    // иначе сбор из Main-scope (viewModelScope/LaunchedEffect) кинул бы NetworkOnMainThreadException/ANR

    private fun toolMsg(callId: String, content: String): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", content)

    // AgentTool.schema = "<описание>; args: {json-schema}" → OpenAI-функция {name, description, parameters}.
    // При [structured]=true нормализуем схему под strict (все свойства required, опциональные —
    // nullable, additionalProperties:false рекурсивно) и ставим strict:true. Опциональность
    // сохраняется через nullable + вырезание null-полей перед вызовом (stripNullArgs) — инструмент
    // видит их как отсутствующие, как без strict. Если схему нормализовать нельзя (свободный объект,
    // свойство без type) — strict для неё не включаем; а если провайдер всё же отверг — run ретраит без strict.
    /** Пример значений args → минимальная JSON-схема (имена свойств + тип по значению; все опциональны).
     *  Даёт нативной модели контракт аргументов, которого при отдаче «сырого примера» не было. */
    private fun exampleToSchema(example: JSONObject): JSONObject {
        val props = JSONObject()
        for (key in example.keys()) {
            val type = when (example.opt(key)) {
                is Boolean -> "boolean"
                is Int, is Long -> "integer"
                is Double, is Float -> "number"
                is JSONObject -> "object"
                is JSONArray -> "array"
                else -> "string"
            }
            props.put(key, JSONObject().put("type", type))
        }
        return JSONObject().put("type", "object").put("properties", props)
    }

    /** Разобрать found_ids из результата find_tool ({"found_ids":["id1","id2"],...}). */
    private fun parseFoundIds(outputJson: String): List<String> = runCatching {
        val arr = JSONObject(outputJson).optJSONArray("found_ids") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun buildTools(tools: List<AgentTool>, structured: Boolean): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val paramsStr = t.schema.substringAfter("args:", "").trim()
            val parsed = runCatching { JSONObject(paramsStr) }.getOrNull()
            // AgentTool.schema по соглашению кладёт в args ПРИМЕР значений ({"path":"…","content":"…"}),
            // а НЕ JSON-схему. Если отдать пример как parameters — у функции нет type/properties, и
            // нативная модель считает, что инструмент без аргументов → зовёт с пустыми/выдуманными args
            // (или провайдер 400 → тихий откат на текст). Пример конвертируем в схему; готовую схему берём как есть.
            val params = when {
                parsed == null -> JSONObject().put("type", "object").put("properties", JSONObject())
                parsed.has("type") && parsed.has("properties") -> parsed // уже настоящая JSON-схема
                else -> exampleToSchema(parsed) // пример значений → схема с именами/типами аргументов
            }
            val fn = JSONObject().put("name", t.id).put("description", t.advertisedDescription()).put("parameters", params)
            if (structured) {
                // Нормализуем КОПИЮ: normalizeStrict мутирует in-place и может вернуть false на
                // полпути (свободный объект/свойство без type). Оригинальную схему тогда НЕ трогаем —
                // иначе провайдеру ушла бы частично-испорченная схема (default срезан, required
                // навязан) без strict. При успехе подменяем на нормализованную + strict.
                val strictParams = JSONObject(params.toString())
                if (normalizeStrict(strictParams)) fn.put("parameters", strictParams).put("strict", true)
            }
            arr.put(JSONObject().put("type", "function").put("function", fn))
        }
        return arr
    }

    /**
     * Приводит JSON-схему к strict-совместимому виду IN-PLACE (объект: additionalProperties:false,
     * required = все свойства, рекурсия в свойства и items; опциональные — nullable). Убирает `default`
     * (strict его запрещает). Возвращает false, если нормализовать нельзя (свободный объект без
     * properties, свойство без описания/типа) — тогда strict не включаем.
     */
    private fun normalizeStrict(schema: JSONObject): Boolean {
        schema.remove("default") // strict не допускает default
        when (schema.optString("type", "")) {
            "object" -> {
                val props = schema.optJSONObject("properties") ?: return false
                schema.put("additionalProperties", false)
                val origReq = schema.optJSONArray("required")
                val reqSet = if (origReq == null) emptySet()
                else (0 until origReq.length()).map { origReq.getString(it) }.toSet()
                val req = JSONArray()
                for (k in props.keys().asSequence().toList()) {
                    req.put(k)
                    val pv = props.optJSONObject(k) ?: return false // свойство без схемы-объекта — не нормализуем
                    if (!normalizeStrict(pv)) return false
                    if (k !in reqSet) makeNullable(pv) // опциональное → nullable (можно прислать null)
                }
                schema.put("required", req)
                return true
            }
            "array" -> {
                val items = schema.optJSONObject("items") ?: return true // массив примитивов — ок
                return normalizeStrict(items)
            }
            "" -> return false // без type strict не примет
            else -> return true // примитив (string/number/integer/boolean)
        }
    }

    /** Делает тип свойства nullable (добавляет "null") — для опциональных в strict-схеме. */
    private fun makeNullable(schema: JSONObject) {
        when (val t = schema.opt("type")) {
            is String -> if (t != "null") schema.put("type", JSONArray().put(t).put("null"))
            is JSONArray -> if ((0 until t.length()).none { t.optString(it) == "null" }) t.put("null")
        }
    }

    /** Вырезает поля со значением null (опциональные args, присланные как null в strict-режиме) —
     * чтобы инструмент видел их как отсутствующие, ровно как без strict. Рекурсивно: normalizeStrict
     * делает опциональные nullable и во ВЛОЖЕННЫХ объектах, поэтому и чистить надо на всех уровнях. */
    private fun stripNullArgs(argsJson: String): String = try {
        val o = JSONObject(argsJson)
        stripNullsDeep(o)
        o.toString()
    } catch (e: Exception) {
        argsJson
    }

    private fun stripNullsDeep(o: JSONObject) {
        for (k in o.keys().asSequence().filter { o.isNull(it) }.toList()) o.remove(k)
        for (k in o.keys().asSequence().toList()) {
            o.optJSONObject(k)?.let { stripNullsDeep(it) }
            o.optJSONArray(k)?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { stripNullsDeep(it) } }
        }
    }

    private fun post(
        url: String, token: String, model: String, messages: JSONArray, tools: JSONArray, firstStep: Boolean,
        reasoning: Boolean = false, temperature: Double? = null,
        reasoningEffort: Boolean = false,
    ): JSONObject {
        val bodyObj = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        if (tools.length() > 0) bodyObj.put("tools", tools).put("tool_choice", "auto")
        // DeepSeek через OpenRouter Zen — flex для снижения цены в 2 раза
        if (model.contains("deepseek", ignoreCase = true)) bodyObj.put("service_tier", "flex")
        // Плагин веб-поиска RouterAI
        bodyObj.put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 3)))
        // «Мышление»: просим модель рассуждать И ВЕРНУТЬ размышление (exclude:false). В текст ответа оно
        // не попадёт (§7) — эмитим его отдельным AgentEvent.Thinking → UI показывает «шторкой».
        if (reasoning) {
            bodyObj.put("reasoning", JSONObject().put("exclude", false))
            if (reasoningEffort) bodyObj.put("reasoning_effort", "medium") // глубина, если модель умеет
        }
        // Пользовательский override температуры (иначе сервер подставит рекомендованную для модели).
        if (temperature != null) bodyObj.put("temperature", temperature)
        val body = bodyObj.toString()
        // Bearer = ключ кошелька/облака: НЕ шлём по plaintext http (перехват токена). Как в Account.call —
        // защита не дошла до этой копии, ошибочный http-URL прокси утёк бы токен.
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
            "небезопасный URL для токена: только https"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                // На ПЕРВОМ шаге распознаём отказ по tools/схеме → сначала ретрай без strict (run),
                // затем откат на текстовый протокол. Ключевые слова узкие: "tool"/"schema"/"strict"
                // (НЕ "function" — оно встречается в куче несвязанных ошибок и давало ложный откат
                // с нативного FC на текстовый протокол для реально tools-совместимой модели).
                if (firstStep && (code == 400 || code == 404 || code == 422) &&
                    (text.contains("tool", true) || text.contains("schema", true) || text.contains("strict", true))
                ) {
                    throw ToolsUnsupportedException("модель $model отвергла tools/схему")
                }
                throw RuntimeException("HTTP $code: ${text.take(160)}")
            }
            val choices = JSONObject(text).optJSONArray("choices")
            return choices?.optJSONObject(0)?.optJSONObject("message")
                ?: throw RuntimeException("нет message в ответе провайдера")
        } finally {
            conn.disconnect()
        }
    }
}
