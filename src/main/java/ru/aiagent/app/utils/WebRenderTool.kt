package ru.aiagent.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * web_render — рендеринг SPA-страниц (JavaScript) через Android WebView и извлечение текста/HTML.
 *
 * Обычный http_request / web_search берёт только исходный HTML — для SPA (React/Vue/Angular и т.п.)
 * контент дорисовывается JS уже в браузере, поэтому нужен реальный движок. Грузим URL в headless-WebView
 * (без окна, applicationContext), ждём onPageFinished + доп. задержку wait_ms на JS-рендер, затем через
 * evaluateJavascript снимаем document.body.innerText (или текст по CSS-селектору) и длину outerHTML.
 *
 * WebView создаётся, работает и уничтожается СТРОГО на главном потоке (withContext(Dispatchers.Main)).
 * Общий таймаут 30с (withTimeoutOrNull). IMPORTANT — сетевой доступ наружу, подтверждаем вне bypass.
 * usesFiles=false: файлы не трогает. Разрешение INTERNET уже есть в манифесте.
 */
class WebRenderTool(private val context: Context) : AgentTool {
    override val id = "web_render"
    override val danger = DangerLevel.IMPORTANT // сетевой запрос наружу
    override val usesFiles = false
    override val schema =
        """отрендерить SPA-страницу (JavaScript) через WebView и вернуть текст/HTML; """ +
            """args: {"url":"https://…","wait_ms":4000,"selector":"опц. CSS-селектор, напр. main"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolResult.Failure("битый JSON args")
        val rawUrl = o.optString("url").takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("нужен args.url")
        val url = if (rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) rawUrl
        else "https://$rawUrl"
        val waitMs = o.optLong("wait_ms", 4000L).coerceIn(0L, 20_000L)
        val selector = o.optString("selector").takeIf { it.isNotBlank() }

        // Анти-SSRF: как и все прочие сетевые тулы (через SafeDns), не пускаем WebView на внутренние
        // адреса (loopback, metadata 169.254.169.254, RFC1918/CGNAT/ULA). Резолвим хост заранее и
        // блокируем; при ошибке резолва — fail-closed. Остаточно: WebView не даёт ConnectCallback, поэтому
        // DNS-rebinding его собственным резолвером тут не закрыт (в отличие от OkHttp-пути). (audit)
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        if (host.isNullOrBlank()) return ToolResult.Failure("web_render: не разобрать хост URL")
        val blocked = withContext(Dispatchers.IO) {
            runCatching { java.net.InetAddress.getAllByName(host).any { isBlockedAddress(it) } }.getOrDefault(true)
        }
        if (blocked) return ToolResult.Failure("web_render: адрес заблокирован (внутренняя сеть/loopback/metadata)")

        // Общий бюджет 30с на всё (загрузка + JS-рендер + извлечение); WebView живёт только внутри Main.
        val result = withTimeoutOrNull(30_000L) {
            withContext(Dispatchers.Main) { render(url, waitMs, selector) }
        } ?: return ToolResult.Failure("web_render: таймаут 30с — страница не отрендерилась")

        return result.fold(
            onSuccess = { ToolResult.Success(it) },
            onFailure = { ToolResult.Failure("web_render: ${it.message?.take(200)}") },
        )
    }

    /** Вся работа с WebView — на главном потоке (вызывается из withContext(Dispatchers.Main)). */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(url: String, waitMs: Long, selector: String?): Result<String> {
        var webView: WebView? = null
        return try {
            suspendCancellableCoroutine { cont ->
                // applicationContext → headless (без Activity/окна). Для снятия outerHTML/innerText этого
                // достаточно: рендер идёт вне экрана, окно WebView не требуется.
                val wv = WebView(context.applicationContext)
                webView = wv
                val handler = Handler(Looper.getMainLooper())
                val resumed = AtomicBoolean(false)
                fun finish(r: Result<String>) {
                    if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(r)
                }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // UA как у настоящего мобильного Chrome — иначе часть сайтов отдаёт заглушку/капчу.
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    blockNetworkImage = true // картинки не нужны для текста — быстрее и легче
                }

                wv.webViewClient = object : WebViewClient() {
                    private val extracted = AtomicBoolean(false)

                    // Анти-DNS-rebinding (audit): WebView резолвит хост САМ, мимо предпроверки — домен мог
                    // отдать публичный IP на предпроверку и внутренний (metadata/RFC1918) на реальную
                    // загрузку. Ре-валидируем КАЖДЫЙ запрос по резолву на момент обращения: адрес во
                    // внутренней сети → отдаём пустой ответ (внутренний контент к нам НЕ попадёт, извлечём
                    // пустоту). Остаточный TOCTOU (наш резолв ≠ коннект WebView) сузился, но полностью его
                    // закрыл бы лишь фетч главного документа через SafeDns + loadDataWithBaseURL.
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        val h = request.url.host
                        if (!h.isNullOrBlank()) {
                            val bad = runCatching {
                                java.net.InetAddress.getAllByName(h).any { isBlockedAddress(it) }
                            }.getOrDefault(true)
                            if (bad) {
                                return android.webkit.WebResourceResponse(
                                    "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        // onPageFinished у SPA может прийти несколько раз — извлекаем один раз.
                        if (!extracted.compareAndSet(false, true)) return
                        // Доп. задержка на JS-дорисовку контента, затем снимаем DOM.
                        handler.postDelayed({
                            runCatching {
                                view.evaluateJavascript(extractionJs(selector)) { value ->
                                    finish(Result.success(buildOutput(url, value)))
                                }
                            }.onFailure { finish(Result.failure(it)) }
                        }, waitMs)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        // Только ошибка ГЛАВНОГО фрейма фатальна; сбои подресурсов игнорируем.
                        if (request.isForMainFrame && !extracted.get()) {
                            finish(Result.failure(IOException("загрузка не удалась: ${error.description}")))
                        }
                    }
                }

                cont.invokeOnCancellation {
                    // Реальный destroy — в finally (гарантированно на Main); здесь только останавливаем загрузку.
                    runCatching { handler.post { webView?.stopLoading() } }
                }
                wv.loadUrl(url)
            }
        } finally {
            // finally выполняется в контексте Main (мы внутри withContext(Dispatchers.Main)) — destroy безопасен.
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
        }
    }

    /** JS: собрать {text, html_len} и вернуть как JSON-СТРОКУ (её надёжно кодирует evaluateJavascript). */
    private fun extractionJs(selector: String?): String {
        val selLiteral = if (selector == null) "null" else JSONObject.quote(selector)
        return """
            (function(){
              try {
                var sel = $selLiteral;
                var node = sel ? document.querySelector(sel) : (document.body || document.documentElement);
                var t = node ? (node.innerText || node.textContent || "") : "";
                var h = document.documentElement ? document.documentElement.outerHTML : "";
                return JSON.stringify({text: t || "", html_len: h.length});
              } catch (e) {
                return JSON.stringify({text: "", html_len: 0, error: String(e)});
              }
            })();
        """.trimIndent()
    }

    /**
     * Разобрать результат evaluateJavascript. callback отдаёт JSON-ЗАКОДИРОВАННУЮ строку (в кавычках,
     * с экранированием), внутри которой — наш JSON. Раскодируем строку через JSONArray("[value]"), затем
     * парсим объект. Текст обрезаем до ~20000 символов.
     */
    private fun buildOutput(url: String, rawValue: String?): String {
        val value = rawValue?.takeIf { it.isNotBlank() && it != "null" }
        val inner = runCatching { JSONArray("[$value]").getString(0) }.getOrNull() ?: value ?: "{}"
        val obj = runCatching { JSONObject(inner) }.getOrNull() ?: JSONObject()
        val text = obj.optString("text").take(20_000)
        val htmlLen = obj.optInt("html_len", 0)
        return """{"url":"${escapeJson(url)}","text":"${escapeJson(text)}","html_len":$htmlLen}"""
    }
}

/** Фабрика инструментов рендеринга веб-страниц. */
fun webRenderTools(context: Context): List<AgentTool> = listOf(WebRenderTool(context))
