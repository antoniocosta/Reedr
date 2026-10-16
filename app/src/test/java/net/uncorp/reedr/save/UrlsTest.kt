package net.uncorp.reedr.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {
    @Test fun findsUrlInSharedText() {
        assertEquals("https://example.com/a?b=1", Urls.findUrl("Great read https://example.com/a?b=1, via @app"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", Urls.findUrl("(see https://en.wikipedia.org/wiki/Foo_(bar))"))
        assertEquals("http://x.org/p", Urls.findUrl("http://x.org/p."))
        assertNull(Urls.findUrl("no link here"))
        assertNull(Urls.findUrl(null))
    }

    @Test fun normalizeStripsTrackingAndFragment() {
        assertEquals(
            "https://example.com/post?id=7",
            Urls.normalize("https://example.com/post?utm_source=tw&id=7&fbclid=abc#comments"),
        )
        assertEquals("https://example.com/", Urls.normalize("https://example.com"))
        assertNull(Urls.normalize("ftp://example.com/file"))
    }

    @Test fun hostDropsWww() {
        assertEquals("nytimes.com", Urls.host("https://www.nytimes.com/x"))
    }
}
