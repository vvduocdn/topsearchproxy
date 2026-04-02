package com.topsearch.app

data class SearchResult(
    val rank:   Int,
    val title:  String,
    val domain: String  = "",
    val isAd:   Boolean = false,   // true = quảng cáo Google Ads
)

sealed class SearchState {

    data object Idle : SearchState()

    /** WebView đang load Google Search trong app */
    data class WebCapturing(
        val keyword:   String,
        val url:       String,
        val city:      String = "",
        val spoofLat:  Double = 0.0,   // tọa độ spoof geolocation trong WebView
        val spoofLng:  Double = 0.0,
        val proxyHost: String = "",    // "host:port" — blank = không dùng proxy
    ) : SearchState()

    /** JS / OCR đang phân tích */
    data class Analyzing(val keyword: String) : SearchState()

    data class Done(
        val keyword: String,
        val results: List<SearchResult>,
        val screenshotPath: String,
        val city: String = "",
    ) : SearchState()

    data class Error(
        val message: String,
        val keyword: String = "",
    ) : SearchState()
}
