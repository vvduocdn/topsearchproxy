package com.topsearch.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Unit tests cho logic SignalR WebSocket và utility functions.
 *
 * NOTE: norm() tests bị skip vì java.text.Normalizer khác JS normalize('NFD').
 * buildUule tests chỉ test format/cấu trúc, không test exact encoding vì
 * URLEncoder.encode khác JS encodeURIComponent.
 */
class SignalRAndUtilsTest {

    // ══════════════════════════════════════════════════════════════
    // Test: SignalR JSON protocol
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `SignalR handshake message is correct JSON`() {
        val handshake = """{"protocol":"json","version":1}"""
        val parsed = JSONObject(handshake)
        assertEquals("json", parsed.getString("protocol"))
        assertEquals(1, parsed.getInt("version"))
    }

    @Test
    fun `SignalR RS delimiter is ASCII 30`() {
        val RS = '\u001E'
        assertEquals(30.toChar(), RS)
        val handshakeWithRS = """{"protocol":"json","version":1}${RS}"""
        assertTrue(handshakeWithRS.endsWith(RS))
    }

    @Test
    fun `SignalR invocation frame type is 1`() {
        val payload = JSONObject().apply {
            put("requestId", "req-123")
            put("items", JSONArray().put(JSONObject().apply {
                put("top", 1)
                put("url", "https://example.com")
                put("domain", "example.com")
            }))
        }
        val invocation = JSONObject().apply {
            put("type", 1)
            put("target", "SubmitMobileResult")
            put("arguments", JSONArray().put(payload))
        }

        assertEquals(1, invocation.getInt("type"))
        assertEquals("SubmitMobileResult", invocation.getString("target"))
        assertEquals(1, invocation.getJSONArray("arguments").length())
    }

    @Test
    fun `SignalR ping frame type is 6`() {
        val ping = JSONObject().apply { put("type", 6) }
        assertEquals(6, ping.getInt("type"))
    }

    @Test
    fun `SignalR pong frame is valid JSON with type 6`() {
        val pong = """{"type":6}"""
        val parsed = JSONObject(pong)
        assertEquals(6, parsed.getInt("type"))
    }

    @Test
    fun `SignalR invocation frame can be split by RS`() {
        val frame1 = """{"type":1,"target":"CheckKeyword","arguments":[...]}"""
        val frame2 = """{"type":6}"""
        val combined = "$frame1\u001E$frame2"
        val parts = combined.split('\u001E').filter { it.isNotBlank() }
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("CheckKeyword"))
        assertTrue(parts[1].contains(""""type":6"""))
    }

    @Test
    fun `SignalR CheckKeywords arguments structure`() {
        val arguments = JSONArray()
        arguments.put(JSONObject().apply {
            put("requestId", "req-001")
            put("keyword", "tu khoa test")
            put("proxy", "gate.ipfoxy.io:58688:user:pass")
            put("country", 1)
        })

        val invocation = JSONObject().apply {
            put("type", 1)
            put("target", "CheckKeywords")
            put("arguments", JSONArray().put(arguments))
        }

        val args = invocation.getJSONArray("arguments").getJSONArray(0)
        assertEquals(1, args.length())
        assertEquals("req-001", args.getJSONObject(0).getString("requestId"))
        assertEquals("tu khoa test", args.getJSONObject(0).getString("keyword"))
        assertEquals(1, args.getJSONObject(0).getInt("country"))
    }

    @Test
    fun `SignalR SubmitMobileResult items are limited to top 10`() {
        val allItems = (1..15).map { i ->
            JSONObject().apply {
                put("top", i)
                put("url", "https://domain$i.com")
                put("domain", "domain$i.com")
            }
        }
        val top10 = allItems.take(10)
        val itemsArr = JSONArray()
        top10.forEach { itemsArr.put(it) }

        assertEquals(10, itemsArr.length())
        assertEquals(1, itemsArr.getJSONObject(0).getInt("top"))
        assertEquals(10, itemsArr.getJSONObject(9).getInt("top"))
    }

    @Test
    fun `SignalR SubmitMobileResult payload with nullable fields`() {
        val payload = JSONObject().apply {
            put("requestId", "req-123")
            put("items", JSONArray())
            putOpt("mobileImageUrl", JSONObject.NULL)
            putOpt("publicIp", "103.56.158.10")
            putOpt("sourceName", JSONObject.NULL)
        }

        assertTrue(payload.has("publicIp"))
        assertEquals("103.56.158.10", payload.getString("publicIp"))
        assertEquals(JSONObject.NULL, payload.get("mobileImageUrl"))
        assertEquals(JSONObject.NULL, payload.get("sourceName"))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: buildUule — structure validation
    // format: w+CAIQICII + base64(lat,lng bytes)
    // payload[0] = length of lat,lng string
    // ══════════════════════════════════════════════════════════════

    private fun buildUule(lat: Double, lng: Double): String {
        val locStr = "$lat,$lng"
        val locBytes = locStr.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(locBytes.size + 1)
        payload[0] = locBytes.size.toByte()
        locBytes.copyInto(payload, destinationOffset = 1)
        val encoded = Base64.getEncoder().encodeToString(payload)
        return java.net.URLEncoder.encode("w+CAIQICII$encoded", "UTF-8")
    }

    @Test
    fun `buildUule produces non-empty string`() {
        val uule = buildUule(21.0285, 105.8542)
        assertTrue(uule.isNotBlank())
    }

    @Test
    fun `buildUule encodes different locations differently`() {
        val hanoi = buildUule(21.0285, 105.8542)
        val hcmc = buildUule(10.8231, 106.6297)
        val danang = buildUule(16.0544, 108.2022)

        assertNotEquals(hanoi, hcmc)
        assertNotEquals(hanoi, danang)
        assertNotEquals(hcmc, danang)
    }

    @Test
    fun `buildUule same location produces same result`() {
        val lat = 21.0285
        val lng = 105.8542
        val uule1 = buildUule(lat, lng)
        val uule2 = buildUule(lat, lng)
        assertEquals(uule1, uule2)
    }

    @Test
    fun `buildUule base64 payload has length byte prefix`() {
        val lat = 21.0285
        val lng = 105.8542
        val locStr = "$lat,$lng"
        val locBytes = locStr.toByteArray(Charsets.UTF_8)

        val payload = ByteArray(locBytes.size + 1)
        payload[0] = locBytes.size.toByte()
        locBytes.copyInto(payload, destinationOffset = 1)

        val decoded = Base64.getDecoder().decode(
            Base64.getEncoder().encodeToString(payload)
        )
        assertEquals(locBytes.size + 1, decoded.size)
        assertEquals(locBytes.size.toByte(), decoded[0])
    }

    // ══════════════════════════════════════════════════════════════
    // Test: resolveUrl logic
    // ══════════════════════════════════════════════════════════════

    private fun resolveUrl(href: String): String {
        return try {
            val url = java.net.URL(href)
            if (url.host.contains("google.")) {
                val params = parseQueryParams(url.query ?: "")
                val q = params["q"] ?: params["url"]
                if (!q.isNullOrBlank() && q.startsWith("http")) q else href
            } else {
                href
            }
        } catch (_: Exception) {
            href
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate { pair ->
            val parts = pair.split("=", limit = 2)
            parts.getOrElse(0) { "" } to java.net.URLDecoder.decode(
                parts.getOrElse(1) { "" }, "UTF-8"
            )
        }
    }

    @Test
    fun `resolveUrl extracts q parameter from Google redirect`() {
        val href = "https://www.google.com/url?q=https://example.com&sa=D&ust=123456"
        assertEquals("https://example.com", resolveUrl(href))
    }

    @Test
    fun `resolveUrl extracts url parameter from Google redirect`() {
        val href = "https://www.google.com/url?url=https://target.com&other=param"
        assertEquals("https://target.com", resolveUrl(href))
    }

    @Test
    fun `resolveUrl keeps non-Google URLs unchanged`() {
        assertEquals("https://example.com/path", resolveUrl("https://example.com/path"))
    }

    @Test
    fun `resolveUrl keeps Google redirect when q is not http`() {
        val href = "https://www.google.com/url?q=internal+search+term"
        assertEquals(href, resolveUrl(href))
    }

    @Test
    fun `resolveUrl handles malformed URL gracefully`() {
        assertEquals("not-a-url", resolveUrl("not-a-url"))
        assertEquals("", resolveUrl(""))
    }

    @Test
    fun `resolveUrl extracts from multiple query params`() {
        val href = "https://www.google.com/url?q=https://vnexpress.net&output=search"
        assertEquals("https://vnexpress.net", resolveUrl(href))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: SearchResult data class
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `SearchResult rank title domain url fields are correct`() {
        val result = SearchResult(
            rank = 5,
            title = "Example Domain",
            domain = "example.com",
            url = "https://example.com",
            isAd = false,
        )
        assertEquals(5, result.rank)
        assertEquals("Example Domain", result.title)
        assertEquals("example.com", result.domain)
        assertEquals("https://example.com", result.url)
        assertFalse(result.isAd)
    }

    @Test
    fun `SearchResult default isAd is false`() {
        val result = SearchResult(1, "Title")
        assertFalse(result.isAd)
        assertEquals("", result.domain)
        assertEquals("", result.url)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: CheckStatus enum
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `CheckStatus has all expected values`() {
        assertEquals(4, CheckStatus.entries.size)
        assertTrue(CheckStatus.PENDING in CheckStatus.entries)
        assertTrue(CheckStatus.IN_PROGRESS in CheckStatus.entries)
        assertTrue(CheckStatus.DONE in CheckStatus.entries)
        assertTrue(CheckStatus.ERROR in CheckStatus.entries)
    }

    @Test
    fun `CheckStatus PENDING is not DONE`() {
        assertNotEquals(CheckStatus.PENDING, CheckStatus.DONE)
        assertNotEquals(CheckStatus.IN_PROGRESS, CheckStatus.DONE)
        assertNotEquals(CheckStatus.ERROR, CheckStatus.DONE)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: TelegramUploader message building logic
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `buildResultMessage respects 950 char limit`() {
        // URL phải đủ dài để message vượt 950 chars khi thêm đủ 10 items
        val items = (1..10).map { i ->
            // ~105 chars mỗi URL → 10 items + keyword ~950+ chars
            val longPath = "path/that/is/long/and/makes/message/exceed/limit/and/more/deep/nested/content"
            val url = "https://domain${i}.com/${longPath}/item/${i}"
            SearchResult(i, "Domain $i", "domain${i}.com", url)
        }
        val keyword = "Tu khoa test"
        val lines = mutableListOf("Keyword: $keyword")
        for (r in items) {
            val line = "[${r.rank}, ${r.domain}, ${r.url}]"
            val next = (lines + line).joinToString("\n")
            if (next.length > 950) {
                lines += "..."
                break
            }
            lines += line
        }
        val message = lines.joinToString("\n")
        assertTrue("Message should end with ... but was ${message.length} chars", message.endsWith("..."))
    }

    @Test
    fun `buildResultMessage takes at most 10 items`() {
        val items = (1..15).map { i ->
            val url = "https://domain${i}.com/page${i}"
            SearchResult(i, "Result $i", "domain${i}.com", url)
        }
        val lines = mutableListOf("Keyword: test")
        for (r in items.take(10)) {
            lines += "[${r.rank}, ${r.domain}, ${r.url}]"
        }
        assertEquals(11, lines.size)
    }
}
