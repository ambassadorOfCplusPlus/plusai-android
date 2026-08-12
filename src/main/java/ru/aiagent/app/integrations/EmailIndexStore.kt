package ru.aiagent.app.integrations

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.aiagent.app.rag.RagEngine
import ru.aiagent.app.security.CryptoBox
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Локальный ЗАШИФРОВАННЫЙ индекс текстов вложений почты (фоновая индексация). Текст лежит
 * под AES-GCM (ключ в Keystore, [CryptoBox]); вектор — открытым (для быстрого поиска без
 * расшифровки; из эмбеддинга восстановить документ нельзя). Всё на устройстве (§7 — не покидает).
 *
 * Тиринг по свежести письма (email_uid): свежие — полный текст; дальше [FULL_KEEP] писем —
 * СЖАТИЕ (gzip внутри шифра); дальше [HARD_KEEP] — УДАЛЕНИЕ (и из поиска).
 */
class EmailIndexStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "email_index.db", null, 1) {

    private val appContext = context.applicationContext

    // Сверка эмбеддера: имя gguf текущей модели vs записанное в meta. Смена модели (иная размерность ИЛИ
    // иное имя) делает старые векторы несопоставимыми — cosineSim вернул бы мусор/равные баллы и поиск
    // отдал бы НЕ ТЕ письма. При рассогласовании чистим индекс (перестроит фоновый индексатор). Возвращает
    // true, если индекс совместим с текущей моделью (можно искать/добавлять).
    private fun ensureEmbedder(): Boolean {
        val cur = RagEngine.embeddingModelFile(appContext)?.name ?: return true // модели нет — не трогаем
        val stored = readableDatabase.rawQuery("SELECT v FROM meta WHERE k='embedder'", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (stored == cur) return true
        if (stored != null) clearAll() // модель сменилась → старые векторы несопоставимы
        writableDatabase.insertWithOnConflict("meta", null,
            ContentValues().apply { put("k", "embedder"); put("v", cur) }, SQLiteDatabase.CONFLICT_REPLACE)
        return stored == null // была пустая (первая индексация текущей моделью) — совместима; иначе только что стёрли
    }

    // WAL: фоновый индексатор (EmailIndexWorker, отдельное подключение) может держать запись секундами,
    // а search_mail_archive читает из СВОЕГО подключения. Без WAL читатель ловит SQLITE_BUSY → краш тула.
    init { setWriteAheadLoggingEnabled(true) }

    data class Hit(val emailUid: Long, val filename: String, val subject: String, val sender: String, val text: String, val score: Float)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE att(
                email_uid INTEGER, filename TEXT, subject TEXT, sender TEXT,
                vec BLOB, blob BLOB, comp INTEGER, chars INTEGER,
                PRIMARY KEY(email_uid, filename))""",
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    // Индекс — ПЕРЕСТРАИВАЕМЫЙ кэш (сами письма/вложения живут на IMAP-сервере, не теряются): при смене
    // схемы дешевле пересобрать, чем писать миграцию зашифрованных blob. Фоновый индексатор наполнит заново.
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("DROP TABLE IF EXISTS att"); db.execSQL("DROP TABLE IF EXISTS meta"); onCreate(db)
    }

    // По умолчанию SQLiteOpenHelper при откате версии БРОСАЕТ исключение (краш тула). Кэш перестраиваем — тот же путь.
    override fun onDowngrade(db: SQLiteDatabase, o: Int, n: Int) = onUpgrade(db, o, n)

    fun has(uid: Long, filename: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM att WHERE email_uid=? AND filename=? LIMIT 1",
            arrayOf(uid.toString(), filename)).use { it.moveToFirst() }

    fun add(uid: Long, filename: String, subject: String, sender: String, vector: FloatArray, text: String) {
        ensureEmbedder() // смена модели → стереть несопоставимый индекс перед записью новыми векторами
        val enc = CryptoBox.encrypt(text.toByteArray(Charsets.UTF_8)) // свежие — без сжатия
        writableDatabase.insertWithOnConflict("att", null, ContentValues().apply {
            put("email_uid", uid); put("filename", filename); put("subject", subject); put("sender", sender)
            put("vec", floatsToBytes(vector)); put("blob", enc); put("comp", 0); put("chars", text.length)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Поиск по вектору запроса: косинус по всем вложениям, top-K с расшифрованным текстом. */
    fun search(queryVec: FloatArray, topK: Int): List<Hit> {
        if (queryVec.isEmpty()) return emptyList()
        if (!ensureEmbedder()) return emptyList() // модель сменилась → индекс только что стёрт, мусор не отдаём
        val rows = ArrayList<Triple<Long, String, Float>>() // uid, filename, score
        val meta = HashMap<Pair<Long, String>, Pair<String, String>>()
        readableDatabase.rawQuery("SELECT email_uid,filename,subject,sender,vec FROM att", null).use { c ->
            while (c.moveToNext()) {
                val uid = c.getLong(0); val fn = c.getString(1)
                val score = RagEngine.cosineSim(queryVec, bytesToFloats(c.getBlob(4)))
                rows += Triple(uid, fn, score)
                meta[uid to fn] = c.getString(2) to c.getString(3)
            }
        }
        return rows.sortedByDescending { it.third }.take(topK.coerceAtLeast(1)).mapNotNull { (uid, fn, score) ->
            val text = readText(uid, fn) ?: return@mapNotNull null
            val (subj, sender) = meta[uid to fn] ?: ("" to "")
            Hit(uid, fn, subj, sender, text, score)
        }
    }

    private fun readText(uid: Long, filename: String): String? =
        readableDatabase.rawQuery("SELECT blob,comp FROM att WHERE email_uid=? AND filename=?",
            arrayOf(uid.toString(), filename)).use { c ->
            if (!c.moveToFirst()) return null
            val dec = runCatching { CryptoBox.decrypt(c.getBlob(0)) }.getOrNull() ?: return null
            val bytes = if (c.getInt(1) == 1) gunzip(dec) else dec
            String(bytes, Charsets.UTF_8)
        }

    /** Тиринг: >[FULL_KEEP] писем назад — сжать; >[HARD_KEEP] — удалить. */
    fun tier() {
        val uids = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT DISTINCT email_uid FROM att ORDER BY email_uid DESC", null).use { c ->
            while (c.moveToNext()) uids += c.getLong(0)
        }
        val mid = uids.drop(FULL_KEEP).take(HARD_KEEP - FULL_KEEP) // сжать
        val old = uids.drop(HARD_KEEP)                             // удалить
        if (old.isNotEmpty()) {
            writableDatabase.execSQL("DELETE FROM att WHERE email_uid IN (${old.joinToString(",")})")
        }
        for (uid in mid) compressEmail(uid)
    }

    private fun compressEmail(uid: Long) {
        val db = writableDatabase
        db.rawQuery("SELECT filename,blob FROM att WHERE email_uid=? AND comp=0", arrayOf(uid.toString())).use { c ->
            while (c.moveToNext()) {
                val fn = c.getString(0)
                val plain = runCatching { CryptoBox.decrypt(c.getBlob(1)) }.getOrNull() ?: continue
                val recompressed = CryptoBox.encrypt(gzip(plain))
                db.update("att", ContentValues().apply { put("blob", recompressed); put("comp", 1) },
                    "email_uid=? AND filename=?", arrayOf(uid.toString(), fn))
            }
        }
    }

    fun lastUid(): Long =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k='last_uid'", null).use {
            if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L
        }

    fun setLastUid(uid: Long) {
        writableDatabase.insertWithOnConflict("meta", null,
            ContentValues().apply { put("k", "last_uid"); put("v", uid.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM att"); writableDatabase.execSQL("DELETE FROM meta")
    }

    private companion object {
        const val FULL_KEEP = 40  // писем назад держим полный текст
        const val HARD_KEEP = 80  // дальше — удаляем
        fun floatsToBytes(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { bb.putFloat(it) }; return bb.array()
        }
        fun bytesToFloats(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.float }
        }
        fun gzip(data: ByteArray): ByteArray =
            ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(data) } }.toByteArray()
        fun gunzip(data: ByteArray): ByteArray =
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
}
