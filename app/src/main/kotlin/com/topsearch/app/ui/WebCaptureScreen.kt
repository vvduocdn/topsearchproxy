package com.topsearch.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.webkit.CookieManager
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
 * Từ debug thực tế trên Samsung S22:
 *   - Link kết quả organic: a.UBFage
 *   - Link quảng cáo: href chứa "/aclk?" hoặc nằm trong #tads / [data-text-ad]
 *   - innerText của a.UBFage: dòng 0=domain, dòng 1=url, dòng 2+=title
 *
 * isAd detection:
 *   1. href chứa "/aclk?" → Google Ads click tracking URL
 *   2. ancestor là #tads, [data-text-ad], .uEierd, .pla-unit
 *   3. Có element "Quảng cáo" / "Ad" / "Sponsored" gần đó
 */
private val EXTRACT_JS = """
(function() {
    try {
        var out        = [];
        var seen       = {};
        var seenDomain = {};

        function isAdElement(el) {
            // Cách 1: href là Google Ads click URL (/aclk?)
            var href = el.href || '';
            if (href.indexOf('/aclk?') >= 0 || href.indexOf('googleadservices') >= 0) return true;

            // Cách 2: nằm trong container quảng cáo
            var adSelectors = ['#tads','#tadsb','[data-text-ad]','.uEierd','.pla-unit','[aria-label="Ads"]'];
            for (var i = 0; i < adSelectors.length; i++) {
                if (el.closest && el.closest(adSelectors[i])) return true;
            }

            // Cách 3: favicon là quả cầu (globe) — Google dùng cho ads khi không có favicon thật
            // Globe icon: img src chứa "globe" hoặc là svg có viewBox="0 0 24 24" với path đặc trưng
            var block = el.closest ? (el.closest('[data-hveid]') || el.closest('[data-ved]') || el.parentElement) : el.parentElement;
            if (block) {
                var imgs = block.querySelectorAll('img');
                for (var k = 0; k < imgs.length; k++) {
                    var src = imgs[k].src || imgs[k].getAttribute('src') || '';
                    // Globe icon Google dùng cho ads: gds-vector-globe hoặc encrypted-tbn
                    if (src.indexOf('globe') >= 0 || src.indexOf('gds-vector') >= 0) return true;
                    // Generic favicon placeholder (1x1 pixel base64)
                    if (src.indexOf('1x1') >= 0 || src === '') {
                        // Nếu không có favicon thật → khả năng là ads
                    }
                }
                // SVG globe (Google render bằng SVG inline)
                var svgs = block.querySelectorAll('svg');
                for (var m = 0; m < svgs.length; m++) {
                    var svgClass = svgs[m].className || '';
                    if (typeof svgClass === 'object') svgClass = svgClass.baseVal || '';
                    if (svgClass.indexOf('globe') >= 0 || svgClass.indexOf('XNo5Ab') >= 0) return true;
                }

                // Cách 4: text "Quảng cáo" / "Sponsored" trong block
                var blockText = (block.innerText || block.textContent || '').toLowerCase();
                var adLabels  = ['quảng cáo', 'sponsored', '·ad·', '· ad ·', 'được tài trợ'];
                for (var n = 0; n < adLabels.length; n++) {
                    if (blockText.indexOf(adLabels[n]) >= 0) return true;
                }
            }
            return false;
        }

        // ── a.UBFage = link kết quả chính (organic + ads đều dùng) ──────
        var links = document.querySelectorAll('a.UBFage');

        for (var i = 0; i < links.length && out.length < 15; i++) {
            var a    = links[i];
            var text = (a.innerText || a.textContent || '').trim();
            if (!text) continue;

            var lines = text.split('\n')
                            .map(function(l) { return l.trim(); })
                            .filter(function(l) { return l.length > 0; });
            if (lines.length < 2) continue;

            var title = lines.length >= 3
                ? lines.slice(2).join(' ').trim()
                : lines[lines.length - 1].trim();
            if (!title || title.length < 3 || seen[title]) continue;

            var domain = '';
            var href   = a.href || '';
            // Ads dùng /aclk? redirect → lấy domain từ display text (dòng 0)
            if (href.indexOf('/aclk?') >= 0 || href.indexOf('googleadservices') >= 0) {
                domain = lines[0].replace(/^https?:\/\//, '').replace(/^www\./, '').split('/')[0].trim();
            } else {
                try { domain = new URL(href).hostname.replace(/^www\./, ''); } catch(e) {}
            }

            if (domain && seenDomain[domain]) continue;

            seen[title] = true;
            if (domain) seenDomain[domain] = true;

            out.push({ t: title, d: domain, u: href, ad: isAdElement(a) });
        }

        // ── Fallback: div[role="heading"] nếu không tìm được qua UBFage ─
        if (out.length === 0) {
            var headings = document.querySelectorAll('div.F0FGWb, [role="heading"]');
            for (var k = 0; k < headings.length && out.length < 15; k++) {
                var hd = headings[k];
                var t2 = (hd.innerText || hd.textContent || '').trim();
                if (!t2 || t2.length < 3 || t2.length > 120 || seen[t2]) continue;
                seen[t2] = true;
                var blk  = hd.closest ? (hd.closest('[data-hveid]') || hd.parentElement) : hd.parentElement;
                var aTag = blk ? blk.querySelector('a.UBFage, a[href^="http"]') : null;
                var d2   = '', u2 = '';
                if (aTag) {
                    u2 = aTag.href;
                    try { d2 = new URL(u2).hostname.replace(/^www\./, ''); } catch(e) {}
                }
                if (d2) out.push({ t: t2, d: d2, u: u2, ad: aTag ? isAdElement(aTag) : false });
            }
        }

        return JSON.stringify(out);
    } catch (e) {
        return JSON.stringify([{ t: 'ERROR:' + e.message, d: '', u: '', ad: false }]);
    }
})()
""".trimIndent()

// ── Composable ────────────────────────────────────────────────────────────────

@Composable
fun WebCaptureScreen(
    url:       String,
    proxyHost: String = "",   // "host:port" — blank = trực tiếp (không qua proxy)
    spoofLat:  Double = 0.0,
    spoofLng:  Double = 0.0,
    onCaptureDone: (screenshotPath: String, jsResults: List<SearchResult>) -> Unit,
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

        // Xóa cookie Google trước mỗi search — tránh cookie PREF/location cũ
        // từ tỉnh trước đè lên proxy IP mới (HN cookie → HCM proxy vẫn ra HN)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

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
        wv.loadUrl(url)
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
            val scrollY = (COUNTDOWN_SEC - i) * 1500
            wv.evaluateJavascript("window.scrollTo({top:$scrollY,behavior:'instant'});", null)
            statusText = when {
                i > 4 -> "Đang tải thêm kết quả…"
                i > 2 -> "Gần xong…"
                else  -> "Chuẩn bị phân tích…"
            }
            delay(1_000)
        }

        capturing = true

        // Scroll về đầu để chụp từ top
        wv.evaluateJavascript("window.scrollTo({top:0,behavior:'instant'});", null)
        delay(300)

        // ── Bước 4: Chạy JS lấy kết quả ─────────────────────────────────
        val jsonStr = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(EXTRACT_JS) { r -> cont.resume(r ?: "[]") }
        }
        Log.d(TAG, "RAW JS → $jsonStr")
        val jsResults = parseJsResults(jsonStr)
        Log.d(TAG, "PARSED ${jsResults.size} results")

        // ── Bước 5: Chụp ảnh ─────────────────────────────────────────────
        try {
            val path = captureWebView(wv, context.getExternalFilesDir(null))
            onCaptureDone(path, jsResults)
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            onCaptureDone("", jsResults)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    WebView.setWebContentsDebuggingEnabled(true)
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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
                            // Inject TRƯỚC khi trang render để Google thấy vị trí giả
                            if (spoofLat != 0.0 && spoofLng != 0.0) {
                                view.evaluateJavascript(buildSpoofLocationJs(spoofLat, spoofLng), null)
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // Inject lại sau khi page load (Google SPA có thể reset)
                            if (spoofLat != 0.0 && spoofLng != 0.0) {
                                view.evaluateJavascript(buildSpoofLocationJs(spoofLat, spoofLng), null)
                            }
                            if (!done) { done = true; pageLoaded = true }
                        }

                        override fun onReceivedError(
                            view: WebView, req: WebResourceRequest, err: WebResourceError,
                        ) {
                            if (!req.isForMainFrame) return
                            // Proxy có auth nhưng fail (session hết hạn / sai credentials)
                            // → clear proxy, reload với UULE-only (không cần proxy)
                            if (localProxy != null && !proxyFallback) {
                                proxyFallback = true
                                Log.w(TAG, "Proxy failed (${err.description}), fallback UULE-only")
                                statusText = "Proxy lỗi, dùng UULE…"
                                ProxyHelper.clearProxy()
                                done = false  // cho phép onPageFinished trigger lại
                                view.loadUrl(url)
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
            Box(
                modifier         = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (pageLoaded) 0.50f else 0.70f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!pageLoaded) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Đang tải kết quả tìm kiếm…",
                            color = Color.White, fontSize = 16.sp,
                            textAlign = TextAlign.Center)
                    }
                } else {
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
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.70f))
            .padding(horizontal = 36.dp, vertical = 28.dp),
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
                modifier         = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f)),
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
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            SearchResult(
                rank   = i + 1,
                title  = obj.optString("t", "").trim(),
                domain = obj.optString("d", "").trim(),
                isAd   = obj.optBoolean("ad", false),
            )
        }.filter { it.title.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun captureWebView(webView: WebView, dir: File?): String {
    val viewW    = webView.width.takeIf  { it > 0 } ?: 1080
    val viewH    = webView.height.takeIf { it > 0 } ?: 1920
    @Suppress("DEPRECATION")
    val scale    = webView.scale.takeIf { it > 0f } ?: 1f
    val fullH    = (webView.contentHeight * scale).toInt().coerceAtLeast(viewH)
    val captureH = minOf(fullH, viewH * 5)

    val bmp    = Bitmap.createBitmap(viewW, captureH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    webView.draw(canvas)

    val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val file = File(dir ?: File("/sdcard"), "screenshot_$ts.png")
    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    bmp.recycle()
    return file.absolutePath
}
