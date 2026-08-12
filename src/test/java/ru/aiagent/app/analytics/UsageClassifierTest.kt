package ru.aiagent.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Классификатор задача→категория ДОЛЖЕН давать те же категории, что C# UsageClassifier (иначе агрегаты
 * не сольются на сервере). Те же входы/ожидания, что в C# UsageStatsTests.
 */
class UsageClassifierTest {
    @Test fun `по тексту`() {
        assertEquals("coding", UsageClassifier.classify("напиши функцию сортировки на python", null))
        assertEquals("math", UsageClassifier.classify("посчитай интеграл этого уравнения", null))
        assertEquals("sysadmin", UsageClassifier.classify("настрой nginx на сервере через ssh", null))
        assertEquals("personal", UsageClassifier.classify("прочитай моё последнее письмо", null))
    }

    @Test fun `по инструментам при нейтральном тексте`() {
        assertEquals("math", UsageClassifier.classify("сделай это", listOf("cas")))
        assertEquals("sysadmin", UsageClassifier.classify("выполни", listOf("run_shell", "ssh_run")))
        assertEquals("personal", UsageClassifier.classify("помоги", listOf("read_email")))
    }

    @Test fun `инструменты перевешивают шум`() {
        assertEquals("coding", UsageClassifier.classify("сделай задачу", listOf("lsp_rename", "grep", "read_file")))
    }

    @Test fun `пусто это other`() {
        assertEquals(UsageClassifier.OTHER, UsageClassifier.classify(null, null))
        assertEquals(UsageClassifier.OTHER, UsageClassifier.classify("привет как дела", emptyList()))
    }

    @Test fun `store считает матрицу и дедупит инструменты`() {
        val s = UsageStore()
        s.record("посчитай производную", listOf("cas", "cas"), listOf("sympy"))
        assertEquals(1, s.categories["math"])
        assertEquals(1, s.tools["cas"])          // toSet → 1, не 2
        assertEquals(1, s.pairs["math|cas"])
        assertEquals(1, s.packs["sympy"])
    }

    @Test fun `subtract вычитает отправленное`() {
        val cur = UsageStore(); cur.record("напиши код", listOf("write_file")); cur.record("почини баг", listOf("write_file"))
        val sent = UsageStore(); sent.record("напиши код", listOf("write_file"))
        cur.subtract(sent)
        assertEquals(1, cur.categories["coding"]) // было 2, отправили 1
        assertEquals(1, cur.tools["write_file"])
    }
}
