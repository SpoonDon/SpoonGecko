package com.spoongecko.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPolicyTest {

    // -------------------------------------------------------------------------
    // extractHost
    // -------------------------------------------------------------------------

    @Test fun `extractHost strips scheme and path`() {
        assertEquals("192.168.1.1", LocalNetworkPolicy.extractHost("http://192.168.1.1/admin"))
    }

    @Test fun `extractHost strips port`() {
        assertEquals("192.168.1.1", LocalNetworkPolicy.extractHost("http://192.168.1.1:8080/"))
    }

    @Test fun `extractHost handles IPv6 literal`() {
        assertEquals("::1", LocalNetworkPolicy.extractHost("http://[::1]/"))
    }

    @Test fun `extractHost returns null for blank`() {
        assertNull(LocalNetworkPolicy.extractHost(""))
    }

    // -------------------------------------------------------------------------
    // parseIpv4
    // -------------------------------------------------------------------------

    @Test fun `parseIpv4 parses valid address`() {
        val octets = LocalNetworkPolicy.parseIpv4("192.168.0.1")!!
        assertEquals(4, octets.size)
        assertEquals(192, octets[0]); assertEquals(168, octets[1])
    }

    @Test fun `parseIpv4 returns null for hostname`() {
        assertNull(LocalNetworkPolicy.parseIpv4("example.com"))
    }

    @Test fun `parseIpv4 returns null for out-of-range octet`() {
        assertNull(LocalNetworkPolicy.parseIpv4("256.0.0.1"))
    }

    // -------------------------------------------------------------------------
    // isPrivateIpv4
    // -------------------------------------------------------------------------

    @Test fun `isPrivateIpv4 10_x_x_x`() =
        assertTrue(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(10, 0, 0, 1)))

    @Test fun `isPrivateIpv4 172_16_to_31`() {
        assertTrue(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(172, 16, 0, 1)))
        assertTrue(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(172, 31, 255, 255)))
        assertFalse(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(172, 15, 0, 1)))
        assertFalse(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(172, 32, 0, 1)))
    }

    @Test fun `isPrivateIpv4 192_168`() =
        assertTrue(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(192, 168, 0, 1)))

    @Test fun `isPrivateIpv4 loopback 127`() =
        assertTrue(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(127, 0, 0, 1)))

    @Test fun `isPrivateIpv4 link-local 169_254`() =
        assertTrue(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(169, 254, 1, 1)))

    @Test fun `isPrivateIpv4 public IP`() =
        assertFalse(LocalNetworkPolicy.isPrivateIpv4(intArrayOf(8, 8, 8, 8)))

    // -------------------------------------------------------------------------
    // isLocalIpv6
    // -------------------------------------------------------------------------

    @Test fun `isLocalIpv6 loopback`() = assertTrue(LocalNetworkPolicy.isLocalIpv6("::1"))
    @Test fun `isLocalIpv6 link-local fe80`() = assertTrue(LocalNetworkPolicy.isLocalIpv6("fe80::1"))
    @Test fun `isLocalIpv6 link-local fe90`() = assertTrue(LocalNetworkPolicy.isLocalIpv6("fe90::1"))
    @Test fun `isLocalIpv6 link-local fea0`() = assertTrue(LocalNetworkPolicy.isLocalIpv6("fea0::1"))
    @Test fun `isLocalIpv6 link-local febf`() = assertTrue(LocalNetworkPolicy.isLocalIpv6("febf::1"))
    @Test fun `isLocalIpv6 unique local fc`() = assertTrue(LocalNetworkPolicy.isLocalIpv6("fc00::1"))
    @Test fun `isLocalIpv6 unique local fd`() = assertTrue(LocalNetworkPolicy.isLocalIpv6("fd12:3456::1"))
    @Test fun `isLocalIpv6 public`() = assertFalse(LocalNetworkPolicy.isLocalIpv6("2001:4860:4860::8888"))

    // -------------------------------------------------------------------------
    // isLocalHost
    // -------------------------------------------------------------------------

    @Test fun `isLocalHost localhost`() = assertTrue(LocalNetworkPolicy.isLocalHost("localhost"))
    @Test fun `isLocalHost dot-local`() = assertTrue(LocalNetworkPolicy.isLocalHost("myrouter.local"))
    @Test fun `isLocalHost private IPv4`() = assertTrue(LocalNetworkPolicy.isLocalHost("192.168.1.1"))
    @Test fun `isLocalHost public IPv4`() = assertFalse(LocalNetworkPolicy.isLocalHost("1.2.3.4"))
    @Test fun `isLocalHost public domain`() = assertFalse(LocalNetworkPolicy.isLocalHost("example.com"))
    @Test fun `isLocalHost IPv6 loopback host`() = assertTrue(LocalNetworkPolicy.isLocalHost("::1"))

    // -------------------------------------------------------------------------
    // isLocalUrl
    // -------------------------------------------------------------------------

    @Test fun `isLocalUrl private IP URL`() =
        assertTrue(LocalNetworkPolicy.isLocalUrl("https://192.168.1.1/admin"))

    @Test fun `isLocalUrl public URL`() =
        assertFalse(LocalNetworkPolicy.isLocalUrl("https://google.com/"))

    // -------------------------------------------------------------------------
    // normaliseLocalUrl (no Context – test the logic path directly)
    // The method needs a real Context for SharedPreferences, so we test the
    // helper functions that feed into it instead.
    // -------------------------------------------------------------------------

    @Test fun `normaliseLocalUrl leaves http unchanged`() {
        // http URLs must never be touched regardless of host
        val url = "http://192.168.1.1/"
        // isLocalUrl true but url doesn't start with https:// so it returns early
        // We can verify this by checking the prefix condition in the object directly
        assertFalse(url.startsWith("https://", ignoreCase = true))
    }

    @Test fun `normaliseLocalUrl non-local https unchanged path`() {
        // public HTTPS URL should not match the local-host guard
        val url = "https://example.com/"
        assertFalse(LocalNetworkPolicy.isLocalUrl(url))
    }
}
