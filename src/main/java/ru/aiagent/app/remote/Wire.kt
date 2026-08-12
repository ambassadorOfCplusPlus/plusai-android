package ru.aiagent.app.remote

import org.json.JSONObject

/**
 * Формат прикладного сообщения ВНУТРИ E2E-шифртекста (сервер его не видит). JSON `{t,m}`:
 *  - `cmd`   — команда контроллера хосту;
 *  - `step`  — промежуточный шаг агента (живой прогресс);
 *  - `final` — финальный ответ;
 *  - `err`   — ошибка.
 * Старый нетипизированный текст (без JSON) трактуем как `final` — обратная совместимость.
 */
object Wire {
    // Типы сообщений. need-verify: хост → контроллеру «сначала сверь ключи (SAS)»; verify-ack:
    // контроллер → хосту после успешной QR/SAS-сверки (взаимная верификация по доверенному каналу).
    const val CMD = "cmd"
    const val STEP = "step"
    const val FINAL = "final"
    const val ERR = "err"
    const val NEED_VERIFY = "need-verify"
    const val VERIFY_ACK = "verify-ack"

    fun make(type: String, text: String): String =
        JSONObject().put("t", type).put("m", text).toString()

    /** Разобрать полезную нагрузку в пару (тип, текст). Не-JSON → ("final", исходный текст). */
    fun parse(payload: String): Pair<String, String> = try {
        val o = JSONObject(payload)
        o.optString("t", "final") to o.optString("m", payload)
    } catch (e: Exception) {
        "final" to payload
    }
}
