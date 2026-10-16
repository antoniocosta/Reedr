package net.uncorp.reedr.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import net.uncorp.reedr.data.ArticleFiles
import net.uncorp.reedr.data.Highlight
import net.uncorp.reedr.data.ReaderFont
import net.uncorp.reedr.data.ReaderPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

interface ReaderCallbacks {
    fun onProgress(fraction: Float)
    fun onHighlightTap(id: Long)
    fun onLinkTap(url: String)
    /** The user chose "Highlight" in the text selection menu; [json] is `{text, prefix, suffix}`. */
    fun onHighlightSelection(json: String)
    /** A pinch changed the text size (CSS px); called once when the fingers lift. */
    fun onTextSize(px: Float)
}

/**
 * The article view. It only ever shows local content: requests are served from the app's assets and the article's
 * folder (WebViewAssetLoader) and everything else is refused, so nothing is fetched from the network while reading.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWebView(context: Context, files: ArticleFiles, private val callbacks: ReaderCallbacks) : WebView(context) {

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(ArticleFiles.READER_HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/articles/", WebViewAssetLoader.InternalStoragePathHandler(context, files.root))
        .build()

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.textZoom = 100
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        webViewClient = object : WebViewClient() {
            // the page itself comes from loadDataWithBaseURL (main frame); every subresource must be a local file
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url) ?: if (request.isForMainFrame) null else BLOCKED

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                callbacks.onLinkTap(request.url.toString())
                return true
            }
        }
        addJavascriptInterface(Bridge(), "Reedr")
    }

    fun show(articleId: Long, html: String) {
        loadDataWithBaseURL("${ArticleFiles.READER_ORIGIN}/articles/$articleId/", html, "text/html", "utf-8", null)
    }

    fun applyStyle(prefs: ReaderPrefs, dark: Boolean) {
        textSize = prefs.fontSize
        evaluateJavascript("ReedrJS.setStyle(${styleVars(prefs, dark)})", null)
    }

    // ---- pinch to change the text size (like Chrome's reading mode); page zoom stays off

    private var textSize = ReaderPrefs().fontSize
    private var pinchStartSize = 0f
    private var frameQueued = false
    private var pinchScale = 1f
    private var pinching = false

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchStartSize = textSize
            pinchScale = 1f
            return true
        }

        // continuous (fractional px) so the text follows the fingers; at most one relayout per frame
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale *= detector.scaleFactor
            textSize = (pinchStartSize * pinchScale).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
            if (!frameQueued) {
                frameQueued = true
                postOnAnimation {
                    frameQueued = false
                    evaluateJavascript("ReedrJS.setStyle({'--size': '${"%.2f".format(java.util.Locale.ROOT, textSize)}px'})", null)
                }
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (textSize != pinchStartSize) callbacks.onTextSize(textSize)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !pinching) {
            // a second finger: the page stops scrolling and doesn't start a text selection
            pinching = true
            super.onTouchEvent(MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
        }
        if (pinching) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        return super.onTouchEvent(event)
    }
    fun addHighlight(h: Highlight) = evaluateJavascript("ReedrJS.applyHighlights(${highlightsJson(listOf(h))})", null)
    fun removeHighlight(id: Long) = evaluateJavascript("ReedrJS.removeHighlight($id)", null)
    fun scrollToHighlight(id: Long) = evaluateJavascript("ReedrJS.scrollToHighlight($id)", null)

    // ---- "Highlight" in the text selection toolbar

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(HighlightCallback(callback), type)

    private inner class HighlightCallback(private val wrapped: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val ok = wrapped.onCreateActionMode(mode, menu)
            menu.add(Menu.NONE, MENU_HIGHLIGHT, 0, "Highlight").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return ok
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = wrapped.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != MENU_HIGHLIGHT) return wrapped.onActionItemClicked(mode, item)
            evaluateJavascript("ReedrJS.captureSelection()") { raw ->
                val json = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                if (json != null) callbacks.onHighlightSelection(json)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = wrapped.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (wrapped is ActionMode.Callback2) wrapped.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun onProgress(fraction: Double) = post { callbacks.onProgress(fraction.toFloat()) }
        @JavascriptInterface fun onHighlightTap(id: Double) = post { callbacks.onHighlightTap(id.toLong()) }
        @JavascriptInterface fun onLinkTap(url: String) = post { callbacks.onLinkTap(url) }
    }

    companion object {
        private const val MENU_HIGHLIGHT = 0x5EED
        const val MIN_TEXT_SIZE = 12f
        const val MAX_TEXT_SIZE = 32f
        private val BLOCKED get() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        data class Palette(val fg: String, val muted: String, val bg: String, val link: String, val rule: String, val mark: String)

        fun palette(prefs: ReaderPrefs, dark: Boolean): Palette = when (prefs.theme) {
            net.uncorp.reedr.data.ReaderTheme.SEPIA -> Palette("#3b2f22", "#7a6a55", "#f6efe1", "#8a4b12", "#e2d6bf", "rgba(230,170,40,0.40)")
            net.uncorp.reedr.data.ReaderTheme.DARK -> DARK
            net.uncorp.reedr.data.ReaderTheme.LIGHT -> LIGHT
            net.uncorp.reedr.data.ReaderTheme.SYSTEM -> if (dark) DARK else LIGHT
        }

        // the collection palette (IPeekr, BLinkr): ink on paper, electric blue accent; highlights are the accent at low opacity
        private val LIGHT = Palette("#000000", "rgba(0,0,0,0.6)", "#ffffff", "#0000EE", "rgba(0,0,0,0.13)", "rgba(0,0,238,0.14)")
        private val DARK = Palette("#ffffff", "rgba(255,255,255,0.6)", "#202124", "#8AB4F8", "rgba(255,255,255,0.2)", "rgba(138,180,248,0.30)")

        fun styleVars(prefs: ReaderPrefs, dark: Boolean): String {
            val p = palette(prefs, dark)
            return JSONObject()
                .put("--fg", p.fg).put("--muted", p.muted).put("--bg", p.bg).put("--link", p.link).put("--rule", p.rule).put("--mark", p.mark)
                .put("--font", if (prefs.font == ReaderFont.SERIF) "Georgia, \"Noto Serif\", serif" else "system-ui, \"Roboto\", sans-serif")
                .put("--size", "${"%.2f".format(java.util.Locale.ROOT, prefs.fontSize)}px")
                .put("--line", prefs.lineHeight.toString())
                .toString()
        }

        fun highlightsJson(list: List<Highlight>): String = JSONArray(list.map {
            JSONObject().put("id", it.id).put("text", it.text).put("prefix", it.prefix).put("suffix", it.suffix)
        }).toString()
    }
}
