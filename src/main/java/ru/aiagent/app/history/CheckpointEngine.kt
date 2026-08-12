package ru.aiagent.app.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Ядро автоотката правок ИИ (FEAT-CHECKPOINTS) — «принудительный git» без git.
 *
 * Чистый JVM-класс: крипто и часы инжектятся, поэтому тестируется без Android/Keystore. Обвязка
 * [CheckpointStore] подставляет [ru.aiagent.app.security.CryptoBox] и `filesDir`.
 *
 * Модель хранения — content-addressed: каждый файл кладётся как blob по SHA-256 своего содержимого
 * (`encrypt(gzip(bytes))`). Неизменные между чекпоинтами файлы делят один blob (дедуп — рычаг №1
 * объёма). Чекпоинт = снимок workspace после ОДНОГО хода агента: список (путь → sha → размер).
 * Откат к чекпоинту C = восстановить его файлы и удалить появившиеся после. Хранилище шифруется
 * целиком (§0/§7: история правок — это файлы пользователя).
 *
 * v1: сжатие — gzip (single-tier). Дельта-против-предыдущей-версии и двухтирный пережим — следующая
 * оптимизация объёма (сейчас дедуп+gzip уже дают «неизменное = 0 байт», а изменённое = gzip-размер).
 */
class CheckpointEngine(
    private val historyDir: File,
    private val workspace: File,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
    private val config: Config,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Config(
        val budgetBytes: Long,                 // потолок размера хранилища
        val retentionMs: Long,                 // окно хранения (скользящее)
        val minKeep: Int = 10,                 // сколько последних чекпоинтов держим ВСЕГДА
        val maxFileBytes: Long = 20L * 1024 * 1024,
        val excludeExt: Set<String> = setOf("gguf", "task", "litertlm", "onnx", "bin"),
        val maxChain: Int = 20,                // макс. длина цепочки дельт (дальше — полный blob)
    )

    data class FileEntry(val path: String, val sha: String, val size: Long)
    data class Checkpoint(val id: String, val ts: Long, val reason: String, val files: List<FileEntry>)
    /** Мета для UI: сколько файлов изменил/добавил/удалил этот ход относительно предыдущего. */
    data class CheckpointMeta(
        val id: String, val ts: Long, val reason: String,
        val fileCount: Int, val changed: Int, val added: Int, val removed: Int,
    )
    data class RestoreResult(val ok: Boolean, val message: String, val restored: Int = 0, val deleted: Int = 0)

    private val blobsDir = File(historyDir, "blobs")
    private val journalFile = File(historyDir, "journal.bin")

    // --- Снимок после хода агента -----------------------------------------------------------

    /** Снять чекпоинт текущего состояния workspace. Возвращает новый чекпоинт или null, если ничего
     *  не изменилось с прошлого (дедуп на уровне хода — не плодим одинаковые снимки). */
    @Synchronized
    fun snapshot(reason: String): Checkpoint? = snapshot(reason, null)

    private fun snapshot(reason: String, protectId: String?): Checkpoint? {
        val doc = loadJournal()
        val prevMap = doc.checkpoints.lastOrNull()?.files?.associate { it.path to it.sha } ?: emptyMap()
        val entries = scanWorkspace(prevMap)
        val last = doc.checkpoints.lastOrNull()
        if (last != null && sameFiles(last.files, entries)) return null
        val cp = Checkpoint(id = "cp_${clock()}_${doc.seq}", ts = clock(), reason = reason.take(200), files = entries)
        doc.seq++
        doc.checkpoints.add(cp)
        enforceRetention(doc, protectId)
        saveJournal(doc)
        return cp
    }

    /** Гарантирует наличие базового снимка ДО первого хода (чтобы первый ход можно было откатить). */
    @Synchronized
    fun ensureBaseline(): Checkpoint? {
        if (loadJournal().checkpoints.isNotEmpty()) return null
        return snapshot("базовое состояние")
    }

    // --- Откат ------------------------------------------------------------------------------

    /** Восстановить состояние на момент чекпоинта [id]. Сам откат обратим: перед восстановлением
     *  снимается чекпоинт текущего состояния («до отката»). */
    @Synchronized
    fun restore(id: String): RestoreResult {
        val doc0 = loadJournal()
        doc0.checkpoints.firstOrNull { it.id == id } ?: return RestoreResult(false, "нет чекпоинта $id")
        // Обратимость: зафиксировать текущее состояние («до отката»), НО защитить цель от вытеснения
        // ретенцией — иначе восстановление старейшего чекпоинта удалило бы его же.
        snapshot("до отката", protectId = id)
        val doc = loadJournal()
        val target = doc.checkpoints.firstOrNull { it.id == id }
            ?: return RestoreResult(false, "чекпоинт $id вытеснен")
        val want = target.files.associateBy { it.path }

        // ВСЁ-ИЛИ-НИЧЕГО: сперва материализуем ВСЕ файлы цели в память (проверяя расшифровку), и только
        // если ВСЁ успешно — трогаем рабочую папку. Раньше сначала удаляли лишнее, потом читали blob'ы:
        // отсутствующий/битый blob → откат прерывался ПОСЛЕ удаления, оставляя файлы полу-удалёнными
        // (безвозвратная потеря), а рапортовал «успех».
        val memo = HashMap<String, ByteArray>()
        val materialized = ArrayList<Pair<String, ByteArray>>(target.files.size)
        for (fe in target.files) {
            if (!blobFile(fe.sha).exists())
                return RestoreResult(false, "откат отменён: нет данных для ${fe.path} (рабочая папка не тронута)")
            val content = try {
                readBlobContent(fe.sha, memo).first
            } catch (e: Exception) {
                return RestoreResult(false, "откат отменён: не читается ${fe.path} (рабочая папка не тронута)")
            }
            materialized += fe.path to content
        }

        var deleted = 0
        if (workspace.exists()) {
            workspace.walkTopDown().filter { it.isFile && !excluded(it) }.toList().forEach { f ->
                if (!want.containsKey(relPath(f))) { if (f.delete()) deleted++ }
            }
        }
        var restored = 0
        for ((path, content) in materialized) {
            val dest = File(workspace, path)
            dest.parentFile?.mkdirs()
            writeAtomic(dest, content)
            restored++
        }
        pruneEmptyDirs(workspace)
        return RestoreResult(true, "восстановлено $restored, удалено $deleted", restored, deleted)
    }

    // --- Чтение для UI ----------------------------------------------------------------------

    fun list(): List<CheckpointMeta> {
        val cps = loadJournal().checkpoints
        return cps.mapIndexed { i, cp ->
            val prev = if (i > 0) cps[i - 1].files.associate { it.path to it.sha } else emptyMap()
            val cur = cp.files.associate { it.path to it.sha }
            val added = cur.keys.count { it !in prev }
            val removed = prev.keys.count { it !in cur }
            val changed = cur.count { (p, h) -> prev[p] != null && prev[p] != h }
            CheckpointMeta(cp.id, cp.ts, cp.reason, cp.files.size, changed, added, removed)
        }.reversed() // новые сверху
    }

    /** Реальный размер хранилища на диске (сумма всех blob'ов). */
    fun usageBytes(): Long = (blobsDir.listFiles()?.sumOf { it.length() } ?: 0L)

    @Synchronized
    fun clear() {
        blobsDir.deleteRecursively()
        journalFile.delete()
    }

    // --- Скан workspace ---------------------------------------------------------------------

    private fun scanWorkspace(prevMap: Map<String, String>): List<FileEntry> {
        if (!workspace.exists()) return emptyList()
        val out = ArrayList<FileEntry>()
        workspace.walkTopDown().filter { it.isFile && !excluded(it) }.forEach { f ->
            val bytes = f.readBytes()
            val sha = sha256(bytes)
            val rel = relPath(f)
            if (!blobFile(sha).exists()) {
                blobsDir.mkdirs()
                writeBlobFor(sha, bytes, prevMap[rel])
            }
            out.add(FileEntry(rel, sha, f.length()))
        }
        out.sortBy { it.path }
        return out
    }

    /** Двухтир: пережать «горячие» blob'ы на максимум сжатия (холодный тир). Возвращает число пережатых. */
    @Synchronized
    fun compact(): Int {
        var n = 0
        blobsDir.listFiles()?.forEach { f ->
            val raw = runCatching { decrypt(f.readBytes()) }.getOrNull() ?: return@forEach
            if (raw.isEmpty() || (raw[0] != TAG_FULL && raw[0] != TAG_DELTA)) return@forEach
            writeAtomic(f, encrypt(recold(raw)))
            n++
        }
        return n
    }

    private fun excluded(f: File): Boolean =
        f.length() > config.maxFileBytes || f.extension.lowercase() in config.excludeExt

    private fun sameFiles(a: List<FileEntry>, b: List<FileEntry>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].path != b[i].path || a[i].sha != b[i].sha) return false
        return true
    }

    // --- Ретенция ---------------------------------------------------------------------------

    // protectId — чекпоинт, который НЕЛЬЗЯ вытеснять (цель идущего отката: иначе snapshot «до отката»
    // мог бы удалить сам восстанавливаемый чекпоинт вместе с его blob'ами).
    private fun enforceRetention(doc: Journal, protectId: String? = null) {
        val now = clock()
        val cps = doc.checkpoints
        // Время: выкидываем старейшие вне окна, но держим minKeep последних и защищённый — всегда.
        while (cps.size > config.minKeep && cps.first().ts < now - config.retentionMs && cps.first().id != protectId)
            cps.removeAt(0)
        // Бюджет: пока размер референсных blob'ов выше потолка — режем старейшие (сверх minKeep/защиты).
        while (cps.size > config.minKeep && cps.first().id != protectId && referencedBytes(cps) > config.budgetBytes)
            cps.removeAt(0)
        gcBlobs(cps)
    }

    private fun referencedBytes(cps: List<Checkpoint>): Long =
        liveShas(cps).sumOf { blobFile(it).length() }

    private fun gcBlobs(cps: List<Checkpoint>) {
        // КОНСЕРВАТИВНО: если хоть одну базу дельты не удалось расшифровать (разовый сбой Keystore/
        // bit-rot), множество живых blob'ов НЕДОСТОВЕРНО — удалять нельзя, иначе снесём живую базу и
        // навсегда сломаем восстановление зависящих чекпоинтов. Пропускаем GC этот раз (повторится позже).
        val referenced = liveShasOrNull(cps) ?: return
        blobsDir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
    }

    /** Живые SHA, либо null — если хоть один существующий blob не читается (тогда GC небезопасен). */
    private fun liveShasOrNull(cps: List<Checkpoint>): Set<String>? {
        val referenced = HashSet<String>()
        for (cp in cps) for (fe in cp.files) if (!addWithBasesSafe(fe.sha, referenced)) return null
        return referenced
    }

    private fun addWithBasesSafe(sha: String, out: HashSet<String>): Boolean {
        var cur: String? = sha
        while (cur != null && out.add(cur)) {
            val (base, ok) = baseShaOfChecked(cur)
            if (!ok) return false // существующий blob не расшифровался → достоверность потеряна
            cur = base
        }
        return true
    }

    /** (baseSha|null, ok). ok=false — blob ЕСТЬ, но не расшифровался (в отличие от «файла нет» = ok). */
    private fun baseShaOfChecked(sha: String): Pair<String?, Boolean> {
        val f = blobFile(sha)
        if (!f.exists()) return null to true
        val raw = runCatching { decrypt(f.readBytes()) }.getOrNull() ?: return null to false
        val base = if (raw.size >= 66 && (raw[0] == TAG_DELTA || raw[0] == TAG_DELTA_COLD))
            String(raw, 2, 64, Charsets.US_ASCII) else null
        return base to true
    }

    // Все живые blob'ы: прямые SHA из чекпоинтов ПЛЮС транзитивные базы дельт (delta-blob хранит
    // baseSha; без обхода этих рёбер GC удалял бы базу, на которую ещё ссылается сохранённая дельта).
    private fun liveShas(cps: List<Checkpoint>): Set<String> {
        val referenced = HashSet<String>()
        cps.forEach { cp -> cp.files.forEach { addWithBases(it.sha, referenced) } }
        return referenced
    }

    private fun addWithBases(sha: String, out: HashSet<String>) {
        var cur: String? = sha
        while (cur != null && out.add(cur)) cur = baseShaOf(cur)
    }

    // baseSha дельта-blob'а (или null для полного/отсутствующего/битого).
    private fun baseShaOf(sha: String): String? {
        val f = blobFile(sha)
        if (!f.exists()) return null
        val raw = runCatching { decrypt(f.readBytes()) }.getOrNull() ?: return null
        return if (raw.size >= 66 && (raw[0] == TAG_DELTA || raw[0] == TAG_DELTA_COLD))
            String(raw, 2, 64, Charsets.US_ASCII) else null
    }

    // --- Журнал (шифрованный JSON) ----------------------------------------------------------

    private class Journal(var seq: Long, val checkpoints: MutableList<Checkpoint>)

    private fun loadJournal(): Journal {
        if (!journalFile.exists()) return Journal(0, ArrayList())
        return try {
            val json = String(decrypt(journalFile.readBytes()), Charsets.UTF_8)
            val root = JSONObject(json)
            val arr = root.getJSONArray("checkpoints")
            val cps = ArrayList<Checkpoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val farr = o.getJSONArray("files")
                val files = ArrayList<FileEntry>(farr.length())
                for (j in 0 until farr.length()) {
                    val fo = farr.getJSONObject(j)
                    files.add(FileEntry(fo.getString("p"), fo.getString("h"), fo.getLong("s")))
                }
                cps.add(Checkpoint(o.getString("id"), o.getLong("ts"), o.optString("reason"), files))
            }
            Journal(root.optLong("seq"), cps)
        } catch (t: Throwable) {
            // Битый журнал не должен ронять приложение — начинаем чистый (blob'ы осиротеют, их подберёт GC).
            Journal(0, ArrayList())
        }
    }

    private fun saveJournal(doc: Journal) {
        val root = JSONObject()
        root.put("seq", doc.seq)
        val arr = JSONArray()
        for (cp in doc.checkpoints) {
            val o = JSONObject()
            o.put("id", cp.id); o.put("ts", cp.ts); o.put("reason", cp.reason)
            val farr = JSONArray()
            for (fe in cp.files) {
                val fo = JSONObject(); fo.put("p", fe.path); fo.put("h", fe.sha); fo.put("s", fe.size); farr.put(fo)
            }
            o.put("files", farr); arr.put(o)
        }
        root.put("checkpoints", arr)
        historyDir.mkdirs()
        writeAtomic(journalFile, encrypt(root.toString().toByteArray(Charsets.UTF_8)))
    }

    // --- Утилиты ----------------------------------------------------------------------------

    private fun blobFile(sha: String) = File(blobsDir, sha)
    private fun relPath(f: File): String = f.relativeTo(workspace).path.replace('\\', '/')

    private fun writeAtomic(dest: File, bytes: ByteArray) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
    }

    private fun pruneEmptyDirs(root: File) {
        root.walkBottomUp().forEach { if (it.isDirectory && it != root && (it.listFiles()?.isEmpty() == true)) it.delete() }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) { val v = b.toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0xf]) }
        return sb.toString()
    }

    // --- Blob: запись с дельтой, чтение с реконструкцией, двухтир --------------------------------

    private fun writeBlobFor(sha: String, content: ByteArray, prevSha: String?) {
        val full = byteArrayOf(TAG_FULL) + deflate(content, hot = true)
        var payload = full
        if (prevSha != null && blobFile(prevSha).exists()) {
            val (prevContent, prevDepth) = readBlobContent(prevSha, HashMap())
            if (prevDepth < config.maxChain) {
                val (pre, suf) = prefixSuffix(prevContent, content)
                val middle = content.copyOfRange(pre, content.size - suf)
                val delta = encodeDelta(prevDepth + 1, prevSha, pre, suf, deflate(middle, hot = true))
                if (delta.size < full.size) payload = delta
            }
        }
        writeAtomic(blobFile(sha), encrypt(payload))
    }

    // Возвращает (содержимое, глубину цепочки дельт). memo кэширует восстановленные базы.
    private fun readBlobContent(sha: String, memo: HashMap<String, ByteArray>): Pair<ByteArray, Int> {
        memo[sha]?.let { return it to 0 }
        val raw = decrypt(blobFile(sha).readBytes())
        check(raw.isNotEmpty()) { "пустой blob $sha" }
        val res: Pair<ByteArray, Int> = when (raw[0]) {
            TAG_FULL, TAG_FULL_COLD -> inflate(raw.copyOfRange(1, raw.size)) to 0
            TAG_DELTA, TAG_DELTA_COLD -> {
                // Валидируем заголовок (74 байта): защита от битого/чужого формата — иначе pre/suf из
                // мусорных байт → NegativeArraySizeException/OOM.
                check(raw.size >= 74) { "битый delta-blob $sha (${raw.size} байт)" }
                val depth = raw[1].toInt() and 0xff
                val baseSha = String(raw, 2, 64, Charsets.US_ASCII)
                val pre = readInt(raw, 66); val suf = readInt(raw, 70)
                check(pre >= 0 && suf >= 0) { "битый delta-blob $sha (pre=$pre suf=$suf)" }
                val middle = inflate(raw.copyOfRange(74, raw.size))
                val base = readBlobContent(baseSha, memo).first
                check(pre + suf <= base.size) { "delta вне границ базы $sha" }
                val outp = ByteArray(pre + middle.size + suf)
                System.arraycopy(base, 0, outp, 0, pre)
                System.arraycopy(middle, 0, outp, pre, middle.size)
                System.arraycopy(base, base.size - suf, outp, pre + middle.size, suf)
                outp to depth
            }
            else -> error("неизвестный тег blob 0x${raw[0].toInt() and 0xff} ($sha)")
        }
        memo[sha] = res.first
        return res
    }

    private fun encodeDelta(depth: Int, baseSha: String, pre: Int, suf: Int, deflatedMiddle: ByteArray): ByteArray {
        val head = ByteArray(74)
        head[0] = TAG_DELTA
        head[1] = minOf(depth, 255).toByte()
        System.arraycopy(baseSha.toByteArray(Charsets.US_ASCII), 0, head, 2, 64)
        writeInt(head, 66, pre); writeInt(head, 70, suf)
        return head + deflatedMiddle
    }

    // Пережать «горячий» payload в «холодный» (макс. сжатие), сохранив семантику full/delta.
    private fun recold(raw: ByteArray): ByteArray = when (raw[0]) {
        TAG_FULL -> byteArrayOf(TAG_FULL_COLD) + deflate(inflate(raw.copyOfRange(1, raw.size)), hot = false)
        else -> { // delta — пережимаем только middle
            val middle = inflate(raw.copyOfRange(74, raw.size))
            val head = raw.copyOfRange(0, 74); head[0] = TAG_DELTA_COLD
            head + deflate(middle, hot = false)
        }
    }

    private fun prefixSuffix(a: ByteArray, b: ByteArray): Pair<Int, Int> {
        val max = minOf(a.size, b.size)
        var pre = 0
        while (pre < max && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < max - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        return pre to suf
    }

    private fun deflate(data: ByteArray, hot: Boolean): ByteArray {
        val d = Deflater(if (hot) Deflater.BEST_SPEED else Deflater.BEST_COMPRESSION)
        try {
            d.setInput(data); d.finish()
            val buf = ByteArray(8192); val bos = ByteArrayOutputStream()
            while (!d.finished()) { val n = d.deflate(buf); bos.write(buf, 0, n) }
            return bos.toByteArray()
        } finally { d.end() } // освободить нативный буфер даже при исключении
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(data)
            val buf = ByteArray(8192); val bos = ByteArrayOutputStream()
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        } finally { inf.end() } // Inflater.inflate бросает DataFormatException на битых данных — не течём
    }

    private fun writeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte(); buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte(); buf[off + 3] = v.toByte()
    }

    private fun readInt(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 24) or ((buf[off + 1].toInt() and 0xff) shl 16) or
            ((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff)

    private companion object {
        const val TAG_FULL: Byte = 'F'.code.toByte()
        const val TAG_DELTA: Byte = 'D'.code.toByte()
        const val TAG_FULL_COLD: Byte = 'f'.code.toByte()
        const val TAG_DELTA_COLD: Byte = 'd'.code.toByte()
    }
}
