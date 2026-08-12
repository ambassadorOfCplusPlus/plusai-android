package ru.aiagent.app.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File

/**
 * НАВЫКИ (идея Hermes-агента «the agent that grows with you»): самообучение. Решив нетривиальную задачу,
 * агент записывает переиспользуемую ПРОЦЕДУРУ (name + когда применять + шаги). В отличие от [MemoryStore]
 * (ФАКТЫ), навык — это КАК делать. Релевантные навыки авто-подставляются в промпт по запросу пользователя
 * (см. AgentChatEngine.withContextPrefix) → модель узнаёт свой прошлый приём и применяет, без явного вызова.
 * Хранится локально (§7): filesDir/skills.json, наружу не уходит.
 */
object SkillStore {
    private const val MAX_SKILLS = 100
    private const val MAX_FIELD = 800

    data class Skill(val name: String, val whenUse: String, val steps: String)

    private fun file(ctx: Context) = File(ctx.filesDir, "skills.json")
    private val lock = Any()

    fun all(ctx: Context): List<Skill> = synchronized(lock) { readLocked(ctx) }

    private fun readLocked(ctx: Context): List<Skill> {
        val raw = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Skill(name, o.optString("when"), o.optString("steps"))
        }
    }

    /** Добавить/обновить навык по имени. Возвращает общее число навыков. */
    fun save(ctx: Context, name: String, whenUse: String, steps: String): Int = synchronized(lock) {
        val n = name.trim().take(120)
        if (n.isEmpty()) return@synchronized readLocked(ctx).size
        val skill = Skill(n, whenUse.trim().take(MAX_FIELD), steps.trim().take(MAX_FIELD))
        val list = readLocked(ctx).filterNot { it.name.equals(n, ignoreCase = true) }.toMutableList()
        list += skill
        val capped = if (list.size > MAX_SKILLS) list.takeLast(MAX_SKILLS) else list
        writeLocked(ctx, capped)
        capped.size
    }

    fun remove(ctx: Context, name: String): Boolean = synchronized(lock) {
        val list = readLocked(ctx)
        val kept = list.filterNot { it.name.equals(name.trim(), ignoreCase = true) }
        if (kept.size == list.size) return@synchronized false
        writeLocked(ctx, kept)
        true
    }

    fun clear(ctx: Context): Boolean = synchronized(lock) { runCatching { file(ctx).delete() }.getOrDefault(false) }

    private fun writeLocked(ctx: Context, list: List<Skill>) {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("name", s.name).put("when", s.whenUse).put("steps", s.steps))
        file(ctx).atomicWriteText(arr.toString()) // общий хелпер (уникальный tmp — без фикс-имени-ловушки)
    }

    /**
     * Топ-k навыков, РЕЛЕВАНТНЫХ запросу (совпадение ключевых слов с name+when). Только overlap>0 — иначе на
     * короткий/нерелевантный запрос в промпт подставились бы случайные навыки (шум для слабых моделей, и все
     * при малом числе навыков). Пустой запрос → пусто (лучше ничего, чем нерелевантное).
     */
    fun relevant(ctx: Context, query: String, k: Int): List<Skill> {
        val skills = all(ctx)
        if (skills.isEmpty()) return emptyList()
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        return skills
            .map { it to overlap(q, tokens(it.name + " " + it.whenUse)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private val SPLIT = Regex("[^\\p{L}\\p{Nd}]+") // hoisted: relevant() бежит на каждом сообщении
    private fun tokens(s: String): Set<String> = s.lowercase().split(SPLIT).filter { it.length >= 3 }.toSet()
    private fun overlap(a: Set<String>, b: Set<String>): Int = a.count { it in b }
}

/** save_skill — записать переиспользуемый навык (КАК решать класс задач). SAFE (свой приватный файл). */
class SaveSkillTool(private val context: Context) : AgentTool {
    override val id = "save_skill"
    override val danger = DangerLevel.SAFE
    override val schema =
        """сохранить переиспользуемый НАВЫК (как решать похожие задачи) — в следующий раз узнаешь и применишь; """ +
            """args: {"name":"краткое имя","when_to_use":"когда применять","steps":"1) … 2) … 3) …"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ToolResult.Failure("битый JSON")
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return ToolResult.Failure("нужен args.name")
        val whenUse = o.optString("when_to_use")
        val steps = o.optString("steps").takeIf { it.isNotBlank() } ?: return ToolResult.Failure("нужен args.steps (шаги)")
        val n = SkillStore.save(context, name, whenUse, steps)
        return ToolResult.Success("""{"saved":true,"skill":"${escapeJson(name)}","total_skills":$n}""")
    }
}

/** list_skills — показать сохранённые навыки. SAFE. */
class ListSkillsTool(private val context: Context) : AgentTool {
    override val id = "list_skills"
    override val danger = DangerLevel.SAFE
    override val schema = """показать сохранённые навыки (имя + когда применять); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val skills = SkillStore.all(context)
        val arr = JSONArray()
        for (s in skills) arr.put(JSONObject().put("name", s.name).put("when", s.whenUse))
        return ToolResult.Success("""{"skills":$arr,"total":${skills.size}}""")
    }
}

/** forget_skill — удалить навык по имени (или ВСЕ, если name пусто). IMPORTANT + подтверждение. */
class ForgetSkillTool(private val context: Context) : AgentTool {
    override val id = "forget_skill"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema = """удалить навык по имени (стереть ВСЕ — только с all:true); args: {"name":"имя","all":false}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ToolResult.Failure("битый JSON")
        val name = (if (o.isNull("name")) "" else o.optString("name")).trim()
        if (name.isBlank()) {
            // Пустое имя НЕ стирает всё по умолчанию (в bypass подтверждения нет → защита от случайного вайпа
            // всех самообученных навыков): нужен явный all:true.
            return if (o.optBoolean("all", false)) {
                SkillStore.clear(context)
                ToolResult.Success("""{"cleared_all":true}""")
            } else {
                ToolResult.Failure("укажи name (какой навык удалить) или all:true, чтобы стереть ВСЕ навыки")
            }
        }
        val ok = SkillStore.remove(context, name)
        return ToolResult.Success("""{"removed":$ok,"skill":"${escapeJson(name)}"}""")
    }
}

/** Сборка инструментов навыков. */
fun skillTools(context: Context): List<AgentTool> =
    listOf(SaveSkillTool(context), ListSkillsTool(context), ForgetSkillTool(context))
