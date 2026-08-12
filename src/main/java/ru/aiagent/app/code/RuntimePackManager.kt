package ru.aiagent.app.code

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Каркас загружаемых рантайм-паков (тулчейны C/C++/Go/Rust) — по решению Q4 они НЕ в APK, а
 * качаются по требованию (как модели). Здесь — учёт/скачивание/распаковка пака. САМИ бинарники
 * тулчейнов не поставляются в Фазе 1.
 *
 * ВАЖНО (честно): на Android 10+ запрещён КОНКРЕТНО путь «скачать бинарник в app-хранилище и
 * запустить его через execve» (SELinux/W^X). Именно поэтому этот менеджер (скачивание пака в
 * filesDir) НЕ годится для запуска нативных тулчейнов на телефоне. Рабочие обходы существуют, но:
 *   - полноценные C++/Go тулчейны (clang/gcc/go) на телефоне требуют либо бандла в APK (сотни МБ),
 *     либо proot-эмуляции (мини-Termux) — решено вынести C++/Go в ДЕСКТОП Фазы 2, где exec свободен;
 *   - лёгкий C — реально на телефоне через libtcc (JIT в память, tcc_run, без exec файла) — отдельно.
 * ИСКЛЮЧЕНИЕ — тулчейны в JVM-байткоде (Kotlin): kotlinc→D8→DEX грузится через DexClassLoader, а это
 * НЕ execve, поэтому SELinux/W^X не мешает (так же грузятся dynamic feature modules). Такой пак здесь
 * штатный — нужна лишь Android-совместимая сборка компилятора (как в AndroidIDE) + runner run_kotlin.
 * Здесь остаётся инфраструктура скачивания паков (модели/данные/ассеты/JVM-тулчейны), не нативный exec.
 */
object RuntimePackManager {
    /**
     * @param expectedSha256 контрольная сумма zip-пака (hex, lowercase) — ОБЯЗАТЕЛЬНА для паков
     * с нативным кодом (idёт в System.load): без сверки подменённый .so исполнился бы как RCE.
     * null → установка запрещена (fail-closed), пока владелец не пропишет реальный хэш.
     */
    data class Pack(
        val id: String, val name: String, val approxMb: Int,
        val url: String? = null, val expectedSha256: String? = null,
    )

    /** Паки. `c` (picoc, ~0.6 МБ) — реальный, грузится через System.load. Остальные — Фаза 2 (десктоп). */
    val known: List<Pack> = listOf(
        // Пак c-arm64.zip = { libcpico.so } (arm64, NDK). Раздаётся сервером: GET /packs/c-arm64.zip.
        // Хэш — от собранного zip (native/cpico/build.sh); сервер обязан раздавать РОВНО этот файл.
        Pack(
            "c", "Компилятор C (picoc)", 1,
            "https://api.plus-ai.ru/packs/c-arm64.zip",
            expectedSha256 = "8c40d2381d7847b0a16622725fc8322a0a4152a377e09509810c304b3b25e0dc",
        ),
        // typescript.zip = { typescript.js } (компилятор TS, чистый JS → в Rhino). Раздаётся сервером:
        // GET /packs/typescript.zip. Собран из npm typescript@3.9.10 (см. packs/typescript/build.sh).
        // ВАЖНО: НЕ поднимать до 5.x — бандл TS ≥4.4 эмитится под ES2018 (?./??/for-of), а Rhino 1.7.14 в
        // режиме интерпретатора (ART) их не парсит → «syntax error typescript.js#87» ещё на загрузке
        // компилятора. TS 3.9.x эмитит свой бандл под ES5 — грузится и транспилирует в Rhino (проверено).
        Pack(
            "typescript", "TypeScript (компилятор)", 2,
            "https://api.plus-ai.ru/packs/typescript.zip",
            expectedSha256 = "c4d7a1c45c07635e4ba994b95d5de8dfdf619309b090fb3663cf8a283ddff7f2",
        ),
        // kotlin.zip = { runtime.jar (dex: kotlinc+r8+StAX+пропатченный Unsafe+драйвер, +.class/ресурсы),
        // lib/kotlin-stdlib.jar, android.jar }. Компиляция+исполнение Kotlin ПРОВЕРЕНЫ на устройстве (ART):
        // kotlinc→снять @Metadata→D8→DexClassLoader→запуск (не execve). Рецепт сборки — packs/kotlin/ (репо).
        // Раздаётся сервером: GET /packs/kotlin.zip. Владелец обязан выложить РОВНО этот файл (сверка SHA-256).
        Pack(
            "kotlin", "Kotlin (kotlinc → DEX)", 103,
            "https://api.plus-ai.ru/packs/kotlin.zip",
            expectedSha256 = "1ff58a14432aaaf9d92db6682ac842c429f588e7e41a67b6ef83feb3d96d4558",
        ),
        // Пак ffmpeg-arm64.zip = { libplusffmpeg.so (JNI-обёртка, зовёт ffmpeg main) + библиотеки ffmpeg
        // под arm64 (libav*/libsw*.so, собраны NDK r26b, ffmpeg n6.1 --enable-small) }. Грузится через
        // System.load из pack-папки — как picoc (dlopen скачанной .so разрешён, execve — нет). Собран
        // из packs/ffmpeg/build_wsl.sh + plusffmpeg.c; раздаётся сервером GET /packs/ffmpeg-arm64.zip.
        Pack(
            "ffmpeg", "FFmpeg (медиа-движок)", 15,
            "https://api.plus-ai.ru/packs/ffmpeg-v2.zip", // -v2: пересборка с harden'ингом C-обёртки (мьютекс g_out, OOM) + busting CDN
            expectedSha256 = "5fc0b12da7078eb86b1d8afd243fedc1fd76bc713b39264d7f1346222ff1b771",
        ),
        // java-bsh.zip = { classes.dex } (d8 от bsh-2.0b6.jar). BeanShell вынесен из APK в пак:
        // грузится DexClassLoader-ом ([LibraryPackLoader]). Раздаётся сервером GET /packs/java-bsh.zip.
        Pack(
            "java", "Java (BeanShell)", 1,
            "https://api.plus-ai.ru/packs/java-bsh.zip",
            expectedSha256 = "fb6eb98cc610ddd3314905163a2adaeddba4b4d975caac2ed122470a6e8bc512",
        ),
        // whisper-arm64.zip = { libwhisper.so (ggml встроен) + libpluswhisper.so (JNI) }. Офлайн-STT из
        // файла (transcribe_audio). Грузится System.load. Собран packs/whisper/build_wsl.sh + pluswhisper.c.
        // Модель ggml-*.bin качается отдельно при первом использовании. Раздаётся GET /packs/whisper-arm64.zip.
        Pack(
            "whisper", "Whisper (распознавание речи)", 1,
            "https://api.plus-ai.ru/packs/whisper-v2.zip", // -v2: пересборка с harden'ингом C-обёртки + busting CDN-кэша
            expectedSha256 = "6e3a6b8f45170659cf2d2f6578786fd2e78e21857e71e074b3d6328df85f5fdf",
        ),
        // jgit.zip = { classes.dex } (d8 от org.eclipse.jgit 6.10 + рантайм-депы slf4j-api/JavaEWAH +
        // GitBackendImpl). JGit вынесен из APK: грузится DexClassLoader-ом ([LibraryPackLoader]),
        // инструменты git_* зовут его через тонкий интерфейс GitBackend. Раздаётся GET /packs/jgit.zip.
        // Пак = { impl.jar } (impl.jar = classes.dex + ресурсы JGit — JGitText.properties и др.: d8-dex
        // ресурсы не несёт, а JGit грузит переводы через ResourceBundle при инициализации). DexClassLoader
        // грузится из impl.jar → видит и классы, и ресурсы. Собран packs/jgit/build.sh; на боевом, HTTP 200.
        Pack(
            "jgit", "Git (JGit)", 3,
            "https://api.plus-ai.ru/packs/jgit-v2.zip", // -v2: busting Cloudflare-кэша (jgit.zip закэширован старым)
            expectedSha256 = "b50738fcf6a409b16b4fc6fc20d0fc469640358608e0531a1dcacc7719bd0aad",
        ),
        // tdlib.zip = { libtdjson.so (c++_static + static OpenSSL → самодостаточен) + libplustd.so — тонкая
        // JNI-обёртка вокруг td_json_client (td_create_client_id/td_send/td_receive/td_execute) }. Грузится
        // System.load из pack-папки (как whisper/ffmpeg — dlopen скачанной .so разрешён). Официальный TDLib
        // (MTProto) для ЛЕГАЛЬНОГО юзер-клиента Telegram (вход своим номером). Собран arm64 из исходников
        // (TDLib 022d602 + OpenSSL 3.5.1, packs/tdlib/build_wsl.sh + plustd.c) и выложен на GET
        // /packs/tdlib.zip. Хэш обязателен: без сверки native .so в System.load = RCE (install() fail-closed).
        // Символы обёртки — см. [TdJsonNative].
        Pack(
            "tdlib", "Telegram (TDLib, юзер-клиент)", 30,
            "https://api.plus-ai.ru/packs/tdlib.zip",
            expectedSha256 = "1d27d23e66301dd8cb688a069d9d8eb3067e58d43fbf180c000a44082ddfcd16",
        ),
        // ssh.zip = { classes.dex } (d8 от JSch mwiede-0.2.21 + SshBackendImpl). SSH вынесен из APK (как JGit):
        // грузится DexClassLoader-ом, тулзы ssh_run/ssh_list зовут через тонкий интерфейс SshBackend. Собран
        // packs/ssh/build.sh (ВНИМАНИЕ: d8 из build-tools 35 — R8 8.2.2 из bt-34 падает NPE на Channel$1.class
        // multi-release jsch-jar). На боевом: GET /packs/ssh.zip. Без SHA install() fail-closed (ssh_run вернёт
        // «нужно скачать пак»), как у tdlib.
        Pack(
            "ssh", "SSH (JSch)", 1,
            "https://api.plus-ai.ru/packs/ssh.zip",
            expectedSha256 = "68ca96b65f28d8cb0c1d8d03bd51669e7deab8c062ebfcf75ccde4f3c9fe1275",
        ),
        Pack("cpp", "C/C++ (clang) — десктоп Ф2", 120),
        Pack("go", "Go — десктоп Ф2", 90),
        Pack("rust", "Rust — десктоп Ф2", 150),
    )

    fun installDir(context: Context, id: String): File =
        File(File(context.filesDir, "runtimes"), id)

    /** Пак установлен (распакован и помечен готовым) И не устарел. Маркер `.ready` хранит SHA-256 того
     *  zip, из которого пак распакован; если известный пак теперь ждёт ДРУГОЙ хэш (обновили версию —
     *  напр. typescript 5.4.5→3.9.10), старая установка считается устаревшей и переустановится сама.
     *  Legacy-маркер "ok" (до этого механизма) грандфатерим как валидный, чтобы не гнать повторно
     *  неизменные тяжёлые паки (kotlin ~103 МБ). */
    fun isInstalled(context: Context, id: String): Boolean {
        val marker = File(installDir(context, id), ".ready")
        if (!marker.exists()) return false
        // Пустой/нечитаемый маркер (обрыв записи, повреждение ФС) — fail-closed: считаем НЕ установленным,
        // чтобы переустановиться, а не пройти дальше с битым паком (иначе runnerClass падал бы «нет runtime.jar»).
        val stamp = runCatching { marker.readText().trim() }.getOrNull()
        if (stamp.isNullOrEmpty()) return false
        if (stamp == "ok") return true // legacy-маркер до SHA-механизма — грандфатерим
        val expected = known.firstOrNull { it.id == id }?.expectedSha256?.lowercase()
        return expected == null || stamp.lowercase() == expected // хэш совпал → актуален
    }

    fun installedPacks(context: Context): List<Pack> = known.filter { isInstalled(context, it.id) }

    /** Скачать, СВЕРИТЬ SHA-256 и распаковать пак (zip). Пометить .ready при успехе; при сбае — чисто.
     *  [onProgress] (0..100, -1 = размер неизвестен) — для индикатора в UI: крупный пак качается минуты,
     *  без прогресса пользователь думает, что зависло. */
    suspend fun install(
        context: Context,
        pack: Pack,
        onProgress: (Int) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = pack.url ?: return@withContext Result.failure(IllegalStateException("для пака ${pack.id} ещё нет источника"))
        // Fail-closed: нативный пак идёт в System.load — без сверки контрольной суммы подменённый
        // .so исполнился бы как RCE (U1). Нет заданного хэша → не устанавливаем вовсе.
        val expected = pack.expectedSha256?.lowercase()
            ?: return@withContext Result.failure(IllegalStateException("SHA-256 пака ${pack.id} не задан — установка запрещена"))
        val dir = installDir(context, pack.id)
        val tmpZip = File(context.cacheDir, "pack_${pack.id}.zip")
        val result = runCatching {
            dir.deleteRecursively(); dir.mkdirs() // чистый старт (без остатков прошлой неудачи)
            // 1) Качаем ВЕСЬ zip во временный файл — хэш надо посчитать ДО распаковки/System.load.
            // readTimeout — лимит ПАУЗЫ между чтениями, не общий: крупный пак (Kotlin ~103 МБ) через
            // домашний туннель может залипать >45 с на дросселе. 120 с паузы — с запасом, но не навсегда.
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 120_000 }
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val total = c.contentLengthLong // -1 если сервер не отдал Content-Length
                var read = 0L; var lastPct = -1
                c.inputStream.use { input ->
                    tmpZip.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            } else onProgress(-1)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            // 2) Сверяем SHA-256 — при несовпадении НЕ распаковываем и не грузим.
            val actual = sha256Hex(tmpZip)
            if (actual != expected) error("SHA-256 не совпал (пак повреждён/подменён): $actual")
            // 3) Только теперь распаковываем из проверенного файла (с защитой zip-slip).
            val guard = dir.canonicalPath + File.separator
            ZipInputStream(tmpZip.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val out = File(dir, e.name)
                    if (!out.canonicalPath.startsWith(guard)) error("zip-slip: ${e.name}") // защита обхода пути (с разделителем)
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                    e = zin.nextEntry
                }
            }
            File(dir, ".ready").writeText(expected) // маркер = SHA пака (для авто-инвалидации при обновлении)
        }.onFailure { dir.deleteRecursively() } // частичная распаковка не должна выглядеть как «почти готово»
        tmpZip.delete()
        result
    }

    /** SHA-256 файла в hex (lowercase) — стримом, чтобы не держать весь пак в памяти. */
    private fun sha256Hex(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun remove(context: Context, id: String) {
        installDir(context, id).deleteRecursively()
        // Инвалидация статических кэшей загрузчиков: без неё после удаления/переустановки пака остался бы
        // закэшированный DexClassLoader (старый код) поверх уже удалённых файлов.
        LibraryPackLoader.invalidate(id)
        if (id == "jgit") ru.aiagent.app.integrations.GitBackends.invalidate()
        if (id == "ssh") ru.aiagent.app.integrations.SshBackends.invalidate()
    }
}
