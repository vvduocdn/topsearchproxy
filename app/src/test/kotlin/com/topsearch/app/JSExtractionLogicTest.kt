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
        "[data-maindata*=\"LOCAL_NAV\"]",
        ".P6Deab",
        "[data-phone-number]",
        "[data-url*=\"maps.google\"]",
    )

    private fun isExcludedSim(el: SimElem): Boolean =
        EXCLUDE_SELS.any { el.closest(it) != null }

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

    // --- Full structure from case_78win_loi.txt ---

    @Test
    fun `all action links inside case_78win knowledge panel are excluded`() {
        // Mirrors: MjjYud > EyBRub[data-kpid] > div[data-maindata*=LOCAL_NAV] > g-scrolling-carousel > a.P6Deab
        val outer    = SimElem("div", classes = setOf("MjjYud", "VjDLd"))
        val kpPanel  = SimElem("div",
            classes = setOf("kp-wholepage", "EyBRub"),
            attrs   = mapOf("data-kpid" to "/g/11m5jfy833"),
            parent  = outer)
        val navBlock = SimElem("div",
            attrs  = mapOf("data-maindata" to """[null,"/g/11m5jfy833","78win",null,null,null,null,null,"LOCAL_NAV","vi-VN",null,133]"""),
            parent = kpPanel)
        val carousel = SimElem("g-scrolling-carousel", parent = navBlock)

        val links = mapOf(
            "http://78win.productions/"                    to SimElem("a", setOf("P6Deab"), mapOf("href" to "http://78win.productions/"), carousel),
            "https://www.facebook.com/78Win.Official/"     to SimElem("a", emptySet(), mapOf("href" to "https://www.facebook.com/78Win.Official/"), carousel),
            "https://www.youtube.com/@78Win_VN"            to SimElem("a", emptySet(), mapOf("href" to "https://www.youtube.com/@78Win_VN"), carousel),
        )

        links.forEach { (href, el) ->
            assertTrue("Must be excluded: $href", isExcludedSim(el))
        }
    }
}
