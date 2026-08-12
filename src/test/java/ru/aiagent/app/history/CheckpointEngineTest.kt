package ru.aiagent.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Тесты ядра автоотката (крипто = identity, часы инжектятся). */
class CheckpointEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L

    private fun engine(
        ws: File, hist: File,
        budget: Long = Long.MAX_VALUE, retentionMs: Long = Long.MAX_VALUE, minKeep: Int = 10,
    ) = CheckpointEngine(
        historyDir = hist, workspace = ws,
        encrypt = { it }, decrypt = { it }, // identity — тест логики, не крипто
        config = CheckpointEngine.Config(budgetBytes = budget, retentionMs = retentionMs, minKeep = minKeep),
        clock = { clock },
    )

    private fun ws() = tmp.newFolder("workspace")
    private fun hist() = tmp.newFolder("history")
    private fun write(dir: File, rel: String, text: String) =
        File(dir, rel).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun snapshotDedupAndChange() {
        val ws = ws(); val e = engine(ws, hist())
        write(ws, "a.txt", "первый")
        val cp1 = e.snapshot("ход 1")
        assertNotNull(cp1)
        assertEquals(1, cp1!!.files.size)
        // Без изменений — нового чекпоинта нет.
        clock += 1000
        assertNull(e.snapshot("ход 2 без правок"))
        // Правка → новый чекпоинт.
        write(ws, "a.txt", "второй")
        clock += 1000
        assertNotNull(e.snapshot("ход 3"))
        assertEquals(2, e.list().size)
    }

    @Test fun restoreRevertsFilesAndDeletesNew() {
        val ws = ws(); val e = engine(ws, hist())
        write(ws, "keep.txt", "v1")
        val cp1 = e.snapshot("база")!!
        // Меняем keep, добавляем new.txt.
        clock += 1000
        write(ws, "keep.txt", "v2-сломано")
        write(ws, "new.txt", "мусор от ИИ")
        e.snapshot("ход ИИ")
        // Откат к cp1.
        clock += 1000
        val r = e.restore(cp1.id)
        assertTrue(r.ok)
        assertEquals("v1", File(ws, "keep.txt").readText())
        assertFalse("new.txt должен быть удалён", File(ws, "new.txt").exists())
    }

    @Test fun excludesLargeAndModels() {
        val ws = ws()
        val e = CheckpointEngine(
            hist(), ws, { it }, { it },
            CheckpointEngine.Config(budgetBytes = Long.MAX_VALUE, retentionMs = Long.MAX_VALUE, maxFileBytes = 100),
            clock = { clock },
        )
        write(ws, "small.txt", "ок")
        write(ws, "model.gguf", "весит-как-модель")             // исключён по расширению
        write(ws, "big.bin", "x".repeat(200))                   // .bin в excludeExt + больше лимита
        write(ws, "huge.txt", "y".repeat(200))                  // больше maxFileBytes=100
        val cp = e.snapshot("ход")!!
        val paths = cp.files.map { it.path }.toSet()
        assertEquals(setOf("small.txt"), paths)
    }

    @Test fun dedupSharesBlobsAcrossCheckpoints() {
        val ws = ws(); val hist = hist(); val e = engine(ws, hist)
        write(ws, "a.txt", "одинаковое содержимое")
        e.snapshot("cp1")
        clock += 1000
        write(ws, "b.txt", "одинаковое содержимое") // тот же контент → тот же blob
        e.snapshot("cp2")
        // Два файла с идентичным содержимым делят один blob (дедуп).
        val blobs = File(hist, "blobs").listFiles()?.size ?: 0
        assertEquals("идентичный контент → один blob", 1, blobs)
    }

    @Test fun deltaReconstructsBigFileEditExactly() {
        val ws = ws(); val e = engine(ws, hist())
        val head = "A".repeat(5000); val tail = "B".repeat(5000)
        write(ws, "big.txt", head + "СТАРАЯ" + tail)
        val cp1 = e.snapshot("v1")!!
        clock += 1000
        write(ws, "big.txt", head + "НОВАЯ_ДЛИННЕЕ_СЕРЕДИНА" + tail)
        e.snapshot("v2")
        // Дельта: суммарный объём заметно меньше двух полных файлов (~10КБ каждый).
        assertTrue("дельта не сэкономила: ${e.usageBytes()}", e.usageBytes() < 9000)
        clock += 1000
        e.restore(cp1.id)
        assertEquals(head + "СТАРАЯ" + tail, File(ws, "big.txt").readText())
    }

    @Test fun compactPreservesContent() {
        val ws = ws(); val e = engine(ws, hist())
        write(ws, "a.txt", "x".repeat(3000))
        val cp1 = e.snapshot("v1")!!
        clock += 1000
        write(ws, "a.txt", "x".repeat(1500) + "прав" + "x".repeat(1500))
        e.snapshot("v2")
        assertTrue(e.compact() > 0)
        clock += 1000
        e.restore(cp1.id)
        assertEquals("x".repeat(3000), File(ws, "a.txt").readText())
    }

    @Test fun retentionByCountAndTime() {
        val ws = ws(); val e = engine(ws, hist(), retentionMs = 5000, minKeep = 2)
        // 4 чекпоинта с шагом 3000мс, каждый меняет файл.
        for (i in 1..4) {
            write(ws, "a.txt", "версия $i")
            e.snapshot("ход $i")
            clock += 3000
        }
        // Держим минимум 2 последних, старые вне окна 5000мс выброшены.
        val list = e.list()
        assertTrue("должно остаться >=2 и <4: ${list.size}", list.size in 2..3)
    }

    // Регресс: GC не должен удалять базу дельты, на которую ещё ссылается выживший чекпоинт.
    @Test fun gcKeepsDeltaBaseChainAfterPrune() {
        val ws = ws(); val e = engine(ws, hist(), retentionMs = 1, minKeep = 1)
        val head = "A".repeat(4000)
        write(ws, "big.txt", head + "v1"); e.snapshot("v1")
        for (v in 2..5) { clock += 1000; write(ws, "big.txt", head + "v$v"); e.snapshot("v$v") }
        val last = e.list().first() // новые сверху; его файл — дельта на пруненные версии
        clock += 1000
        val r = e.restore(last.id)
        assertTrue("база дельты не должна быть удалена GC: ${r.message}", r.ok)
        assertEquals(head + "v5", File(ws, "big.txt").readText())
    }

    // Регресс: откат к старейшему чекпоинту не должен вытеснять его же снимком «до отката».
    @Test fun restoreOldestSurvivesRetention() {
        val ws = ws(); val e = engine(ws, hist(), retentionMs = 1, minKeep = 2)
        write(ws, "a.txt", "v1"); val cp1 = e.snapshot("v1")!!
        clock += 1000; write(ws, "a.txt", "v2"); e.snapshot("v2")
        // [cp1,cp2] = minKeep. Меняем workspace → «до отката» добавит 3-й чекпоинт, что вытолкнуло бы cp1
        // из окна ретенции; protectId цели обязан его сохранить.
        clock += 1000; write(ws, "a.txt", "uncommitted")
        val r = e.restore(cp1.id)
        assertTrue("откат к старейшему не должен падать: ${r.message}", r.ok)
        assertEquals("v1", File(ws, "a.txt").readText())
    }

    @Test fun clearWipesHistory() {
        val ws = ws(); val e = engine(ws, hist())
        write(ws, "a.txt", "x")
        e.snapshot("cp")
        assertTrue(e.usageBytes() > 0)
        e.clear()
        assertEquals(0, e.list().size)
        assertEquals(0, e.usageBytes())
    }
}
