package com.topsearch.app

enum class CheckStatus { PENDING, IN_PROGRESS, DONE, ERROR }

data class KeywordBatchItem(
    val requestId:  String,
    val keyword:    String,
    val status:     CheckStatus = CheckStatus.PENDING,
    val retryCount: Int         = 0,
)

data class SearchResult(
    val rank:   Int,
    val title:  String,
    val domain: String  = "",
    val url:    String  = "",
    val isAd:   Boolean = false,   // true = quảng cáo Google Ads
)

sealed class SearchState {

    data object Idle : SearchState()

    /** WebView đang load Google Search trong app */
    data class WebCapturing(
        val keyword:   String,
        val url:       String,
        val city:      String = "",
        val spoofLat:  Double = 0.0,
        val spoofLng:  Double = 0.0,
        val proxyHost: String = "",
        val proxyIp:   String = "",
        val country:   Int    = 1,
    ) : SearchState()

    /** JS / OCR đang phân tích */
    data class Analyzing(val keyword: String) : SearchState()

    data class Done(
        val keyword:        String,
        val results:        List<SearchResult>,
        val screenshotPath: String,
        val city:           String = "",
        val proxyIp:        String = "",
        val socketInfo:     String = "",
    ) : SearchState()

    data class Error(
        val message: String,
        val keyword: String = "",
    ) : SearchState()
}
