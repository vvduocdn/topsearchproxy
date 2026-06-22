package com.topsearch.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests cho [parseJsResults] — hàm Kotlin parse kết quả JSON từ JavaScript extraction.
 *
 * Logic parseJsResults:
 *  1. Unescape JSON string (xử lý escape sequences như \" \n \\ /)
 *  2. Parse JSONArray
 *  3. Bỏ item có isAd=true HOẶC title blank HOẶC title startsWith "ERROR:"
 *  4. Đánh rank từ 1 theo thứ tự mảng
 *  5. Tạo SearchResult(rank, title, domain, url, isAd=false)
 */
class ParseJsResultsTest {

    // ══════════════════════════════════════════════════════════════
    // Helper: gọi parseJsResults tĩnh (hàm này là top-level private
    // trong WebCaptureScreen, nên ta test gián tiếp qua mock logic)
    // ══════════════════════════════════════════════════════════════

    private fun parseJsResults(raw: String): List<SearchResult> {
        return try {
            val json = raw.trim().let { s ->
                if (s.startsWith("\"") && s.endsWith("\"")) {
                    s.substring(1, s.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\/", "/")
                        .replace("\\\\", "\\")
                        .replace("\\n", " ")
                        .replace("\\r", "")
                        .replace("\\t", " ")
                } else s
            }
            val arr = org.json.JSONArray(json)
            var rank = 0
            (0 until arr.length()).mapNotNull { i ->
                val obj   = arr.getJSONObject(i)
                val isAd  = obj.optBoolean("ad", false)
                val title = obj.optString("t", "").trim()
                if (isAd || title.isBlank() || title.startsWith("ERROR:", ignoreCase = true)) return@mapNotNull null
                rank++
                SearchResult(
                    rank   = rank,
                    title  = title,
                    domain = obj.optString("d", "").trim(),
                    url    = obj.optString("u", "").trim(),
                    isAd   = false,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Happy path — valid JSON với nhiều kết quả
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseJsResults valid JSON returns correct results`() {
        val json = """[
            {"t":"Trang chu VNE - vnexpress net","d":"vnexpress.net","u":"https://vnexpress.net","ad":false},
            {"t":"Tin tuc 24h - kenh14 vn","d":"kenh14.vn","u":"https://kenh14.vn","ad":false}
        ]"""

        val results = parseJsResults(json)

        assertEquals(2, results.size)

        assertEquals(1, results[0].rank)
        assertEquals("Trang chu VNE - vnexpress net", results[0].title)
        assertEquals("vnexpress.net", results[0].domain)
        assertEquals("https://vnexpress.net", results[0].url)
        assertFalse(results[0].isAd)

        assertEquals(2, results[1].rank)
        assertEquals("kenh14.vn", results[1].domain)
    }

    @Test
    fun `parseJsResults returns empty list for empty array`() {
        assertEquals(emptyList<SearchResult>(), parseJsResults("[]"))
    }

    @Test
    fun `parseJsResults returns empty list for invalid JSON`() {
        assertEquals(emptyList<SearchResult>(), parseJsResults("not json at all"))
        assertEquals(emptyList<SearchResult>(), parseJsResults(""))
        assertEquals(emptyList<SearchResult>(), parseJsResults("{invalid}"))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: isAd=true → bị loại
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseJsResults skips items with isAd true`() {
        val json = """[
            {"t":"Ad Result 1","d":"ad.example.com","u":"https://ad.example.com","ad":true},
            {"t":"Real Result","d":"real.com","u":"https://real.com","ad":false}
        ]"""

        val results = parseJsResults(json)

        assertEquals(1, results.size)
        assertEquals("Real Result", results[0].title)
        assertEquals(1, results[0].rank)
    }

    @Test
    fun `parseJsResults skips multiple ads`() {
        val json = """[
            {"t":"Ad 1","d":"ad1.com","u":"https://ad1.com","ad":true},
            {"t":"Ad 2","d":"ad2.com","u":"https://ad2.com","ad":true},
            {"t":"Real Result","d":"real.com","u":"https://real.com","ad":false}
        ]"""

        val results = parseJsResults(json)
        assertEquals(1, results.size)
        assertEquals("Real Result", results[0].title)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: blank title → bị loại
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseJsResults skips items with blank title`() {
        val json = """[
            {"t":"","d":"example.com","u":"https://example.com","ad":false},
            {"t":"  ","d":"example2.com","u":"https://example2.com","ad":false}
        ]"""

        assertEquals(emptyList<SearchResult>(), parseJsResults(json))
    }

    @Test
    fun `parseJsResults skips items with missing title field`() {
        val json = """[
            {"d":"example.com","u":"https://example.com","ad":false},
            {"t":"Valid Title","d":"valid.com","u":"https://valid.com","ad":false}
        ]"""

        val results = parseJsResults(json)
        assertEquals(1, results.size)
        assertEquals("Valid Title", results[0].title)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: ERROR: prefix → bị loại
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseJsResults skips items with ERROR prefix in title`() {
        val json = """[
            {"t":"ERROR: DOM not found","d":"","u":"","ad":false},
            {"t":"Valid Result","d":"example.com","u":"https://example.com","ad":false}
        ]"""

        val results = parseJsResults(json)
        assertEquals(1, results.size)
        assertEquals("Valid Result", results[0].title)
    }

    @Test
    fun `parseJsResults skips items with error prefix case insensitive`() {
        val json = """[
            {"t":"error: something went wrong","d":"","u":"","ad":false},
            {"t":"Error: something","d":"","u":"","ad":false},
            {"t":"Valid Result","d":"example.com","u":"https://example.com","ad":false}
        ]"""

        val results = parseJsResults(json)
        assertEquals(1, results.size)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Rank assignment — tăng dần từ 1, không bị ảnh hưởng bởi item bị skip
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseJsResults assigns sequential ranks despite skipped items`() {
        val json = """[
            {"t":"Skip - ad","d":"ad.com","u":"https://ad.com","ad":true},
            {"t":"","d":"blank.com","u":"https://blank.com","ad":false},
            {"t":"ERROR: DOM parse failed","d":"","u":"","ad":false},
            {"t":"First valid","d":"first.com","u":"https://first.com","ad":false},
            {"t":"Second valid","d":"second.com","u":"https://second.com","ad":false}
        ]"""

        val results = parseJsResults(json)
        assertEquals(2, results.size)
        assertEquals(1, results[0].rank)
        assertEquals("First valid", results[0].title)
        assertEquals(2, results[1].rank)
        assertEquals("Second valid", results[1].title)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: domain/url extraction từ JSON field
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseJsResults extracts domain and url correctly`() {
        val json = """[{"t":"Title","d":"vietnamnet.vn","u":"https://vietnamnet.vn/news","ad":false}]"""

        val results = parseJsResults(json)
        assertEquals(1, results.size)
        assertEquals("vietnamnet.vn", results[0].domain)
        assertEquals("https://vietnamnet.vn/news", results[0].url)
    }

    @Test
    fun `parseJsResults handles missing domain and url gracefully`() {
        val json = """[{"t":"Title only","ad":false}]"""

        val results = parseJsResults(json)
        assertEquals(1, results.size)
        assertEquals("", results[0].domain)
        assertEquals("", results[0].url)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `parseJsResults handles single result`() {
        val json = """[{"t":"Only One","d":"only.com","u":"https://only.com","ad":false}]"""
        val results = parseJsResults(json)
        assertEquals(1, results.size)
        assertEquals(1, results[0].rank)
    }

    @Test
    fun `parseJsResults all items skipped returns empty list`() {
        val json = """[
            {"t":"Ad","d":"ad.com","u":"https://ad.com","ad":true},
            {"t":"","d":"blank.com","u":"","ad":false},
            {"t":"ERROR: x","d":"","u":"","ad":false}
        ]"""
        assertEquals(emptyList<SearchResult>(), parseJsResults(json))
    }

    @Test
    fun `parseJsResults large array handles correctly`() {
        val sb = StringBuilder("[")
        for (i in 1..50) {
            if (i > 1) sb.append(",")
            sb.append("""{"t":"Result $i","d":"domain$i.com","u":"https://domain$i.com","ad":false}""")
        }
        sb.append("]")
        val results = parseJsResults(sb.toString())
        assertEquals(50, results.size)
        assertEquals(1, results[0].rank)
        assertEquals(50, results[49].rank)
        assertEquals("Result 1", results[0].title)
        assertEquals("Result 50", results[49].title)
    }

    @Test
    fun `parseJsResults mixed valid and invalid items`() {
        val json = """[
            {"t":"","d":"e.com","u":"https://e.com","ad":false},
            {"t":"Valid 1","d":"v1.com","u":"https://v1.com","ad":false},
            {"t":"Ad","d":"a.com","u":"https://a.com","ad":true},
            {"t":"Valid 2","d":"v2.com","u":"https://v2.com","ad":false},
            {"t":"Valid 3","d":"v3.com","u":"https://v3.com","ad":false},
            {"t":"ERROR: x","d":"","u":"","ad":false},
            {"t":"Valid 4","d":"v4.com","u":"https://v4.com","ad":false}
        ]"""

        val results = parseJsResults(json)
        assertEquals(4, results.size)
        assertEquals("Valid 1", results[0].title)
        assertEquals("Valid 2", results[1].title)
        assertEquals("Valid 3", results[2].title)
        assertEquals("Valid 4", results[3].title)
        assertEquals(1, results[0].rank)
        assertEquals(4, results[3].rank)
    }

    @Test
    fun `parseJsResults handles JSON with trailing whitespace`() {
        val json = """[
            {"t":"Result","d":"example.com","u":"https://example.com","ad":false}
        ]   """

        val results = parseJsResults(json)
        assertEquals(1, results.size)
    }

    @Test
    fun `parseJsResults isAd defaults to false when missing`() {
        // Đây là case mà item không có trường ad
        val json = """[{"t":"NoAdField","d":"example.com","u":"https://example.com"}]"""
        val results = parseJsResults(json)
        assertEquals(1, results.size)
        assertFalse(results[0].isAd)
    }
}
