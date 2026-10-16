package net.uncorp.reedr.ui.reader

import net.uncorp.reedr.data.Article
import net.uncorp.reedr.data.ArticleFiles
import net.uncorp.reedr.data.Highlight
import net.uncorp.reedr.data.ReaderPrefs
import org.jsoup.nodes.Entities
import org.json.JSONObject

/** The reader page around an article's stored body. Style values are inlined so the first frame already has the right theme. */
object ReaderPage {
    fun build(article: Article, body: String, highlights: List<Highlight>, prefs: ReaderPrefs, dark: Boolean): String {
        val vars = JSONObject(ReaderWebView.styleVars(prefs, dark))
        val css = vars.keys().asSequence().joinToString("; ") { "$it: ${vars.getString(it)}" }
        val meta = listOfNotNull(
            article.siteName,
            article.byline?.takeIf { it != article.siteName },
            article.readingMinutes.takeIf { it > 0 }?.let { "$it min read" },
        ).joinToString(" · ")
        val init = JSONObject()
            .put("progress", article.progress.toDouble())
            .put("highlights", org.json.JSONArray(ReaderWebView.highlightsJson(highlights)))
        val lang = article.lang?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) } ?: ""
        val assets = "${ArticleFiles.READER_ORIGIN}/assets"
        return """<!doctype html>
<html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="$assets/reader.css">
<style>:root { $css }</style>
</head><body><div id="page">
<header><h1>${Entities.escape(article.title)}</h1><p class="meta">${Entities.escape(meta)}</p></header>
<main id="content">$body</main>
<footer><a href="${Entities.escape(article.url)}">Original article</a></footer>
</div>
<script>window.__reedr = $init;</script>
<script src="$assets/reader.js"></script>
</body></html>"""
    }
}
