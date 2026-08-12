package ru.aiagent.app.debug

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Дебаг-консоль поверх Compose semantics-дерева — «командная строка вместо интерфейса» (только debug).
 *
 * Смысл: отлаживать приложение с ПК ТЕКСТОМ, а не скринами (скрин = 1–2 мин на экран). Консоль
 * читает ЖИВОЕ semantics-дерево (тот же источник, что accessibility и Compose-тесты) и исполняет
 * действия через [SemanticsActions.OnClick]/[SemanticsActions.SetText] — это буквально те же
 * колбэки, что дёргает палец по кнопке. Никакого параллельного «командного» кода: «команда делает
 * ровно то же, что кнопка».
 *
 * Доступ с ПК:
 *   adb forward tcp:8099 tcp:8099
 *   curl localhost:8099/state                     — текущий экран: маршрут, текст, кнопки, поля
 *   curl "localhost:8099/do?index=3&action=click" — нажать элемент #3 (как тап по кнопке)
 *   curl "localhost:8099/do?label=Отправить"      — нажать первый элемент с такой подписью
 *   curl "localhost:8099/do?index=5&action=settext&value=привет" — ввести текст в поле #5
 *
 * Безопасность: слушает ТОЛЬКО 127.0.0.1, живёт только в debug-сборке (src/debug). В релизе класса
 * нет вовсе (см. src/release/DebugHooks — заглушка).
 */
object DebugServer {
    private const val TAG = "plusai-debug"
    private const val PORT = 8099

    @Volatile var currentRoute: String = "?"
    @Volatile private var started = false
    private var activityRef: WeakReference<Activity> = WeakReference(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Запоминает активити (для доступа к decorView) и один раз поднимает сервер. */
    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        if (started) return
        started = true
        Thread({ serve() }, "plusai-debug-server").apply { isDaemon = true; start() }
        Log.i(TAG, "debug-консоль: adb forward tcp:$PORT tcp:$PORT  →  curl localhost:$PORT/state")
    }

    private fun serve() {
        val server = try {
            ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            Log.e(TAG, "не смог занять порт $PORT: ${t.message}"); started = false; return
        }
        while (true) {
            val sock = try { server.accept() } catch (t: Throwable) { break }
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine()
                if (requestLine == null) { sock.close(); continue }
                // Дренируем заголовки (тело не используем — всё через query-параметры).
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val path = requestLine.split(' ').getOrElse(1) { "/" }
                val body = handle(path)
                writeResponse(sock.getOutputStream(), body)
            } catch (t: Throwable) {
                runCatching { writeResponse(sock.getOutputStream(), "error: ${t.message}") }
            } finally {
                runCatching { sock.close() }
            }
        }
        runCatching { server.close() }
    }

    private fun writeResponse(os: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        os.write(header.toByteArray(Charsets.US_ASCII))
        os.write(bytes)
        os.flush()
    }

    // --- Роутинг запросов -------------------------------------------------------------------

    private fun handle(path: String): String {
        val q = path.substringAfter('?', "")
        val route = path.substringBefore('?')
        val params = parseQuery(q)
        return when (route) {
            "/", "/help" -> HELP
            "/state" -> onMain { dumpState() }
            "/do" -> {
                val res = onMain { performAction(params) }
                // Дать композиции перерисоваться, затем показать новый экран (действие + наблюдение за раз).
                Thread.sleep(180)
                res + "\n\n" + onMain { dumpState() }
            }
            else -> "неизвестный путь '$route'\n\n$HELP"
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }

    // --- Чтение состояния экрана ------------------------------------------------------------

    private fun dumpState(): String {
        val nodes = collectNodes() ?: return "нет активного экрана (активити не привязано)"
        val sb = StringBuilder()
        val windows = (nodes.maxOfOrNull { it.rootIndex } ?: 0) + 1
        sb.append("route=").append(currentRoute)
            .append("   (элементов: ").append(nodes.size)
            .append(if (windows > 1) ", окон: $windows" else "").append(")\n")
        sb.append("— # — тип — подпись — [флаги] ————————————————————\n")
        var lastRoot = -1
        nodes.forEachIndexed { i, entry ->
            val d = describe(entry.node)
            if (d != null) {
                if (entry.rootIndex != lastRoot && windows > 1) {
                    lastRoot = entry.rootIndex
                    sb.append("——— окно #").append(entry.rootIndex)
                        .append(if (entry.rootIndex > 0) " (попап/диалог) " else " (основное) ")
                        .append("———\n")
                }
                sb.append("#").append(i).append("  ").append("  ".repeat(entry.depth.coerceAtMost(6)))
                    .append(d).append('\n')
            }
        }
        return sb.toString()
    }

    /** Человекочитаемая строка узла, либо null если узел «пустой» (чистый layout без текста/действий). */
    private fun describe(n: SemanticsNode): String? {
        val cfg = n.config
        val text = cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.trim().orEmpty()
        val desc = cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.trim().orEmpty()
        val editable = cfg.getOrNull(SemanticsProperties.EditableText)?.text
        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            editable != null -> editable
            else -> ""
        }
        val clickable = cfg.getOrNull(SemanticsActions.OnClick) != null
        val settable = cfg.getOrNull(SemanticsActions.SetText) != null
        val disabled = cfg.contains(SemanticsProperties.Disabled)
        val toggle = cfg.getOrNull(SemanticsProperties.ToggleableState)
        val role = cfg.getOrNull(SemanticsProperties.Role)?.toString()

        if (label.isEmpty() && !clickable && !settable) return null // чистый layout — пропускаем

        val type = when {
            settable -> "FIELD"
            clickable -> "BTN"
            else -> "text"
        }
        val flags = buildList {
            if (clickable) add("click")
            if (settable) { add("editable"); add("text=\"${editable.orEmpty().take(60)}\"") }
            if (disabled) add("DISABLED")
            if (toggle != null) add("toggle=$toggle")
            if (role != null) add("role=$role")
        }.joinToString(" ")

        val shown = if (label.length > 80) label.take(80) + "…" else label
        return "$type \"$shown\"" + if (flags.isNotEmpty()) "  [$flags]" else ""
    }

    // --- Исполнение действий ----------------------------------------------------------------

    private fun performAction(p: Map<String, String>): String {
        val nodes = collectNodes() ?: return "нет активного экрана"
        val action = p["action"] ?: "click"
        val target = when {
            p["index"] != null -> {
                val i = p["index"]!!.toIntOrNull() ?: return "index не число: ${p["index"]}"
                nodes.getOrNull(i)?.node ?: return "нет элемента #$i (всего ${nodes.size})"
            }
            p["label"] != null -> {
                val needle = p["label"]!!
                nodes.map { it.node }.firstOrNull { n ->
                    val cfg = n.config
                    val hasAction = cfg.getOrNull(SemanticsActions.OnClick) != null ||
                        cfg.getOrNull(SemanticsActions.SetText) != null
                    hasAction && labelOf(n).contains(needle, ignoreCase = true)
                } ?: return "не нашёл кликабельный элемент с подписью «$needle»"
            }
            else -> return "нужен index или label"
        }
        return when (action) {
            "click" -> {
                val a = target.config.getOrNull(SemanticsActions.OnClick)
                    ?: return "у элемента нет действия click: «${labelOf(target)}»"
                val ok = a.action?.invoke() ?: false
                "ok: click «${labelOf(target)}» → $ok"
            }
            "settext" -> {
                val a = target.config.getOrNull(SemanticsActions.SetText)
                    ?: return "элемент не редактируемый: «${labelOf(target)}»"
                val value = p["value"].orEmpty()
                val ok = a.action?.invoke(AnnotatedString(value)) ?: false
                "ok: settext «$value» → $ok"
            }
            else -> "неизвестное action=$action (click|settext)"
        }
    }

    private fun labelOf(n: SemanticsNode): String {
        val cfg = n.config
        return cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.trim()?.takeIf { it.isNotEmpty() }
            ?: cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: cfg.getOrNull(SemanticsProperties.EditableText)?.text
            ?: "(без подписи)"
    }

    // --- Обход semantics-дерева -------------------------------------------------------------

    private class Entry(val node: SemanticsNode, val depth: Int, val rootIndex: Int)

    /**
     * Плоский DFS-список узлов merged-дерева ВСЕХ окон процесса (стабильный порядок между /state и
     * /do). Попапы (DropdownMenu, выпадающий выбор модели) и Dialog живут в отдельных окнах
     * WindowManager — их не видно из decorView активити, поэтому берём все root-view процесса.
     */
    private fun collectNodes(): List<Entry>? {
        if (activityRef.get() == null) return null
        val roots = ArrayList<RootForTest>()
        for (v in allRootViews()) collectComposeRoots(v, roots)
        if (roots.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        roots.forEachIndexed { ri, root ->
            val rootNode = rootSemanticsNode(root.semanticsOwner) ?: return@forEachIndexed
            walk(rootNode, 0, ri, out)
        }
        return out
    }

    private fun walk(node: SemanticsNode, depth: Int, rootIndex: Int, out: MutableList<Entry>) {
        out.add(Entry(node, depth, rootIndex))
        for (child in node.children) walk(child, depth + 1, rootIndex, out)
    }

    private fun collectComposeRoots(v: View, out: MutableList<RootForTest>) {
        if (v is RootForTest) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectComposeRoots(v.getChildAt(i), out)
    }

    /**
     * Все корневые View процесса (окно активити + все попап/диалог-окна) через приватный
     * WindowManagerGlobal.mViews (приём Espresso RootsOracle). Читается на главном потоке —
     * WM мутирует mViews тоже на нём, гонки нет. Fallback — decorView активити.
     */
    private fun allRootViews(): List<View> {
        runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val instance = cls.getMethod("getInstance").invoke(null)
            val raw = cls.getDeclaredField("mViews").apply { isAccessible = true }.get(instance)
            val list = when (raw) {
                is List<*> -> raw.filterIsInstance<View>()
                is Array<*> -> raw.filterIsInstance<View>()
                else -> emptyList()
            }
            if (list.isNotEmpty()) return list
        }
        val act = activityRef.get() ?: return emptyList()
        return listOf(act.window.decorView)
    }

    private fun rootSemanticsNode(owner: SemanticsOwner): SemanticsNode? =
        runCatching { owner.rootSemanticsNode }.getOrNull()

    // --- Утилиты ----------------------------------------------------------------------------

    /** Выполнить блок на главном потоке и дождаться результата (semantics читается только на UI-потоке). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var err: Throwable? = null
        mainHandler.post {
            try { result = block() } catch (t: Throwable) { err = t } finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw RuntimeException("таймаут главного потока")
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private val HELP = """
        Plus AI — debug-консоль (semantics-зеркало интерфейса)
        GET /state                                  — текущий экран текстом
        GET /do?index=N&action=click                — нажать элемент #N (= тап по кнопке)
        GET /do?label=ТЕКСТ                          — нажать первый элемент с такой подписью
        GET /do?index=N&action=settext&value=ТЕКСТ   — ввести текст в поле #N
        После /do печатается новое состояние экрана.
    """.trimIndent()
}
