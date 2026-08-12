package ru.aiagent.app.integrations.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * End-to-end проверка MCP-клиента против ЛОКАЛЬНОГО mock-сервера (raw ServerSocket — com.sun.httpserver нет
 * на classpath android-unit-теста): JSON-RPC initialize → tools/list → tools/call, разбор application/json И
 * SSE. Закрывает п.15 «клиент ни разу не гонялся». http://127.0.0.1 разрешён isSafeUrl.
 */
class McpClientTest {

    /** Мини HTTP/1.1-сервер на raw-сокете: по телу запроса отдаёт (Content-Type, тело) от handler. */
    private class MockMcp(private val handler: (body: String, id: String) -> Pair<String, String>) {
        private val ss = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${ss.localPort}/mcp"

        init {
            Thread {
                try {
                    while (!ss.isClosed) {
                        val sock = ss.accept()
                        Thread {
                            try {
                                sock.use { s ->
                                    val ins = s.getInputStream()
                                    val header = readHeaders(ins) ?: return@use
                                    val clen = Regex("(?i)content-length:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                                    val body = ByteArray(clen)
                                    var r = 0
                                    while (r < clen) { val n = ins.read(body, r, clen - r); if (n < 0) break; r += n }
                                    val bodyStr = String(body, Charsets.UTF_8)
                                    val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(bodyStr)?.groupValues?.get(1) ?: "0"
                                    val (ct, resp) = handler(bodyStr, id)
                                    val rb = resp.toByteArray()
                                    val out = s.getOutputStream()
                                    out.write(
                                        ("HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nMcp-Session-Id: s1\r\n" +
                                            "Content-Length: ${rb.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                                    )
                                    out.write(rb)
                                    out.flush()
                                }
                            } catch (_: Throwable) {}
                        }.also { it.isDaemon = true; it.start() }
                    }
                } catch (_: Throwable) {}
            }.also { it.isDaemon = true; it.start() }
        }

        private fun readHeaders(ins: InputStream): String? {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = ins.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                sb.append(b.toChar())
            }
            return sb.toString()
        }

        fun close() = ss.close()
    }

    @Test fun discoverAndCallJson() = runBlocking {
        val mock = MockMcp { body, id ->
            when {
                body.contains("\"initialize\"") -> "application/json" to """{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{}}}"""
                body.contains("\"tools/list\"") -> "application/json" to
                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"echo","description":"echo text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}}"""
                body.contains("\"tools/call\"") -> "application/json" to """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"HELLO"}]}}"""
                else -> "application/json" to "" // notifications/initialized
            }
        }
        try {
            val srv = McpServer("test-json", mock.url, "")
            val tools = McpClient.discover(srv).getOrThrow()
            assertEquals(1, tools.size)
            assertEquals("echo", tools[0].name)
            assertTrue("схема — реальная inputSchema", tools[0].schema.contains("properties"))
            val out = McpClient.call(srv, "echo", """{"text":"hi"}""").getOrThrow()
            assertEquals("HELLO", out)
        } finally { mock.close() }
    }

    @Test fun callSseMultilineParsed() = runBlocking {
        val mock = MockMcp { body, id ->
            when {
                body.contains("\"initialize\"") -> "application/json" to """{"jsonrpc":"2.0","id":$id,"result":{}}"""
                body.contains("\"tools/call\"") -> "text/event-stream" to
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":\ndata: {\"content\":[{\"type\":\"text\",\"text\":\"SSE_OK\"}]}}\n\n"
                else -> "application/json" to ""
            }
        }
        try {
            val out = McpClient.call(McpServer("test-sse", mock.url, ""), "x", "{}").getOrThrow()
            assertEquals("SSE_OK", out)
        } finally { mock.close() }
    }

    @Test fun serverErrorSurfaces() = runBlocking {
        val mock = MockMcp { body, id ->
            when {
                body.contains("\"initialize\"") -> "application/json" to """{"jsonrpc":"2.0","id":$id,"result":{}}"""
                body.contains("\"tools/call\"") -> "application/json" to """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no such tool"}}"""
                else -> "application/json" to ""
            }
        }
        try {
            val r = McpClient.call(McpServer("test-err", mock.url, ""), "nope", "{}")
            assertTrue("ошибка сервера → Failure", r.isFailure)
            assertTrue(r.exceptionOrNull()?.message?.contains("no such tool") == true)
        } finally { mock.close() }
    }

    @Test fun httpsGuard() = runBlocking {
        assertTrue(McpClient.discover(McpServer("bad", "http://example.com/mcp", "tok")).isFailure)
        assertFalse(McpClient.isSafeUrl("http://localhost.evil.com/mcp"))
        assertTrue(McpClient.isSafeUrl("https://any.host/mcp"))
        assertTrue(McpClient.isSafeUrl("http://127.0.0.1:8080/mcp"))
    }

    @Test fun privateHostGuardDoesNotEatOrdinaryDomains() {
        // ULA-префиксы fc/fd относятся к IPv6-литералам. Подстрочная проверка резала обычные домены.
        assertFalse("fcbank.com не приватный", McpClient.isPrivateHost("fcbank.com"))
        assertFalse("fdny.gov не приватный", McpClient.isPrivateHost("fdny.gov"))
        assertTrue("ULA-литерал приватный", McpClient.isPrivateHost("fd00::1"))
        assertTrue("loopback v6", McpClient.isPrivateHost("::1"))
        assertTrue("link-local v4", McpClient.isPrivateHost("169.254.169.254"))
        assertFalse("обычный домен", McpClient.isPrivateHost("example.com"))
    }
}
