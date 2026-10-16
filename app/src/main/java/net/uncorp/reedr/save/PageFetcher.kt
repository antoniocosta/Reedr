package net.uncorp.reedr.save

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

class UnsupportedPageException(message: String) : IOException(message)

/** A downloaded page: [html] is already charset-decoded; [finalUrl] is after redirects. */
class FetchedPage(val finalUrl: String, val html: String, val leadImageUrl: String?)

/** One plain GET with a normal browser User-Agent; jsoup decodes the bytes (BOM, HTTP header and <meta charset>). */
class PageFetcher(private val client: OkHttpClient, private val userAgent: () -> String) {

    fun fetch(url: String): FetchedPage {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent())
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
            .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = "The site answered HTTP ${response.code}"
                // client errors won't fix themselves (except timeouts and rate limits); server errors are retried
                if (response.code in 400..499 && response.code != 408 && response.code != 429) throw UnsupportedPageException(message)
                throw IOException(message)
            }
            val finalUrl = response.request.url.toString()
            val type = response.body.contentType()
            val subtype = type?.let { "${it.type}/${it.subtype}" }.orEmpty()
            val bytes = response.body.source().use { src ->
                src.request(MAX_BYTES + 1)
                if (src.buffer.size > MAX_BYTES) throw UnsupportedPageException("The page is too large")
                src.buffer.readByteArray()
            }
            return when {
                subtype == "text/plain" -> {
                    val text = String(bytes, type?.charset() ?: Charsets.UTF_8)
                    FetchedPage(finalUrl, plainTextAsHtml(text, finalUrl), null)
                }
                subtype.isEmpty() || subtype.contains("html") || subtype.contains("xml") -> {
                    val doc = Jsoup.parse(bytes.inputStream(), type?.charset()?.name(), finalUrl)
                    FetchedPage(finalUrl, doc.outerHtml(), leadImage(doc))
                }
                subtype == "application/pdf" -> throw UnsupportedPageException("PDF files can't be saved yet")
                else -> throw UnsupportedPageException("This link isn't a web page ($subtype)")
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 15L * 1024 * 1024

        fun leadImage(doc: Document): String? =
            sequenceOf("meta[property=og:image]", "meta[name=twitter:image]", "meta[property=og:image:url]")
                .mapNotNull { doc.selectFirst(it)?.absUrl("content")?.takeIf(String::isNotEmpty) }
                .firstOrNull()

        private fun plainTextAsHtml(text: String, url: String): String {
            val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: url
            val body = text.split(Regex("""\n\s*\n""")).joinToString("") { "<p>" + org.jsoup.nodes.Entities.escape(it.trim()) + "</p>" }
            return "<html><head><title>${org.jsoup.nodes.Entities.escape(title)}</title></head><body><article>$body</article></body></html>"
        }
    }
}
