package com.topsearch.app

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topsearch.app.ui.VietnamCity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

class SearchViewModel(appContext: Application) : AndroidViewModel(appContext) {

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    fun startSearch(
        keyword: String,
        city:    VietnamCity = VietnamCity("🌐 Toàn quốc", ""),
    ) {
        val kw = keyword.trim().ifBlank { return }

        viewModelScope.launch {
            // GPS tắt — dùng tọa độ thành phố cho UULE (server-side)
            val uuleLat   = city.lat
            val uuleLng   = city.lng
            val cityLabel = city.label

            val encoded = URLEncoder.encode(kw, "UTF-8")
            val params  = buildString {
                append("q=$encoded")
                append("&udm=14")
                append("&num=20")
                append("&hl=vi")
                append("&gl=vn")
                if (uuleLat != 0.0 && uuleLng != 0.0) {
                    append("&uule=${buildUule(uuleLat, uuleLng)}")
                } else if (city.nearParam.isNotBlank()) {
                    append("&near=${URLEncoder.encode(city.nearParam, "UTF-8")}")
                }
            }

            _state.value = SearchState.WebCapturing(
                keyword   = kw,
                city      = cityLabel,
                url       = "https://www.google.com/search?$params",
                spoofLat  = 0.0,   // GPS tắt — không spoof geolocation
                spoofLng  = 0.0,
                proxyHost = city.proxyHostPort,
            )
        }
    }

    fun onWebCaptureDone(
        keyword: String,
        screenshotPath: String,
        jsResults: List<SearchResult>,
    ) {
        val city = (_state.value as? SearchState.WebCapturing)?.city ?: ""
        if (jsResults.isNotEmpty()) {
            _state.value = SearchState.Done(keyword, jsResults, screenshotPath, city)
            return
        }
        if (screenshotPath.isBlank()) {
            _state.value = SearchState.Error("Không lấy được kết quả", keyword)
            return
        }
        _state.value = SearchState.Analyzing(keyword)
        viewModelScope.launch {
            try {
                val results = OcrHelper.extractSearchResults(screenshotPath)
                _state.value = SearchState.Done(keyword, results, screenshotPath, city)
            } catch (e: Exception) {
                _state.value = SearchState.Error("OCR thất bại: ${e.message}", keyword)
            }
        }
    }

    fun onWebCaptureError(keyword: String, error: String) {
        _state.value = SearchState.Error(error, keyword)
    }

    fun reset() { _state.value = SearchState.Idle }

    companion object {
        const val COUNTDOWN_SEC = 5

        fun buildUule(lat: Double, lng: Double): String {
            val locStr   = "$lat,$lng"
            val locBytes = locStr.toByteArray(Charsets.UTF_8)
            val payload  = ByteArray(locBytes.size + 1)
            payload[0]   = locBytes.size.toByte()
            locBytes.copyInto(payload, destinationOffset = 1)
            val encoded  = Base64.encodeToString(payload, Base64.NO_WRAP)
            return URLEncoder.encode("w+CAIQICII$encoded", "UTF-8")
        }
    }
}
