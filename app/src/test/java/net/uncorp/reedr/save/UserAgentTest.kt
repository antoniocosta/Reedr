package net.uncorp.reedr.save

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentTest {
    @Test fun webViewStringBecomesChrome() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Mobile Safari/537.36",
            UserAgent.fromWebView(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36",
            ),
        )
    }

    @Test fun chromeStringIsUnchanged() {
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, UserAgent.fromWebView(chrome))
    }
}
