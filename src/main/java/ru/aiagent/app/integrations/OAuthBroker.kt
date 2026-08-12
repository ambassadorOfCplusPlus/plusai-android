package ru.aiagent.app.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.app.cloud.Account
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Единый OAuth-брокер всех коннекторов (§7): сервер меняет код на токен (client_secret живёт только
 * у него), приложение получает токен и дальше ходит к провайдеру НАПРЯМУЮ — почта по IMAP, диски по
 * своим REST API. Данные пользователя через наш сервер не идут.
 *
 * Два сценария завершения согласия: callback ([take] — провайдер редиректит на сервер, приложение
 * поллит по state) и ручной код ([exchange] — пользователь копирует код со страницы провайдера;
 * так у Яндекса, где redirect_uri фиксированный).
 *
 * Хранение полученных токенов и их обновление — в [OAuthStore] (см. `OAuthAccounts.kt`).
 */
object OAuthBroker {

    data class StartInfo(val authUrl: String, val manual: Boolean, val imapHost: String)
    data class Token(val email: String, val access: String, val refresh: String, val imapHost: String)

    /** Маркер «токен ещё не готов» (пользователь не завершил согласие) — по нему UI поллит take(). */
    const val NOT_READY = "not_ready"

    private fun base(context: android.content.Context): String =
        (Account.current(context)?.url ?: Account.DEFAULT_URL).trimEnd('/')

    // OAuth-эндпоинты сервера требуют залогиненного пользователя (userOf(r) != "") — привязка flow к
    // аккаунту, защита от кражи state. Значит КАЖДЫЙ запрос обязан нести Bearer-токен аккаунта, иначе
    // сервер отвечает «войдите в аккаунт» даже при выполненном входе (это был баг: токен не слался).
    private fun requireToken(context: android.content.Context): String =
        Account.current(context)?.token?.takeIf { it.isNotBlank() }
            ?: error("войдите в аккаунт Plus AI")

    /** Шаг 1: получить ссылку согласия. */
    suspend fun start(context: android.content.Context, provider: String): Result<StartInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = requireToken(context)
                val (code, body) = get("${base(context)}/v1/oauth/$provider/start", token)
                if (code !in 200..299) error(errorOf(body))
                val j = JSONObject(body)
                StartInfo(j.getString("auth_url"), j.optBoolean("manual", false), j.optString("imap_host"))
            }
        }

    /** Шаг 2 (ручной): обменять код со страницы провайдера на токен. */
    suspend fun exchange(context: android.content.Context, provider: String, code: String): Result<Token> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = requireToken(context)
                val (c, body) = post("${base(context)}/v1/oauth/$provider/exchange", JSONObject().put("code", code.trim()), token)
                if (c !in 200..299) error(errorOf(body))
                tokenOf(body)
            }
        }

    /**
     * Шаг 2 (callback-flow, не ручной): забрать токен, который сервер получил по redirect от провайдера.
     * Пользователь дал согласие в браузере → провайдер редиректнул на сервер → сервер сохранил токен
     * под [state]. Приложение поллит этот эндпоинт: сервер отдаёт 200 с токеном, либо 202/404 (ErrNotReady),
     * пока согласие не завершено. В последнем случае → Result.failure с распознаваемым маркером [NOT_READY],
     * чтобы UI мог продолжать поллинг, а не показывать ошибку.
     */
    suspend fun take(context: android.content.Context, provider: String, state: String): Result<Token> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = requireToken(context)
                val (code, body) = get("${base(context)}/v1/oauth/$provider/token?state=${enc(state)}", token)
                if (code == 202 || code == 404 || body.contains(NOT_READY, ignoreCase = true) ||
                    body.contains("ErrNotReady", ignoreCase = true)) error(NOT_READY)
                if (code !in 200..299) error(errorOf(body))
                tokenOf(body)
            }
        }

    /** Обновить истёкший access-токен по refresh (когда IMAP вернул ошибку авторизации). */
    suspend fun refresh(context: android.content.Context, provider: String, refreshToken: String): Result<Token> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = requireToken(context)
                val (c, body) = post("${base(context)}/v1/oauth/$provider/refresh", JSONObject().put("refresh_token", refreshToken), token)
                if (c !in 200..299) error(errorOf(body))
                tokenOf(body)
            }
        }

    private fun tokenOf(body: String): Token {
        val j = JSONObject(body)
        return Token(j.optString("email"), j.getString("access_token"), j.optString("refresh_token"), j.optString("imap_host"))
    }

    private fun get(url: String, token: String? = null): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            return code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    private fun post(url: String, body: JSONObject, token: String? = null): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10000; readTimeout = 20000
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            return code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun errorOf(body: String): String =
        runCatching { JSONObject(body).optString("error").ifBlank { "ошибка сервера" } }.getOrDefault("ошибка сети")
}
