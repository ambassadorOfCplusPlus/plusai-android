package ru.aiagent.app.cloud

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.aiagent.app.AgentChatEngine
import ru.aiagent.core.agent.AgentEvent
import ru.aiagent.core.agent.AgentLoop
import ru.aiagent.core.agent.AutonomyMode
import ru.aiagent.core.agent.ChatGenerator
import ru.aiagent.core.agent.ConfirmationHandler

/**
 * Оркестратор (менеджер-работник): дорогой «планировщик» разбивает задачу на план,
 * дешёвый «исполнитель» (агент с инструментами) выполняет шаги, планировщик оценивает
 * результат и при необходимости уточняет. Экономно: тяжёлая модель думает только на
 * планировании/оценке, массовое исполнение — на дешёвой.
 */

/**
 * Пресет: какая модель ПЛАНИРУЕТ (главная) и какие ИСПОЛНЯЮТ (одна или несколько рабочих).
 * Один рабочий → главная планирует, он выполняет весь план (как раньше). Несколько рабочих →
 * шаги плана распределяются между ними (команда), главная в конце оценивает общий результат.
 */
data class OrchestratorPreset(val name: String, val planner: String, val executors: List<String>) {
    /** Совместимость: первый исполнитель. */
    val executor: String get() = executors.firstOrNull() ?: planner

    companion object {
        /** Пользовательская конфигурация: своя главная + свои рабочие (см. настройку в чате). */
        fun custom(planner: String, executors: List<String>): OrchestratorPreset {
            val exec = executors.filter { it.isNotBlank() }.ifEmpty { listOf(planner) }
            return OrchestratorPreset("Свои модели (${exec.size})", planner, exec)
        }
    }
}

// Планировщик — умная (дорогая) модель, исполнитель — дешёвый быстрый Flash.
val ORCHESTRATOR_PRESETS = listOf(
    OrchestratorPreset("DeepSeek (Pro→Flash)", "deepseek/deepseek-v4-pro", listOf("deepseek/deepseek-v4-flash")),
    OrchestratorPreset("Claude Sonnet 5 → Flash", "anthropic/claude-sonnet-5", listOf("deepseek/deepseek-v4-flash")),
    OrchestratorPreset("Claude Opus 4.8 → Flash", "anthropic/claude-opus-4.8", listOf("deepseek/deepseek-v4-flash")),
    OrchestratorPreset("GPT-5.5 → Flash", "openai/gpt-5.5", listOf("deepseek/deepseek-v4-flash")),
    OrchestratorPreset("Gemini 3.1 Pro → Flash", "google/gemini-3.1-pro-preview", listOf("deepseek/deepseek-v4-flash")),
    OrchestratorPreset("DeepSeek эконом (Flash→Flash)", "deepseek/deepseek-v4-flash", listOf("deepseek/deepseek-v4-flash")),
)

/** Строка вывода оркестратора для UI. kind: plan | step | tool | eval | answer. */
data class OrchLine(val kind: String, val text: String)

object OrchestratorEngine {

    private const val MAX_ROUNDS = 2 // план → исполнение → оценка, при неуспехе ещё один круг

    /** Собрать полный текст облачной генерации (не-стриминг) для планировщика/критика. */
    private suspend fun cloudText(context: Context, prompt: String, model: String): String {
        val sb = StringBuilder()
        CloudEngine.reply(context, prompt, model = model).collect { sb.append(it) }
        return sb.toString().trim()
    }

    fun run(
        context: Context,
        task: String,
        preset: OrchestratorPreset,
        mode: AutonomyMode,
        confirm: ConfirmationHandler,
    ): Flow<OrchLine> = flow {
        // Исполнитель — наш AgentLoop, но генератор ходит в ОБЛАКО (дешёвая модель).
        // Приватные инструменты (почта/календарь) исключаем: их результат ушёл бы в облако,
        // нарушив §7 и обещание «ничего не уходит на сервер». Они — только для локальной модели.
        // L8 для облака (§0/§7): набор общий для всех рабочих моделей, поэтому сужаем по САМОЙ СЛАБОЙ из них
        // (иначе слабый исполнитель получил бы DANGEROUS и в bypass исполнил бы без подтверждения). Класс —
        // эвристикой по id (у облачных моделей нет бенч-балла). Планировщик тоже учитываем: он может быть = исполнитель.
        val execIds = (preset.executors.filter { it.isNotBlank() }.ifEmpty { listOf(preset.planner) })
        val cloudCls = execIds.map { cloudModelClass(it) }.minByOrNull { it.ordinal } ?: ru.aiagent.core.agent.ModelClass.STANDARD
        val executable = ru.aiagent.core.agent.ToolGating.dangerAllowed(
            cloudCls, AgentChatEngine.allTools(context),
        )
        val help = ru.aiagent.core.agent.ListToolsTool { executable }
        // tool-RAG: исполнителю показываем релевантный топ + help (остальное добирает через help).
        val advertised = AgentChatEngine.selectRelevant(
            context, task, executable, ru.aiagent.core.agent.ToolGating.advertisedCap(cloudCls),
        ) + help
        val execMap = (executable + help).associateBy { it.id }
        val blocked = ru.aiagent.app.AppSettings.blockedTools(context)
        // Фабрика цикла-исполнителя для КОНКРЕТНОЙ рабочей модели: генератор ходит в облако именно ей.
        // blockedTools: пользовательский запрет инструментов действует и в оркестраторе — иначе через
        // планировщик-исполнитель отключённый инструмент выполнялся бы (обход настройки безопасности).
        fun loopFor(model: String) = AgentLoop(
            ChatGenerator { p -> cloudText(context, p, model) }, execMap, confirm,
            advertised = advertised, blockedTools = blocked,
        )
        // Рабочие модели: одна → весь план ей (как раньше); несколько → шаги плана делятся между ними.
        val workers = preset.executors.filter { it.isNotBlank() }.ifEmpty { listOf(preset.planner) }

        // Кругов план→исполнение→оценка. Команда (>1 рабочего) исполняет план целиком за проход и стоит
        // ~кратно дороже (каждый шаг — свой агент-цикл), поэтому ей даём ОДИН круг без переисполнения всех
        // шагов; одиночный исполнитель может уточниться вторым кругом.
        val rounds = if (workers.size > 1) 1 else MAX_ROUNDS
        var guidance = ""
        repeat(rounds) { round ->
            // 1) Планировщик (дорогая модель) — план по шагам.
            val planPrompt = buildString {
                appendLine("Ты — планировщик. Разбей задачу на 2–5 конкретных шагов (нумерованный список), без пояснений.")
                if (guidance.isNotBlank()) appendLine("Учти замечания прошлой попытки: $guidance")
                appendLine("Задача: $task")
            }
            val plan = cloudText(context, planPrompt, preset.planner)
            emit(OrchLine("plan", plan))

            // 2) Исполнение.
            val execAnswer = StringBuilder()
            if (workers.size == 1) {
                // Один исполнитель — выполняет всю задачу по плану (исходное поведение, без регрессии).
                val loop = loopFor(workers[0])
                val execTask = "Выполни задачу по плану.\nПлан:\n$plan\n\nЗадача: $task"
                loop.run(execTask, mode).collect { ev ->
                    when (ev) {
                        is AgentEvent.ToolCall -> emit(OrchLine("tool", "→ ${ev.toolId} ${ev.argsJson.take(80)}"))
                        is AgentEvent.ToolObservation -> emit(OrchLine("tool", "✓ выполнено"))
                        is AgentEvent.Answer -> execAnswer.append(ev.text)
                        else -> {}
                    }
                }
                emit(OrchLine("step", "Исполнитель завершил круг ${round + 1}"))
            } else {
                // Команда рабочих: шаги плана распределяются по кругу (шаг i → workers[i % N]). Каждый
                // видит общий план и итоги предыдущих шагов, выполняет ТОЛЬКО свой шаг своими инструментами.
                val steps = parseSteps(plan)
                steps.forEachIndexed { i, step ->
                    val model = workers[i % workers.size]
                    val who = model.substringAfterLast('/')
                    emit(OrchLine("step", "Шаг ${i + 1}/${steps.size} → $who"))
                    val stepPrompt = buildString {
                        appendLine("Общая задача: $task")
                        appendLine("Полный план:\n$plan")
                        if (execAnswer.isNotBlank()) appendLine("Итоги предыдущих шагов:\n${execAnswer.toString().take(3000)}")
                        appendLine("Выполни ТОЛЬКО шаг ${i + 1}: $step")
                    }
                    val stepAns = StringBuilder()
                    // Сбой одного шага (напр. облачная ошибка у рабочего) НЕ рушит весь прогон команды:
                    // отмечаем шаг как неудавшийся и идём дальше, сохранив итоги предыдущих. Отмену
                    // (пользователь остановил) пробрасываем — это не ошибка шага.
                    try {
                        loopFor(model).run(stepPrompt, mode).collect { ev ->
                            when (ev) {
                                is AgentEvent.ToolCall -> emit(OrchLine("tool", "[$who] → ${ev.toolId} ${ev.argsJson.take(70)}"))
                                is AgentEvent.ToolObservation -> emit(OrchLine("tool", "[$who] ✓ выполнено"))
                                is AgentEvent.Answer -> stepAns.append(ev.text)
                                else -> {}
                            }
                        }
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        emit(OrchLine("tool", "[$who] ✗ шаг ${i + 1} не выполнен: ${t.message?.take(120)}"))
                        stepAns.append("(шаг не выполнен: ${t.message?.take(120)})")
                    }
                    execAnswer.append("Шаг ${i + 1} ($who): ${stepAns.toString().trim()}\n\n")
                }
                emit(OrchLine("step", "Команда из ${workers.size} моделей завершила круг ${round + 1}"))
            }

            // 3) Критик (дорогая модель) — оценивает результат.
            val evalPrompt = buildString {
                appendLine("Ты — критик. Оцени, решена ли задача результатом ниже.")
                appendLine("Если решена — ответь строкой, начинающейся с 'ГОТОВО:' и кратким итогом.")
                appendLine("Если нет — ответь строкой 'ДОРАБОТАТЬ:' и что именно не так.")
                appendLine("Задача: $task")
                appendLine("Результат: ${execAnswer.toString().take(4000)}")
            }
            val verdict = cloudText(context, evalPrompt, preset.planner)
            emit(OrchLine("eval", verdict))

            if (verdict.trimStart().startsWith("ГОТОВО", true)) {
                // Критик доволен: итог = его резюме после «ГОТОВО:», иначе — сама работа.
                val final = verdict.substringAfter(":", "").trim().ifBlank { execAnswer.toString().trim() }
                emit(OrchLine("answer", final))
                return@flow
            }
            if (round == rounds - 1) {
                // Последний круг без «ГОТОВО»: отдаём ПРОДЕЛАННУЮ РАБОТУ, а не текст претензии критика
                // (раньше substringAfter(":") выдавал пользователю жалобу «не хватает…» вместо результата).
                emit(OrchLine("answer", execAnswer.toString().trim().ifBlank { verdict }))
                return@flow
            }
            guidance = verdict.substringAfter(":", "").trim()
        }
    }

    // Маркер пункта в НАЧАЛЕ строки: "1. ", "1) ", "- ", "* ", "• ". Снимаем ТОЛЬКО его (replaceFirst),
    // а не dropWhile — иначе у «1. 3D-модель» съелась бы ведущая цифра содержимого («3»).
    private val LIST_MARKER = Regex("^\\s*(?:\\d+[.)]|[-*•])\\s+")

    /** Разобрать план на шаги. Приоритет: строки-пункты (нумерация ИЛИ буллеты). Если разметки нет, но
     *  строк несколько — каждая непустая строка как шаг (иначе команда молча схлопнулась бы в 1 исполнителя).
     *  Совсем одна строка — один шаг. Кап 8, чтобы не уйти в неограниченное число облачных вызовов. */
    private fun parseSteps(plan: String): List<String> {
        val lines = plan.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val items = lines.filter { LIST_MARKER.containsMatchIn(it) }
            .map { it.replaceFirst(LIST_MARKER, "").trim() }
            .filter { it.isNotEmpty() }
        val steps = when {
            items.isNotEmpty() -> items
            lines.size > 1 -> lines
            else -> listOf(plan.trim())
        }
        return steps.take(8)
    }
}
