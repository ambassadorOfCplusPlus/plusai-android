package ru.aiagent.app.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Запись о E2E-пире: идентификатор, запинненный публичный ключ, статус сверки SAS. */
data class PeerRecord(val id: String, val pubkey: String, val verified: Boolean)

/**
 * Персистентный стор доверенных пиров связки «телефон↔ПК» (зеркало десктопного `VerifiedPeers`).
 *
 * ЗАЧЕМ. TOFU-пиннинг в памяти держался только сессию (ключи и так эфемерные — см. DeviceIdentity).
 * Со стабильным identity-ключом пиннинг и статус сверки SAS должны переживать рестарт: первый ключ
 * пира фиксируем, СМЕНУ отклоняем, «verified» пишем после внеполосной сверки SAS и храним постоянно.
 *
 * Персистенс инъектируется ([load]/[save] сериализованного JSON), чтобы юнит-тест был герметичным;
 * прод-обёртка [fromPrefs] хранит в SharedPreferences.
 */
class VerifiedPeers(
    private val load: () -> String?,
    private val save: (String) -> Unit,
) {
    private val items = linkedMapOf<String, PeerRecord>()

    init {
        val raw = load()
        if (!raw.isNullOrBlank()) runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isNotBlank()) items[id] =
                    PeerRecord(id, o.optString("pubkey"), o.optBoolean("verified"))
            }
        }
    }

    @Synchronized fun get(id: String): PeerRecord? = items[id]

    @Synchronized fun all(): List<PeerRecord> = items.values.toList()

    /**
     * Зафиксировать ключ пира при первом контакте (TOFU). true — ключ новый или совпал с известным;
     * false — у известного пира ключ ДРУГОЙ (возможна MITM-подмена реле), запись НЕ меняется.
     */
    @Synchronized fun pin(id: String, pubkey: String): Boolean {
        if (id.isBlank() || pubkey.isBlank()) return false
        val existing = items[id]
        if (!TofuPin.accept(existing?.pubkey, pubkey)) return false // смена ключа известного пира → отказ
        if (existing == null) {
            // Новый id с уже доверенным ключом наследует verified: одно устройство под несколькими
            // ролями (host/controller) с ОДНИМ ключом; подделать ключ без приватной части нельзя.
            val inherited = items.values.any { it.pubkey == pubkey && it.verified }
            items[id] = PeerRecord(id, pubkey, verified = inherited)
            persist()
        }
        return true
    }

    /**
     * Пометить сверенным — только если запинненный ключ совпадает. Помечает ВСЕ записи с этим ключом
     * (одно устройство под несколькими id: host + controller) — одна сверка на устройство.
     */
    @Synchronized fun markVerified(id: String, pubkey: String) {
        val target = items[id] ?: return
        if (target.pubkey != pubkey) return
        var changed = false
        for ((k, r) in items.toList()) if (r.pubkey == pubkey && !r.verified) {
            items[k] = r.copy(verified = true); changed = true
        }
        if (changed) persist()
    }

    /** Забыть пира (пере-спаривание после легальной переустановки устройства). */
    @Synchronized fun forget(id: String): Boolean {
        val removed = items.remove(id) != null
        if (removed) persist()
        return removed
    }

    private fun persist() {
        val arr = JSONArray()
        for (r in items.values) arr.put(
            JSONObject().put("id", r.id).put("pubkey", r.pubkey).put("verified", r.verified),
        )
        save(arr.toString())
    }

    companion object {
        /** Прод-хранилище: SharedPreferences `plusai_remote`, ключ `peers`. */
        fun fromPrefs(context: Context): VerifiedPeers {
            val sp = context.getSharedPreferences("plusai_remote", Context.MODE_PRIVATE)
            return VerifiedPeers(
                load = { sp.getString("peers", null) },
                save = { sp.edit().putString("peers", it).apply() },
            )
        }
    }
}
