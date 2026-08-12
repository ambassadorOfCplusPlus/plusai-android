package ru.aiagent.app.code

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.aiagent.core.agent.ToolResult

/** LuaJ — чистый JVM. Проверяем, что песочница убрала Java-мост/шелл, а математика жива. */
class LuaSandboxTest {

    private fun run(code: String): String {
        val esc = code.replace("\\", "\\\\").replace("\"", "\\\"")
        val res = runBlocking { RunLuaTool().invoke("""{"code":"$esc"}""") }
        return when (res) {
            is ToolResult.Success -> res.outputJson
            is ToolResult.Failure -> "FAIL:${res.message}"
            else -> "?"
        }
    }

    @Test fun `математика работает`() {
        assertTrue(run("print(2+3)").contains("5"))
    }

    @Test fun `luajava недоступен (нет Java-моста)`() {
        assertTrue("luajava должен быть nil", run("print(tostring(luajava))").contains("nil"))
    }

    @Test fun `os_execute и io вырезаны`() {
        assertTrue(run("print(tostring(os.execute))").contains("nil"))
        assertTrue(run("print(tostring(io))").contains("nil"))
    }
}
