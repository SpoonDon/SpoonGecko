package com.spoongecko.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDelegateAttacherTest {

    @Test fun `buildLoadFailureMessage includes URL when present`() {
        assertEquals(
            "Failed to load: http://192.168.0.1",
            SessionDelegateAttacher.buildLoadFailureMessage("http://192.168.0.1")
        )
    }

    @Test fun `buildLoadFailureMessage falls back when URL missing`() {
        assertEquals("Failed to load page", SessionDelegateAttacher.buildLoadFailureMessage(null))
        assertEquals("Failed to load page", SessionDelegateAttacher.buildLoadFailureMessage("   "))
    }

    @Test fun `buildLoadErrorHtml escapes user visible values`() {
        val html = SessionDelegateAttacher.buildLoadErrorHtml(
            "https://example.com/?q=<tag>",
            "Error <boom> & fail"
        )

        assertTrue(html.contains("Failed to load: https://example.com/?q=&lt;tag&gt;"))
        assertTrue(html.contains("Error &lt;boom&gt; &amp; fail"))
    }
}
