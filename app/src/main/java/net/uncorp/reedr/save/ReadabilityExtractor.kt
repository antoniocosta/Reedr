package net.uncorp.reedr.save

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

data class Extracted(
    val title: String?,
    val byline: String?,
    val siteName: String?,
    val excerpt: String?,
    val lang: String?,
    val content: String,
    /** Readability's count of text characters in [content]. */
    val length: Int,
    val leadImageUrl: String?,
)

/**
 * Runs Mozilla Readability (assets/readability.js, the engine of Firefox Reader View) in an offscreen WebView.
 *
 * [fromHtml] is the fast path: the downloaded HTML is parsed with DOMParser inside an empty page, so none of the
 * site's scripts run and nothing else is downloaded. [fromRenderedPage] is the fallback for pages built by
 * JavaScript: it loads the live page (images blocked), lets it settle and runs Readability on the rendered DOM.
 */
class ReadabilityExtractor(context: Context) {
    private val app = context.applicationContext
    private val readabilityJs: String by lazy { app.assets.open("readability.js").bufferedReader().use { it.readText() } }

    suspend fun fromHtml(html: String, baseUrl: String): Extracted? = withContext(Dispatchers.Main) {
        withWebView { web ->
            web.settings.blockNetworkLoads = true
            web.addJavascriptInterface(Input(html, baseUrl), "ReedrInput")
            val loaded = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = if (request.isForMainFrame) null else BLOCKED
                        override fun onPageFinished(view: WebView, url: String) { if (cont.isActive) cont.resume(true) }
                    }
                    web.loadDataWithBaseURL(SANDBOX_URL, "<!doctype html><html><head></head><body></body></html>", "text/html", "utf-8", null)
                }
            }
            if (loaded == null) return@withWebView null
            withTimeoutOrNull(20_000) { evaluate(web, "$readabilityJs\n$PARSE_INPUT") }
        }
    }

    suspend fun fromRenderedPage(url: String, timeoutMs: Long = 25_000): Extracted? = withContext(Dispatchers.Main) {
        withWebView { web ->
            web.settings.blockNetworkImage = true
            web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            var finished = false
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { finished = false }
                override fun onPageFinished(view: WebView, url: String) { finished = true }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !request.url.scheme.orEmpty().startsWith("http")
            }
            web.loadUrl(url)
            // wait for the load, then for client-side rendering: until the text stops growing for 2 s (or 10 s pass);
            // extract whatever is there at the deadline
            withTimeoutOrNull(timeoutMs) { while (!finished) delay(200) }
            withTimeoutOrNull(10_000) {
                var last = -1
                var stableSince = System.currentTimeMillis()
                delay(800)
                while (true) {
                    val length = suspendCancellableCoroutine { cont ->
                        web.evaluateJavascript("document.body ? document.body.innerText.length : 0") { cont.resume(it?.toIntOrNull() ?: 0) }
                    }
                    if (length != last) { last = length; stableSince = System.currentTimeMillis() }
                    else if (System.currentTimeMillis() - stableSince >= 2_000) break
                    delay(400)
                }
            }
            withTimeoutOrNull(20_000) { evaluate(web, "(function(){\n$readabilityJs\n$PARSE_DOCUMENT\n})()") }
        }
    }

    private suspend fun evaluate(web: WebView, script: String): Extracted? = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(script) { raw -> cont.resume(runCatching { parse(raw) }.getOrNull()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun <T> withWebView(block: suspend (WebView) -> T): T {
        val web = WebView(app)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.userAgentString = UserAgent.chrome(app)
        // an unattached WebView still lays out at this size, which some lazy-rendering pages need
        web.layout(0, 0, 1080, 2400)
        try {
            return block(web)
        } finally {
            web.stopLoading()
            web.destroy()
        }
    }

    private class Input(private val html: String, private val url: String) {
        @JavascriptInterface fun html(): String = html
        @JavascriptInterface fun url(): String = url
    }

    companion object {
        private const val SANDBOX_URL = "https://reedr-extract.invalid/"
        private val BLOCKED get() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        private const val RESULT = """
            if (!r || !r.content) return null;
            var og = doc.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
            return JSON.stringify({title: r.title, byline: r.byline, siteName: r.siteName, excerpt: r.excerpt, lang: r.lang,
              content: r.content, length: r.length || 0, lead: og ? og.content : null});"""

        private const val PARSE_INPUT = """
            (function() {
              var doc = new DOMParser().parseFromString(ReedrInput.html(), 'text/html');
              var base = doc.createElement('base'); base.href = ReedrInput.url();
              (doc.head || doc.documentElement).prepend(base);
              var r = new Readability(doc, {charThreshold: 300}).parse();
              $RESULT
            })()"""

        private const val PARSE_DOCUMENT = """
            var doc = document.cloneNode(true);
            var r = new Readability(doc, {charThreshold: 300}).parse();
            $RESULT"""

        /** evaluateJavascript hands back the JSON encoding of the returned value: a JSON string holding our JSON object, or `null`. */
        internal fun parse(raw: String?): Extracted? {
            if (raw.isNullOrEmpty() || raw == "null") return null
            val json = JSONObject(JSONTokener(raw).nextValue() as? String ?: return null)
            fun str(key: String) = json.optString(key).takeIf { !json.isNull(key) && it.isNotBlank() }
            return Extracted(
                title = str("title"), byline = str("byline"), siteName = str("siteName"), excerpt = str("excerpt"), lang = str("lang"),
                content = str("content") ?: return null, length = json.optInt("length"), leadImageUrl = str("lead"),
            )
        }
    }
}
