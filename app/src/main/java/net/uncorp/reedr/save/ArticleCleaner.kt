package net.uncorp.reedr.save

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Post-processes Readability's output: picks one real URL per image (lazy-loading attributes, srcset, <picture>),
 * sanitizes to an allowlist (no scripts, iframes, styles or event handlers survive), makes links absolute and
 * lists the images so they can be downloaded and swapped for local files.
 */
object ArticleCleaner {
    class Prepared internal constructor(internal val doc: Document, val imageUrls: List<String>)

    private const val MAX_IMAGES = 80
    private const val TARGET_WIDTH = 1600
    private val LAZY_SRC_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-hi-res-src", "data-full-src")
    private val LAZY_SRCSET_ATTRS = listOf("srcset", "data-srcset", "data-lazy-srcset")
    private val SRCSET_CANDIDATE = Regex("""([^\s,]\S*?)\s+(\d+(?:\.\d+)?)([wx])\b""")
    private val PLACEHOLDER = Regex("""placeholder|blank\.(gif|png)|spacer\.gif|1x1\.""", RegexOption.IGNORE_CASE)

    private val safelist: Safelist = Safelist.relaxed()
        .addTags("figure", "figcaption", "hr", "mark", "del", "ins", "s", "abbr", "time", "details", "summary", "section", "article", "main")
        .addAttributes("abbr", "title")
        .addAttributes("time", "datetime")
        .preserveRelativeLinks(false)

    fun prepare(contentHtml: String, baseUrl: String, title: String?): Prepared {
        val dirty = Jsoup.parseBodyFragment(contentHtml, baseUrl)
        dirty.select("picture").forEach(::flattenPicture)
        dirty.select("img").forEach { resolveImage(it) }
        dirty.select("noscript").remove()

        val clean = Cleaner(safelist).clean(dirty)
        clean.outputSettings().prettyPrint(false)

        clean.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isBlank() || isTrackingPixel(img)) img.remove()
        }
        dropDuplicateTitle(clean, title)
        removeEmpty(clean)

        val urls = LinkedHashSet<String>()
        for (img in clean.select("img")) {
            if (urls.size >= MAX_IMAGES) img.remove() else urls += img.attr("src")
        }
        return Prepared(clean, urls.toList())
    }

    /**
     * Rewrites images to their local files (relative to the article folder, e.g. `img/3.jpg`).
     * [localPaths] maps an original URL to its file, or to null when it could not be downloaded; such images are dropped,
     * because the reader works offline and never loads remote content.
     */
    fun finish(prepared: Prepared, localPaths: Map<String, String?>): Result {
        val doc = prepared.doc
        for (img in doc.select("img")) {
            val local = localPaths[img.attr("src")]
            if (local == null) {
                val parent = img.parent()
                img.remove()
                if (parent != null && parent.tagName() == "figure" && parent.select("img").isEmpty() && parent.text().isBlank()) parent.remove()
            } else {
                img.attr("src", local)
                img.removeAttr("width").removeAttr("height")
            }
        }
        removeEmpty(doc)
        val text = doc.body().text()
        return Result(html = doc.body().html(), text = text, wordCount = countWords(text))
    }

    data class Result(val html: String, val text: String, val wordCount: Int)

    fun countWords(text: String): Int {
        var words = 0
        var cjk = 0
        var inWord = false
        for (ch in text) {
            when {
                isCjk(ch) -> { cjk++; inWord = false }
                ch.isWhitespace() -> inWord = false
                !inWord -> { words++; inWord = true }
            }
        }
        // CJK text has no spaces; ~2 characters read like one word
        return words + cjk / 2
    }

    private fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA || block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private fun flattenPicture(picture: Element) {
        val img = picture.selectFirst("img") ?: run { picture.remove(); return }
        if (bestFromSrcset(img) == null) {
            picture.select("source").firstOrNull { it.hasAttr("srcset") && !it.attr("type").contains("avif") }
                ?.let { img.attr("srcset", it.attr("srcset")); img.attr("data-base", it.baseUri()) }
        }
        picture.replaceWith(img)
    }

    private fun resolveImage(img: Element) {
        val src = img.attr("src").trim()
        val chosen = bestFromSrcset(img)
            ?: LAZY_SRC_ATTRS.firstNotNullOfOrNull { a -> img.absUrl(a).takeIf { it.isNotEmpty() } }
            ?: img.absUrl("src").takeIf { src.isNotEmpty() && !src.startsWith("data:") && !PLACEHOLDER.containsMatchIn(src) }
        for (a in LAZY_SRC_ATTRS + LAZY_SRCSET_ATTRS + "data-base" + "sizes" + "loading" + "decoding") img.removeAttr(a)
        if (chosen == null) img.remove() else img.attr("src", chosen)
    }

    /** The candidate of a srcset closest to [TARGET_WIDTH] without going over (or the smallest if all are bigger). */
    internal fun bestFromSrcset(img: Element): String? {
        val raw = LAZY_SRCSET_ATTRS.firstNotNullOfOrNull { img.attr(it).takeIf(String::isNotBlank) } ?: return null
        // "url 480w, url 2x" — sites also omit the space after the comma, and URLs may contain commas, so match
        // url+descriptor pairs instead of splitting; a lone URL without descriptor counts as 1x
        val described = SRCSET_CANDIDATE.findAll(raw).map { m ->
            val n = m.groupValues[2].toFloat()
            m.groupValues[1] to if (m.groupValues[3] == "w") n.toInt() else (n * 800).toInt()
        }.toList()
        val candidates = described.ifEmpty { listOf(raw.trim().substringBefore(' ').trimEnd(',') to 800) }
            .filter { it.first.isNotEmpty() && !it.first.startsWith("data:") }
        if (candidates.isEmpty()) return null
        val pick = candidates.filter { it.second <= TARGET_WIDTH }.maxByOrNull { it.second } ?: candidates.minBy { it.second }
        val base = img.attr("data-base").ifEmpty { img.baseUri() }
        return resolve(base, pick.first)
    }

    private fun resolve(base: String, url: String): String? =
        runCatching { java.net.URI(base).resolve(url.replace(" ", "%20")).toString() }.getOrNull()?.takeIf { it.startsWith("http") }

    private fun isTrackingPixel(img: Element): Boolean {
        val w = img.attr("width").toIntOrNull()
        val h = img.attr("height").toIntOrNull()
        return (w != null && w <= 2) || (h != null && h <= 2)
    }

    private fun dropDuplicateTitle(doc: Document, title: String?) {
        if (title.isNullOrBlank()) return
        val first = doc.body().select("h1, h2").firstOrNull() ?: return
        if (first.text().trim().equals(title.trim(), ignoreCase = true)) first.remove()
    }

    private fun removeEmpty(doc: Document) {
        doc.body().select("p, div, span, section, figure, li, a").reversed().forEach { el ->
            if (el.text().isBlank() && el.select("img, hr, br, td").isEmpty() && el.childrenSize() == 0) el.remove()
        }
    }
}
