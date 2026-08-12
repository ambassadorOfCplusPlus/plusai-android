package ru.aiagent.app.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Rhino — чистый JVM, тестируется без Android. Проверяем песочницу и изоляцию scope. */
class JsEngineTest {

    @Test fun `обычный JS считается`() {
        assertEquals("6", JsEngine.run("2*3"))
    }

    @Test fun `Java-мост закрыт — RCE через java недоступен`() {
        // ClassShutter должен запретить доступ к любому Java-классу: результат НЕ должен содержать
        // успешный вызов Runtime/System. Любой из путей (исключение или пустой/ошибочный вывод) — ок,
        // главное — нет признаков реально исполненной Java-команды.
        val out = runCatching { JsEngine.run("''+java.lang.System.getProperty('user.dir')") }
            .getOrElse { "blocked:${it.message}" }
        assertFalse("Java-класс не должен быть виден скрипту: $out", out.contains("/") || out.contains("\\"))
    }

    @Test fun `run изолирован — переменные не текут между вызовами`() {
        JsEngine.run("var leaked = 123; leaked")
        // finish() глушит голое «undefined», поэтому проверяем через явную строку.
        assertEquals("ISOLATED", JsEngine.run("typeof leaked === 'undefined' ? 'ISOLATED' : 'LEAK'"))
    }

    @Test fun `code-mode мост tool передаёт имя и JSON-аргументы, log собирает вывод`() {
        val calls = mutableListOf<Pair<String, String>>()
        val out = JsEngine.runOrchestration(
            "const items = JSON.parse(tool('list_dir', {path:'.'})); log('файлов: ' + items.length); items.length",
        ) { name, args ->
            calls.add(name to args)
            if (name == "list_dir") "[\"a\",\"b\"]" else "?"
        }
        assertEquals(1, calls.size)
        assertEquals("list_dir", calls[0].first)
        assertTrue("объект стал JSON строкой: ${calls[0].second}", calls[0].second.contains("\"path\":\".\""))
        assertTrue("лог собран: $out", out.contains("файлов: 2"))
        assertTrue("итог выражения: $out", out.contains("2"))
    }

    @Test fun `code-mode цикл — несколько tool за один прогон`() {
        var n = 0
        JsEngine.runOrchestration("for (let i=0;i<3;i++){ tool('write_file', {path:'f'+i}); }") { _, _ -> n++; "ok" }
        assertEquals(3, n)
    }

    @Test fun `code-mode ошибка исполнителя всплывает наружу`() {
        try {
            JsEngine.runOrchestration("tool('nope', {})") { _, _ -> throw RuntimeException("недоступен") }
            fail("ждали ошибку из invokeTool")
        } catch (e: Throwable) {
            assertTrue((e.message ?: "").contains("недоступен") || (e.message ?: "").contains("nope") || e is RuntimeException)
        }
    }

    // CPU-цикл прерывается наблюдателем инструкций Rhino (interrupt()/stop() из watchdog его не берут).
    @Test(timeout = 15_000) fun `бесконечный цикл прерывается по CPU-бюджету`() {
        val t0 = System.currentTimeMillis()
        try {
            JsEngine.execFresh("while(true){}", 300) // бюджет 300мс
            fail("ждали прерывание бесконечного цикла")
        } catch (e: Throwable) {
            assertTrue("ошибка про лимит времени: ${e.message}", (e.message ?: "").contains("лимит"))
        }
        assertTrue("должно прерваться быстро (не висеть)", System.currentTimeMillis() - t0 < 8_000)
    }
}
