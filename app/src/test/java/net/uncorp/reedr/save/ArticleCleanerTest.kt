package net.uncorp.reedr.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleCleanerTest {
    private val base = "https://news.example.com/2026/story.html"

    @Test fun stripsActiveContentAndAbsolutizesLinks() {
        val p = ArticleCleaner.prepare(
            """<div><p onclick="evil()">Hello <a href="/other">link</a></p><script>alert(1)</script>
               <iframe src="https://ads.example"></iframe><p style="color:red">World</p></div>""",
            base, null,
        )
        val r = ArticleCleaner.finish(p, emptyMap())
        assertFalse(r.html.contains("script"))
        assertFalse(r.html.contains("iframe"))
        assertFalse(r.html.contains("onclick"))
        assertFalse(r.html.contains("style="))
        assertTrue(r.html.contains("""href="https://news.example.com/other""""))
        assertEquals("Hello link World", r.text)
    }

    @Test fun picksLazyAndSrcsetImages() {
        val p = ArticleCleaner.prepare(
            """<p>x</p><img src="data:image/gif;base64,AAAA" data-src="/lazy.jpg">
               <img src="small.jpg" srcset="a-400.jpg 400w, a-1200.jpg 1200w, a-2400.jpg 2400w">
               <picture><source srcset="p.avif" type="image/avif"><source srcset="p.webp 1x, p2.webp 2x"><img src="p.jpg"></picture>
               <img src="https://t.example/pixel.gif" width="1" height="1">""",
            base, null,
        )
        assertEquals(
            listOf("https://news.example.com/lazy.jpg", "https://news.example.com/2026/a-1200.jpg", "https://news.example.com/2026/p2.webp"),
            p.imageUrls,
        )
    }

    @Test fun srcsetWithoutSpacesAfterCommasAndPlaceholders() {
        val p = ArticleCleaner.prepare(
            """<p>x</p><img src="https://static.example/grey-placeholder.png">
               <img srcset="https://i.example/240/a.webp 240w,https://i.example/976/a.webp 976w,https://i.example/2048/a.webp 2048w">
               <img srcset="https://c.example/w_400,h_300/b.jpg 400w, https://c.example/w_1200,h_900/b.jpg 1200w">""",
            base, null,
        )
        assertEquals(listOf("https://i.example/976/a.webp", "https://c.example/w_1200,h_900/b.jpg"), p.imageUrls)
    }

    @Test fun finishSwapsLocalFilesAndDropsMissing() {
        val p = ArticleCleaner.prepare("""<p>t</p><figure><img src="/a.jpg"></figure><figure><img src="/b.jpg"><figcaption>cap</figcaption></figure>""", base, null)
        val r = ArticleCleaner.finish(p, mapOf("https://news.example.com/a.jpg" to null, "https://news.example.com/b.jpg" to "img/0.jpg"))
        assertEquals("""<p>t</p><figure><img src="img/0.jpg"><figcaption>cap</figcaption></figure>""", r.html)
    }

    @Test fun dropsHeadingThatRepeatsTitle() {
        val p = ArticleCleaner.prepare("<h1>The Story</h1><p>Body</p>", base, "The story")
        assertEquals("<p>Body</p>", ArticleCleaner.finish(p, emptyMap()).html)
    }

    @Test fun countsWords() {
        assertEquals(4, ArticleCleaner.countWords("one two  three\nfour"))
        assertEquals(2, ArticleCleaner.countWords("日本語で"))
    }
}
