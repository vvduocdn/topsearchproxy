package com.topsearch.app

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executors

private const val TAG = "ProxyHelper"

/** Thông tin proxy đã parse */
data class ProxyInfo(
    val host: String,
    val port: Int,
    val user: String = "",
    val pass: String = "",
) {
    val requiresAuth: Boolean get() = user.isNotBlank()
    val hostPort: String get() = "$host:$port"
}

object ProxyHelper {

    /**
     * Đặt proxy WebView toàn cục.
     * @param hostPortAuth  "host:port" hoặc "host:port:user:pass"
     * @param onResult      true nếu set thành công
     */
    fun setProxy(hostPortAuth: String, onResult: (Boolean) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "PROXY_OVERRIDE not supported on this device")
            onResult(false)
            return
        }
        val info = parse(hostPortAuth) ?: run {
            Log.w(TAG, "Invalid proxy format: $hostPortAuth")
            onResult(false)
            return
        }
        applyProxy(info, onResult)
    }

    /** Xoá proxy override sau khi capture xong */
    fun clearProxy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            resetProxy()
        }
    }

    /**
     * Parse "host:port" hoặc "host:port:user:pass".
     * Trả null nếu format sai.
     */
    fun parse(raw: String): ProxyInfo? {
        val s = raw.trim()
        if (s.isBlank()) return null

        // Tách phần host:port ra khỏi user:pass
        // Format: host:port[:user:pass]
        // host có thể là domain hoặc IPv4; IPv6 dạng [::1]:port không phổ biến với IPFoxy nên bỏ qua
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

    // ── Private ───────────────────────────────────────────────────────────────

    private fun applyProxy(info: ProxyInfo, onResult: (Boolean) -> Unit) {
        // Chromium chỉ nhận "host:port" — KHÔNG nhúng user:pass vào URL
        val config = ProxyConfig.Builder()
            .addProxyRule("${info.host}:${info.port}")
            .build()
        ProxyController.getInstance().setProxyOverride(
            config,
            Executors.newSingleThreadExecutor(),
        ) {
            Log.d(TAG, "Proxy set → ${info.host}:${info.port} (auth=${info.requiresAuth})")
            onResult(true)
        }
    }

    private fun resetProxy() {
        ProxyController.getInstance().clearProxyOverride(
            Executors.newSingleThreadExecutor(),
        ) {
            Log.d(TAG, "Proxy cleared")
        }
    }
}
