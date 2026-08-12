package ru.aiagent.app.integrations

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Логика «401 → обновить токен → повторить», общая для всех OAuth-коннекторов. Раньше она была
 * расписана руками у каждого вызова (22 места на 4 коннектора) и не покрывалась ничем.
 */
class OAuthRetryTest {

    @Test
    fun `успешный запрос не трогает refresh`() = runBlocking {
        var calls = 0
        var refreshes = 0
        val r = retryOnUnauthorized(
            token = "t1",
            isUnauthorized = { it != 200 },
            refresh = { refreshes++; "t2" },
            block = { calls++; 200 },
        )
        assertEquals(200, r)
        assertEquals("ровно один запрос", 1, calls)
        assertEquals("refresh не вызывался", 0, refreshes)
    }

    @Test
    fun `401 обновляет токен и повторяет запрос свежим токеном`() = runBlocking {
        val used = mutableListOf<String>()
        val r = retryOnUnauthorized(
            token = "stale",
            isUnauthorized = { it != 200 },
            refresh = { "fresh" },
            block = { token -> used += token; if (token == "fresh") 200 else 401 },
        )
        assertEquals(200, r)
        assertEquals("второй запрос ушёл со свежим токеном", listOf("stale", "fresh"), used)
    }

    @Test
    fun `неудачный refresh отдаёт исходную ошибку и не повторяет запрос`() = runBlocking {
        var calls = 0
        val r = retryOnUnauthorized(
            token = "stale",
            isUnauthorized = { it != 200 },
            refresh = { null }, // refresh отсутствует или обмен не удался
            block = { calls++; 401 },
        )
        assertEquals("отдаём исходный ответ, а не выдумываем свой", 401, r)
        assertEquals("повтора не было", 1, calls)
    }

    @Test
    fun `повтор ровно один — отозванный токен не уводит в цикл`() = runBlocking {
        var calls = 0
        var refreshes = 0
        val r = retryOnUnauthorized(
            token = "stale",
            isUnauthorized = { it != 200 },
            refresh = { refreshes++; "fresh" },
            block = { calls++; 401 }, // и после обновления 401 — токен отозван
        )
        assertEquals(401, r)
        assertEquals("ровно два запроса, не цикл", 2, calls)
        assertEquals("обновлялись один раз", 1, refreshes)
    }

    @Test
    fun `Result-вариант — не авторизационная ошибка не приводит к повтору`() = runBlocking {
        var calls = 0
        val r = retryOnUnauthorized<Result<String>>(
            token = "t1",
            isUnauthorized = { looksLikeAuthError(it.exceptionOrNull()?.message) },
            refresh = { "t2" },
            block = { calls++; Result.failure(RuntimeException("timeout")) },
        )
        assertTrue(r.isFailure)
        assertEquals("на сетевой ошибке refresh не жжём", 1, calls)
    }

    // --- распознавание авторизационной ошибки (клиенты, сообщающие об отказе исключением) ---

    @Test
    fun `авторизационные ошибки распознаются`() {
        assertTrue(looksLikeAuthError("HTTP 401: Unauthorized"))
        assertTrue(looksLikeAuthError("UnauthorizedError"))
        assertTrue(looksLikeAuthError("token expired"))
        assertTrue(looksLikeAuthError("auth failed"))
    }

    @Test
    fun `сетевые и прочие ошибки не жгут refresh`() {
        assertFalse(looksLikeAuthError("timeout"))
        assertFalse(looksLikeAuthError("HTTP 404: not found"))
        assertFalse(looksLikeAuthError("HTTP 500"))
        assertFalse(looksLikeAuthError(null))
        assertFalse(looksLikeAuthError(""))
    }
}
