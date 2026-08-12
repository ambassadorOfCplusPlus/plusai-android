package ru.aiagent.app.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Стабильный identity-ключ устройства (телефон-сторона): приватный ключ персистится, публичный ключ
 * ПЕРЕЖИВАЕТ «рестарт». Персистенс инъектируется, тест герметичен на JVM.
 */
class DeviceIdentityTest {
    @Test
    fun `публичный ключ стабилен между загрузками`() {
        val cell = arrayOfNulls<String>(1)
        val pub1 = DeviceIdentity.loadOrCreate({ cell[0] }, { cell[0] = it }).publicKeyBase64()
        val pub2 = DeviceIdentity.loadOrCreate({ cell[0] }, { cell[0] = it }).publicKeyBase64()
        assertEquals(pub1, pub2)
    }

    @Test
    fun `разные хранилища разные ключи`() {
        val c1 = arrayOfNulls<String>(1)
        val c2 = arrayOfNulls<String>(1)
        val a = DeviceIdentity.loadOrCreate({ c1[0] }, { c1[0] = it }).publicKeyBase64()
        val b = DeviceIdentity.loadOrCreate({ c2[0] }, { c2[0] = it }).publicKeyBase64()
        assertNotEquals(a, b)
    }

    @Test
    fun `выведенный ключ совпадает после перезагрузки`() {
        val cell = arrayOfNulls<String>(1)
        val peer = E2ECrypto()
        val k1 = DeviceIdentity.loadOrCreate({ cell[0] }, { cell[0] = it }).deriveKey(peer.publicKeyBase64())
        val k2 = DeviceIdentity.loadOrCreate({ cell[0] }, { cell[0] = it }).deriveKey(peer.publicKeyBase64())
        assertArrayEquals(k1, k2)
    }
}
