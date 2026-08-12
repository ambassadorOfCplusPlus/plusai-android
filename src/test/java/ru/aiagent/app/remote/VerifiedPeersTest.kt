package ru.aiagent.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Персистентный стор доверенных E2E-пиров (телефон-сторона). Персистенс инъектируется (в проде —
 * SharedPreferences), поэтому тест герметичен на JVM. Смысл — как у десктопного VerifiedPeers:
 * TOFU-пиннинг, пометка verified после сверки SAS, забывание при пере-спаривании.
 */
class VerifiedPeersTest {
    // Имитация хранилища: одна ячейка строки.
    private val cell = arrayOfNulls<String>(1)
    private fun store() = VerifiedPeers(load = { cell[0] }, save = { cell[0] = it })

    @Test
    fun `первый контакт пиннится неверифицированным`() {
        val p = store()
        assertTrue(p.pin("pc-1", "KEY-A"))
        val r = p.get("pc-1")!!
        assertEquals("KEY-A", r.pubkey)
        assertFalse(r.verified)
    }

    @Test
    fun `смена ключа отклоняется TOFU`() {
        val p = store()
        p.pin("pc-1", "KEY-A")
        assertFalse(p.pin("pc-1", "KEY-EVIL"))
        assertEquals("KEY-A", p.get("pc-1")!!.pubkey)
    }

    @Test
    fun `markVerified держится после перезагрузки`() {
        store().apply { pin("pc-1", "KEY-A"); markVerified("pc-1", "KEY-A") }
        // Новый экземпляр читает ту же ячейку — verified пережил «рестарт».
        assertTrue(store().get("pc-1")!!.verified)
    }

    @Test
    fun `markVerified чужого ключа игнорируется`() {
        val p = store()
        p.pin("pc-1", "KEY-A")
        p.markVerified("pc-1", "KEY-OTHER")
        assertFalse(p.get("pc-1")!!.verified)
    }

    @Test
    fun `verify распространяется на тот же ключ`() {
        // Устройство под двумя id (host + controller) с одним pubkey — одна сверка покрывает оба.
        val p = store()
        p.pin("pc-host", "KEY-A")
        p.pin("pc-ctrl", "KEY-A")
        p.markVerified("pc-host", "KEY-A")
        assertTrue(p.get("pc-host")!!.verified)
        assertTrue(p.get("pc-ctrl")!!.verified)
    }

    @Test
    fun `новый id с доверенным ключом наследует verified`() {
        val p = store()
        p.pin("pc-host", "KEY-A")
        p.markVerified("pc-host", "KEY-A")
        p.pin("pc-ctrl", "KEY-A")
        assertTrue(p.get("pc-ctrl")!!.verified)
    }

    @Test
    fun `forget удаляет пира`() {
        val p = store()
        p.pin("pc-1", "KEY-A")
        assertTrue(p.forget("pc-1"))
        assertNull(p.get("pc-1"))
    }
}
