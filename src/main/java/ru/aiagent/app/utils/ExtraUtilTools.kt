package ru.aiagent.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import ru.aiagent.core.agent.tools.jsonField
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Дополнительные утилиты-инструменты (§3.4): криптография, хеши, генераторы, сравнение файлов,
 * валидация и ограниченный shell. Всё на стандартной библиотеке JDK/Android — без новых зависимостей.
 * Файловые инструменты принимают resolve: путь → File внутри песочницы (null → отказ).
 */

// --- общие крипто-параметры (AES-256-GCM + PBKDF2) ---
private const val SALT_LEN = 16
private const val IV_LEN = 12
private const val GCM_TAG_BITS = 128
private const val PBKDF2_ITERATIONS = 120_000
private const val AES_KEY_BITS = 256
private const val MAX_OUT = 8000

// --- пределы для построчного diff (LCS-матрица O(n·m) + оба файла в памяти) ---
private const val DIFF_MAX_LINES = 5000
private const val DIFF_MAX_BYTES = 5L * 1024 * 1024 // 5 МБ на файл
// GCM требует полного doFinal для проверки тега, поэтому decrypt держит в памяти И шифртекст, И открытый
// текст (2×). Кап защищает от OOM на большом файле (encrypt стримит, ему кап не нужен).
private const val DECRYPT_MAX_BYTES = 256L * 1024 * 1024 // 256 МБ

/** Соответствие имени алгоритма из args → имя JCA MessageDigest. */
private fun digestName(algo: String?): String? = when (algo?.lowercase()) {
    null, "", "sha256", "sha-256" -> "SHA-256"
    "md5" -> "MD5"
    "sha1", "sha-1" -> "SHA-1"
    "sha512", "sha-512" -> "SHA-512"
    else -> null
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val i = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[i ushr 4]).append("0123456789abcdef"[i and 0x0F])
    }
    return sb.toString()
}

/** Ключ AES-256 из пароля через PBKDF2WithHmacSHA256. */
private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
    val key = factory.generateSecret(spec).encoded
    return SecretKeySpec(key, "AES")
}

// ---------------------------------------------------------------------------
// generate_uuid
// ---------------------------------------------------------------------------
/** generate_uuid — случайный UUID версии 4. */
class GenerateUuidTool : AgentTool {
    override val id = "generate_uuid"
    override val danger = DangerLevel.SAFE
    override val schema = """сгенерировать UUID v4; args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult =
        ToolResult.Success("""{"uuid":"${UUID.randomUUID()}"}""")
}

// ---------------------------------------------------------------------------
// generate_password
// ---------------------------------------------------------------------------
/** generate_password — криптостойкий пароль на SecureRandom. */
class GeneratePasswordTool : AgentTool {
    override val id = "generate_password"
    override val danger = DangerLevel.SAFE
    override val schema =
        """сгенерировать надёжный пароль (SecureRandom); args: {"length":16,"symbols":true}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val length = runCatching { JSONObject(argsJson).optInt("length", 16) }.getOrDefault(16).coerceIn(4, 256)
        val symbols = runCatching { JSONObject(argsJson).optBoolean("symbols", true) }.getOrDefault(true)
        val letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val digits = "0123456789"
        val syms = "!@#$%^&*()-_=+[]{};:,.?/"
        val alphabet = letters + digits + if (symbols) syms else ""
        val rnd = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return ToolResult.Success("""{"password":"${escapeJson(sb.toString())}","length":$length}""")
    }
}

// ---------------------------------------------------------------------------
// hash_text
// ---------------------------------------------------------------------------
/** hash_text — хеш строки (md5/sha1/sha256/sha512). */
class HashTextTool : AgentTool {
    override val id = "hash_text"
    override val danger = DangerLevel.SAFE
    override val schema =
        """хеш строки; args: {"text":"...","algo":"sha256"} (md5/sha1/sha256/sha512)"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val text = jsonField(argsJson, "text") ?: return ToolResult.Failure("нет args.text")
        val algo = jsonField(argsJson, "algo")
        val digest = digestName(algo) ?: return ToolResult.Failure("неизвестный algo (md5/sha1/sha256/sha512)")
        val hex = MessageDigest.getInstance(digest).digest(text.toByteArray(Charsets.UTF_8)).toHex()
        return ToolResult.Success("""{"algo":"$digest","hash":"$hex"}""")
    }
}

// ---------------------------------------------------------------------------
// hash_file
// ---------------------------------------------------------------------------
/** hash_file — хеш файла из песочницы (потоково). */
class HashFileTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "hash_file"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """хеш файла; args: {"path":"...","algo":"sha256"} (md5/sha1/sha256/sha512)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val path = jsonField(argsJson, "path") ?: return@withContext ToolResult.Failure("нет args.path")
        val file = resolve(path) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        if (!file.isFile) return@withContext ToolResult.Failure("файл не найден: $path")
        val digest = digestName(jsonField(argsJson, "algo"))
            ?: return@withContext ToolResult.Failure("неизвестный algo (md5/sha1/sha256/sha512)")
        try {
            val md = MessageDigest.getInstance(digest)
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            ToolResult.Success("""{"algo":"$digest","hash":"${md.digest().toHex()}","size":${file.length()}}""")
        } catch (t: Throwable) {
            ToolResult.Failure("hash_file: ${t.message?.take(160)}")
        }
    }
}

// ---------------------------------------------------------------------------
// encrypt_file
// ---------------------------------------------------------------------------
/** encrypt_file — AES-256-GCM; в начало выходного файла пишутся соль(16)+IV(12). */
class EncryptFileTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "encrypt_file"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """зашифровать файл (AES-256-GCM, ключ из пароля PBKDF2); args: {"path":"...","out":"...","password":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val path = jsonField(argsJson, "path") ?: return@withContext ToolResult.Failure("нет args.path")
        val outPath = jsonField(argsJson, "out") ?: return@withContext ToolResult.Failure("нет args.out")
        val password = jsonField(argsJson, "password") ?: return@withContext ToolResult.Failure("нет args.password")
        val src = resolve(path) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        val dst = resolve(outPath) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        if (!src.isFile) return@withContext ToolResult.Failure("файл не найден: $path")
        try {
            val rnd = SecureRandom()
            val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
            val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            dst.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dst.outputStream().use { rawOut ->
                    rawOut.write(salt)
                    rawOut.write(iv)
                    javax.crypto.CipherOutputStream(rawOut, cipher).use { cipherOut ->
                        input.copyTo(cipherOut, 64 * 1024)
                    }
                }
            }
            ToolResult.Success("""{"encrypted":"${escapeJson(outPath)}","size":${dst.length()}}""")
        } catch (t: Throwable) {
            ToolResult.Failure("encrypt_file: ${t.message?.take(160)}")
        }
    }
}

// ---------------------------------------------------------------------------
// decrypt_file
// ---------------------------------------------------------------------------
/** decrypt_file — обратная операция: читает соль(16)+IV(12) из начала файла. */
class DecryptFileTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "decrypt_file"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """расшифровать файл (AES-256-GCM, ключ из пароля PBKDF2); args: {"path":"...","out":"...","password":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val path = jsonField(argsJson, "path") ?: return@withContext ToolResult.Failure("нет args.path")
        val outPath = jsonField(argsJson, "out") ?: return@withContext ToolResult.Failure("нет args.out")
        val password = jsonField(argsJson, "password") ?: return@withContext ToolResult.Failure("нет args.password")
        val src = resolve(path) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        val dst = resolve(outPath) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        if (!src.isFile) return@withContext ToolResult.Failure("файл не найден: $path")
        if (src.length() < (SALT_LEN + IV_LEN).toLong()) return@withContext ToolResult.Failure("файл слишком мал / не зашифрован этим инструментом")
        if (src.length() > DECRYPT_MAX_BYTES) return@withContext ToolResult.Failure("файл больше ${DECRYPT_MAX_BYTES shr 20} МБ — расшифровка не поддерживается (риск нехватки памяти)")
        try {
            src.inputStream().use { input ->
                val salt = ByteArray(SALT_LEN)
                val iv = ByteArray(IV_LEN)
                if (input.read(salt) != SALT_LEN || input.read(iv) != IV_LEN) {
                    return@withContext ToolResult.Failure("не удалось прочитать соль/IV")
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
                // Явный doFinal(): CipherInputStream проглатывает AEADBadTagException в close(),
                // поэтому при неверном пароле возвращал бы Success с мусором. doFinal бросит
                // AEADBadTagException, если GCM-тег не сошёлся, — и мы честно вернём Failure.
                val ciphertext = input.readBytes()
                val plain = cipher.doFinal(ciphertext)
                dst.parentFile?.mkdirs()
                dst.outputStream().use { it.write(plain) }
            }
            ToolResult.Success("""{"decrypted":"${escapeJson(outPath)}","size":${dst.length()}}""")
        } catch (t: Throwable) {
            // неверный пароль / повреждённые данные → провал GCM-аутентификации
            runCatching { dst.delete() }
            ToolResult.Failure("decrypt_file: неверный пароль или повреждённые данные (${t.message?.take(120)})")
        }
    }
}

// ---------------------------------------------------------------------------
// diff_files
// ---------------------------------------------------------------------------
/** diff_files — построчное сравнение (LCS), unified-подобный вывод. */
class DiffFilesTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "diff_files"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """построчное сравнение двух файлов (unified-подобный вывод); args: {"a":"...","b":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val pathA = jsonField(argsJson, "a") ?: return@withContext ToolResult.Failure("нет args.a")
        val pathB = jsonField(argsJson, "b") ?: return@withContext ToolResult.Failure("нет args.b")
        val fileA = resolve(pathA) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        val fileB = resolve(pathB) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        if (!fileA.isFile) return@withContext ToolResult.Failure("файл не найден: $pathA")
        if (!fileB.isFile) return@withContext ToolResult.Failure("файл не найден: $pathB")
        // Предохранитель ДО чтения/построения матрицы: по размеру файла (память) и по числу строк
        // (LCS-таблица O(n·m)). Иначе большие файлы кладут процесс по OOM.
        if (fileA.length() > DIFF_MAX_BYTES || fileB.length() > DIFF_MAX_BYTES) {
            return@withContext ToolResult.Failure(
                "файлы слишком большие для построчного diff (> ${DIFF_MAX_BYTES / (1024 * 1024)} МБ)",
            )
        }
        try {
            val a = fileA.readText().split("\n")
            val b = fileB.readText().split("\n")
            if (a.size > DIFF_MAX_LINES || b.size > DIFF_MAX_LINES) {
                return@withContext ToolResult.Failure(
                    "файлы слишком большие для построчного diff (> $DIFF_MAX_LINES строк)",
                )
            }
            val diff = unifiedDiff(a, b, pathA, pathB)
            val trimmed = if (diff.length > MAX_OUT) diff.take(MAX_OUT) + "\n… (обрезано)" else diff
            val identical = a == b
            ToolResult.Success(
                JSONObject().put("identical", identical).put("diff", trimmed).toString()
            )
        } catch (t: Throwable) {
            ToolResult.Failure("diff_files: ${t.message?.take(160)}")
        }
    }

    /** Классический LCS-diff по строкам → строки '-' / '+' / ' '. */
    private fun unifiedDiff(a: List<String>, b: List<String>, nameA: String, nameB: String): String {
        val n = a.size
        val m = b.size
        // таблица длин LCS
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val sb = StringBuilder()
        sb.append("--- ").append(nameA).append('\n')
        sb.append("+++ ").append(nameB).append('\n')
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    sb.append("  ").append(a[i]).append('\n'); i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    sb.append("- ").append(a[i]).append('\n'); i++
                }
                else -> {
                    sb.append("+ ").append(b[j]).append('\n'); j++
                }
            }
        }
        while (i < n) { sb.append("- ").append(a[i]).append('\n'); i++ }
        while (j < m) { sb.append("+ ").append(b[j]).append('\n'); j++ }
        return sb.toString()
    }
}

// ---------------------------------------------------------------------------
// create_folder
// ---------------------------------------------------------------------------
/** create_folder — создать папку (mkdirs) в песочнице. */
class CreateFolderTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "create_folder"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """создать папку (с промежуточными); args: {"path":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val path = jsonField(argsJson, "path") ?: return@withContext ToolResult.Failure("нет args.path")
        val dir = resolve(path) ?: return@withContext ToolResult.Failure("путь вне разрешённой папки")
        if (dir.isDirectory) return@withContext ToolResult.Success("""{"created":false,"exists":true,"path":"${escapeJson(path)}"}""")
        if (dir.isFile) return@withContext ToolResult.Failure("по этому пути уже есть файл: $path")
        return@withContext if (dir.mkdirs()) {
            ToolResult.Success("""{"created":true,"path":"${escapeJson(path)}"}""")
        } else {
            ToolResult.Failure("не удалось создать папку: $path")
        }
    }
}

// ---------------------------------------------------------------------------
// validate_data
// ---------------------------------------------------------------------------
/** validate_data — проверка формата email/url/json. */
class ValidateDataTool : AgentTool {
    override val id = "validate_data"
    override val danger = DangerLevel.SAFE
    override val schema =
        """проверить формат значения; args: {"value":"...","type":"email|url|json"} → {valid, error}"""

    private val emailRe = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    override suspend fun invoke(argsJson: String): ToolResult {
        val value = jsonField(argsJson, "value") ?: return ToolResult.Failure("нет args.value")
        val type = jsonField(argsJson, "type")?.lowercase() ?: return ToolResult.Failure("нет args.type (email|url|json)")
        val error: String? = when (type) {
            "email" -> if (emailRe.matches(value.trim())) null else "не похоже на email"
            "url" -> {
                runCatching {
                    val u = java.net.URL(value.trim())
                    if (u.protocol.isNullOrBlank() || u.host.isNullOrBlank()) "нет схемы или хоста" else null
                }.getOrElse { "некорректный URL" }
            }
            "json" -> {
                val t = value.trim()
                runCatching {
                    if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
                    null
                }.getOrElse { it.message ?: "невалидный JSON" }
            }
            else -> return ToolResult.Failure("неизвестный type (email|url|json)")
        }
        return if (error == null) {
            ToolResult.Success("""{"valid":true}""")
        } else {
            ToolResult.Success("""{"valid":false,"error":"${escapeJson(error)}"}""")
        }
    }
}

// ---------------------------------------------------------------------------
// run_shell
// ---------------------------------------------------------------------------
/**
 * run_shell — shell-команда в корне песочницы. Доступен ограниченный набор Android-утилит (toybox:
 * ls/cat/grep/echo/…), НЕ полноценный ПК-shell. Опасно: класс DANGEROUS.
 */
class RunShellTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "run_shell"
    override val danger = DangerLevel.DANGEROUS
    // §7: shell исполняет команды с правами приложения и абсолютным путём читает что угодно —
    // включая databases/ (RAG-индекс) и shared_prefs/ в обход SandboxFs.deniedPaths. Поэтому НЕ
    // отдаём его облачной модели (privateData): иначе `cat databases/rag_vectors.db` слил бы личные
    // данные провайдеру. Локальной модели он остаётся (данные с устройства не уходят). На ПК-клиенте
    // shell облаку доступен осознанно (там это заявленная фича), здесь — вспомогательный toybox.
    override val privateData = true
    override val usesFiles = true
    override val schema =
        """выполнить shell-команду в песочнице (ограниченный набор Android-утилит/toybox, не как на ПК); args: {"command":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val command = jsonField(argsJson, "command") ?: return@withContext ToolResult.Failure("нет args.command")
        val workDir = resolve(".") ?: return@withContext ToolResult.Failure("нет доступа к песочнице")
        try {
            val process = ProcessBuilder(listOf("/system/bin/sh", "-c", command))
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            // stdout читаем на ОТДЕЛЬНОМ потоке (redirectErrorStream=true): иначе при выводе > размера
            // pipe-буфера ядра (~64 КБ) процесс заблокируется на write, а мы — на waitFor → deadlock.
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = br.read(buf)
                            if (n < 0) break
                            synchronized(sb) { sb.append(buf, 0, n) }
                        }
                    }
                } catch (_: Throwable) {
                    // поток чтения завершится при destroyForcibly() — это ожидаемо
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ToolResult.Failure("таймаут 30с — команда прервана")
            }
            reader.join(1000)
            val output = synchronized(sb) { sb.toString() }
            val trimmed = if (output.length > MAX_OUT) output.take(MAX_OUT) + "\n… (обрезано)" else output
            ToolResult.Success(
                JSONObject().put("exit", process.exitValue()).put("output", trimmed).toString()
            )
        } catch (t: Throwable) {
            ToolResult.Failure("run_shell: ${t.message?.take(160)}")
        }
    }
}

/** Фабрика всех дополнительных утилит. Инструменты без файлов игнорируют [resolve]. */
fun extraUtilTools(resolve: (String) -> File?): List<AgentTool> = listOf(
    GenerateUuidTool(),
    GeneratePasswordTool(),
    HashTextTool(),
    HashFileTool(resolve),
    EncryptFileTool(resolve),
    DecryptFileTool(resolve),
    DiffFilesTool(resolve),
    CreateFolderTool(resolve),
    ValidateDataTool(),
    RunShellTool(resolve),
)
