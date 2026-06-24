package com.topsearch.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.topsearch.app.LocalProxyServer
import com.topsearch.app.ProxyHelper
import com.topsearch.app.SearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "WebCapture"
private const val COUNTDOWN_SEC = 6
private const val MAX_CAPTURE_TILES = 80
private const val MAX_CAPTURE_CHUNK_HEIGHT_PX = 24_000
private const val CAPTURE_SETTLE_MS = 650L
private const val PIXEL_COPY_RETRIES = 3
private const val SCREENSHOT_JPEG_QUALITY = 90
private const val MAX_CAPTURE_OVERLAP_CSS_PX = 600
private const val MIN_SEAM_PROGRESS_RATIO = 0.45f
private const val MAX_SEAM_PROGRESS_RATIO = 0.92f
private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; SM-S908B) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/124.0.0.0 Mobile Safari/537.36"

private data class CaptureOutput(
    val paths: List<String>,
    val results: List<SearchResult>,
    val checkedAt: Long = 0L,
)

// ── Composable ────────────────────────────────────────────────────────────────

@Composable
fun WebCaptureScreen(
    url:       String,
    keyword:   String = "",
    proxyHost: String = "",
    publicIp:  String = "",
    spoofLat:  Double = 0.0,
    spoofLng:  Double = 0.0,
    onCaptureDone: (screenshotPaths: List<String>, jsResults: List<SearchResult>, detectedCity: String, checkedAt: Long) -> Unit,
    onError:       (String) -> Unit,
) {
    val context        = LocalContext.current
    var pageLoaded     by remember { mutableStateOf(false) }
    var countdown      by remember { mutableIntStateOf(COUNTDOWN_SEC) }
    var statusText     by remember { mutableStateOf("Đang tải kết quả…") }
    var capturing      by remember { mutableStateOf(false) }
    var webViewRef     by remember { mutableStateOf<WebView?>(null) }
    // true khi proxy fail → đã fallback về UULE-only (tránh retry loop)
    var proxyFallback  by remember { mutableStateOf(false) }
    // URL của trang CAPTCHA — set để trigger LaunchedEffect giải
    var captchaPageUrl   by remember { mutableStateOf("") }
    var captchaRetryCount by remember { mutableIntStateOf(0) }
    // LocalProxyServer — chỉ tạo khi proxy có auth (tự thêm Proxy-Authorization header)
    val localProxy     = remember<LocalProxyServer?> {
        val info = ProxyHelper.parse(proxyHost)
        if (info != null && info.requiresAuth)
            LocalProxyServer(info.host, info.port, info.user, info.pass).also { it.start() }
        else null
    }

    // ── Bước 0: Đặt proxy rồi load URL ───────────────────────────────────────
    LaunchedEffect(webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect

        // Xóa toàn bộ dữ liệu trình duyệt trước mỗi search — đảm bảo không có
        // cookie/cache/localStorage từ tỉnh trước đè lên proxy IP mới
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        wv.clearCache(true)
        wv.clearHistory()
        WebStorage.getInstance().deleteAllData()

        if (proxyHost.isNotBlank()) {
            statusText = "Đang kết nối proxy…"
            // Nếu có local proxy → dùng 127.0.0.1:port (no auth needed, local proxy lo)
            // Nếu không có auth → trỏ thẳng vào remote proxy
            val proxyToSet = if (localProxy != null)
                "127.0.0.1:${localProxy.localPort}"
            else
                proxyHost
            val ok = suspendCancellableCoroutine { cont ->
                ProxyHelper.setProxy(proxyToSet) { cont.resume(it) }
            }
            if (!ok) Log.w(TAG, "Proxy set failed")
        }
        val acceptLanguage = if (url.contains("google.co.th"))
            "th-TH,th;q=0.9,en-US;q=0.8,en;q=0.7"
        else
            "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
        Log.d(TAG, "▶ loadUrl keyword='$keyword' url=$url Accept-Language=$acceptLanguage")
        wv.loadUrl(url, mapOf("Accept-Language" to acceptLanguage))
    }

    // Giải CAPTCHA khi Google hiện trang /sorry/
    LaunchedEffect(captchaPageUrl) {
        if (captchaPageUrl.isBlank()) return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect

        captchaRetryCount++
        val attempt = captchaRetryCount
        statusText = "Đang giải CAPTCHA… (lần $attempt)"
        Log.w(TAG, "═══ CAPTCHA START attempt=$attempt url=$captchaPageUrl ═══")

        // Chờ iframe reCAPTCHA load xong
        delay(2_000)

        // Dump DOM để debug
        val domInfo = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript("""
                (function(){
                    var el = document.querySelector('[data-sitekey]');
                    var cb = document.querySelector('[data-callback]');
                    var ds = document.querySelector('[data-s]');
                    return JSON.stringify({
                        sitekey:  el  ? el.getAttribute('data-sitekey')  : '',
                        callback: cb  ? cb.getAttribute('data-callback') : '',
                        dataS:    ds  ? ds.getAttribute('data-s')        : '',
                        hasForm:  !!document.querySelector('form'),
                        bodySnip: (document.body ? document.body.innerText : '').substring(0,100)
                    });
                })()
            """.trimIndent()) { r -> cont.resume((r ?: "null").trim().removeSurrounding("\"").replace("\\\"","\"")) }
        }
        Log.w(TAG, "CAPTCHA DOM → $domInfo")

        val domObj = try { org.json.JSONObject(domInfo) } catch (_: Exception) { org.json.JSONObject() }
        val siteKey = domObj.optString("sitekey")
        val dataS   = domObj.optString("dataS")
        Log.w(TAG, "CAPTCHA siteKey='$siteKey' dataS=${dataS.take(20)}")

        // Dùng cùng proxy với WebView để Google chấp nhận token
        val proxyInfo = if (proxyHost.isNotBlank()) com.topsearch.app.ProxyHelper.parse(proxyHost) else null

        val token = com.topsearch.app.CapSolverHelper.solve(captchaPageUrl, siteKey, dataS, proxyInfo)
        if (token == null) {
            Log.e(TAG, "CAPTCHA solve FAILED — CapSolver trả null")
            onError("Không giải được CAPTCHA — thử lại")
            captchaPageUrl = ""
            return@LaunchedEffect
        }

        Log.w(TAG, "CAPTCHA token nhận được length=${token.length}")
        wv.evaluateJavascript(GoogleSearchJs.buildCaptchaSubmitJs(token)) { r ->
            Log.w(TAG, "CAPTCHA inject result=$r")
        }
        captchaPageUrl = ""
    }

    // Dọn dẹp khi rời màn hình
    DisposableEffect(Unit) {
        onDispose {
            localProxy?.stop()
            if (proxyHost.isNotBlank()) ProxyHelper.clearProxy()
        }
    }

    LaunchedEffect(pageLoaded) {
        if (!pageLoaded) return@LaunchedEffect
        val wv = webViewRef ?: run { onError("WebView chưa sẵn sàng"); return@LaunchedEffect }

        // ── Bước 1: Chờ Google render DOM (poll mỗi 400ms, tối đa 8s) ────
        statusText = "Chờ Google render…"
        var ready  = false
        repeat(20) {
            if (ready) return@repeat
            val count = suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(GoogleSearchJs.WAIT_READY_JS) { r ->
                    cont.resume(r?.trim()?.toIntOrNull() ?: 0)
                }
            }
            Log.d(TAG, "Poll $it → element count = $count")
            if (count > 0) { ready = true } else { delay(400) }
        }

        // ── Bước 2: Debug dump để biết DOM thực tế ────────────────────────
        val debugStr = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(GoogleSearchJs.DEBUG_JS) { r -> cont.resume(r ?: "null") }
        }
        Log.d(TAG, "DEBUG DOM → $debugStr")

        // ── Bước 3: Scroll từng bước trong countdown ─────────────────────
        for (i in COUNTDOWN_SEC downTo 1) {
            countdown  = i
            val scrollY = (COUNTDOWN_SEC - i) * 600
            wv.evaluateJavascript("window.scrollTo({top:$scrollY,behavior:'smooth'});", null)
            statusText = when {
                i > 10 -> "Đang tải kết quả…"
                i > 5  -> "Đang cuộn xem kết quả…"
                i > 2  -> "Gần xong…"
                else   -> "Chuẩn bị phân tích…"
            }
            delay(1_000)
        }

        capturing = true

        // Bước 4: Detect city trước; result top sẽ extract sau capture để khớp DOM đã render.
        val rawCity = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(GoogleSearchJs.LOCATION_JS) { r ->
                cont.resume((r ?: "").trim().removeSurrounding("\""))
            }
        }
        Log.d(TAG, "DETECTED CITY → $rawCity")

        // Scroll về đầu để chụp từ top
        wv.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)
        delay(300)

        // ── Bước 5: Chụp ảnh ─────────────────────────────────────────────
        // Capture full page trước để Google lazy-render đủ kết quả cuối trang.
        val captureOutput = try {
            val output = captureWebViewTiles(wv, context.getExternalFilesDir(null), publicIp)
            Log.d(TAG, "Captured ${output.paths.size} tile(s)")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            CaptureOutput(emptyList(), emptyList())
        }

        delay(250)

        // Extract sau capture, khi WebView vẫn ở cuối trang để không mất top cuối.
        val jsonStr = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(GoogleSearchJs.EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS) { r -> cont.resume(r ?: "[]") }
        }
        Log.d(TAG, "RAW HEADING ORDER JS -> $jsonStr")
        val jsResults = parseJsResults(jsonStr)
        Log.d(TAG, "PARSED HEADING ORDER ${jsResults.size} results")
        logParsedTopResults(jsResults)

        wv.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)
        val finalResults = jsResults.take(10)
        Log.d(TAG, "onCaptureDone checkedAt=${captureOutput.checkedAt} paths=${captureOutput.paths.size} results=${finalResults.size}")
        onCaptureDone(captureOutput.paths, finalResults, rawCity, captureOutput.checkedAt)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    WebView.setWebContentsDebuggingEnabled(true)
                    // Software layer chỉ cần cho API < 26 (webView.draw fallback).
                    // API >= 26 dùng PixelCopy đọc GPU surface → phải để hardware rendering.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    }
                    settings.apply {
                        javaScriptEnabled    = true
                        domStorageEnabled    = true
                        databaseEnabled      = true
                        userAgentString      = CHROME_UA
                        loadWithOverviewMode = true
                        useWideViewPort      = true
                        setSupportZoom(false)
                        builtInZoomControls  = false
                        displayZoomControls  = false
                        // Dùng cache mặc định để Google thấy session có history → ít CAPTCHA hơn
                        cacheMode            = WebSettings.LOAD_DEFAULT
                    }
                    // Bật cookie — quan trọng để Google nhận diện là browser thật
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    // ── Spoof geolocation (client-side) ─────────────────
                    // Grant quyền location tự động → không hỏi user
                    // Inject tọa độ giả trước khi Google JS chạy
                    if (spoofLat != 0.0 && spoofLng != 0.0) {
                        settings.setGeolocationEnabled(true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String,
                                callback: GeolocationPermissions.Callback,
                            ) {
                                callback.invoke(origin, true, false)
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        private var done = false
                        private var transientRetryCount = 0
                        private var homepageLoopCount = 0

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            Log.d(TAG, "onPageStarted url=$url")
                            // Inject TRƯỚC khi trang render để Google thấy vị trí giả
                            if (spoofLat != 0.0 && spoofLng != 0.0) {
                                view.evaluateJavascript(GoogleSearchJs.buildSpoofLocationJs(spoofLat, spoofLng), null)
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            Log.d(TAG, "onPageFinished url=$url done=$done")
                            if (spoofLat != 0.0 && spoofLng != 0.0) {
                                view.evaluateJavascript(GoogleSearchJs.buildSpoofLocationJs(spoofLat, spoofLng), null)
                            }
                            if (done) return
                            // Detect CAPTCHA (Google sorry page)
                            if (url.contains("/sorry/") || url.contains("recaptcha.google.com")) {
                                if (captchaRetryCount >= 3) {
                                    Log.e(TAG, "CAPTCHA: max retries (3) reached, proxy bị block")
                                    onError("Proxy bị Google block CAPTCHA — thử proxy khác")
                                    return
                                }
                                Log.w(TAG, "CAPTCHA detected → $url")
                                captchaPageUrl = url
                                return
                            }
                            if (keyword.isNotBlank() && !url.contains("/search")) {
                                // Phase 1: homepage loaded → inject keyword and submit
                                homepageLoopCount++
                                if (homepageLoopCount > 4) {
                                    Log.w(TAG, "Homepage redirect loop detected ($homepageLoopCount times) — proxy issue")
                                    onError("Proxy lỗi — thử lại để đổi proxy khác")
                                    return
                                }
                                Log.d(TAG, "Phase 1 → homepage detected (#$homepageLoopCount), inject keyword='$keyword'")
                                view.evaluateJavascript(GoogleSearchJs.buildSearchJs(keyword)) { result ->
                                    Log.d(TAG, "Phase 1 inject result=$result")
                                }
                            } else {
                                // Phase 2: results page (or direct URL) → trigger extraction
                                Log.d(TAG, "Phase 2 → results page detected, trigger extraction")
                                done = true
                                pageLoaded = true
                            }
                        }

                        override fun onReceivedError(
                            view: WebView, req: WebResourceRequest, err: WebResourceError,
                        ) {
                            if (!req.isForMainFrame) return
                            val desc = err.description ?: ""
                            val isProxyError = localProxy != null && !proxyFallback &&
                                (desc.contains("PROXY", ignoreCase = true) ||
                                 desc.contains("TUNNEL", ignoreCase = true) ||
                                 desc.contains("ERR_CONNECTION_REFUSED", ignoreCase = true) ||
                                 desc.contains("ERR_CONNECTION_RESET", ignoreCase = true))
                            if (isProxyError) {
                                proxyFallback = true
                                Log.w(TAG, "Proxy failed (${err.description}) — không fallback để tránh kết quả sai tỉnh")
                                ProxyHelper.clearProxy()
                                onError("Proxy lỗi — thử lại để đổi proxy khác")
                            } else if (transientRetryCount < 1) {
                                transientRetryCount++
                                Log.w(TAG, "Transient error (${err.description}), silent reload #$transientRetryCount")
                                view.reload()
                            } else if (localProxy != null && !proxyFallback &&
                                desc.contains("ERR_SSL", ignoreCase = true)) {
                                // SSL error sau khi đã reload 1 lần + có proxy → proxy SSL issue
                                proxyFallback = true
                                Log.w(TAG, "Proxy SSL failed (${err.description}) after reload")
                                ProxyHelper.clearProxy()
                                onError("Proxy lỗi — thử lại để đổi proxy khác")
                            } else {
                                Log.w(TAG, "Page load error (${err.description}) proxy=${localProxy != null}")
                                onError("Lỗi tải trang: ${err.description}")
                            }
                        }
                    }
                    // loadUrl gọi từ LaunchedEffect(webViewRef) — sau khi proxy được set
                }
            },
        )

        if (!capturing) {
            if (captchaPageUrl.isNotBlank()) {
                // CAPTCHA đang giải — ẩn overlay, hiện trạng thái nhỏ ở trên
                Box(
                    modifier         = Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(statusText, color = Color.White, fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier
                            .background(Color.Black.copy(alpha = 0.60f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 6.dp))
                }
            } else if (!pageLoaded) {
                Box(
                    modifier         = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.70f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Đang tải kết quả tìm kiếm…",
                            color = Color.White, fontSize = 16.sp,
                            textAlign = TextAlign.Center)
                    }
                }
            } else {
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CountdownOverlay(countdown, statusText)
                }
            }
        }
    }
}

@Composable
private fun CountdownOverlay(countdown: Int, statusText: String) {
    val progress by animateFloatAsState(
        targetValue   = 1f - countdown.toFloat() / COUNTDOWN_SEC,
        animationSpec = tween(900),
        label         = "cd",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress    = { progress },
                modifier    = Modifier.size(88.dp),
                strokeWidth = 6.dp,
                strokeCap   = StrokeCap.Round,
                color       = Color(0xFF4CAF50),
                trackColor  = Color.White.copy(alpha = 0.2f),
            )
            Box(
                contentAlignment = Alignment.Center,
            ) {
                Text("$countdown", fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(statusText, color = Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

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
        val arr = JSONArray(json)
        var rank = 0
        (0 until arr.length()).mapNotNull { i ->
            val obj   = arr.getJSONObject(i)
            val isAd  = obj.optBoolean("ad", false)
            val title = obj.optString("t", "").trim()
            val blk   = obj.optString("_blk", "")
            android.util.Log.d("TopSearch", "JS[$i] d=${obj.optString("d","")} blk=$blk t=${title.take(40)}")
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

// Log 10 top đầu để đối chiếu ảnh, dialog và payload submit.
private fun logParsedTopResults(results: List<SearchResult>) {
    if (results.isEmpty()) {
        Log.w(TAG, "TOP DEBUG: no parsed results")
        return
    }
    Log.d(TAG, "TOP DEBUG: parsed=${results.size}, submitWillSend=${results.take(10).size}")
    results.take(10).forEach { r ->
        Log.d(TAG, "TOP DEBUG #${r.rank}: domain=${r.domain} url=${r.url} title=${r.title}")
    }
    if (results.size > 10) {
        Log.d(TAG, "TOP DEBUG: ${results.size - 10} extra result(s) parsed but not shown/submitted")
    }
}

/**
 * Capture full page bằng PixelCopy.
 * Scroll theo CSS px, ghép theo actualY và crop overlap để tránh trùng ảnh.
 */
private suspend fun captureWebViewTiles(webView: WebView, dir: File?, publicIp: String = "", captureTime: Date? = null): CaptureOutput {
    val w       = webView.width.takeIf { it > 0 } ?: 1080
    val viewHPx = webView.height.takeIf { it > 0 } ?: 1920
    val now     = captureTime ?: Date()
    val ts      = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
    val tsDisplay = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(now)
    val base    = dir ?: File("/sdcard")

    // PixelCopy cần vùng WebView thật trên màn hình (real on-screen rect).
    val window = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        (webView.context as? Activity)?.window
    } else null
    val loc     = IntArray(2).also { webView.getLocationOnScreen(it) }
    val srcRect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + viewHPx)

    webView.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)
    delay(CAPTURE_SETTLE_MS)

    val (cssViewH, initialCssPageH) = readPageMetrics(webView, viewHPx)
    // JS trả về CSS pixel; bitmap dùng pixel vật lý (physical pixel).
    val dpr = if (cssViewH > 0) viewHPx.toFloat() / cssViewH else 1f
    var totalPhysH = kotlin.math.ceil(initialCssPageH * dpr).toInt().coerceAtLeast(1)
    Log.d(TAG, "captureFullPage: viewHPx=$viewHPx cssViewH=$cssViewH cssPageH=$initialCssPageH dpr=$dpr totalPhysH=$totalPhysH")

    val paths = mutableListOf<String>()
    val tileBmp = Bitmap.createBitmap(w, viewHPx, Bitmap.Config.ARGB_8888)

    var chunkTop = 0
    var chunkBitmap = createChunkBitmap(w, totalPhysH, chunkTop)
    var chunkCanvas = Canvas(chunkBitmap)
    var chunkHasPixels = false
    var chunkIndex = 1

    // Lưu chunk hiện tại: trang thường 1 file, trang quá cao thì nhiều part.
    fun saveChunkIfNeeded(force: Boolean = false, isLast: Boolean = false, contentBottom: Int = 0) {
        if (!chunkHasPixels && !force) return
        val suffix = if (chunkIndex == 1 && totalPhysH <= MAX_CAPTURE_CHUNK_HEIGHT_PX) {
            "full"
        } else {
            "part_%02d".format(chunkIndex)
        }

        // Vẽ overlay time + IP chỉ trên chunk cuối (footer của trang)
        if (isLast) {
            val overlayCanvas = Canvas(chunkBitmap)
            val line1 = "Time: $tsDisplay"
            val line2: String? = if (publicIp.isNotBlank()) {
                val parts = publicIp.split(".")
                val masked = if (parts.size == 4) "${parts[0]}.***.${parts[3]}" else publicIp
                "IP: $masked"
            } else null
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color    = android.graphics.Color.WHITE
                textSize = (chunkBitmap.width * 0.025f).coerceIn(24f, 40f)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val bgPaint = android.graphics.Paint().apply { color = 0xCC000000.toInt() }
            val pad   = (textPaint.textSize * 0.5f).toInt()
            val lineH = (textPaint.textSize * 1.35f).toInt()
            val lines = listOfNotNull(line1, line2)
            val boxW  = (lines.maxOf { textPaint.measureText(it) } + pad * 2).toInt()
            val boxH  = lineH * lines.size + pad
            // Vẽ tại đáy nội dung thực tế, không phải đáy bitmap được cấp phát
            val effectiveBottom = if (contentBottom > chunkTop)
                (contentBottom - chunkTop).coerceAtMost(chunkBitmap.height)
            else
                chunkBitmap.height
            val boxL  = (chunkBitmap.width - boxW).toFloat()
            val boxT  = (effectiveBottom - boxH).toFloat().coerceAtLeast(0f)
            overlayCanvas.drawRect(boxL, boxT, chunkBitmap.width.toFloat(), effectiveBottom.toFloat(), bgPaint)
            lines.forEachIndexed { i, text ->
                overlayCanvas.drawText(text, boxL + pad, boxT + pad + textPaint.textSize + lineH * i, textPaint)
            }
        }

        val file = File(base, "topsearch_${ts}_$suffix.jpg")
        FileOutputStream(file).use {
            chunkBitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, it)
        }
        paths += file.absolutePath
        Log.d(TAG, "Saved screenshot chunk ${file.name} top=$chunkTop height=${chunkBitmap.height}")
        chunkHasPixels = false
        chunkIndex++
    }

    // Sang bitmap output tiếp theo khi tọa độ page Y vượt chunk hiện tại.
    fun advanceChunkTo(targetY: Int) {
        while (targetY >= chunkTop + chunkBitmap.height && chunkTop + chunkBitmap.height < totalPhysH) {
            val nextChunkTop = chunkTop + chunkBitmap.height
            saveChunkIfNeeded()
            chunkBitmap.recycle()
            chunkTop = nextChunkTop
            chunkBitmap = createChunkBitmap(w, totalPhysH, chunkTop)
            chunkCanvas = Canvas(chunkBitmap)
        }
    }

    var offsetCss   = 0
    var prevActualY = -1
    var idx         = 0
    var capturedBottom = 0
    // Chụp có overlap, rồi crop phần dính bằng capturedBottom.
    val overlapCss = minOf(MAX_CAPTURE_OVERLAP_CSS_PX, (cssViewH * 0.25f).toInt()).coerceAtLeast(1)
    val scrollStepCss = (cssViewH - overlapCss).coerceAtLeast(1)

    while (idx < MAX_CAPTURE_TILES) {
        webView.evaluateJavascript("window.scrollTo({top:$offsetCss,behavior:'instant'});", null)
        delay(CAPTURE_SETTLE_MS)
        val actualY = readActualScrollY(webView, offsetCss)
        Log.d(

            TAG,

            "actualY=$actualY prev=$prevActualY offsetCss=$offsetCss totalPhysH=$totalPhysH idx=$idx"

        )
        // Tin vị trí scroll thật của browser (actualY), không tin offset đã yêu cầu.
        if (actualY == prevActualY) break

        val latestCssPageH = readPageMetrics(webView, viewHPx).second
        totalPhysH = maxOf(totalPhysH, kotlin.math.ceil(latestCssPageH * dpr).toInt())
        // Ưu tiên cắt giữa các result block, tránh cắt ngang title/snippet.
        val minCutCss = actualY + (cssViewH * MIN_SEAM_PROGRESS_RATIO).toInt()
        val maxCutCss = actualY + (cssViewH * MAX_SEAM_PROGRESS_RATIO).toInt()
        val fallbackCutCss = (actualY + scrollStepCss).coerceAtMost(latestCssPageH)
        val domCutCss = findSafeCutCssY(webView, actualY, cssViewH)
        val cutCss = when {
            actualY + cssViewH >= latestCssPageH - 2 -> latestCssPageH
            domCutCss in minCutCss..maxCutCss -> domCutCss
            else -> fallbackCutCss
        }.coerceAtLeast(actualY + 1)

        // Từ tile 2, ẩn sticky/fixed bar của Google để không lặp trong ảnh ghép.
        setCaptureOverlaysHidden(webView, hide = idx > 0)
        delay(80)

        if (!copyWebViewToBitmap(webView, window, srcRect, tileBmp, idx)) {
            setCaptureOverlaysHidden(webView, hide = false)
            tileBmp.recycle()
            chunkBitmap.recycle()
            webView.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)
            return CaptureOutput(emptyList(), emptyList())
        }

        val tileTop = (actualY * dpr).toInt().coerceAtLeast(0)
        val tileBottom = minOf(tileTop + viewHPx, kotlin.math.ceil(cutCss * dpr).toInt(), totalPhysH)
        if (tileTop > capturedBottom + 2) {
            Log.w(TAG, "Capture gap detected: tileTop=$tileTop capturedBottom=$capturedBottom idx=$idx")
        }
        // Không vẽ ngược lên vùng đã ghép xong, tránh đè/trùng nội dung.
        var segmentTop = maxOf(tileTop, capturedBottom)

        while (segmentTop < tileBottom) {
            advanceChunkTo(segmentTop)
            val chunkBottom = (chunkTop + chunkBitmap.height).coerceAtMost(totalPhysH)
            val segmentBottom = minOf(tileBottom, chunkBottom)
            // Crop tọa độ tile vào đúng chunk output hiện tại.
            val srcTop = segmentTop - tileTop
            val srcBottom = segmentBottom - tileTop
            val dstTop = segmentTop - chunkTop
            val dstBottom = segmentBottom - chunkTop

            if (srcBottom > srcTop && dstBottom > dstTop) {
                chunkCanvas.drawBitmap(
                    tileBmp,
                    Rect(0, srcTop, w, srcBottom),
                    Rect(0, dstTop, w, dstBottom),
                    null,
                )
                chunkHasPixels = true
                capturedBottom = maxOf(capturedBottom, segmentBottom)
            }
            segmentTop = segmentBottom
        }

        prevActualY = actualY
        offsetCss = (cutCss - overlapCss).coerceAtLeast(0)
        Log.d(TAG, "capture seam idx=$idx actualY=$actualY cutCss=$cutCss domCutCss=$domCutCss nextOffset=$offsetCss")
        idx++
    }

    tileBmp.recycle()
    saveChunkIfNeeded(force = paths.isEmpty(), isLast = true, contentBottom = capturedBottom)
    chunkBitmap.recycle()
    setCaptureOverlaysHidden(webView, hide = false)

    Log.d(TAG, "Full page saved as ${paths.size} file(s), tiles=$idx dpr=$dpr totalPhysH=$totalPhysH capturedBottom=$capturedBottom overlapCss=$overlapCss")
    return CaptureOutput(paths, emptyList(), now.time)
}

private fun createChunkBitmap(width: Int, totalHeight: Int, chunkTop: Int): Bitmap {
    val height = (totalHeight - chunkTop)
        .coerceAtMost(MAX_CAPTURE_CHUNK_HEIGHT_PX)
        .coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(android.graphics.Color.WHITE)
    }
}

private suspend fun readPageMetrics(webView: WebView, fallbackViewHeightPx: Int): Pair<Int, Int> =
    suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript("[window.innerHeight,document.documentElement.scrollHeight]") { r ->
            try {
                val arr = JSONArray(r?.trim() ?: "[]")
                cont.resume(Pair(arr.optInt(0, fallbackViewHeightPx / 3), arr.optInt(1, fallbackViewHeightPx / 3)))
            } catch (_: Exception) {
                cont.resume(Pair(fallbackViewHeightPx / 3, fallbackViewHeightPx / 3))
            }
        }
    }

private suspend fun readActualScrollY(webView: WebView, fallback: Int): Int =
    suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript("window.pageYOffset") { r ->
            cont.resume(r?.trim()?.toFloatOrNull()?.toInt() ?: fallback)
        }
    }

private suspend fun findSafeCutCssY(webView: WebView, actualY: Int, cssViewH: Int): Int {
    val minCut = actualY + (cssViewH * MIN_SEAM_PROGRESS_RATIO).toInt()
    val maxCut = actualY + (cssViewH * MAX_SEAM_PROGRESS_RATIO).toInt()
    // Trả về ranh giới result cuối cùng trong vùng cắt an toàn (safe cut zone).
    val js = """
        (function(minCut, maxCut) {
            var cuts = [];
            function add(y) {
                y = Math.round(y);
                if (y >= minCut && y <= maxCut) cuts.push(y);
            }
            function visibleBlock(el) {
                var r = el.getBoundingClientRect();
                if (r.width < 120 || r.height < 24) return null;
                if (r.bottom <= 0 || r.top >= window.innerHeight) return null;
                return r;
            }
            var selectors = [
                '#rso > div',
                '#rso .MjjYud',
                '#rso div.g',
                '#search .MjjYud',
                '#search div[data-sokoban-container] > div'
            ];
            selectors.forEach(function(sel) {
                Array.prototype.forEach.call(document.querySelectorAll(sel), function(el) {
                    try {
                        var r = visibleBlock(el);
                        if (!r) return;
                        add(window.pageYOffset + r.top);
                        add(window.pageYOffset + r.bottom);
                    } catch(e) {}
                });
            });
            cuts = cuts.filter(function(v, i, a) { return a.indexOf(v) === i; })
                       .sort(function(a, b) { return a - b; });
            return cuts.length ? cuts[cuts.length - 1] : 0;
        })($minCut, $maxCut)
    """.trimIndent()
    return suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(js) { r ->
            cont.resume(r?.trim()?.toFloatOrNull()?.toInt() ?: 0)
        }
    }
}

private suspend fun setCaptureOverlaysHidden(webView: WebView, hide: Boolean) {
    // Chỉ ẩn overlay fixed/sticky; result block bình thường vẫn giữ nguyên.
    val js = if (hide) {
        """
        (function() {
            var count = 0;
            var maxTop = Math.min(260, window.innerHeight * 0.35);
            Array.prototype.forEach.call(document.querySelectorAll('body *'), function(el) {
                try {
                    var st = window.getComputedStyle(el);
                    if (st.position !== 'fixed' && st.position !== 'sticky') return;
                    var r = el.getBoundingClientRect();
                    if (r.width < 40 || r.height < 8 || r.height > window.innerHeight * 0.45) return;
                    if (r.top > maxTop && r.bottom < window.innerHeight - 80) return;
                    if (!el.hasAttribute('data-topsearch-old-visibility')) {
                        el.setAttribute('data-topsearch-old-visibility', el.style.visibility || '');
                    }
                    el.style.visibility = 'hidden';
                    count++;
                } catch(e) {}
            });
            return count;
        })()
        """.trimIndent()
    } else {
        """
        (function() {
            var count = 0;
            Array.prototype.forEach.call(document.querySelectorAll('[data-topsearch-old-visibility]'), function(el) {
                var old = el.getAttribute('data-topsearch-old-visibility') || '';
                el.style.visibility = old;
                el.removeAttribute('data-topsearch-old-visibility');
                count++;
            });
            return count;
        })()
        """.trimIndent()
    }
    suspendCancellableCoroutine<Unit> { cont ->
        webView.evaluateJavascript(js) { r ->
            Log.d(TAG, "capture overlay hide=$hide affected=$r")
            cont.resume(Unit)
        }
    }
}

private suspend fun copyWebViewToBitmap(
    webView: WebView,
    window: android.view.Window?,
    srcRect: Rect,
    target: Bitmap,
    tileIndex: Int,
): Boolean {
    if (window == null) {
        target.eraseColor(android.graphics.Color.WHITE)
        webView.draw(Canvas(target))
        return true
    }

    // PixelCopy có thể fail khi Chromium đang repaint; retry ngắn trước khi bỏ.
    repeat(PIXEL_COPY_RETRIES) { attempt ->
        val result = suspendCancellableCoroutine<Int> { cont ->
            PixelCopy.request(window, srcRect, target, { cont.resume(it) }, Handler(Looper.getMainLooper()))
        }
        if (result == PixelCopy.SUCCESS) return true
        Log.w(TAG, "PixelCopy failed result=$result tile=$tileIndex attempt=${attempt + 1}/$PIXEL_COPY_RETRIES")
        delay(120L * (attempt + 1))
    }
    return false
}

private suspend fun captureWebViewTilesOld(webView: WebView, dir: File?): List<String> {
    val w       = webView.width.takeIf { it > 0 } ?: 1080
    val viewHPx = webView.height.takeIf { it > 0 } ?: 1920
    val ts      = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val base    = dir ?: File("/sdcard")

    val window = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        (webView.context as? Activity)?.window
    } else null
    val loc     = IntArray(2).also { webView.getLocationOnScreen(it) }
    val srcRect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + viewHPx)

    webView.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)
    delay(400)

    // Lấy cả viewport height lẫn tổng chiều cao trang (CSS px) trong 1 lần gọi
    val (cssViewH, cssPageH) = suspendCancellableCoroutine<Pair<Int, Int>> { cont ->
        webView.evaluateJavascript("[window.innerHeight,document.documentElement.scrollHeight]") { r ->
            try {
                val arr = JSONArray(r?.trim() ?: "[]")
                cont.resume(Pair(arr.optInt(0, viewHPx / 3), arr.optInt(1, viewHPx / 3)))
            } catch (e: Exception) {
                cont.resume(Pair(viewHPx / 3, viewHPx / 3))
            }
        }
    }

    // dpr = tỷ lệ pixel vật lý / CSS pixel
    val dpr        = if (cssViewH > 0) viewHPx.toFloat() / cssViewH else 1f
    val totalPhysH = (cssPageH * dpr).toInt().coerceIn(1, 16_000)
    Log.d(TAG, "captureFullPage: viewHPx=$viewHPx cssViewH=$cssViewH cssPageH=$cssPageH dpr=$dpr totalPhysH=$totalPhysH")

    val full    = Bitmap.createBitmap(w, totalPhysH, Bitmap.Config.RGB_565)
    val canvas  = Canvas(full)
    val tileBmp = Bitmap.createBitmap(w, viewHPx, Bitmap.Config.RGB_565)

    var offsetCss   = 0
    var prevActualY = -1
    var idx         = 0

    while (idx < 20) {
        webView.evaluateJavascript("window.scrollTo({top:$offsetCss,behavior:'instant'});", null)
        delay(400)

        val actualY = suspendCancellableCoroutine<Int> { cont ->
            webView.evaluateJavascript("window.pageYOffset") { r ->
                cont.resume(r?.trim()?.toFloatOrNull()?.toInt() ?: offsetCss)
            }
        }
        // Browser đã clamp về vị trí cũ → đã chụp hết trang
        if (actualY == prevActualY) break

        if (window != null) {
            suspendCancellableCoroutine<Unit> { cont ->
                PixelCopy.request(window, srcRect, tileBmp, { result ->
                    if (result != PixelCopy.SUCCESS) Log.w(TAG, "PixelCopy failed result=$result idx=$idx")
                    cont.resume(Unit)
                }, Handler(Looper.getMainLooper()))
            }
        } else {
            tileBmp.eraseColor(android.graphics.Color.WHITE)
            webView.draw(Canvas(tileBmp))
        }

        // Vẽ tile tại đúng vị trí pixel tương ứng actualY — không dùng i*viewHPx
        // Nhờ vậy tile cuối (bị browser clamp) sẽ đặt đúng chỗ, không bị lặp/hở
        val drawTop = (actualY * dpr).toInt()
        val drawH   = viewHPx.coerceAtMost(totalPhysH - drawTop)
        if (drawTop < totalPhysH && drawH > 0) {
            canvas.drawBitmap(tileBmp, Rect(0, 0, w, drawH), Rect(0, drawTop, w, drawTop + drawH), null)
        }

        prevActualY = actualY
        offsetCss  += cssViewH
        idx++
    }

    tileBmp.recycle()
    webView.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)

    if (idx == 0) { full.recycle(); return emptyList() }

    val file = File(base, "topsearch_${ts}_full.jpg")
    FileOutputStream(file).use { full.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    full.recycle()

    Log.d(TAG, "Full page saved: ${file.name} ($idx tiles dpr=$dpr totalPhysH=$totalPhysH)")
    return listOf(file.absolutePath)
}
