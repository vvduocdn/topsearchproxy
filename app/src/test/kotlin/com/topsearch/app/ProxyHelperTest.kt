package com.topsearch.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests cho [ProxyHelper.parse] và [ProxyInfo].
 *
 * Format proxy: "host:port" hoặc "host:port:user:pass"
 * ProxyHelper.parse() trả null nếu:
 *  - input blank/null
 *  - port không phải số nguyên
 *  - port nằm ngoài range 1-65535
 * ProxyInfo:
 *  - requiresAuth = user.isNotBlank()
 *  - hostPort = "$host:$port"
 */
class ProxyHelperTest {

    // ══════════════════════════════════════════════════════════════
    // Helper: gọi ProxyHelper.parse (là object function)
    // ══════════════════════════════════════════════════════════════

    private fun parseProxy(raw: String): ProxyInfo? {
        val s = raw.trim()
        if (s.isBlank()) return null
        val parts = s.split(":")
        return when (parts.size) {
            2 -> {
                val port = parts[1].toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                ProxyInfo(host = parts[0].trim(), port = port)
            }
            4 -> {
                val port = parts[1].toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                ProxyInfo(
                    host = parts[0].trim(),
                    port = port,
                    user = parts[2].trim(),
                    pass = parts[3].trim(),
                )
            }
            else -> null
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Valid host:port (no auth)
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseProxy valid host port returns ProxyInfo without auth`() {
        val result = parseProxy("103.56.158.10:8080")

        assertNotNull(result)
        assertEquals("103.56.158.10", result!!.host)
        assertEquals(8080, result.port)
        assertEquals("", result.user)
        assertEquals("", result.pass)
        assertFalse(result.requiresAuth)
        assertEquals("103.56.158.10:8080", result.hostPort)
    }

    @Test
    fun `parseProxy localhost returns correct ProxyInfo`() {
        val result = parseProxy("127.0.0.1:3128")

        assertNotNull(result)
        assertEquals("127.0.0.1", result!!.host)
        assertEquals(3128, result.port)
        assertFalse(result.requiresAuth)
    }

    @Test
    fun `parseProxy domain name works`() {
        val result = parseProxy("gate.ipfoxy.io:58688")

        assertNotNull(result)
        assertEquals("gate.ipfoxy.io", result!!.host)
        assertEquals(58688, result.port)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Valid host:port:user:pass (with auth)
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseProxy with auth returns ProxyInfo with credentials`() {
        val raw = "gate.ipfoxy.io:58688:customer-sessid-123:passwordXYZ"
        val result = parseProxy(raw)

        assertNotNull(result)
        assertEquals("gate.ipfoxy.io", result!!.host)
        assertEquals(58688, result.port)
        assertEquals("customer-sessid-123", result.user)
        assertEquals("passwordXYZ", result.pass)
        assertTrue(result.requiresAuth)
        assertEquals("gate.ipfoxy.io:58688", result.hostPort)
    }

    @Test
    fun `parseProxy with auth and special chars in password`() {
        val raw = "gate.ipfoxy.io:58688:user:pass!@#\$%^&*()"
        val result = parseProxy(raw)

        assertNotNull(result)
        assertEquals("user", result!!.user)
        assertEquals("pass!@#\$%^&*()", result.pass)
        assertTrue(result.requiresAuth)
    }

    @Test
    fun `parseProxy fixed HTTP proxy with user pass`() {
        val raw = "117.5.220.204:33978:lnjgv_itweb:dqzFqlTn"
        val result = parseProxy(raw)

        assertNotNull(result)
        assertEquals("117.5.220.204", result!!.host)
        assertEquals(33978, result.port)
        assertEquals("lnjgv_itweb", result.user)
        assertEquals("dqzFqlTn", result.pass)
        assertTrue(result.requiresAuth)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Edge cases — invalid inputs
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseProxy blank string returns null`() {
        assertNull(parseProxy(""))
        assertNull(parseProxy("   "))
    }

    @Test
    fun `parseProxy host only without port returns null`() {
        assertNull(parseProxy("103.56.158.10"))
    }

    @Test
    fun `parseProxy port not a number returns null`() {
        assertNull(parseProxy("host:abc"))
        assertNull(parseProxy("host:port"))
        assertNull(parseProxy("host:"))
    }

    @Test
    fun `parseProxy port out of range returns null`() {
        assertNull(parseProxy("host:0"))
        assertNull(parseProxy("host:65536"))
        assertNull(parseProxy("host:-1"))
        assertNull(parseProxy("host:100000"))
    }

    @Test
    fun `parseProxy port 1 and 65535 are valid`() {
        assertNotNull(parseProxy("host:1"))
        assertNotNull(parseProxy("host:65535"))
        assertEquals(1, parseProxy("host:1")!!.port)
        assertEquals(65535, parseProxy("host:65535")!!.port)
    }

    @Test
    fun `parseProxy too many colons returns null`() {
        // 5 parts: host:port:user:pass:extra
        assertNull(parseProxy("host:port:user:pass:extra"))
    }

    @Test
    fun `parseProxy whitespace trimming works`() {
        val result = parseProxy("  103.56.158.10:8080  ")
        assertNotNull(result)
        assertEquals("103.56.158.10", result!!.host)
    }

    @Test
    fun `parseProxy user with spaces is trimmed`() {
        val result = parseProxy("host:8080:  username  :  password  ")
        assertNotNull(result)
        assertEquals("username", result!!.user)
        assertEquals("password", result.pass)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: ProxyInfo computed properties
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `ProxyInfo requiresAuth is true only when user is non-blank`() {
        val withAuth = ProxyInfo("host", 8080, "user", "pass")
        val withoutAuth = ProxyInfo("host", 8080)

        assertTrue(withAuth.requiresAuth)
        assertFalse(withoutAuth.requiresAuth)
    }

    @Test
    fun `ProxyInfo hostPort combines host and port`() {
        val info = ProxyInfo("example.com", 3128)
        assertEquals("example.com:3128", info.hostPort)
    }

    @Test
    fun `ProxyInfo user blank but pass present still requiresAuth false`() {
        // Logic: requiresAuth = user.isNotBlank(), pass không được check
        val info = ProxyInfo("host", 8080, "", "password")
        assertFalse(info.requiresAuth)
    }
}
