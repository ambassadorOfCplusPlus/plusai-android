package ru.aiagent.app.rag

import android.content.Context
import ru.aiagent.app.AppSettings
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.toolsdocs.extractDocumentText
import java.io.File

/**
 * Инструменты RAG (S8) — поиск по проиндексированным документам пользователя и индексация.
 * Всё локально: эмбеддинги и база не покидают устройство (ТЗ §7).
 */

/**
 * search_knowledge — семантический поиск по базе знаний пользователя.
 *
 * Доступ облачной модели к RAG регулируется настройкой [AppSettings.ragCloudMode] (§7-выбор владельца):
 *  - "local"  — инструмент privateData: облаку недоступен вовсе (сырые документы наружу не уходят).
 *  - "hybrid" — облаку доступен, но возвращает НЕ сырые фрагменты, а ответ, синтезированный ЛОКАЛЬНОЙ
 *               моделью по этим фрагментам: сами документы остаются на устройстве, наружу — только вывод.
 *  - "direct" — облаку доступен и возвращает сами найденные фрагменты (владелец согласился их отдавать).
 */
class SearchKnowledgeTool(private val context: Context) : AgentTool {
    override val id = "search_knowledge"
    override val danger = DangerLevel.SAFE
    // privateData зависит от режима: в "local" прячем от облака; в "hybrid"/"direct" — доступен
    // (в hybrid облако получает только локальный синтез, сырьё не покидает устройство).
    override val privateData get() = AppSettings.ragCloudMode(context) == "local"
    override val schema = """найти ответ в загруженных документах (база знаний); args: {"query":"вопрос"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val query = jsonStr(argsJson, "query") ?: return ToolResult.Failure("нет args.query")
        if (RagEngine.embeddingModelFile(context) == null)
            return ToolResult.Failure("база знаний недоступна: установите эмбеддинг-модель (bge-m3) на вкладке Модели")
        return try {
            val hits = RagEngine.search(context, query, topK = 4)
            if (hits.isEmpty())
                return ToolResult.Success("""{"found":0,"note":"в базе ничего не найдено — сначала загрузите документы"}""")
            // HYBRID: сырые фрагменты читает ЛОКАЛЬНАЯ модель и синтезирует ответ — наружу уходит только он.
            if (AppSettings.ragCloudMode(context) == "hybrid") {
                val rawCtx = hits.joinToString("\n---\n") { "[${it.documentUri}] ${it.text.take(600)}" }
                val instr = "Ответь на запрос по фрагментам из базы знаний пользователя. Отвечай ТОЛЬКО по " +
                    "фрагментам, кратко и по делу; если ответа в них нет — так и скажи. Не цитируй лишнего.\n\n" +
                    "Запрос: $query\n\nФрагменты:\n${rawCtx.take(6000)}"
                val answer = runCatching { ru.aiagent.app.ChatEngine.agentGenerate(context, instr) }
                    .getOrElse {
                        return ToolResult.Failure("для приватного поиска (режим «через локальную модель») нужна " +
                            "локальная модель — установите/выберите её на вкладке Модели, либо переключите режим " +
                            "RAG на «напрямую» в Настройках")
                    }
                val sources = hits.joinToString(", ") { File(it.documentUri).name }
                ToolResult.Success("""{"found":${hits.size},"answer":"${esc(answer.trim().take(4000))}","sources":"${esc(sources)}"}""")
            } else {
                // DIRECT (или локальная модель): сами найденные фрагменты.
                val ctx = hits.joinToString("\n---\n") { "[${it.documentUri}] ${it.text.take(600)}" }
                ToolResult.Success("""{"found":${hits.size},"context":"${esc(ctx.take(4000))}"}""")
            }
        } catch (t: Throwable) {
            ToolResult.Failure("поиск: ${t.message?.take(200)}")
        }
    }
}

/** index_document — добавить файл из рабочей папки в базу знаний. */
class IndexDocumentTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "index_document"
    override val usesFiles = true
    override val danger = DangerLevel.IMPORTANT
    override val schema = """добавить документ в базу знаний для поиска; args: {"path":"файл.pdf"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val path = jsonStr(argsJson, "path") ?: return ToolResult.Failure("нет args.path")
        val file = resolve(path) ?: return ToolResult.Failure("путь вне разрешённой папки")
        if (!file.exists()) return ToolResult.Failure("файла нет: $path")
        if (RagEngine.embeddingModelFile(context) == null)
            return ToolResult.Failure("установите эмбеддинг-модель (bge-m3) на вкладке Модели")
        return try {
            val text = extractDocumentText(context, file)
            if (text.isBlank()) return ToolResult.Failure("документ пуст или не распознан")
            val skipped = RagEngine.indexText(context, file.name, text).getOrThrow()
            ToolResult.Success("""{"indexed":"${esc(file.name)}","chars":${text.length},"skipped_chunks":$skipped}""")
        } catch (t: Throwable) {
            ToolResult.Failure("индексация: ${t.message?.take(200)}")
        }
    }
}

private fun jsonStr(json: String, field: String): String? =
    Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        .find(json)?.groupValues?.get(1)
        ?.let { ru.aiagent.core.agent.tools.unescapeJson(it) }

// Общий escapeJson: экранирует и управляющие символы (\t,\r,\b,\f,<0x20) — ручной 3-replace давал битый JSON.
private fun esc(s: String) = ru.aiagent.core.agent.tools.escapeJson(s)
