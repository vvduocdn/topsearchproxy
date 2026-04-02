package com.topsearch.app

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phân tích ảnh chụp màn hình Google Search bằng ML Kit.
 *
 * Chiến lược nhận diện tiêu đề kết quả:
 *  1. Đọc toàn bộ TextBlock (mỗi block ~ 1 đoạn văn / widget trên màn hình)
 *  2. Phân tích từng block theo 5 tiêu chí lọc:
 *     a. Độ dài  – tiêu đề thường ≥ 20 ký tự, < 200 ký tự
 *     b. Vị trí Y – bỏ status bar (~top 5%) và navigation bar (~bottom 8%)
 *     c. URL / breadcrumb – bỏ text chứa "›", "»", ".com", "https://"
 *     d. Navigation noise – bỏ tab-bar, search-bar text
 *     e. Ratio chữ thực – ít nhất 60% ký tự là chữ cái
 *  3. Sắp xếp theo Y-position, lấy tối đa 10 kết quả đầu tiên
 */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun extractSearchResults(imagePath: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
                ?: throw IllegalStateException("Không decode được ảnh: $imagePath")

            val inputImage = InputImage.fromBitmap(bitmap, 0)

            val visionText = suspendCancellableCoroutine { cont ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            bitmap.recycle()

            parseResults(visionText, imagePath)
        }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseResults(text: Text, imagePath: String): List<SearchResult> {
        val imageH = estimateImageHeight(imagePath)

        // Lấy tất cả block kèm bounds
        data class Candidate(val text: String, val bounds: Rect)

        val candidates = text.textBlocks
            .filter { it.boundingBox != null }
            .map { block ->
                // Dùng toàn bộ text của block (join các line bằng space)
                val merged = block.lines.joinToString(" ") { it.text.trim() }
                Candidate(merged, block.boundingBox!!)
            }
            .sortedBy { it.bounds.top }   // top → bottom

        val results = mutableListOf<String>()

        for (c in candidates) {
            val t = c.text.trim()
            if (t.isBlank()) continue

            // ── Bộ lọc ────────────────────────────────────────────────────

            // 1. Bỏ status bar và navigation bar (vùng ngoài rìa màn hình)
            if (imageH > 0) {
                val relTop = c.bounds.top.toFloat() / imageH
                val relBot = c.bounds.bottom.toFloat() / imageH
                if (relTop < 0.04f || relBot > 0.95f) continue
            }

            // 2. Độ dài hợp lý
            if (t.length < 18 || t.length > 220) continue

            // 3. Không phải URL / breadcrumb
            if (looksLikeUrl(t)) continue

            // 4. Không phải navigation / chrome UI text
            if (isNavNoise(t)) continue

            // 5. Phải chứa đủ chữ thật (≥ 55% ký tự)
            val letterRatio = t.count { it.isLetter() }.toFloat() / t.length
            if (letterRatio < 0.55f) continue

            // 6. Không hoàn toàn là hoa (thường là button / label)
            if (t.length > 6 && t == t.uppercase() && !t.any { it.isLowerCase() }) continue

            results += cleanTitle(t)
            if (results.size >= 10) break
        }

        return results.mapIndexed { i, title -> SearchResult(i + 1, title) }
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    private val URL_REGEX = Regex(
        """https?://|www\.|\.com\b|\.vn\b|\.org\b|\.net\b|\.io\b|[›»]|\.\w{2,4}/""",
        RegexOption.IGNORE_CASE,
    )

    private fun looksLikeUrl(text: String): Boolean = URL_REGEX.containsMatchIn(text)

    /** Text xuất hiện trong thanh tìm kiếm, tab bar, footer của Google */
    private val NAV_EXACT = setOf(
        "google", "all", "images", "news", "videos", "maps", "shopping",
        "tools", "settings", "sign in", "more", "web", "search",
        "tìm kiếm", "hình ảnh", "tin tức", "video", "bản đồ", "mua sắm",
        "about", "advertising", "business", "privacy", "terms",
        "giới thiệu", "quảng cáo", "doanh nghiệp", "bảo mật", "điều khoản",
    )
    private val NAV_CONTAINS = listOf(
        "kết quả tìm kiếm", "search results", "about this result",
        "về kết quả này", "quảng cáo", "sponsored", "tài trợ",
        "xem thêm kết quả", "more results",
    )

    private fun isNavNoise(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower in NAV_EXACT) return true
        return NAV_CONTAINS.any { lower.contains(it) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun cleanTitle(text: String): String =
        text
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^\s*[\d]+[.)]\s*"""), "")   // bỏ số thứ tự đầu dòng
            .trim()

    /** Ước lượng chiều cao ảnh để loại bỏ status-bar / nav-bar */
    private fun estimateImageHeight(imagePath: String): Int {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, opts)
        return opts.outHeight
    }
}
