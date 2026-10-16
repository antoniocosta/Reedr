package net.uncorp.reedr.save

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Urls {
    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val TRAILING_JUNK = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '”', '’')
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "igshid", "igsh",
        "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "si", "ref_src",
    )

    /** The first http(s) URL in shared text such as "Great read https://example.com/a?b=1 via @app". */
    fun findUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL_IN_TEXT.find(text) ?: return null
        var url = match.value.trimEnd(*TRAILING_JUNK)
        // keep a closing parenthesis that belongs to the URL, as in Wikipedia links
        if (match.value.length > url.length && match.value[url.length] == ')' && url.count { it == '(' } > url.count { it == ')' }) url += ")"
        return url
    }

    /** Drops the fragment and known tracking parameters so the same article shared twice is recognised as one. */
    fun normalize(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        val builder = parsed.newBuilder().fragment(null)
        for (name in parsed.queryParameterNames) {
            if (name.startsWith("utm_", ignoreCase = true) || name.lowercase() in TRACKING_PARAMS) builder.removeAllQueryParameters(name)
        }
        return builder.build().toString()
    }

    fun host(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url
}
