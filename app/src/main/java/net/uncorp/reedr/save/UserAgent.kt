package net.uncorp.reedr.save

import android.content.Context
import android.webkit.WebSettings

/**
 * The User-Agent of a normal Chrome on this phone. It is the system WebView's own string without the two parts that
 * mark an embedded WebView ("; wv" and "Version/4.0"), which some sites answer with different pages or bot checks.
 * It stays current by itself: the Chrome version is the one of the installed WebView.
 */
object UserAgent {
    fun chrome(context: Context): String = fromWebView(WebSettings.getDefaultUserAgent(context))

    internal fun fromWebView(ua: String): String = ua
        .replace("; wv)", ")")
        .replace(Regex("""\s?Version/\d+(\.\d+)*"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}
