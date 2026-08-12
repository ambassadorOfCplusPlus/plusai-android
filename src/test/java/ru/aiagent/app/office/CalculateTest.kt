package ru.aiagent.app.office

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.aiagent.core.agent.ToolResult

/** Проверка калькулятора (отчёт тестирования сообщал о падении на скобках). */
class CalculateTest {
    private fun calc(expr: String): String {
        val res = runBlocking { CalculateTool().invoke("""{"expr":"$expr"}""") }
        return when (res) {
            is ToolResult.Success -> res.outputJson
            is ToolResult.Failure -> "FAIL:${res.message}"
            else -> "?"
        }
    }

    @Test fun `скобки и деление`() {
        // (2+2)*5/3 = 6.666… — из отчёта падало «лишние символы у позиции 7».
        val out = calc("(2+2)*5/3")
        assertTrue("ожидали успех, получили: $out", out.contains("6.66") || out.contains("\"result\":6"))
    }

    @Test fun `степень и унарный минус`() {
        assertTrue(calc("-2^3 + (10-4)").contains("result"))
    }

    @Test fun `деление на ноль — понятная ошибка`() {
        assertTrue(calc("1/0").startsWith("FAIL"))
    }

    @Test fun `переэкранированный слеш от модели`() {
        // DeepSeek часто шлёт "10\/2" — раньше jsonField не разэкранировал \/ и калькулятор падал.
        val out = calc("10\\/2")
        assertTrue("ожидали 5, получили: $out", out.contains("\"result\":5"))
    }
}
