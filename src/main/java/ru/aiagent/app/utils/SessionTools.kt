package ru.aiagent.app.utils

import android.content.Context
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File

/**
 * РАБОЧАЯ ПАМЯТЬ СЕССИИ и ПЛАН — для долгих многошаговых задач (в отличие от [MemoryStore],
 * которая мала и хранит факты МЕЖДУ чатами и уходит в системный промпт).
 *
 *  - Память сессии (session_note/session_recall): накопитель промежуточных результатов по ходу
 *    задачи («обработал колледжи 1–5», найденные факты). Переживает переполнение контекста: даже
 *    если история чата обрежется, ключевое остаётся здесь и перечитывается инструментом.
 *  - План (plan_update/plan_show): markdown-чеклист шагов. Модель ведёт его и видит прогресс,
 *    не теряя нить на длинной задаче.
 *
 * Оба файла ЛОКАЛЬНЫ (§7, filesDir), наружу не уходят. Кап по размеру: память 10 МБ (FIFO —
 * старое вытесняется), план 5 МБ (перезапись целиком). Всё под lock (append не атомарен).
 */
object SessionStore {
    private const val MEMORY_MAX_BYTES = 10L * 1024 * 1024 // рабочая память сессии — до 10 МБ
    private const val PLAN_MAX_BYTES = 5L * 1024 * 1024     // план — до 5 МБ (реально килобайты)
    private const val MAX_NOTE_LEN = 20_000                 // одна заметка — не гигант (защита от вставки простыни)
    private val lock = Any()

    private fun memFile(ctx: Context) = File(ctx.filesDir, "session-memory.md")
    private fun planFile(ctx: Context) = File(ctx.filesDir, "session-plan.md")

    // --- Память сессии ---

    /** Дописать заметку. При превышении 10 МБ вытесняем САМЫЕ СТАРЫЕ строки (FIFO — свежее важнее). */
    fun note(ctx: Context, text: String): Long = synchronized(lock) {
        val clean = text.trim().take(MAX_NOTE_LEN)
        if (clean.isEmpty()) return@synchronized sizeOf(memFile(ctx))
        val f = memFile(ctx)
        val lines = runCatching { if (f.exists()) f.readLines().toMutableList() else mutableListOf() }
            .getOrDefault(mutableListOf())
        lines += "- $clean"
        // FIFO-кап по байтам: пока суммарный размер > лимита — выкидываем строки с начала.
        var joined = lines.joinToString("\n")
        while (joined.toByteArray().size > MEMORY_MAX_BYTES && lines.size > 1) {
            lines.removeAt(0)
            joined = lines.joinToString("\n")
        }
        writeAtomic(f, joined)
        joined.toByteArray().size.toLong()
    }

    /** Прочитать рабочую память сессии (пусто, если её нет). */
    fun recall(ctx: Context): String = synchronized(lock) {
        runCatching { memFile(ctx).takeIf { it.exists() }?.readText()?.trim().orEmpty() }.getOrDefault("")
    }

    fun clearMemory(ctx: Context): Boolean = synchronized(lock) {
        runCatching { memFile(ctx).delete() }.getOrDefault(false)
    }

    // --- План ---

    /** Задать/переписать план целиком (пустой текст → очистить). Кап 5 МБ. */
    fun setPlan(ctx: Context, plan: String): Boolean = synchronized(lock) {
        val f = planFile(ctx)
        val trimmed = plan.trim()
        if (trimmed.isEmpty()) return@synchronized runCatching { f.delete() }.getOrDefault(false)
        // Кап: план не должен раздуваться (это чеклист, а не свалка). Обрезаем с конца, если гигант.
        val capped = if (trimmed.toByteArray().size > PLAN_MAX_BYTES)
            String(trimmed.toByteArray().copyOf(PLAN_MAX_BYTES.toInt()), Charsets.UTF_8) else trimmed
        writeAtomic(f, capped)
        true
    }

    fun getPlan(ctx: Context): String = synchronized(lock) {
        runCatching { planFile(ctx).takeIf { it.exists() }?.readText()?.trim().orEmpty() }.getOrDefault("")
    }

    private fun sizeOf(f: File): Long = runCatching { if (f.exists()) f.length() else 0L }.getOrDefault(0L)

    // Атомарная запись tmp+rename: краш посреди writeText не оставит обрезанный файл.
    private fun writeAtomic(f: File, content: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(f)) { f.writeText(content); tmp.delete() }
        }
    }
}

/** session_note — записать промежуточный результат в рабочую память сессии (для долгих задач). SAFE. */
class SessionNoteTool(private val context: Context) : AgentTool {
    override val id = "session_note"
    override val danger = DangerLevel.SAFE
    override val schema =
        """записать промежуточный результат в рабочую память задачи (переживает обрезку истории чата); """ +
            """args: {"text":"обработаны колледжи 1–5, осталось 10"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val text = runCatching { JSONObject(argsJson).optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("нужен args.text")
        val bytes = SessionStore.note(context, text)
        return ToolResult.Success("""{"saved":true,"memory_bytes":$bytes}""")
    }
}

/** session_recall — прочитать рабочую память задачи (продолжить с того, на чём остановились). SAFE. */
class SessionRecallTool(private val context: Context) : AgentTool {
    override val id = "session_recall"
    override val danger = DangerLevel.SAFE
    // Доступен и облачной модели (решение владельца — облако видит всю память задачи). Ядро §7 не задето:
    // личные файлы/RAG/почта остаются privateData. Вернуть приватность — снова privateData=true здесь.
    override val schema = """показать рабочую память текущей задачи (что уже сделано/найдено); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val mem = SessionStore.recall(context)
        return ToolResult.Success("""{"memory":"${escapeJson(mem.ifBlank { "(память задачи пуста)" })}"}""")
    }
}

/** plan_update — задать/обновить план задачи (markdown-чеклист). Пустой plan → очистить. SAFE. */
class PlanUpdateTool(private val context: Context) : AgentTool {
    override val id = "plan_update"
    override val danger = DangerLevel.SAFE
    override val schema =
        """задать/обновить план задачи как чеклист (отмечай сделанное [x]); перезаписывает целиком; """ +
            """args: {"plan":"[x] найти письма\n[ ] распознать сканы\n[ ] разложить по папкам"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val plan = runCatching { JSONObject(argsJson).optString("plan") }.getOrNull() ?: ""
        SessionStore.setPlan(context, plan)
        return ToolResult.Success("""{"updated":true}""")
    }
}

/** plan_show — показать текущий план с прогрессом. SAFE. */
class PlanShowTool(private val context: Context) : AgentTool {
    override val id = "plan_show"
    override val danger = DangerLevel.SAFE
    // Доступен и облачной модели (решение владельца — облако видит план). Ядро §7 не задето. План и так
    // авто-подставляется в промпт (planBlock), plan_show даёт полную версию по запросу.
    override val schema = """показать текущий план задачи с прогрессом; args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val plan = SessionStore.getPlan(context)
        return ToolResult.Success("""{"plan":"${escapeJson(plan.ifBlank { "(плана нет — задай plan_update)" })}"}""")
    }
}
