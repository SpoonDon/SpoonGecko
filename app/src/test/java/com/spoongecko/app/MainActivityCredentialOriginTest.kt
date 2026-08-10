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

        assertEquals("https://example.com/login", origin)
    }

    @Test
    fun `returns null when both origin and active tab are missing`() {
        val origin = resolveCredentialOrigin(
            loginOrigin = " ",
            activeTabUrl = null
        )

        assertNull(origin)
    }
}
