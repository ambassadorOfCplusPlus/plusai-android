package ru.aiagent.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.FileObserver
import android.view.Display
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.aiagent.app.phone.PhoneControlService
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Инструменты сетевой диагностики и распознавания с экрана (S11+).
 * Стиль — как остальные device-тулзы (конструктор с context/resolve, хелперы str/num/ok/esc из UtilTools).
 *
 * Ничего лишнего в манифест не добавляют: ping/traceroute — обычные бинарники /system/bin,
 * file_watch — песочница, qr_from_screen — уже существующая служба спец-возможностей.
 */

// ──────────────────────────── PING ────────────────────────────

/**
 * ping — пинг хоста через системный бинарник /system/bin/ping. Если он недоступен или
 * ничего не ответил — честный fallback на InetAddress.isReachable (без разбора RTT).
 */
class PingTool(private val context: Context) : AgentTool {
    override val id = "ping"
    override val danger = DangerLevel.SAFE
    override val schema = """пинг хоста; args: {"host":"8.8.8.8","count":4}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val host = str(argsJson, "host")?.trim()
            ?: return@withContext ToolResult.Failure("нет args.host")
        if (host.isEmpty()) return@withContext ToolResult.Failure("пустой args.host")
        val count = numOrNull(argsJson, "count")?.toInt()?.coerceIn(1, 20) ?: 4

        // Попытка №1: настоящий ping (даёт RTT).
        val proc = runCatching {
            runProcess(listOf("/system/bin/ping", "-c", count.toString(), "-W", "2", host), 15_000)
        }.getOrNull()

        if (proc != null && proc.output.isNotBlank()) {
            val out = proc.output.trim()
            // Строка вида: "rtt min/avg/max/mdev = 20.4/21.0/21.9/0.5 ms" (или "round-trip min/avg/max").
            val m = Regex("min/avg/max\\S*\\s*=\\s*([\\d.]+)/([\\d.]+)/([\\d.]+)").find(out)
            val reachable = out.contains("bytes from") || (m != null)
            val sb = StringBuilder("{")
            sb.append(""""host":"${esc(host)}","reachable":$reachable""")
            if (m != null) {
                sb.append(""","min_ms":${m.groupValues[1]},"avg_ms":${m.groupValues[2]},"max_ms":${m.groupValues[3]}""")
            }
            sb.append(""","output":"${esc(out.take(2000))}"}""")
            return@withContext ToolResult.Success(sb.toString())
        }

        // Fallback: бинарника нет / молчит — проверяем доступность средствами JVM.
        return@withContext try {
            val reachable = InetAddress.getByName(host).isReachable(2000)
            ToolResult.Success(
                """{"host":"${esc(host)}","reachable":$reachable,""" +
                    """"method":"InetAddress.isReachable","note":"ping-бинарник недоступен, RTT не измерен"}""",
            )
        } catch (t: Throwable) {
            ToolResult.Failure("ping: ${t.message?.take(200) ?: "хост недоступен"}")
        }
    }
}

// ──────────────────────────── TRACEROUTE ────────────────────────────

/**
 * traceroute — трассировка маршрута БЕЗ root. Бинарника /system/bin/traceroute на большинстве
 * Android НЕТ, зато /system/bin/ping есть всегда. Используем технику «ping с растущим TTL»:
 * для ttl = 1,2,3… запускаем `ping -c 1 -t <ttl> -W 2 <host>`. Промежуточный роутер, отбросив
 * пакет по истечении TTL, возвращает ICMP «Time to live exceeded», и его IP виден в строке
 * `From X.X.X.X …`. Когда очередной хоп отвечает уже целевым IP (`bytes from <ip>` / `(ip)`),
 * трасса завершена. Хоп без ответа помечаем `*`.
 */
class TracerouteTool(@Suppress("unused") private val context: Context) : AgentTool {
    override val id = "traceroute"
    override val danger = DangerLevel.SAFE
    override val schema =
        """трассировка маршрута до хоста без root (ping с растущим ICMP TTL, промежуточные """ +
            """роутеры отвечают «TTL exceeded»); args: {"host":"example.com","max_hops":30} """ +
            """(max_hops 1..30, по умолчанию 30)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val host = str(argsJson, "host")?.trim()
            ?: return@withContext ToolResult.Failure("нет args.host")
        if (host.isEmpty()) return@withContext ToolResult.Failure("пустой args.host")
        val maxHops = numOrNull(argsJson, "max_hops")?.toInt()?.coerceIn(1, 30) ?: 30

        // Сначала резолвим целевой IP, чтобы понимать, что трасса дошла именно до хоста.
        val targetIp = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()

        val hops = StringBuilder()
        var reached = false
        var firstProcOk = false

        for (ttl in 1..maxHops) {
            val proc = try {
                runProcess(listOf("/system/bin/ping", "-c", "1", "-t", ttl.toString(), "-W", "2", host), 5_000)
            } catch (t: Throwable) {
                // start() бросает IOException только если самого бинарника ping нет.
                return@withContext if (!firstProcOk) {
                    ToolResult.Failure("traceroute недоступен: нет /system/bin/ping на этом устройстве")
                } else {
                    // ping вдруг перестал запускаться посреди трассы — отдаём, что успели собрать.
                    finishTrace(host, targetIp, hops, reached)
                }
            }
            firstProcOk = true
            val out = proc.output

            // IP хопа: сначала промежуточный роутер (TTL exceeded), затем — сам целевой хост.
            val fromIp = Regex("[Ff]rom (?:[^()\\n]*\\()?(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(out)?.groupValues?.get(1)
            val bytesFrom = Regex("bytes from (?:[^()]*\\()?(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(out)?.groupValues?.get(1)
            val ttlExceeded = out.contains("Time to live exceeded", ignoreCase = true) ||
                out.contains("ttl exceeded", ignoreCase = true)
            val rtt = Regex("time[=<]\\s*([\\d.]+)").find(out)?.groupValues?.get(1)

            val hopIp: String?
            val isTarget: Boolean
            when {
                // Целевой хост ответил (echo reply), без TTL-exceeded.
                bytesFrom != null && !ttlExceeded -> {
                    hopIp = bytesFrom
                    isTarget = true
                }
                // Промежуточный роутер вернул «TTL exceeded».
                fromIp != null -> {
                    hopIp = fromIp
                    // Если это совпало с целевым IP — тоже считаем, что дошли.
                    isTarget = (targetIp != null && fromIp == targetIp)
                }
                // На всякий случай: bytes from без явного echo, но и без роутера.
                bytesFrom != null -> {
                    hopIp = bytesFrom
                    isTarget = true
                }
                else -> {
                    hopIp = null
                    isTarget = false
                }
            }

            if (hops.isNotEmpty()) hops.append(",")
            if (hopIp == null) {
                hops.append("""{"ttl":$ttl,"ip":"*","rtt_ms":null}""")
            } else {
                val rttField = if (rtt != null) rtt else "null"
                hops.append("""{"ttl":$ttl,"ip":"${esc(hopIp)}","rtt_ms":$rttField}""")
            }

            if (isTarget) {
                reached = true
                break
            }
        }

        finishTrace(host, targetIp, hops, reached)
    }

    private fun finishTrace(host: String, targetIp: String?, hops: StringBuilder, reached: Boolean): ToolResult {
        val ipField = if (targetIp != null) ""","target_ip":"${esc(targetIp)}"""" else ""
        return ToolResult.Success(
            """{"host":"${esc(host)}"$ipField,"reached":$reached,"hops":[$hops]}""",
        )
    }
}

// ──────────────────────────── FILE_WATCH ────────────────────────────

/**
 * file_watch — следить за изменениями в папке песочницы ограниченное время (1..60 с)
 * через android.os.FileObserver. Собирает события CREATE/MODIFY/DELETE (и перемещения) и
 * возвращает список. usesFiles=true — работает с реальными файлами пользователя.
 */
class FileWatchTool(private val resolve: (String) -> File?) : AgentTool {
    override val id = "file_watch"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """следить за изменениями в папке (до 60 с); args: {"dir":".","seconds":10}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val dirArg = str(argsJson, "dir")?.trim().orEmpty().ifEmpty { "." }
        val dir = resolve(dirArg) ?: return ToolResult.Failure("путь вне разрешённой папки: $dirArg")
        if (!dir.exists() || !dir.isDirectory) {
            return ToolResult.Failure("нет такой папки: $dirArg")
        }
        val seconds = numOrNull(argsJson, "seconds")?.toInt()?.coerceIn(1, 60) ?: 10

        // События из колбэка FileObserver приходят на его собственном потоке — список синхронизируем.
        val events = Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM

        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = record(events, event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = record(events, event, path)
            }
        }

        return try {
            observer.startWatching()
            delay(seconds * 1000L)
            val snapshot = synchronized(events) { events.toList() }
            val items = snapshot.joinToString(",") {
                """{"type":"${it.first}","name":"${esc(it.second)}"}"""
            }
            ToolResult.Success(
                """{"dir":"${esc(dirArg)}","seconds":$seconds,"count":${snapshot.size},"changes":[$items]}""",
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            // structured concurrency: отмену пробрасываем, а не глотаем общим catch ниже
            throw c
        } catch (t: Throwable) {
            ToolResult.Failure("file_watch: ${t.message?.take(200)}")
        } finally {
            observer.stopWatching()
        }
    }

    private fun record(sink: MutableList<Pair<String, String>>, event: Int, path: String?) {
        val type = when (event and FileObserver.ALL_EVENTS) {
            FileObserver.CREATE -> "create"
            FileObserver.MODIFY -> "modify"
            FileObserver.DELETE -> "delete"
            FileObserver.MOVED_TO -> "moved_to"
            FileObserver.MOVED_FROM -> "moved_from"
            else -> return // прочие события (открытие/чтение/атрибуты) не интересны
        }
        sink.add(type to (path ?: "?"))
    }
}

// ──────────────────────────── QR_FROM_SCREEN ────────────────────────────

/**
 * qr_from_screen — распознать QR/штрихкод прямо с ЭКРАНА. IMPORTANT.
 *
 * Скриншот снимается тем же способом, что take_screenshot: через уже существующую службу
 * [PhoneControlService] (AccessibilityService, метод takeScreenshot, API 30+). Затем кадр
 * декодируется ZXing (та же логика, что в ScanQrTool). Если служба не включена / нет кадра —
 * понятный Failure. usesFiles=false (в файл ничего не пишется), но конструктору нужен context.
 */
class QrFromScreenTool(private val context: Context) : AgentTool {
    override val id = "qr_from_screen"
    override val danger = DangerLevel.IMPORTANT
    override val schema = """распознать QR/штрихкод с экрана; args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ToolResult.Failure("захват экрана доступен только на Android 11+ (API 30)")
        }
        val svc = PhoneControlService.instance
            ?: return ToolResult.Failure(
                "захват экрана требует включённой службы управления телефоном " +
                    "(Настройки → Спец. возможности → Plus AI). Без неё распознавание недоступно.",
            )

        val bitmap = try {
            captureScreen(svc)
        } catch (t: Throwable) {
            return ToolResult.Failure("qr_from_screen: ${t.message?.take(200)}")
        } ?: return ToolResult.Failure("не удалось получить кадр экрана")

        return withContext(Dispatchers.Default) {
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val src = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                val res = runCatching {
                    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(src)))
                }.getOrNull()
                    ?: return@withContext ToolResult.Failure("код не найден на экране")
                ToolResult.Success("""{"text":"${esc(res.text)}","format":"${res.barcodeFormat}"}""")
            } finally {
                bitmap.recycle()
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreen(svc: PhoneControlService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(context),
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        val bmp = try {
                            val hw = screenshot.hardwareBuffer
                            val hwBmp = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                            val soft = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp?.recycle()
                            hw.close()
                            soft
                        } catch (t: Throwable) {
                            null
                        }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
}

// ──────────────────────────── ХЕЛПЕР: ПРОЦЕСС ────────────────────────────

private class ProcOutput(val output: String, val exitCode: Int?)

/**
 * Запускает внешний процесс с redirectErrorStream и жёстким таймаутом. stdout читается на
 * отдельном потоке, чтобы переполнение pipe-буфера не подвесило ожидание. По таймауту процесс
 * убивается. Бросает IOException, если бинарника нет (нужно для честного Failure у traceroute).
 */
private fun runProcess(cmd: List<String>, timeoutMs: Long): ProcOutput {
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val sb = StringBuilder()
    val reader = Thread {
        try {
            proc.inputStream.bufferedReader().use { br ->
                val buf = CharArray(4096)
                while (true) {
                    val n = br.read(buf)
                    if (n < 0) break
                    synchronized(sb) { sb.append(buf, 0, n) }
                }
            }
        } catch (_: Throwable) {
            // поток чтения завершится при destroy() — это ожидаемо
        }
    }.apply { isDaemon = true; start() }

    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) proc.destroy()
    reader.join(1000)
    val exit = if (finished) runCatching { proc.exitValue() }.getOrNull() else null
    return ProcOutput(synchronized(sb) { sb.toString() }, exit)
}

// ──────────────────────────── ФАБРИКА ────────────────────────────

/** Набор инструментов сетевой диагностики и распознавания с экрана. */
fun netDiagTools(context: Context, resolve: (String) -> File?): List<AgentTool> = listOf(
    PingTool(context),
    TracerouteTool(context),
    FileWatchTool(resolve),
    QrFromScreenTool(context),
)
