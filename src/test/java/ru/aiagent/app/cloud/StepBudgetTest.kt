package ru.aiagent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class StepBudgetTest {
    @Test fun byModelSizeAndClass() {
        // Тиры: начальные 128 / базовые 256 / крупные 512 / флагманы 1048.
        // Мелкие/быстрые варианты — раньше флагманов.
        assertEquals(128, CloudFunctionAgent.stepBudget("openai/gpt-4o-mini"))       // mini
        assertEquals(128, CloudFunctionAgent.stepBudget("google/gemini-2.0-flash-lite")) // lite (раньше flash)
        assertEquals(256, CloudFunctionAgent.stepBudget("deepseek/deepseek-v4-flash")) // flash
        // По размеру.
        assertEquals(128, CloudFunctionAgent.stepBudget("meta/llama-3.1-8b"))        // 8b < 10 → начальные
        assertEquals(256, CloudFunctionAgent.stepBudget("qwen/qwen-2.5-72b-instruct")) // 72b ≤ 80 → базовые
        assertEquals(1048, CloudFunctionAgent.stepBudget("qwen/qwen3-235b-a22b-instruct")) // 235b > 200 → флагман
        // Флагманы (по имени).
        assertEquals(1048, CloudFunctionAgent.stepBudget("anthropic/claude-opus-4"))
        assertEquals(1048, CloudFunctionAgent.stepBudget("deepseek/deepseek-v4"))
        // Неизвестно — «базовые».
        assertEquals(256, CloudFunctionAgent.stepBudget("acme/mystery-model"))
    }
}
