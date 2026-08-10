package com.spoongecko.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlRouterTest {

    @Test fun `resolveNavigationUrl keeps explicit scheme`() {
        assertEquals("https://example.com", UrlRouter.resolveNavigationUrl("https://example.com"))
        assertEquals("http://192.168.1.2", UrlRouter.resolveNavigationUrl("http://192.168.1.2"))
    }

    @Test fun `resolveNavigationUrl defaults local hosts to http`() {
        assertEquals("http://192.168.1.20", UrlRouter.resolveNavigationUrl("192.168.1.20"))
        assertEquals("http://localhost:8080", UrlRouter.resolveNavigationUrl("localhost:8080"))
        assertEquals("http://10.0.2.2:3000", UrlRouter.resolveNavigationUrl("10.0.2.2:3000"))
    }

    @Test fun `resolveNavigationUrl defaults public domains to https`() {
        assertEquals("https://example.com", UrlRouter.resolveNavigationUrl("example.com"))
    }

    @Test fun `resolveNavigationUrl returns null for search terms`() {
        assertNull(UrlRouter.resolveNavigationUrl("android gecko browser"))
        assertNull(UrlRouter.resolveNavigationUrl("what is v1.0"))
        assertNull(UrlRouter.resolveNavigationUrl("v1.0"))
    }
}
