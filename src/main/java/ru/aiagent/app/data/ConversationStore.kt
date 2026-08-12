package ru.aiagent.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Сообщение диалога для хранения. kind: user|assistant|tool. */
data class StoredMsg(val kind: String, val text: String)

/** Полный диалог. */
data class Conversation(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val messages: MutableList<StoredMsg> = mutableListOf(),
    // id незавершённой серверной генерации (отвязанный досыл): при открытии диалога телефон возобновит
    // опрос и дозаберёт ответ, даже если приложение было закрыто во время генерации. null — нет активной.
    var pendingSession: String? = null,
)

/** Краткая карточка для списка истории. */
data class ConversationMeta(val id: String, val title: String, val updatedAt: Long, val count: Int)

/**
 * Хранилище диалогов (JSON-файлы в filesDir/conversations). Полностью на устройстве.
 * Мелкие корпуса → JSON достаточно; при росте — SQLite без смены API.
 */
class ConversationStore(context: Context) {
    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }

    fun list(): List<ConversationMeta> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    ConversationMeta(
                        id = o.getString("id"),
                        title = o.optString("title", "Без названия"),
                        updatedAt = o.optLong("updatedAt", 0),
                        count = o.optJSONArray("messages")?.length() ?: 0,
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Conversation? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val msgs = mutableListOf<StoredMsg>()
            val arr = o.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                msgs += StoredMsg(m.getString("kind"), m.getString("text"))
            }
            Conversation(
                o.getString("id"), o.optString("title"), o.optLong("updatedAt"), msgs,
                o.optString("pendingSession").takeIf { it.isNotBlank() && it != "null" }, // "null" — от старых записей
            )
        }.getOrNull()
    }

    fun save(conv: Conversation) {
        if (conv.messages.isEmpty()) return
        val arr = JSONArray()
        conv.messages.forEach { m ->
            arr.put(JSONObject().put("kind", m.kind).put("text", m.text))
        }
        val o = JSONObject()
            .put("id", conv.id)
            .put("title", conv.title.ifBlank { autoTitle(conv) })
            .put("updatedAt", conv.updatedAt)
            .put("messages", arr)
        // Ключ добавляем ТОЛЬКО если сессия есть. JSONObject.NULL нельзя: на Android org.json optString
        // над NULL возвращает строку "null" (не ""), и load() принял бы её за id → ложный resume.
        conv.pendingSession?.let { o.put("pendingSession", it) }
        // Атомарно: пишем во временный файл и переименовываем — обрыв процесса
        // не оставит усечённый JSON (иначе диалог молча пропадёт при чтении).
        // tmp — уникальный (иначе два параллельных writer'а одного id пишут в один файл
        // и портят JSON); при неудаче переименования НЕ удаляем оригинал (иначе потеря истории).
        val target = File(dir, "${conv.id}.json")
        // Уникальное имя temp: два конкурентных save() одного id не должны делить .tmp (иначе — битый JSON).
        val tmp = File(dir, "${conv.id}.${System.nanoTime()}.${Thread.currentThread().id}.tmp")
        runCatching {
            tmp.writeText(o.toString())
            if (!tmp.renameTo(target)) {
                // На части ФС rename не перезаписывает существующий: удаляем цель и пробуем снова.
                // НЕ пишем «на месте» (writeText поверх target) — обрыв оставил бы усечённый JSON.
                target.delete()
                if (!tmp.renameTo(target)) error("не удалось сохранить диалог ${conv.id}")
            }
        }.onFailure { tmp.delete() }
    }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    /** Заголовок из первого сообщения пользователя. */
    fun autoTitle(conv: Conversation): String =
        conv.messages.firstOrNull { it.kind == "user" }?.text?.take(40)?.ifBlank { "Диалог" } ?: "Диалог"

    companion object {
        /** Новый id: счётчик из prefs + случайный суффикс (иначе сброс счётчика/восстановление
         *  бэкапа рассинхронно с файлами → coll., новый чат затирает conv_N.json). */
        fun newId(context: Context): String {
            val prefs = context.getSharedPreferences("plusai_conv", Context.MODE_PRIVATE)
            val n = prefs.getInt("seq", 0) + 1
            prefs.edit().putInt("seq", n).apply()
            return "conv_${n}_" + java.util.UUID.randomUUID().toString().take(8)
        }
    }
}
