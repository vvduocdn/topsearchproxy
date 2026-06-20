package com.topsearch.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton bus between SearchService (WebSocket) and SearchViewModel (UI).
 *
 * Flow:
 *  1. SearchService receives CheckKeyword → registers callback → emits to [incoming]
 *  2. SearchViewModel collects [incoming] → starts search via WebView
 *  3. SearchViewModel calls [dispatchResult] when done → callback fires → Service submits
 */
object SearchBridge {

    data class SocketRequest(
        val requestId: String,
        val keyword:   String,
        val proxy:     String,
        val country:   Int,
    )

    /** Trạng thái kết nối WebSocket — Service cập nhật, UI observe */
    val isConnected = MutableStateFlow(false)

    /** Service → ViewModel: new keyword to search */
    val incoming = MutableSharedFlow<SocketRequest>(extraBufferCapacity = 64)

    /** ViewModel → Service: search results ready (screenshotPaths empty if capture failed) */
    fun dispatchResult(
        requestId:     String,
        results:       List<SearchResult>,
        screenshotPaths: List<String> = emptyList(),
        publicIp:      String         = "",
    ) {
        callbacks.remove(requestId)?.invoke(results, screenshotPaths, publicIp)
    }

    /** Called by Service before emitting [incoming] */
    fun registerCallback(requestId: String, cb: (List<SearchResult>, List<String>, String) -> Unit) {
        callbacks[requestId] = cb
    }

    private val callbacks = ConcurrentHashMap<String, (List<SearchResult>, List<String>, String) -> Unit>()
}
