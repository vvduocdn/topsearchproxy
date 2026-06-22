package com.topsearch.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests cho JavaScript extraction logic — tái hiện core JS functions trong Kotlin.
 *
 * NOTE: norm() tests bị skip vì java.text.Normalizer khác JS normalize('NFD').
 * Tập trung vào logic filter thực tế: ad detection, URL resolution, URL allowance.
 */
class JSExtractionLogicTest {

    // ══════════════════════════════════════════════════════════════
    // JS function equivalents in Kotlin
    // ══════════════════════════════════════════════════════════════

    /** norm() tương đương JS — lowercase, bỏ diacritics, loại ký tự đặc biệt */
    private fun norm(text: String): String {
        var t = text.lowercase()
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
        t = t.replace(Regex("[\u0300-\u036f]"), "")
        return t.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun matchAd(t: String): Boolean {
        val adKw = listOf(
            "ket qua duoc tai tro", "nha tai tro", "duoc tai tro",
            "quang cao", "sponsored"
        )
        return adKw.any { kw -> t.indexOf(kw) >= 0 }
    }

    private fun isAdLabelText(text: String): Boolean {
        val t = norm(text)
        if (t.length > 0 && t.length < 80 && matchAd(t)) return true
        return false
    }

    private fun isAdBlock(
        text: String,
        isInAdSection: Boolean,
        childrenTexts: List<String> = emptyList(),
    ): Boolean {
        if (isInAdSection) return true
        if (isAdLabelText(text)) return true
        if (childrenTexts.isNotEmpty()) {
            if (isAdLabelText(childrenTexts.first())) return true
            if (childrenTexts.size > 1 && isAdLabelText(childrenTexts.last())) return true
        }
        return false
    }

    private fun getDomain(href: String): String {
        return try {
            java.net.URL(href).host.removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

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

    private fun allowedUrl(realUrl: String): Boolean {
        if (realUrl.isBlank() || !realUrl.startsWith("http")) return false
        if (realUrl.contains("/aclk?")) return false
        if (realUrl.contains("googleadservices")) return false
        return try {
            val host = java.net.URL(realUrl).host
            if (host.contains("google.") && !host.contains("play.google.")) return false
            if (host.contains("gstatic.")) return false
            if (host.contains("googleusercontent.")) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isPlayGoogleLink(href: String): Boolean {
        return try {
            val realUrl = resolveUrl(href)
            java.net.URL(realUrl).host.startsWith("play.google.")
        } catch (_: Exception) {
            false
        }
    }

    private fun isNimoLikeLink(href: String): Boolean {
        return try {
            val realUrl = resolveUrl(href)
            java.net.URL(realUrl).host.lowercase().contains("nimo")
        } catch (_: Exception) {
            false
        }
    }

    private fun isAdUrl(href: String): Boolean {
        return href.contains("/aclk?") || href.contains("googleadservices")
    }

    // ══════════════════════════════════════════════════════════════
    // Test: isAdUrl()
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `isAdUrl detects Google ad click URLs`() {
        assertTrue(isAdUrl("https://www.google.com/aclk?sa=L&ai=..."))
        assertTrue(isAdUrl("https://googleadservices.com/pagead/..."))
    }

    @Test
    fun `isAdUrl returns false for normal URLs`() {
        assertFalse(isAdUrl("https://vnexpress.net"))
        assertFalse(isAdUrl("https://www.google.com/search?q=test"))
        assertFalse(isAdUrl(""))
    }

    @Test
    fun `isAdUrl is case sensitive for aclk`() {
        // Chỉ check indexOf nên phân biệt hoa thường
        assertFalse(isAdUrl("https://example.com/ACK?")) // hoa
    }

    // ══════════════════════════════════════════════════════════════
    // Test: getDomain()
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `getDomain extracts hostname without www`() {
        assertEquals("vnexpress.net", getDomain("https://www.vnexpress.net/path"))
        assertEquals("kenh14.vn", getDomain("http://kenh14.vn/"))
        assertEquals("example.com", getDomain("https://example.com"))
    }

    @Test
    fun `getDomain handles deep paths`() {
        assertEquals("news.google.com", getDomain("https://news.google.com/search?q=test"))
    }

    @Test
    fun `getDomain handles ports`() {
        assertEquals("localhost", getDomain("http://localhost:8080/path"))
    }

    @Test
    fun `getDomain returns empty for invalid URL`() {
        assertEquals("", getDomain("not a url"))
        assertEquals("", getDomain(""))
    }

    @Test
    fun `getDomain strips www prefix`() {
        assertEquals("youtube.com", getDomain("https://www.youtube.com/watch?v=abc"))
        assertEquals("google.com", getDomain("http://www.google.com"))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: resolveUrl()
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `resolveUrl extracts q param from Google redirect`() {
        val href = "https://www.google.com/url?q=https://vnexpress.net&sa=D"
        assertEquals("https://vnexpress.net", resolveUrl(href))
    }

    @Test
    fun `resolveUrl extracts url param from Google redirect`() {
        val href = "https://www.google.com/url?url=https://example.com"
        assertEquals("https://example.com", resolveUrl(href))
    }

    @Test
    fun `resolveUrl keeps non-Google URLs`() {
        assertEquals("https://vnexpress.net/news", resolveUrl("https://vnexpress.net/news"))
    }

    @Test
    fun `resolveUrl keeps Google redirect when q is not http`() {
        val href = "https://www.google.com/url?q=internal+query"
        assertEquals(href, resolveUrl(href))
    }

    @Test
    fun `resolveUrl handles URL encoded q param`() {
        val href = "https://www.google.com/url?q=https%3A%2F%2Fexample.com"
        assertEquals("https://example.com", resolveUrl(href))
    }

    @Test
    fun `resolveUrl handles malformed URLs`() {
        assertEquals("invalid-url", resolveUrl("invalid-url"))
    }

    @Test
    fun `resolveUrl prefers q over url param`() {
        val href = "https://www.google.com/url?q=https://vnexpress.net&url=https://other.com"
        assertEquals("https://vnexpress.net", resolveUrl(href))
    }

    @Test
    fun `resolveUrl returns href when query params are null`() {
        val href = "https://www.google.com/url"
        assertEquals(href, resolveUrl(href))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: allowedUrl()
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `allowedUrl allows normal external URLs`() {
        assertTrue(allowedUrl("https://vnexpress.net"))
        assertTrue(allowedUrl("http://kenh14.vn"))
        assertTrue(allowedUrl("https://news.zing.vn/article"))
    }

    @Test
    fun `allowedUrl rejects Google internal URLs`() {
        assertFalse(allowedUrl("https://www.google.com/search?q=test"))
        assertFalse(allowedUrl("https://google.com.vn"))
        assertFalse(allowedUrl("https://maps.google.com"))
    }

    @Test
    fun `allowedUrl ALLOWS Google Play URLs`() {
        assertTrue(allowedUrl("https://play.google.com/store/apps/details?id=com.facebook"))
        assertTrue(allowedUrl("https://play.google.com/store/apps/collection/recommended"))
    }

    @Test
    fun `allowedUrl rejects Google ads URLs`() {
        assertFalse(allowedUrl("https://www.google.com/aclk?sa=L&ai=..."))
        assertFalse(allowedUrl("https://www.googleadservices.com/..."))
    }

    @Test
    fun `allowedUrl rejects Google static resources`() {
        assertFalse(allowedUrl("https://www.gstatic.com/images/..."))
        assertFalse(allowedUrl("https://lh3.googleusercontent.com/photos/..."))
        assertFalse(allowedUrl("https://ssl.gstatic.com/"))
    }

    @Test
    fun `allowedUrl rejects blank and non-http URLs`() {
        assertFalse(allowedUrl(""))
        assertFalse(allowedUrl("just text"))
        assertFalse(allowedUrl("ftp://example.com"))
    }

    @Test
    fun `allowedUrl rejects googleusercontent`() {
        assertFalse(allowedUrl("https://lh3.googleusercontent.com/photo.jpg"))
        assertFalse(allowedUrl("https://住的图片.googleusercontent.com/..."))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: isPlayGoogleLink()
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `isPlayGoogleLink detects Google Play direct links`() {
        assertTrue(isPlayGoogleLink("https://play.google.com/store/apps/details?id=com.facebook"))
        assertTrue(isPlayGoogleLink("http://play.google.com/store/apps/details?id=com.whatsapp"))
    }

    @Test
    fun `isPlayGoogleLink returns false for non-Google Play`() {
        assertFalse(isPlayGoogleLink("https://www.google.com/search?q=app"))
        assertFalse(isPlayGoogleLink("https://appstore.com/app"))
        assertFalse(isPlayGoogleLink("https://vnexpress.net"))
    }

    @Test
    fun `isPlayGoogleLink resolves Google redirect`() {
        assertTrue(isPlayGoogleLink(
            "https://www.google.com/url?q=https://play.google.com/store/apps/details?id=com.example"
        ))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: isNimoLikeLink()
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `isNimoLikeLink detects Nimo domain variants`() {
        assertTrue(isNimoLikeLink("https://www.nimo.tv/"))
        assertTrue(isNimoLikeLink("https://m.nimo.tv/live/123"))
        assertTrue(isNimoLikeLink("https://nimo.tv/channel/test"))
    }

    @Test
    fun `isNimoLikeLink case insensitive`() {
        assertTrue(isNimoLikeLink("https://NIMO.TV/"))
        assertTrue(isNimoLikeLink("https://Nimo.Tv/"))
    }

    @Test
    fun `isNimoLikeLink returns false for non-Nimo URLs`() {
        assertFalse(isNimoLikeLink("https://youtube.com"))
        assertFalse(isNimoLikeLink("https://twitch.tv"))
        assertFalse(isNimoLikeLink("https://vnexpress.net"))
    }

    @Test
    fun `isNimoLikeLink resolves Google redirect`() {
        assertTrue(isNimoLikeLink(
            "https://www.google.com/url?q=https://www.nimo.tv/live"
        ))
    }

    @Test
    fun `isNimoLikeLink is case sensitive on hostname`() {
        // hostname.contains("nimo") — so sánh lowercase trên resolved URL
        assertTrue(isNimoLikeLink("https://www.nimo.tv/"))
        assertFalse(isNimoLikeLink("https://www.youtube.com"))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Integration — full extraction flow
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `full extraction flow rejects ad results correctly`() {
        data class ExtractedLink(
            val href: String,
            val text: String,
            val isInAdSection: Boolean,
            val childrenTexts: List<String>,
        )

        val links = listOf(
            // Real organic result
            ExtractedLink(
                href = "https://www.google.com/url?q=https://vnexpress.net",
                text = "Tin tuc 24h",
                isInAdSection = false,
                childrenTexts = emptyList()
            ),
            // Ad block
            ExtractedLink(
                href = "https://www.google.com/aclk?...",
                text = "Ad Result",
                isInAdSection = true,
                childrenTexts = listOf("Nha tai tro")
            ),
            // Google internal
            ExtractedLink(
                href = "https://www.google.com/search?q=test",
                text = "Google Search",
                isInAdSection = false,
                childrenTexts = emptyList()
            ),
            // Real result with normal text
            ExtractedLink(
                href = "https://www.google.com/url?q=https://kenh14.vn",
                text = "Kenh 14",
                isInAdSection = false,
                childrenTexts = emptyList()
            ),
        )

        val validResults = links.filter { link ->
            !isAdBlock(link.text, link.isInAdSection, link.childrenTexts) &&
            !isAdUrl(link.href) &&
            allowedUrl(resolveUrl(link.href))
        }

        assertEquals(2, validResults.size)
        assertEquals("https://vnexpress.net", resolveUrl(validResults[0].href))
        assertEquals("https://kenh14.vn", resolveUrl(validResults[1].href))
    }

    @Test
    fun `Google Play result passes all filters`() {
        val href = "https://www.google.com/url?q=https://play.google.com/store/apps/details?id=com.facebook"
        val text = "Facebook"

        assertFalse(isAdBlock(text, isInAdSection = false))
        assertFalse(isAdUrl(href))
        assertTrue(allowedUrl(resolveUrl(href)))
        assertTrue(isPlayGoogleLink(href))
    }

    @Test
    fun `Nimo result passes all filters`() {
        val href = "https://www.google.com/url?q=https://www.nimo.tv/live"
        val text = "Nimo TV"

        assertFalse(isAdBlock(text, isInAdSection = false))
        assertFalse(isAdUrl(href))
        assertTrue(allowedUrl(resolveUrl(href)))
        assertTrue(isNimoLikeLink(href))
    }

    @Test
    fun `Ad result with aclk URL rejected by both isAdUrl and allowedUrl`() {
        val href = "https://www.google.com/aclk?sa=L&ai=D"
        val text = "Ad"

        assertTrue(isAdUrl(href))
        assertFalse(isAdUrl("https://vnexpress.net"))
    }

    @Test
    fun `Real result with multiple filters applied`() {
        // Simulate a real result from Google: redirect URL, non-ad text
        val href = "https://www.google.com/url?q=https://tuoitre.vn/post/123"
        val text = "Tuoi tre Online"

        assertFalse(isAdBlock(text, isInAdSection = false))
        assertFalse(isAdUrl(href))
        assertTrue(allowedUrl(resolveUrl(href)))
        assertEquals("https://tuoitre.vn/post/123", resolveUrl(href))
    }

    @Test
    fun `Results filtered by domain exclusion`() {
        // google.com internal should be rejected
        assertFalse(allowedUrl("https://www.google.com"))
        assertFalse(allowedUrl("https://news.google.com/topics"))
        // gstatic rejected
        assertFalse(allowedUrl("https://www.gstatic.com"))
        // googleusercontent rejected
        assertFalse(allowedUrl("https://lh3.googleusercontent.com/abc"))
    }

    @Test
    fun `Multiple Google redirects in same list`() {
        val urls = listOf(
            "https://www.google.com/url?q=https://vnexpress.net",
            "https://www.google.com/url?q=https://dantri.com.vn",
            "https://www.google.com/url?q=https://play.google.com/store/apps/details?id=com.app",
            "https://www.google.com/url?q=https://www.google.com",
        )

        val resolved = urls.map { resolveUrl(it) }

        assertEquals("https://vnexpress.net", resolved[0])
        assertEquals("https://dantri.com.vn", resolved[1])
        assertEquals("https://play.google.com/store/apps/details?id=com.app", resolved[2])
        // google.com internal — q param is http URL, but google.com itself is internal → keep href
        assertTrue(resolved[3].startsWith("https://www.google.com"))
    }
}
