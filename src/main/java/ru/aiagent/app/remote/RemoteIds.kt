package ru.aiagent.app.remote

import android.content.Context
import java.util.UUID

/**
 * Стабильные идентификаторы устройства в E2E-релэй. РАЗНЫЕ роли одного телефона получают РАЗНЫЕ id,
 * иначе их poll-очереди на сервере пересекаются и роли «съедают» сообщения друг друга:
 *  - [phoneBase] — контроллер (экран «ПК»: телефон управляет ПК), анонсит ПУСТОЕ имя;
 *  - [phoneHost] — хост (сервис: телефоном управляет ПК), анонсит имя «Телефон».
 */
object RemoteIds {
    fun phoneBase(context: Context): String {
        val sp = context.getSharedPreferences("plusai_remote", Context.MODE_PRIVATE)
        var id = sp.getString("phone_id", null)
        if (id.isNullOrBlank()) {
            id = "phone-" + UUID.randomUUID().toString().replace("-", "").take(12)
            sp.edit().putString("phone_id", id).apply()
        }
        return id
    }

    fun phoneHost(context: Context): String = phoneBase(context) + "-host"
}
