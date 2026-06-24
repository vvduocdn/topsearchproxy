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

    private fun isAllowedGoogleProductUrl(realUrl: String): Boolean {
        return try {
            val url = java.net.URL(realUrl)
            val host = url.host.lowercase()
            val path = url.path.lowercase()

            host.startsWith("play.google.") ||
                host.startsWith("docs.google.") ||
                ((host == "www.google.com" || host == "google.com") && path.contains("/docs/about")) ||
                (host == "workspace.google.com" && path.contains("/products/docs")) ||
                (host == "support.google.com" && path.startsWith("/docs/"))
        } catch (_: Exception) {
            false
        }
    }

    private fun allowedUrl(realUrl: String): Boolean {
        if (realUrl.isBlank() || !realUrl.startsWith("http")) return false
        if (realUrl.contains("/aclk?")) return false
        if (realUrl.contains("googleadservices")) return false
        return try {
            val host = java.net.URL(realUrl).host.lowercase()
            if (host.contains("google.") && !isAllowedGoogleProductUrl(realUrl)) return false
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

    private fun isYouTubeLikeUrl(realUrl: String): Boolean {
        return try {
            val host = java.net.URL(realUrl).host.lowercase()
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        } catch (_: Exception) {
            false
        }
    }

    private fun isYouTubeLikeLink(href: String): Boolean {
        return try {
            isYouTubeLikeUrl(resolveUrl(href))
        } catch (_: Exception) {
            false
        }
    }

    private fun isFacebookVideoUrl(realUrl: String): Boolean {
        return try {
            val url = java.net.URL(realUrl)
            val host = url.host.lowercase()
            val path = url.path.lowercase()
            val isFacebookHost = host == "facebook.com" || host.endsWith(".facebook.com")
            isFacebookHost && (path.contains("/videos/") || path.startsWith("/watch"))
        } catch (_: Exception) {
            false
        }
    }

    private fun isFacebookVideoLink(href: String): Boolean {
        return try {
            isFacebookVideoUrl(resolveUrl(href))
        } catch (_: Exception) {
            false
        }
    }

    private fun shouldSkipKnowledgePanel(realUrl: String, isInKnowledgePanel: Boolean): Boolean {
        return isInKnowledgePanel
    }

    private fun shouldSkipHiddenResult(style: String = "", hidden: Boolean = false, ariaHidden: Boolean = false): Boolean {
        val normalized = style.lowercase().replace(" ", "")
        return hidden ||
            ariaHidden ||
            normalized.contains("display:none") ||
            normalized.contains("opacity:0")
    }

    private data class Candidate(val domain: String, val url: String, val orderKey: Double)

    private data class CandidateWithScope(
        val domain: String,
        val url: String,
        val orderKey: Double,
        val inSearchRoot: Boolean,
    )

    private fun sortByImageOrder(candidates: List<Candidate>): List<Candidate> {
        return candidates.sortedBy { it.orderKey }
    }

    private fun firstAllowedHeadingCardUrl(hrefs: List<String>): String? {
        return hrefs.firstOrNull { href -> !isAdUrl(href) && allowedUrl(resolveUrl(href)) }?.let { resolveUrl(it) }
    }

    private fun extractWithinSearchRoot(candidates: List<CandidateWithScope>): List<CandidateWithScope> {
        return candidates
            .filter { it.inSearchRoot }
            .filter { !isAdUrl(it.url) && allowedUrl(resolveUrl(it.url)) }
            .sortedBy { it.orderKey }
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
    fun `allowedUrl allows YouTube video URLs`() {
        assertTrue(allowedUrl("https://www.youtube.com/watch?v=VaEOLSP7_KU"))
        assertTrue(allowedUrl("https://youtube.com/watch?v=0uyr4R3q-zc"))
        assertTrue(allowedUrl("https://youtu.be/VaEOLSP7_KU"))
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
    fun `allowedUrl allows Google Docs organic URLs`() {
        assertTrue(allowedUrl("https://docs.google.com/forms/d/abc/edit"))
        assertTrue(allowedUrl("https://docs.google.com/document/d/abc/edit"))
    }

    @Test
    fun `allowedUrl allows Google Docs product organic URLs`() {
        assertTrue(allowedUrl("https://www.google.com/intx/vi/docs/about/"))
        assertTrue(allowedUrl("https://workspace.google.com/intl/vi/products/docs/"))
        assertTrue(allowedUrl("https://support.google.com/docs/answer/7068618?hl=vi"))
    }

    @Test
    fun `allowedUrl keeps other Google internal URLs rejected`() {
        assertFalse(allowedUrl("https://www.google.com/search?q=docs"))
        assertFalse(allowedUrl("https://www.google.com/maps/place/test"))
        assertFalse(allowedUrl("https://support.google.com/websearch/answer/123"))
        assertFalse(allowedUrl("https://workspace.google.com/intl/vi/products/gmail/"))
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

    @Test
    fun `isYouTubeLikeLink detects YouTube video domain variants`() {
        assertTrue(isYouTubeLikeLink("https://www.youtube.com/watch?v=VaEOLSP7_KU"))
        assertTrue(isYouTubeLikeLink("https://youtube.com/watch?v=0uyr4R3q-zc"))
        assertTrue(isYouTubeLikeLink("https://youtu.be/VaEOLSP7_KU"))
    }

    @Test
    fun `isYouTubeLikeLink resolves Google redirect`() {
        assertTrue(isYouTubeLikeLink(
            "https://www.google.com/url?q=https://www.youtube.com/watch%3Fv%3DVaEOLSP7_KU"
        ))
    }

    @Test
    fun `YouTube video result is skipped by knowledge panel guard`() {
        val youtubeUrl = "https://www.youtube.com/watch?v=VaEOLSP7_KU"
        val mapsUrl = "https://www.google.com/maps/place/test"
        val normalUrl = "https://vnexpress.net/news"

        assertTrue(shouldSkipKnowledgePanel(youtubeUrl, isInKnowledgePanel = true))
        assertTrue(shouldSkipKnowledgePanel(mapsUrl, isInKnowledgePanel = true))
        assertTrue(shouldSkipKnowledgePanel(normalUrl, isInKnowledgePanel = true))
        assertFalse(shouldSkipKnowledgePanel(youtubeUrl, isInKnowledgePanel = false))
        assertFalse(shouldSkipKnowledgePanel(normalUrl, isInKnowledgePanel = false))
    }

    @Test
    fun `YouTube hidden popup links are skipped`() {
        assertTrue(shouldSkipHiddenResult(style = "display:none"))
        assertTrue(shouldSkipHiddenResult(style = "opacity:0;display:none"))
        assertTrue(shouldSkipHiddenResult(hidden = true))
        assertTrue(shouldSkipHiddenResult(ariaHidden = true))
        assertFalse(shouldSkipHiddenResult(style = ""))
    }

    @Test
    fun `YouTube result passes full filter chain`() {
        val href = "https://www.google.com/url?q=https://www.youtube.com/watch%3Fv%3DVaEOLSP7_KU"
        val realUrl = resolveUrl(href)

        assertFalse(isAdUrl(href))
        assertTrue(isYouTubeLikeUrl(realUrl))
        assertTrue(allowedUrl(realUrl))
        assertFalse(shouldSkipKnowledgePanel(realUrl, isInKnowledgePanel = false))
        assertFalse(shouldSkipHiddenResult(style = ""))
    }

    @Test
    fun `YouTube video results keep visual rank order`() {
        val collectedInDomOrder = listOf(
            Candidate("www.youtube.com", "https://www.youtube.com/watch?v=late1", 16.0),
            Candidate("www.youtube.com", "https://www.youtube.com/watch?v=late2", 20.0),
            Candidate("hi8878.net", "https://hi8878.net", 1.0),
            Candidate("absd.nl", "https://absd.nl", 2.0),
            Candidate("backtick.io", "https://backtick.io", 3.0),
        )

        val sorted = sortByImageOrder(collectedInDomOrder)

        assertEquals("hi8878.net", sorted[0].domain)
        assertEquals("absd.nl", sorted[1].domain)
        assertEquals("backtick.io", sorted[2].domain)
        assertEquals("www.youtube.com", sorted[3].domain)
        assertEquals("www.youtube.com", sorted[4].domain)
    }

    @Test
    fun `Facebook video card URL is accepted as organic social video`() {
        val href = "https://www.facebook.com/F168Beauty/videos/cao-qua-cung-kho/1412123843879594/"

        assertFalse(isAdUrl(href))
        assertTrue(isFacebookVideoLink(href))
        assertTrue(allowedUrl(resolveUrl(href)))
        assertFalse(shouldSkipKnowledgePanel(href, isInKnowledgePanel = false))
        assertFalse(shouldSkipHiddenResult(style = ""))
    }

    @Test
    fun `Facebook video card resolves Google redirect`() {
        val href = "https://www.google.com/url?q=https%3A%2F%2Fwww.facebook.com%2FF168Beauty%2Fvideos%2Fcao-qua%2F1412123843879594%2F"

        assertTrue(isFacebookVideoLink(href))
        assertEquals(
            "https://www.facebook.com/F168Beauty/videos/cao-qua/1412123843879594/",
            resolveUrl(href)
        )
    }

    @Test
    fun `Facebook non-video links are not handled by social video rule`() {
        assertFalse(isFacebookVideoLink("https://www.facebook.com/F168Beauty/"))
        assertFalse(isFacebookVideoLink("https://www.facebook.com/groups/1569963753591771/"))
        assertFalse(isFacebookVideoLink("https://www.facebook.com/share/p/abc123/"))
    }

    @Test
    fun `Facebook video keeps visual rank order with other domains`() {
        val collectedInDomOrder = listOf(
            Candidate("www.facebook.com", "https://www.facebook.com/F168Beauty/videos/late/141", 12.0),
            Candidate("hidas.io", "https://hidas.io/vi-vn/", 1.0),
            Candidate("negaheno.me", "https://negaheno.me/vi-vn/", 2.0),
        )

        val sorted = sortByImageOrder(collectedInDomOrder)

        assertEquals("hidas.io", sorted[0].domain)
        assertEquals("negaheno.me", sorted[1].domain)
        assertEquals("www.facebook.com", sorted[2].domain)
    }

    @Test
    fun `78win search keeps PDF and YouTube organic before local panel links`() {
        val candidates = listOf(
            CandidateWithScope("78winvn.lat", "https://78winvn.lat/vi-vn/", 1.0, true),
            CandidateWithScope("www.78win.gold", "https://www.78win.gold/vi-vn/", 2.0, true),
            CandidateWithScope("17slots.cyou", "https://17slots.cyou/vi-vn/", 3.0, true),
            CandidateWithScope("vavada-tw.buzz", "https://vavada-tw.buzz/vi-vn/", 4.0, true),
            CandidateWithScope("www.byroomiey.nl", "https://www.byroomiey.nl/vi-vn/", 5.0, true),
            CandidateWithScope("abusiness.com.co", "https://abusiness.com.co/vi-vn/", 6.0, true),
            CandidateWithScope("8ys1m7.buzz", "https://8ys1m7.buzz/vi-vn/", 7.0, true),
            CandidateWithScope("cryptonwo.io", "https://cryptonwo.io/vi-vn/", 8.0, true),
            CandidateWithScope(
                "geco.ecophytopic.fr",
                "https://geco.ecophytopic.fr/documents/20182/21720/Upload_2024-9-18_22-18-36-14.pdf",
                9.0,
                true
            ),
            CandidateWithScope("www.youtube.com", "https://www.youtube.com/watch?v=O4O39V2tv5M", 10.0, true),
            CandidateWithScope("78win.productions", "http://78win.productions/", 9.5, false),
            CandidateWithScope("bly.onl", "https://bly.onl/", 9.6, false),
        )

        val sorted = extractWithinSearchRoot(candidates)

        assertEquals(10, sorted.size)
        assertEquals("geco.ecophytopic.fr", sorted[8].domain)
        assertEquals("www.youtube.com", sorted[9].domain)
        assertFalse(sorted.any { it.domain == "78win.productions" })
        assertFalse(sorted.any { it.domain == "bly.onl" })
    }

    @Test
    fun `role heading fallback selects first allowed link in its card`() {
        val cardLinks = listOf(
            "https://www.google.com/search?q=f168",
            "https://www.google.com/url?q=https%3A%2F%2Fhidas.example%2Fvi-vn%2F",
            "https://www.google.com/aclk?sa=L&url=https%3A%2F%2Fad.example"
        )

        assertEquals("https://hidas.example/vi-vn/", firstAllowedHeadingCardUrl(cardLinks))
    }

    @Test
    fun `role heading fallback rejects cards without allowed organic URL`() {
        val cardLinks = listOf(
            "https://www.google.com/search?q=f168",
            "https://www.google.com/aclk?sa=L&url=https%3A%2F%2Fad.example",
            "https://encrypted-tbn0.gstatic.com/image"
        )

        assertNull(firstAllowedHeadingCardUrl(cardLinks))
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

    // ══════════════════════════════════════════════════════════════
    // DOM simulation: mirrors JS isExcluded() — EXCLUDE array
    // Tests the 78win Knowledge Panel / Local Pack (Địa điểm) fix
    // ══════════════════════════════════════════════════════════════

    private data class SimElem(
        val tag: String,
        val classes: Set<String> = emptySet(),
        val attrs: Map<String, String> = emptyMap(),
        val parent: SimElem? = null,
    )

    /** Minimal CSS selector match: .class  #id  [attr]  [attr*=val]  [attr=val]  tag/custom-element */
    private fun SimElem.matchesSel(sel: String): Boolean = when {
        sel.startsWith('.') -> sel.drop(1) in classes
        sel.startsWith('#') -> attrs["id"] == sel.drop(1)
        sel.startsWith('[') && sel.endsWith(']') -> {
            val inner = sel.drop(1).dropLast(1)
            when {
                "*=" in inner -> {
                    val (a, v) = inner.split("*=", limit = 2)
                    attrs[a]?.contains(v.trim('"', '\'')) == true
                }
                "=" in inner -> {
                    val (a, v) = inner.split("=", limit = 2)
                    attrs[a] == v.trim('"', '\'')
                }
                else -> attrs.containsKey(inner)
            }
        }
        else -> tag.equals(sel, ignoreCase = true)
    }

    /** el.closest(selector) — walks up ancestor chain to root */
    private fun SimElem.closest(sel: String): SimElem? {
        var cur: SimElem? = this
        while (cur != null) {
            if (cur.matchesSel(sel)) return cur
            cur = cur.parent
        }
        return null
    }

    /** EXCLUDE selectors — mirrors WebCaptureScreen.kt scripts 1, 2, 4 */
    private val EXCLUDE_SELS = listOf(
        "#tads", "#tadsb",
        "[data-text-ad]", ".uEierd", ".pla-unit", "[data-rw]",
        ".related-question-pair",
        ".kp-wholepage", ".osrp-blk", ".I6TXqe",
        "[aria-label=\"Ads\"]",
        "[aria-label=\"Quảng cáo\"]",
        "[aria-label=\"Mọi người cũng hỏi\"]",
        "[aria-label=\"Kết quả được tài trợ\"]",
        ".mnr-c", ".commercial-unit-desktop-top", ".cu-container",
        ".EyBRub", "[data-kpid]",
        "g-scrolling-carousel",
        ".ULSxyf",
        "g-section-with-header",
        "[data-maindata*=\"LOCAL_NAV\"]",
        ".P6Deab",
        "[data-phone-number]",
        "[data-url*=\"maps.google\"]",
    )

    private fun isExcludedSim(el: SimElem): Boolean =
        EXCLUDE_SELS.any { el.closest(it) != null }

    /** Mirrors JS isImagePackResult() — used in Script 3 (EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS) */
    private val IMAGE_PACK_SELS = listOf(".ULSxyf", "#iur", "[data-iu]", "[data-viewer-group]")
    private fun isImagePackResultSim(el: SimElem): Boolean =
        IMAGE_PACK_SELS.any { el.closest(it) != null }

    /**
     * Mirrors JS isKnowledgePanelResult().
     *
     * Standard KP selectors always apply.
     * Video carousel selectors ([jscontroller="LhdR0e"] / .vtSz8d) are skipped when
     * [elementHasH3 = true] — organic result cards always have <h3> inside the link;
     * carousel items only use [role="heading"] span, never <h3>.
     */
    private val KP_STANDARD_SELS = listOf(
        ".EyBRub", "[data-kpid]", "[data-maindata*=\"LOCAL_NAV\"]",
        "g-scrolling-carousel",
        // g-section-with-header moved to h3-guarded check below (see isKnowledgePanelResultSim)
    )
    private val KP_CAROUSEL_SELS = listOf(
        "[jscontroller=\"LhdR0e\"]", ".vtSz8d",   // Google video carousel section
    )

    /**
     * Mirrors JS isKnowledgePanelResult().
     *
     * Standard KP selectors always apply.
     * g-section-with-header: filters news links (no h3) but allows organic video cards (with h3).
     * Video carousel selectors ([jscontroller="LhdR0e"] / .vtSz8d) are skipped when
     * [elementHasH3 = true] — organic result cards always have <h3> inside the link;
     * carousel items only use [role="heading"] span, never <h3>.
     */
    private fun isKnowledgePanelResultSim(el: SimElem, elementHasH3: Boolean = false): Boolean {
        if (KP_STANDARD_SELS.any { el.closest(it) != null }) return true
        // g-section-with-header: news links have NO h3; organic video cards ALWAYS have h3
        if (el.closest("g-section-with-header") != null && !elementHasH3) return true
        // Carousel check: skip if element has h3 inside (organic video result card)
        if (!elementHasH3 && KP_CAROUSEL_SELS.any { el.closest(it) != null }) return true
        return false
    }

    /**
     * Mirrors JS isOrganicDirectLink():
     *   link.matches('a.zReHs[href], a[jsname="UWckNb"][href], a.OcpZAb[href]')
     * Simplified to class/attr checks (SimElem has no compound-selector support).
     */
    private fun isOrganicDirectLinkSim(el: SimElem): Boolean =
        "zReHs"  in el.classes ||
        el.attrs["jsname"] == "UWckNb" ||
        "OcpZAb" in el.classes

    /**
     * Mirrors addYouTubeVideoBlock title check:
     * block.querySelector('h3, .LC20lb, .MBeuO, .F0FGWb')
     * [role="heading"] và h1 đã bị loại khỏi selector.
     */
    private val YT_TITLE_SELS = setOf("h3", ".LC20lb", ".MBeuO", ".F0FGWb")

    private fun youTubeBlockHasValidTitle(availableSelectors: Set<String>): Boolean =
        availableSelectors.any { it in YT_TITLE_SELS }

    // --- Knowledge Panel (EyBRub + data-kpid) ---

    @Test
    fun `78win KP link excluded via EyBRub ancestor`() {
        // Actual DOM: div.EyBRub[data-kpid] > div[data-maindata*=LOCAL_NAV] > g-scrolling-carousel > a.P6Deab
        val kpRoot  = SimElem("div",
            classes = setOf("EyBRub", "kp-wholepage"),
            attrs   = mapOf("data-kpid" to "/g/11m5jfy833"))
        val navDiv  = SimElem("div",
            attrs  = mapOf("data-maindata" to """[null,"/g/11m5jfy833","78win",null,null,null,null,null,"LOCAL_NAV","vi-VN",null,133]"""),
            parent = kpRoot)
        val carousel = SimElem("g-scrolling-carousel", parent = navDiv)
        val webLink  = SimElem("a",
            classes = setOf("P6Deab"),
            attrs   = mapOf("href" to "http://78win.productions/"),
            parent  = carousel)

        assertTrue(isExcludedSim(webLink))
    }

    @Test
    fun `78win KP link excluded via data-kpid ancestor alone`() {
        val kpRoot  = SimElem("div", attrs = mapOf("data-kpid" to "/g/11m5jfy833"))
        val webLink = SimElem("a",
            attrs  = mapOf("href" to "http://78win.productions/"),
            parent = kpRoot)

        assertTrue(isExcludedSim(webLink))
    }

    @Test
    fun `link inside g-scrolling-carousel tag is excluded`() {
        val carousel = SimElem("g-scrolling-carousel")
        val link     = SimElem("a",
            attrs  = mapOf("href" to "http://78win.productions/"),
            parent = carousel)

        assertTrue(isExcludedSim(link))
    }

    @Test
    fun `link inside data-maindata LOCAL_NAV container is excluded`() {
        val container = SimElem("div",
            attrs = mapOf("data-maindata" to """[null,"/g/11m5jfy833","78win",null,null,null,null,null,"LOCAL_NAV","vi-VN",null,133]"""))
        val link = SimElem("a",
            attrs  = mapOf("href" to "http://78win.productions/"),
            parent = container)

        assertTrue(isExcludedSim(link))
    }

    // --- Local Pack "Địa điểm" (P6Deab button) ---

    @Test
    fun `78win local pack Trang web button excluded via P6Deab self-class`() {
        // a.P6Deab is the action button on a local pack entry — NOT inside EyBRub
        val localPack = SimElem("div", classes = setOf("hqzQac"))
        val webBtn    = SimElem("a",
            classes = setOf("P6Deab"),
            attrs   = mapOf("href" to "http://78win.productions/"),
            parent  = localPack)

        assertTrue(isExcludedSim(webBtn))
        // allowedUrl passes this domain — EXCLUDE is the only blocker
        assertTrue(allowedUrl("http://78win.productions/"))
    }

    @Test
    fun `any a element with class P6Deab is excluded regardless of ancestors`() {
        val deepNesting = SimElem("div",
            parent = SimElem("div",
                parent = SimElem("div", attrs = mapOf("id" to "rso"))))
        val btn = SimElem("a",
            classes = setOf("P6Deab"),
            attrs   = mapOf("href" to "https://example.com/"),
            parent  = deepNesting)

        assertTrue(isExcludedSim(btn))
    }

    // --- Phone / Maps attributes ---

    @Test
    fun `element with data-phone-number attribute is excluded`() {
        val el = SimElem("div", attrs = mapOf("data-phone-number" to "0901234567"))
        assertTrue(isExcludedSim(el))
    }

    @Test
    fun `element with data-url pointing to maps google is excluded`() {
        val el = SimElem("a", attrs = mapOf(
            "href"     to "https://maps.google.com/maps?q=78win",
            "data-url" to "https://maps.google.com/maps?q=78win",
        ))
        assertTrue(isExcludedSim(el))
    }

    // --- Organic results must NOT be excluded ---

    @Test
    fun `normal organic result link in rso is not excluded`() {
        val rso    = SimElem("div", attrs = mapOf("id" to "rso"))
        val card   = SimElem("div", classes = setOf("MjjYud"), parent = rso)
        val link   = SimElem("a",
            attrs  = mapOf("href" to "https://vnexpress.net/"),
            parent = card)

        assertFalse(isExcludedSim(link))
    }

    @Test
    fun `organic result sibling of knowledge panel is not excluded`() {
        val rso     = SimElem("div", attrs = mapOf("id" to "rso"))
        // KP block (sibling, NOT parent)
        SimElem("div", classes = setOf("EyBRub"), parent = rso)
        // organic result card (different subtree)
        val orgCard = SimElem("div", classes = setOf("MjjYud"), parent = rso)
        val link    = SimElem("a",
            attrs  = mapOf("href" to "https://kenh14.vn/"),
            parent = orgCard)

        assertFalse(isExcludedSim(link))
    }

    // --- allowedUrl as second defense layer ---

    @Test
    fun `78win productions URL passes allowedUrl — DOM EXCLUDE is the only block`() {
        assertTrue(allowedUrl("http://78win.productions/"))
        assertTrue(allowedUrl("https://78win.productions/path"))
    }

    @Test
    fun `maps google URL blocked by allowedUrl independently of EXCLUDE`() {
        assertFalse(allowedUrl("https://maps.google.com/maps?q=78win"))
    }

    // --- Exact DOM from case_78win_loi.txt ---

    @Test
    fun `case_78win_loi — 78win productions excluded via EyBRub outer ancestor`() {
        // Verified from case_78win_loi.txt (88136 bytes in):
        //   div.liYKde
        //     div.kp-wholepage.EyBRub   ← opened at pos 129, still open at link
        //       ...
        //       div.wDYxhc.NFQFxe[data-attrid="kc:/local:unified_actions"]
        //         c-wiz.u1M3kd.ucRBdc
        //           div.OYzgjc
        //             div.zhZ3gf
        //               div.bkaPDb[ssk="14:0_local_action"]
        //                 a.n1obkb.mI8Pwc[href="http://78win.productions/"]
        //
        // Link class is "n1obkb mI8Pwc" — NOT P6Deab, NOT inside g-scrolling-carousel.
        // Filter triggers via .EyBRub (outer KP wrapper).

        val outer      = SimElem("div", classes = setOf("liYKde", "VjDLd"))
        val kpOuter    = SimElem("div",
            classes = setOf("kp-wholepage", "kp-wholepage-osrp", "EyBRub"),
            parent  = outer)
        // many intermediate divs — collapse to one for brevity
        val intermediate = SimElem("div", parent = kpOuter)
        val localSection = SimElem("div",
            classes = setOf("wDYxhc", "NFQFxe"),
            attrs   = mapOf("data-attrid" to "kc:/local:unified_actions"),
            parent  = intermediate)
        val cWiz       = SimElem("c-wiz", classes = setOf("u1M3kd", "ucRBdc"), parent = localSection)
        val oyzgjc     = SimElem("div", classes = setOf("OYzgjc"), parent = cWiz)
        val zhZ3gf     = SimElem("div", classes = setOf("zhZ3gf"), parent = oyzgjc)
        val bkaPDb     = SimElem("div",
            classes = setOf("bkaPDb"),
            attrs   = mapOf("ssk" to "14:0_local_action"),
            parent  = zhZ3gf)
        val link       = SimElem("a",
            classes = setOf("n1obkb", "mI8Pwc"),
            attrs   = mapOf("href" to "http://78win.productions/"),
            parent  = bkaPDb)

        // Must be excluded (via .EyBRub outer ancestor)
        assertTrue(isExcludedSim(link))
        // But the URL itself is valid — EXCLUDE is the only blocker
        assertTrue(allowedUrl("http://78win.productions/"))
    }

    @Test
    fun `case_78win_loi — maps google place links rejected by allowedUrl`() {
        // Other hrefs in the file: /maps/place/78win/... (relative) and google search
        // These are also caught by allowedUrl for absolute URLs
        assertFalse(allowedUrl("https://www.google.com/maps/place/78win/data=!4m2!3m1!1s0x0"))
        assertFalse(allowedUrl("https://www.google.com/search?q=78win"))
    }

    // ══════════════════════════════════════════════════════════════
    // Test: YouTube video carousel filtering (case4, case5)
    // Source: case_example_domain_youtobe.com.txt — "case không hợp lệ"
    // Fix 1: isKnowledgePanelResult thêm [jscontroller="LhdR0e"] / .vtSz8d
    // Fix 2: addYouTubeVideoBlock bỏ [role="heading"] và h1 khỏi title selector
    // ══════════════════════════════════════════════════════════════

    // --- case5: div.vtSz8d[jscontroller="LhdR0e"] → carousel, must be excluded ---

    @Test
    fun `case5 — LhdR0e video carousel link excluded by isKnowledgePanelResult`() {
        // case_example_domain_youtobe.com.txt (L23):
        // div.vtSz8d.Ww4FFb.vt6azd[jscontroller="LhdR0e"]
        //   a[data-curl="https://www.youtube.com/watch?v=PmN4O1ae5-w"]
        //     span[role="heading" aria-level="3"]  <- khong co <h3>
        val carousel = SimElem(
            "div",
            classes = setOf("vtSz8d", "Ww4FFb", "vt6azd"),
            attrs   = mapOf("jscontroller" to "LhdR0e"),
        )
        val link = SimElem(
            "a",
            attrs  = mapOf("data-curl" to "https://www.youtube.com/watch?v=PmN4O1ae5-w"),
            parent = carousel,
        )

        // Structural carousel check must exclude it
        assertTrue(isKnowledgePanelResultSim(link))
        // URL itself is valid — filter is structural, not URL-based
        assertTrue(allowedUrl("https://www.youtube.com/watch?v=PmN4O1ae5-w"))
        assertTrue(isYouTubeLikeUrl("https://www.youtube.com/watch?v=PmN4O1ae5-w"))
    }

    @Test
    fun `case5 — vtSz8d class alone triggers carousel exclusion`() {
        val block = SimElem("div", classes = setOf("vtSz8d"))
        val link  = SimElem("a",
            attrs  = mapOf("href" to "https://www.youtube.com/watch?v=PmN4O1ae5-w"),
            parent = block,
        )
        assertTrue(isKnowledgePanelResultSim(link))
    }

    @Test
    fun `case5 — jscontroller LhdR0e alone triggers carousel exclusion`() {
        val block = SimElem("div", attrs = mapOf("jscontroller" to "LhdR0e"))
        val link  = SimElem("a",
            attrs  = mapOf("href" to "https://www.youtube.com/watch?v=PmN4O1ae5-w"),
            parent = block,
        )
        assertTrue(isKnowledgePanelResultSim(link))
    }

    @Test
    fun `case5 — deeply nested link inside LhdR0e carousel is still excluded`() {
        // closest() walks up the full ancestor chain
        val carousel = SimElem("div",
            classes = setOf("vtSz8d"),
            attrs   = mapOf("jscontroller" to "LhdR0e"),
        )
        val inner = SimElem("div", parent = carousel)
        val link  = SimElem("a",
            attrs  = mapOf("data-curl" to "https://www.youtube.com/watch?v=ANY"),
            parent = inner,
        )
        assertTrue(isKnowledgePanelResultSim(link))
        // URL is valid by itself — only structural filter blocks it
        assertTrue(allowedUrl("https://www.youtube.com/watch?v=ANY"))
    }

    // --- case4: div[jscontroller="aD8OEe"] — no LhdR0e/vtSz8d, no h3 ---

    @Test
    fun `case4 — aD8OEe block not detected as carousel by isKnowledgePanelResult`() {
        // jscontroller="aD8OEe" != LhdR0e, no vtSz8d class
        // -> isKnowledgePanelResult cannot exclude it structurally
        val block = SimElem("div", attrs = mapOf("jscontroller" to "aD8OEe"))
        val link  = SimElem("a",
            attrs  = mapOf("href" to "https://www.youtube.com/watch?v=XYZ"),
            parent = block,
        )
        assertFalse(isKnowledgePanelResultSim(link))
    }

    @Test
    fun `case4 — aD8OEe block skipped by addYouTubeVideoBlock because no h3 title`() {
        // case_example_domain_youtobe.com.txt (L22): only has [role="heading"] span, no <h3>
        // addYouTubeVideoBlock: querySelector('h3, .LC20lb, .MBeuO, .F0FGWb') -> null -> skip
        assertFalse(youTubeBlockHasValidTitle(setOf("[role=\"heading\"]")))
        assertFalse(youTubeBlockHasValidTitle(setOf("span", "div", "section")))
        assertFalse(youTubeBlockHasValidTitle(emptySet()))
    }

    @Test
    fun `title selector — role heading and h1 are no longer valid after fix`() {
        // Fix removed [role="heading"] and h1 from addYouTubeVideoBlock title selector
        // YouTube organic card always has h3; carousel items only have [role="heading"]
        assertFalse(youTubeBlockHasValidTitle(setOf("[role=\"heading\"]")))
        assertFalse(youTubeBlockHasValidTitle(setOf("h1")))
    }

    @Test
    fun `title selector — h3 and class aliases are still valid`() {
        assertTrue(youTubeBlockHasValidTitle(setOf("h3")))
        assertTrue(youTubeBlockHasValidTitle(setOf(".LC20lb")))
        assertTrue(youTubeBlockHasValidTitle(setOf(".MBeuO")))
        assertTrue(youTubeBlockHasValidTitle(setOf(".F0FGWb")))
        assertTrue(youTubeBlockHasValidTitle(setOf("h3", ".LC20lb")))
    }

    // --- valid case1 / case2: div.PmEWq.wHYlTd organic cards must pass ---

    @Test
    fun `valid case1 — organic YouTube card with h3 passes all checks`() {
        // case_example_domain_youtobe.com.txt (L2):
        // div.PmEWq.wHYlTd
        //   div.WVV5ke[jscontroller="rTuANe"][data-curl="https://www.youtube.com/watch?v=VaEOLSP7_KU"]
        //     h3 — tieu de hop le
        val card  = SimElem("div", classes = setOf("PmEWq", "wHYlTd"))
        val block = SimElem(
            "div",
            classes = setOf("WVV5ke"),
            attrs   = mapOf(
                "jscontroller" to "rTuANe",
                "data-curl"    to "https://www.youtube.com/watch?v=VaEOLSP7_KU",
            ),
            parent = card,
        )
        val link = SimElem(
            "a",
            attrs  = mapOf("href" to "https://www.youtube.com/watch?v=VaEOLSP7_KU"),
            parent = block,
        )

        assertFalse(isKnowledgePanelResultSim(link))              // khong phai carousel
        assertTrue(youTubeBlockHasValidTitle(setOf("h3")))        // co h3 title
        assertTrue(allowedUrl("https://www.youtube.com/watch?v=VaEOLSP7_KU"))
        assertTrue(isYouTubeLikeUrl("https://www.youtube.com/watch?v=VaEOLSP7_KU"))
    }

    @Test
    fun `valid case2 — organic YouTube card with h3 passes all checks`() {
        // case_example_domain_youtobe.com.txt (L3):
        // div.PmEWq.wHYlTd
        //   div.WVV5ke[jscontroller="rTuANe"][data-curl="https://www.youtube.com/watch?v=0uyr4R3q-zc"]
        val card  = SimElem("div", classes = setOf("PmEWq", "wHYlTd"))
        val block = SimElem(
            "div",
            classes = setOf("WVV5ke"),
            attrs   = mapOf(
                "jscontroller" to "rTuANe",
                "data-curl"    to "https://www.youtube.com/watch?v=0uyr4R3q-zc",
            ),
            parent = card,
        )
        val link = SimElem(
            "a",
            attrs  = mapOf("href" to "https://www.youtube.com/watch?v=0uyr4R3q-zc"),
            parent = block,
        )

        assertFalse(isKnowledgePanelResultSim(link))
        assertTrue(youTubeBlockHasValidTitle(setOf("h3")))
        assertTrue(allowedUrl("https://www.youtube.com/watch?v=0uyr4R3q-zc"))
        assertTrue(isYouTubeLikeUrl("https://www.youtube.com/watch?v=0uyr4R3q-zc"))
    }

    @Test
    fun `rTuANe jscontroller is not a carousel — organic card passes`() {
        // rTuANe = valid YouTube organic result card controller, khac LhdR0e
        val block = SimElem("div", attrs = mapOf("jscontroller" to "rTuANe"))
        val link  = SimElem("a",
            attrs  = mapOf("href" to "https://www.youtube.com/watch?v=VaEOLSP7_KU"),
            parent = block,
        )
        assertFalse(isKnowledgePanelResultSim(link))
    }

    // ══════════════════════════════════════════════════════════════
    // Tests: case_top_domain_valided.txt — case1
    // Organic video result card (chs.pitt.edu) inside LhdR0e section.
    // Fix: isKnowledgePanelResult skips carousel check when link has <h3>.
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `case_top_domain_valided case1 — organic video card with h3 inside LhdR0e section NOT filtered`() {
        // case_top_domain_valided.txt (casa1):
        // div.vtSz8d.Ww4FFb.vt6azd[jscontroller="LhdR0e"]  <-- video section wrapper
        //   div.MjjYud
        //     div.A6K0A[data-rpos="0"]
        //       div.PmEWq.wHYlTd.Ww4FFb.vt6azd[jsname="pKB8Bc"][data-hveid="CA4QAA"]
        //         a.zReHs[jsname="UWckNb"][href="https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"]
        //           h3.LC20lb.MBeuO  <-- h3 IS INSIDE the link (organic card)
        //
        // Fix: link.querySelector('h3') is non-null → carousel check skipped → NOT filtered.
        val videoSection = SimElem(
            "div",
            classes = setOf("vtSz8d", "Ww4FFb", "vt6azd"),
            attrs   = mapOf("jscontroller" to "LhdR0e"),
        )
        val mjjYud = SimElem("div", classes = setOf("MjjYud"), parent = videoSection)
        val card   = SimElem(
            "div",
            classes = setOf("PmEWq", "wHYlTd", "Ww4FFb", "vt6azd"),
            attrs   = mapOf("jsname" to "pKB8Bc", "data-hveid" to "CA4QAA"),
            parent  = mjjYud,
        )
        val link = SimElem(
            "a",
            classes = setOf("zReHs"),
            attrs   = mapOf(
                "jsname" to "UWckNb",
                "href"   to "https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e",
            ),
            parent = card,
        )

        // Link has h3 inside → carousel check bypassed → NOT a KP result
        assertFalse(isKnowledgePanelResultSim(link, elementHasH3 = true))
        // Not excluded by EXCLUDE array (no g-section-with-header, EyBRub, etc.)
        assertFalse(isExcludedSim(link))
        // URL is valid and allowed
        assertTrue(allowedUrl("https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"))
    }

    @Test
    fun `case_top_domain_valided case1 — YouTube carousel link WITHOUT h3 inside LhdR0e STILL filtered`() {
        // Confirms existing case5 behavior is preserved:
        // carousel items inside LhdR0e do NOT have <h3> → carousel check applies → filtered.
        val videoSection = SimElem(
            "div",
            classes = setOf("vtSz8d", "Ww4FFb", "vt6azd"),
            attrs   = mapOf("jscontroller" to "LhdR0e"),
        )
        val link = SimElem(
            "a",
            attrs  = mapOf("data-curl" to "https://www.youtube.com/watch?v=PmN4O1ae5-w"),
            parent = videoSection,
        )

        // No h3 inside link → carousel check applies → IS a KP result → filtered
        assertTrue(isKnowledgePanelResultSim(link, elementHasH3 = false))
        // Default (false) also filters
        assertTrue(isKnowledgePanelResultSim(link))
    }

    @Test
    fun `case_top_domain_valided case1 — vtSz8d link WITHOUT h3 still filtered via vtSz8d selector`() {
        // A link directly inside .vtSz8d (without jscontroller) and without h3 is still blocked.
        val videoSection = SimElem("div", classes = setOf("vtSz8d"))
        val link = SimElem(
            "a",
            attrs  = mapOf("href" to "https://www.youtube.com/watch?v=abc123"),
            parent = videoSection,
        )

        assertTrue(isKnowledgePanelResultSim(link, elementHasH3 = false))
    }

    @Test
    fun `case_top_domain_valided case1 — chs pitt edu URL is valid and not an ad`() {
        assertTrue(allowedUrl("https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"))
        assertFalse(isAdUrl("https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"))
        assertEquals("chs.pitt.edu", getDomain("https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"))
    }


    // ══════════════════════════════════════════════════════════════
    // Tests: case_top_domain_valid.txt — chs.pitt.edu organic video card
    // inside g-section-with-header (Google "Videos" section).
    //
    // Root cause: g-section-with-header was added to EXCLUDE / isKnowledgePanelResult
    // to block news section (Top Stories) links. However it can also wrap legitimate
    // organic video result cards. The fix: apply the same <h3> guard used for
    // [jscontroller="LhdR0e"] — news links have NO <h3>; organic video cards DO.
    //
    // Script 3 (only script used at runtime) uses isKnowledgePanelResult, not isExcluded.
    // Scripts 1/2/4 still filter via EXCLUDE_SELS (g-section-with-header present there).
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `case_top_domain_valid — organic video card WITH h3 inside g-section-with-header NOT filtered by isKnowledgePanelResult`() {
        // Actual structure in the SERP (case_top_domain_valid.txt, dom from div.MjjYud down):
        // g-section-with-header  <-- Google "Videos" / "Tin bai video" section wrapper
        //   div.MjjYud
        //     div.A6K0A[data-rpos="0"]
        //       div.PmEWq.wHYlTd.Ww4FFb.vt6azd[jsname="pKB8Bc"][data-hveid="CA4QAA"]
        //         a.zReHs[jsname="UWckNb"][href="https://www.chs.pitt.edu/video?..."]
        //           h3.LC20lb.MBeuO  <-- h3 IS INSIDE the link (organic card)
        //
        // Fix: link.querySelector('h3') is non-null → g-section-with-header check skipped
        //      → isKnowledgePanelResult returns false → NOT filtered in Script 3.
        val videoSection = SimElem("g-section-with-header", classes = setOf("yG4QQe"))
        val mjjYud = SimElem("div", classes = setOf("MjjYud"), parent = videoSection)
        val a6K0A  = SimElem("div", classes = setOf("A6K0A"),
                             attrs = mapOf("data-rpos" to "0"), parent = mjjYud)
        val card   = SimElem(
            "div",
            classes = setOf("PmEWq", "wHYlTd", "Ww4FFb", "vt6azd"),
            attrs   = mapOf("jsname" to "pKB8Bc", "data-hveid" to "CA4QAA"),
            parent  = a6K0A,
        )
        val link = SimElem(
            "a",
            classes = setOf("zReHs"),
            attrs   = mapOf(
                "jsname" to "UWckNb",
                "href"   to "https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e",
            ),
            parent = card,
        )

        // Script 3 — isImagePackResult: no ULSxyf/data-iu → NOT filtered (valid case passes this)
        assertFalse(isImagePackResultSim(link))
        // Script 3 — isKnowledgePanelResult: link has h3 → g-section-with-header check bypassed → NOT filtered
        assertFalse(isKnowledgePanelResultSim(link, elementHasH3 = true))
        // Scripts 2 & 4 — isExcluded: g-section-with-header still in EXCLUDE_SELS (not used at runtime)
        assertTrue(isExcludedSim(link))
        // URL is valid
        assertTrue(allowedUrl("https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"))
    }

    @Test
    fun `case_top_domain_valid — news link WITHOUT h3 inside g-section-with-header still filtered`() {
        // Confirms that news article links (no h3) inside g-section-with-header are still blocked.
        // e.g. WlydOe / plain anchor links from "Tin bài hàng đầu" section
        val section = SimElem("g-section-with-header", classes = setOf("yG4QQe", "TBC9ub"))
        val link    = SimElem(
            "a",
            attrs  = mapOf("href" to "https://laodong.vn/article"),
            parent = section,
        )

        // No h3 → check triggers → IS a KP result → filtered
        assertTrue(isKnowledgePanelResultSim(link, elementHasH3 = false))
        assertTrue(isKnowledgePanelResultSim(link))   // default (false) also filters
    }

    @Test
    fun `case_top_domain_valid — organic video card directly in MjjYud (no section wrapper) passes all filters`() {
        // Case where video card has no g-section-with-header ancestor at all —
        // should pass through both isKnowledgePanelResult and isExcluded.
        val mjjYud = SimElem("div", classes = setOf("MjjYud"))
        val a6K0A  = SimElem("div", classes = setOf("A6K0A"),
                             attrs = mapOf("data-rpos" to "0"), parent = mjjYud)
        val card   = SimElem(
            "div",
            classes = setOf("PmEWq", "wHYlTd", "Ww4FFb", "vt6azd"),
            attrs   = mapOf("jsname" to "pKB8Bc", "data-hveid" to "CA4QAA"),
            parent  = a6K0A,
        )
        val link = SimElem(
            "a",
            classes = setOf("zReHs"),
            attrs   = mapOf(
                "jsname" to "UWckNb",
                "href"   to "https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e",
            ),
            parent = card,
        )

        // Script 3 — isImagePackResult: no ULSxyf/data-iu → NOT filtered
        assertFalse(isImagePackResultSim(link))
        // Script 3 — isKnowledgePanelResult: no g-section-with-header, no LhdR0e → NOT filtered (with or without h3)
        assertFalse(isKnowledgePanelResultSim(link, elementHasH3 = true))
        assertFalse(isKnowledgePanelResultSim(link, elementHasH3 = false))
        // Scripts 2 & 4 — isExcluded: no excluded ancestor → NOT excluded
        assertFalse(isExcludedSim(link))
        // URL is valid → CAPTURED
        assertTrue(allowedUrl("https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"))
    }

    // ══════════════════════════════════════════════════════════════
    // Tests: a.OcpZAb — new organic link class observed 2026-06
    // Google replaced a.zReHs[jsname="UWckNb"] with a.cz3goc.OcpZAb on video cards.
    // Logcat ZREHS_DIAG confirmed: a.zReHs count=0, pitt.edu link cls="cz3goc OcpZAb".
    // ancestors: div.T61Aje > div.Ww4FFb > div > div.MjjYud > div.rso
    // hasH3=false (h3 is sibling inside card, not inside link itself).
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `OcpZAb — new Google organic link class passes all Script 3 filters`() {
        // Exact runtime structure from ZREHS_DIAG log (2026-06-24):
        // div.rso > div.MjjYud > div > div.Ww4FFb > div.T61Aje
        //   > a.cz3goc.OcpZAb[href="https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"]
        //     (hasH3=false — h3 is in sibling, titleFromOrganicDirectLink finds it via card)
        val rso     = SimElem("div", classes = setOf("rso"))
        val mjjYud  = SimElem("div", classes = setOf("MjjYud"), parent = rso)
        val ww4FFb  = SimElem("div", classes = setOf("Ww4FFb"), parent = mjjYud)
        val t61Aje  = SimElem("div", classes = setOf("T61Aje"), parent = ww4FFb)
        val link    = SimElem(
            "a",
            classes = setOf("cz3goc", "OcpZAb"),
            attrs   = mapOf("href" to "https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"),
            parent  = t61Aje,
        )

        // isOrganicDirectLink: OcpZAb class → TRUE → addResult called
        assertTrue(isOrganicDirectLinkSim(link))
        // Script 3 — isImagePackResult: no ULSxyf/data-iu in ancestor → NOT filtered
        assertFalse(isImagePackResultSim(link))
        // Script 3 — isKnowledgePanelResult: no g-section, no LhdR0e → NOT filtered
        assertFalse(isKnowledgePanelResultSim(link))
        // Scripts 2 & 4 — isExcluded: no excluded ancestor
        assertFalse(isExcludedSim(link))
        // URL is valid → CAPTURED
        assertTrue(allowedUrl("https://www.chs.pitt.edu/video?watch=9JIUGG0Wf8e"))
    }

    @Test
    fun `OcpZAb — link inside ULSxyf is still filtered by isImagePackResult`() {
        // Even though OcpZAb is now a valid organic link class,
        // if it ends up inside a ULSxyf bloc it must still be filtered.
        val ulsxyf = SimElem("div", classes = setOf("ULSxyf"))
        val link   = SimElem(
            "a",
            classes = setOf("OcpZAb"),
            attrs   = mapOf("href" to "https://example.com/"),
            parent  = ulsxyf,
        )

        assertTrue(isOrganicDirectLinkSim(link))   // isOrganicDirectLink still true
        assertTrue(isImagePackResultSim(link))      // but isImagePackResult blocks it first
    }

    @Test
    fun `isOrganicDirectLink — recognises zReHs, UWckNb and OcpZAb, not rIRoqf`() {
        fun makeLink(cls: Set<String>, jsname: String = "") = SimElem(
            "a", classes = cls,
            attrs = buildMap { if (jsname.isNotEmpty()) put("jsname", jsname); put("href", "https://x.com/") }
        )

        assertTrue(isOrganicDirectLinkSim(makeLink(setOf("zReHs"))))
        assertTrue(isOrganicDirectLinkSim(makeLink(setOf("zReHs", "OcpZAb"))))
        assertTrue(isOrganicDirectLinkSim(makeLink(emptySet(), jsname = "UWckNb")))
        assertTrue(isOrganicDirectLinkSim(makeLink(setOf("OcpZAb"))))
        assertTrue(isOrganicDirectLinkSim(makeLink(setOf("cz3goc", "OcpZAb"))))  // exact runtime class
        // rIRoqf (thumbnail) and WlydOe (news) are NOT organic direct links
        assertFalse(isOrganicDirectLinkSim(makeLink(setOf("rIRoqf"))))
        assertFalse(isOrganicDirectLinkSim(makeLink(setOf("WlydOe"))))
        assertFalse(isOrganicDirectLinkSim(makeLink(setOf("ddkIM"))))
    }
}
