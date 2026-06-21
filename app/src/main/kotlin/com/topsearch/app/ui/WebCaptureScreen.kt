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
private const val MAX_CAPTURE_TILES = 48
private const val MAX_CAPTURE_CHUNK_HEIGHT_PX = 12_000
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

/** Dump trang để biết Google đang render cái gì */
private val DEBUG_JS = """
(function() {
    // Tìm tất cả <a> dẫn ra ngoài Google — đây là link kết quả
    var links = document.querySelectorAll('a[href^="http"]');
    var extLinks = [];
    for (var i = 0; i < links.length && extLinks.length < 5; i++) {
        var a = links[i];
        if (a.href.indexOf('google.com') >= 0) continue;
        if (a.href.indexOf('googleapis.com') >= 0) continue;
        var txt = (a.innerText || a.textContent || '').trim().substring(0, 60);
        if (!txt || txt.length < 3) continue;
        // Lấy class của a và parent gần nhất
        var p = a.parentElement;
        extLinks.push({
            href:     a.href.substring(0, 60),
            text:     txt,
            aClass:   a.className.substring(0, 40),
            pTag:     p ? p.tagName : '',
            pClass:   p ? p.className.substring(0, 40) : ''
        });
    }
    // Tìm thẻ có role="heading" (Google dùng thay h3)
    var headings = document.querySelectorAll('[role="heading"]');
    var hdump = [];
    for (var j = 0; j < Math.min(headings.length, 3); j++) {
        hdump.push({
            tag:   headings[j].tagName,
            cls:   headings[j].className.substring(0, 40),
            text:  (headings[j].innerText||'').substring(0, 50)
        });
    }
    return JSON.stringify({
        bodyLen:   document.body ? document.body.innerHTML.length : 0,
        h3:        document.querySelectorAll('h3').length,
        roleHead:  headings.length,
        cite:      document.querySelectorAll('cite').length,
        extLinks:  extLinks,
        headings:  hdump
    });
})()
""".trimIndent()

/** JS poll — chờ đến khi Google render xong ít nhất 1 kết quả */
private val WAIT_READY_JS = """
(function() {
    var h3 = document.querySelectorAll('h3');
    var g  = document.querySelectorAll('div.g, .yuRUbf, .LC20lb');
    return (h3.length + g.length);
})()
""".trimIndent()

/**
 * Trích xuất KẾT QUẢ ORGANIC từ Google Search — không tính ads, PAA, Knowledge Panel.
 *
 * Phương pháp chính: tìm h3 trong #rso, bỏ qua các section không phải organic.
 * PAA / KP / Ads đều bị loại qua exclusion list.
 * h3 trong PAA thường không có external link → tự bị lọc ra.
 *
 * Fallback: a.UBFage nếu h3 tìm được < 5 kết quả.
 */
private val EXTRACT_JS = """
(function() {
    try {
        var out  = [];
        var seen = {};

        var EXCLUDE = [
            '#tads', '#tadsb',
            '[data-text-ad]', '.uEierd', '.pla-unit',
            '.related-question-pair',
            '.kp-wholepage', '.osrp-blk', '.I6TXqe',
            '[aria-label="Ads"]'
        ];

        function isExcluded(el) {
            if (!el || !el.closest) return false;
            for (var i = 0; i < EXCLUDE.length; i++) {
                if (el.closest(EXCLUDE[i])) return true;
            }
            return false;
        }

        function isAdLink(el) {
            var href = el.href || '';
            if (href.indexOf('/aclk?') >= 0 || href.indexOf('googleadservices') >= 0) return true;
            var block = el.closest ? (el.closest('[data-hveid]') || el.parentElement) : el.parentElement;
            if (block) {
                var txt = (block.innerText || block.textContent || '').toLowerCase();
                var ads = ['quảng cáo', 'sponsored', 'được tài trợ'];
                for (var n = 0; n < ads.length; n++) {
                    if (txt.indexOf(ads[n]) >= 0) return true;
                }
            }
            return false;
        }

        function getDomain(href) {
            try { return new URL(href).hostname.replace(/^www\./, ''); } catch(e) { return ''; }
        }

        function resolveHref(href) {
            try {
                var url = new URL(href);
                if (url.hostname.indexOf('google.') >= 0) {
                    var q = url.searchParams.get('q') || url.searchParams.get('url');
                    if (q && q.indexOf('http') === 0) return q;
                }
                return href;
            } catch(e) { return href; }
        }

        function tryAdd(title, aTag) {
            if (!title || title.length < 3 || title.length > 200) return false;
            if (!aTag) return false;
            var href = resolveHref(aTag.href || '');
            if (!href || href.indexOf('http') !== 0) return false;
            if (isAdLink(aTag) || isExcluded(aTag)) return false;
            if (seen[href]) return false;
            var d = getDomain(href);
            if (!d || d.indexOf('google.') >= 0) return false;
            seen[href] = true;
            out.push({ t: title, d: d, u: href, ad: false });
            return true;
        }

        var searchRoot = document.querySelector('#rso, #search') || document.body;

        // ── Strategy 1: h3 trong search results, loại exclusion section ──────
        var h3s = searchRoot.querySelectorAll('h3');
        for (var i = 0; i < h3s.length && out.length < 20; i++) {
            var h3 = h3s[i];
            if (isExcluded(h3)) continue;

            var title = (h3.innerText || h3.textContent || '').trim();
            if (!title || title.length < 3 || title.length > 200) continue;

            // Tìm link: trong h3, ancestor <a>, hoặc leo lên tối đa 6 cấp tìm con cháu
            var aTag = h3.querySelector('a[href]') || h3.closest('a[href]');
            if (!aTag) {
                var par = h3.parentElement;
                for (var p = 0; p < 6 && par; p++) {
                    var c = par.querySelector('a[href^="http"]');
                    if (c && !isExcluded(c)) { aTag = c; break; }
                    par = par.parentElement;
                }
            }
            tryAdd(title, aTag);
        }

        // ── Strategy 2: a.UBFage (fallback nếu h3 tìm được < 5) ─────────────
        if (out.length < 5) {
            var links = document.querySelectorAll('a.UBFage');
            for (var j = 0; j < links.length && out.length < 20; j++) {
                var a = links[j];
                if (isExcluded(a) || isAdLink(a)) continue;
                var text = (a.innerText || a.textContent || '').trim();
                if (!text) continue;
                var lines = text.split('\n')
                                .map(function(l) { return l.trim(); })
                                .filter(function(l) { return l.length > 0; });
                if (lines.length < 2) continue;
                var t2 = lines.length >= 3
                    ? lines.slice(2).join(' ').trim()
                    : lines[lines.length - 1].trim();
                tryAdd(t2, a);
            }
        }

        return JSON.stringify(out);
    } catch(e) {
        return JSON.stringify([{ t: 'ERROR:' + e.message, d: '', u: '', ad: false }]);
    }
})()
""".trimIndent()

/** JS lấy vị trí Google đang phục vụ kết quả — trả raw text, không filter cứng */
private val LOCATION_JS = """
(function() {
    try {
        function clean(text) {
            // Lấy phần trước dấu · hoặc - để bỏ "· Cập nhật vị trí"
            return (text || '').split(/[·\-–|]/)[0].trim();
        }

        // 1. Location chip Google hiển thị đầu trang (ưu tiên nhất — chính xác nhất)
        var chip = document.querySelector('g-location-chip, .tpmBl, .kPDuDb');
        if (chip) {
            var t1 = clean(chip.innerText || chip.textContent || '');
            if (t1.length > 1) return t1;
        }

        // 2. Footer — thường chứa "Hà Nội · Cập nhật vị trí"
        var foot = document.querySelector('#foot, #fbar, [id="foot"]');
        if (foot) {
            var t2 = clean(foot.innerText || foot.textContent || '');
            if (t2.length > 1 && t2.length < 60) return t2;
        }

        // 3. Local pack header "Kết quả tìm kiếm gần ..."
        var localHeader = document.querySelector('.yp1CPe, [data-attrid="location"]');
        if (localHeader) {
            var t3 = clean(localHeader.innerText || '');
            if (t3.length > 1) return t3;
        }

        return '';
    } catch(e) { return ''; }
})()
""".trimIndent()

// ── Composable ────────────────────────────────────────────────────────────────

@Composable
fun WebCaptureScreen(
    url:       String,
    keyword:   String = "",
    proxyHost: String = "",   // "host:port" — blank = trực tiếp (không qua proxy)
    spoofLat:  Double = 0.0,
    spoofLng:  Double = 0.0,
    onCaptureDone: (screenshotPaths: List<String>, jsResults: List<SearchResult>, detectedCity: String) -> Unit,
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
        wv.evaluateJavascript(buildCaptchaSubmitJs(token)) { r ->
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
                wv.evaluateJavascript(WAIT_READY_JS) { r ->
                    cont.resume(r?.trim()?.toIntOrNull() ?: 0)
                }
            }
            Log.d(TAG, "Poll $it → element count = $count")
            if (count > 0) { ready = true } else { delay(400) }
        }

        // ── Bước 2: Debug dump để biết DOM thực tế ────────────────────────
        val debugStr = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(DEBUG_JS) { r -> cont.resume(r ?: "null") }
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

        // ── Bước 4: Chạy JS lấy kết quả (trước khi scroll về top để giữ DOM đầy đủ) ──
        val jsonStr = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(EXTRACT_JS) { r -> cont.resume(r ?: "[]") }
        }
        Log.d(TAG, "RAW JS → $jsonStr")
        val jsResults = parseJsResults(jsonStr)
        Log.d(TAG, "PARSED ${jsResults.size} results")

        // ── Bước 4b: Detect city từ DOM Google ───────────────────────────
        val rawCity = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(LOCATION_JS) { r ->
                cont.resume((r ?: "").trim().removeSurrounding("\""))
            }
        }
        Log.d(TAG, "DETECTED CITY → $rawCity")

        // Scroll về đầu để chụp từ top
        wv.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)
        delay(300)

        // ── Bước 5: Chụp ảnh ─────────────────────────────────────────────
        try {
            val paths = captureWebViewTiles(wv, context.getExternalFilesDir(null))
            Log.d(TAG, "Captured ${paths.size} tile(s)")
            onCaptureDone(paths, jsResults, rawCity)
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            onCaptureDone(emptyList(), jsResults, rawCity)
        }
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

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            Log.d(TAG, "onPageStarted url=$url")
                            // Inject TRƯỚC khi trang render để Google thấy vị trí giả
                            if (spoofLat != 0.0 && spoofLng != 0.0) {
                                view.evaluateJavascript(buildSpoofLocationJs(spoofLat, spoofLng), null)
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            Log.d(TAG, "onPageFinished url=$url done=$done")
                            if (spoofLat != 0.0 && spoofLng != 0.0) {
                                view.evaluateJavascript(buildSpoofLocationJs(spoofLat, spoofLng), null)
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
                                Log.d(TAG, "Phase 1 → homepage detected, inject keyword='$keyword'")
                                view.evaluateJavascript(buildSearchJs(keyword)) { result ->
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
                            if (localProxy != null && !proxyFallback) {
                                // Proxy có auth nhưng fail → báo lỗi rõ, không fallback về IP thật
                                // (fallback sẽ cho kết quả sai tỉnh vì IP máy ở HCM)
                                proxyFallback = true
                                Log.w(TAG, "Proxy failed (${err.description}) — không fallback để tránh kết quả sai tỉnh")
                                ProxyHelper.clearProxy()
                                onError("Proxy lỗi — thử lại để đổi proxy khác")
                            } else {
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

/**
 * JS override navigator.geolocation — Google client sẽ thấy vị trí giả này.
 * Inject cả onPageStarted lẫn onPageFinished để cover Google SPA re-render.
 */
private fun buildSearchJs(keyword: String): String {
    val escaped = keyword.replace("\\", "\\\\").replace("'", "\\'")
    return """
    (function() {
        var q = document.querySelector('textarea[name="q"]') || document.querySelector('input[name="q"]');
        if (!q) return false;
        q.value = '$escaped';
        var form = q.form || q.closest('form');
        if (form) { form.submit(); return true; }
        return false;
    })()
    """.trimIndent()
}

private fun buildCaptchaSubmitJs(token: String): String {
    val escaped = token.replace("'", "\\'")
    return """
    (function(token) {
        // 1. Điền vào textarea response
        document.querySelectorAll('textarea[name="g-recaptcha-response"]').forEach(function(el) {
            el.value = token;
        });
        var r = document.getElementById('g-recaptcha-response');
        if (r) r.value = token;

        // 2. Gọi data-callback trực tiếp (Google sorry dùng "cr")
        var widget = document.querySelector('[data-callback]');
        if (widget) {
            var fnName = widget.getAttribute('data-callback');
            if (fnName && typeof window[fnName] === 'function') {
                window[fnName](token);
                return 'called:' + fnName;
            }
        }

        // 3. Fallback: walk ___grecaptcha_cfg
        try {
            var cfg = window.___grecaptcha_cfg;
            if (cfg && cfg.clients) {
                (function walk(obj, depth) {
                    if (!obj || typeof obj !== 'object' || depth > 6) return;
                    if (typeof obj.callback === 'function') { obj.callback(token); return; }
                    Object.keys(obj).forEach(function(k) { walk(obj[k], depth + 1); });
                })(cfg.clients, 0);
            }
        } catch(e) {}

        // 4. Submit form
        var form = document.querySelector('form#captcha-form') || document.querySelector('form');
        if (form) {
            setTimeout(function() { form.submit(); }, 300);
            return 'submitted';
        }
        return 'no-form';
    })('$escaped')
    """.trimIndent()
}

private fun buildSpoofLocationJs(lat: Double, lng: Double) = """
(function() {
    var _lat = $lat, _lng = $lng;
    var fakePos = {
        coords: {
            latitude:         _lat,
            longitude:        _lng,
            accuracy:         10,
            altitude:         null,
            altitudeAccuracy: null,
            heading:          null,
            speed:            null
        },
        timestamp: Date.now()
    };
    navigator.geolocation.getCurrentPosition = function(success, error, opts) {
        setTimeout(function() { success(fakePos); }, 0);
    };
    navigator.geolocation.watchPosition = function(success, error, opts) {
        setTimeout(function() { success(fakePos); }, 0);
        return 1;
    };
    navigator.geolocation.clearWatch = function() {};
})();
""".trimIndent()

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
            if (isAd || title.isBlank()) return@mapNotNull null
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

/**
 * Scroll từ top → footer, chụp từng viewport bằng PixelCopy rồi ghép thành 1 ảnh full page.
 * Dùng window.innerHeight (CSS px) làm bước scroll — KHÔNG dùng webView.height (physical px).
 */
private suspend fun captureWebViewTiles(webView: WebView, dir: File?): List<String> {
    val w       = webView.width.takeIf { it > 0 } ?: 1080
    val viewHPx = webView.height.takeIf { it > 0 } ?: 1920
    val ts      = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
    fun saveChunkIfNeeded(force: Boolean = false) {
        if (!chunkHasPixels && !force) return
        val suffix = if (chunkIndex == 1 && totalPhysH <= MAX_CAPTURE_CHUNK_HEIGHT_PX) {
            "full"
        } else {
            "part_%02d".format(chunkIndex)
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

        // Tin vị trí scroll thật của browser (actualY), không tin offset đã yêu cầu.
        val actualY = readActualScrollY(webView, offsetCss)
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
            return emptyList()
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
    saveChunkIfNeeded(force = paths.isEmpty())
    chunkBitmap.recycle()
    setCaptureOverlaysHidden(webView, hide = false)
    webView.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)

    Log.d(TAG, "Full page saved as ${paths.size} file(s), tiles=$idx dpr=$dpr totalPhysH=$totalPhysH capturedBottom=$capturedBottom overlapCss=$overlapCss")
    return paths
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
