package com.spoongecko.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityCredentialOriginTest {
    @Test
    fun `uses login origin when provided`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = "https://example.com",
            activeTabUrl = "https://fallback.test/login"
        )

        assertEquals("https://example.com", origin)
    }

    @Test
    fun `falls back to active tab url when login origin is missing`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = null,
            activeTabUrl = "https://example.com/login"
        )

        assertEquals("https://example.com", origin)
    }

    @Test
    fun `falls back to active tab url when login origin is blank`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = " ",
            activeTabUrl = "https://example.com/login"
        )

        assertEquals("https://example.com", origin)
    }

    @Test
    fun `falls back to active tab url when login origin is empty`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = "",
            activeTabUrl = "https://example.com/login"
        )

        assertEquals("https://example.com", origin)
    }

    @Test
    fun `keeps fallback port when active tab url has explicit port`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = null,
            activeTabUrl = "https://example.com:8443/login"
        )

        assertEquals("https://example.com:8443", origin)
    }

    @Test
    fun `returns null when login origin is blank and active tab url is missing`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = " ",
            activeTabUrl = null
        )

        assertNull(origin)
    }

    @Test
    fun `returns null when both login origin and active tab url are null`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = null,
            activeTabUrl = null
        )

        assertNull(origin)
    }

    @Test
    fun `returns null when fallback active tab url is not http or https`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = null,
            activeTabUrl = "about:blank"
        )

        assertNull(origin)
    }
}
