package ru.aiagent.app.utils

import android.content.Context
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File

/**
 * Личная долговременная память ИИ: текстовый файл, куда агент сам записывает, что нужно запомнить
 * (предпочтения пользователя, факты о проектах, договорённости). Содержимое авто-подставляется в
 * системный промпт (см. AgentChatEngine.userContext), поэтому помнится МЕЖДУ чатами без явного recall.
 * Хранится локально (§7): filesDir/memory.md, наружу не уходит.
 */
object MemoryStore {
    private const val MAX_NOTES = 300         // кап, чтобы память не раздувала контекст
    private const val MAX_NOTE_LEN = 500

    private fun file(ctx: Context) = File(ctx.filesDir, "memory.md")
    private val lock = Any() // read-modify-write append не атомарен: без лока два remember теряют одну заметку

    /** Все заметки как готовый markdown-текст (пусто, если памяти нет). */
    fun read(ctx: Context): String = synchronized(lock) {
        runCatching { file(ctx).takeIf { it.exists() }?.readText()?.trim().orEmpty() }.getOrDefault("")
    }

    /** Добавить заметку. Возвращает общее число заметок после добавления. */
    fun append(ctx: Context, note: String): Int = synchronized(lock) {
        val clean = note.trim().replace(Regex("\\s+"), " ").take(MAX_NOTE_LEN)
        if (clean.isEmpty()) return@synchronized countLocked(ctx)
        val f = file(ctx)
        val lines = runCatching { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() }
            .getOrDefault(emptyList())
            .toMutableList()
        if (lines.none { it.removePrefix("- ").trim().equals(clean, ignoreCase = true) }) lines += "- $clean"
        val capped = if (lines.size > MAX_NOTES) lines.takeLast(MAX_NOTES) else lines
        f.atomicWriteText(capped.joinToString("\n")) // общий хелпер (tmp+rename, уникальный tmp)
        capped.size
    }

    fun clear(ctx: Context): Boolean = synchronized(lock) { runCatching { file(ctx).delete() }.getOrDefault(false) }

    private fun countLocked(ctx: Context): Int =
        runCatching { if (file(ctx).exists()) file(ctx).readLines().count { it.isNotBlank() } else 0 }.getOrDefault(0)

    fun count(ctx: Context): Int = synchronized(lock) { countLocked(ctx) }
}

/** remember — записать факт в долговременную память ИИ. SAFE (свой приватный файл). */
class RememberTool(private val context: Context) : AgentTool {
    override val id = "remember"
    override val danger = DangerLevel.SAFE
    override val schema =
        """запомнить факт надолго (память между чатами): предпочтения, договорённости, факты о проектах; """ +
            """args: {"note":"пользователь предпочитает ответы кратко"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val note = runCatching { JSONObject(argsJson).optString("note") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("нужен args.note")
        val n = MemoryStore.append(context, note)
        return ToolResult.Success("""{"saved":true,"total_notes":$n}""")
    }
}

/** recall — прочитать долговременную память (обычно не нужно: она уже в системном промпте). SAFE. */
class RecallTool(private val context: Context) : AgentTool {
    override val id = "recall"
    override val danger = DangerLevel.SAFE
    // Доступен и облачной модели (решение владельца — облако видит всю память). Память и так авто-
    // подставляется в промпт (memBlock); recall даёт её по явному запросу. Ядро §7 (файлы/RAG/почта) не задето.
    override val schema = """показать всё, что запомнено в долговременной памяти; args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val mem = MemoryStore.read(context)
        return ToolResult.Success("""{"memory":"${escapeJson(mem.ifBlank { "(память пуста)" })}"}""")
    }
}

/** forget — очистить долговременную память. IMPORTANT + подтверждение (необратимо стирает память). */
class ForgetTool(private val context: Context) : AgentTool {
    override val id = "forget"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema = """стереть ВСЮ долговременную память; args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        MemoryStore.clear(context)
        return ToolResult.Success("""{"cleared":true}""")
    }
}
