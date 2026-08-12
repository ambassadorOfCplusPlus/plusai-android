package ru.aiagent.app.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.aiagent.app.cloud.Account

class RemoteAiClient(private val context: Context) {

    data class Response(val text: String, val isError: Boolean = false)

    companion object {
        private const val TAG = "RemoteAiClient"
        private const val TIMEOUT_SEC = 90L
    }

    suspend fun ask(prompt: String): Response {
        val session = Account.current(context) ?: return err("не выполнен вход")

        val relay = RelayClient(session.url.trimEnd('/'), session.token)
        val crypto = DeviceIdentity.fromPrefs(context)
        val peers = VerifiedPeers.fromPrefs(context)
        val myId = RemoteIds.phoneBase(context) + "-controller"

        // Ищем ПК среди верифицированных пиров (не телефон, verified)
        val pc = peers.all().firstOrNull { p ->
            p.verified && p.id != RemoteIds.phoneHost(context) && p.id != myId
        }
        if (pc == null) {
            // Пробуем announce — может ПК только что появился
            val announced = runCatching {
                withContext(Dispatchers.IO) {
                    relay.announce(myId, "", crypto.publicKeyBase64())
                }
            }.getOrDefault(emptyList())
            val freshPc = announced.firstOrNull { it.id != myId && it.id != RemoteIds.phoneHost(context) }
            if (freshPc != null) {
                peers.pin(freshPc.id, freshPc.pubkey)
                Log.i(TAG, "Найден ПК через announce: ${freshPc.id}")
                return askTo(freshPc.id, freshPc.pubkey, prompt, relay, crypto, myId)
            }
            return err("ПК не найден — запустите десктоп и включите ПК-режим")
        }

        Log.i(TAG, "Отправка на ПК ${pc.id} (verified=${pc.verified})")
        return askTo(pc.id, pc.pubkey, prompt, relay, crypto, myId)
    }

    private suspend fun askTo(
        pcId: String, pcPubkey: String, prompt: String,
        relay: RelayClient, crypto: E2ECrypto, myId: String,
    ): Response {
        val sharedKey = crypto.deriveKey(pcPubkey)

        // Отправляем CMD
        val payload = Wire.make(Wire.CMD, prompt)
        val encPayload = E2ECrypto.encrypt(sharedKey, payload)
        try {
            withContext(Dispatchers.IO) { relay.send(myId, pcId, encPayload) }
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
        }

        // Ждём ответ
        val deadline = System.currentTimeMillis() / 1000 + TIMEOUT_SEC
        var cursor = System.currentTimeMillis() / 1000 - 30
        val response = StringBuilder()
        var stepCount = 0

        while (System.currentTimeMillis() / 1000 < deadline) {
            val msgs = try {
                withContext(Dispatchers.IO) { relay.poll(myId, cursor) }
            } catch (e: Exception) {
                delay(2000)
                continue
            }
            if (msgs.isNotEmpty()) {
                for (msg in msgs) {
                    cursor = maxOf(cursor, msg.seq + 1)
                    if (msg.from != pcId) continue
                    val plain = runCatching { E2ECrypto.decrypt(sharedKey, msg.payload) }.getOrNull() ?: continue
                    val (type, text) = Wire.parse(plain)
                    when (type) {
                        Wire.FINAL -> {
                            Log.i(TAG, "Ответ от ПК ($stepCount шагов, ${text.length} символов)")
                            return Response(response.append(text).toString().ifBlank { text })
                        }
                        Wire.ERR -> {
                            Log.w(TAG, "Ошибка от ПК: $text")
                            return Response(text, true)
                        }
                        Wire.STEP -> {
                            stepCount++
                            if (response.length < 4000) response.append(text).append("\n")
                        }
                    }
                }
            }
            delay(1000)
        }
        val final = response.toString()
        return if (final.isNotBlank()) Response(final)
        else err("ПК не ответил (таймаут ${TIMEOUT_SEC}с)")
    }

    private fun err(msg: String) = Response(msg, true)
}
