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
        val isTest:    Boolean = false,
    )

    data class SubmitFailure(
        val requestId: String,
        val message:   String,
    )

    data class SubmitSuccess(
        val requestId: String,
    )

    /** Trạng thái kết nối WebSocket — Service cập nhật, UI observe */
    val isConnected = MutableStateFlow(false)

    /** true when ViewModel is processing one queued keyword */
    val isProcessing = MutableStateFlow(false)

    /** Service → ViewModel: new keyword to search */
    val incoming = MutableSharedFlow<SocketRequest>(extraBufferCapacity = 64)

    /** Full batch notification — ViewModel collects to populate batch status UI */
    val incomingBatch = MutableSharedFlow<List<SocketRequest>>(extraBufferCapacity = 8)

    /** ViewModel → Service: re-queue pending keywords after app restart (crash recovery) */
    val resumeRequest = MutableSharedFlow<List<SocketRequest>>(extraBufferCapacity = 8)

    /** ViewModel → Service: manually inject a test keyword through the full socket flow */
    val testRequest = MutableSharedFlow<SocketRequest>(extraBufferCapacity = 8)

    /** Service → ViewModel: result is invalid for submit, mark keyword as failed */
    val submitFailure = MutableSharedFlow<SubmitFailure>(extraBufferCapacity = 16)

    /** Service → ViewModel: payload was sent, mark keyword as done */
    val submitSuccess = MutableSharedFlow<SubmitSuccess>(extraBufferCapacity = 16)

    /** ViewModel → Service: search results ready (screenshotPaths empty if capture failed) */
    fun dispatchResult(
        requestId:     String,
        results:       List<SearchResult>,
        screenshotPaths: List<String> = emptyList(),
        publicIp:      String         = "",
        totalCount:    Int            = results.size,
        checkedAt:     Long           = 0L,
    ) {
        android.util.Log.d("SearchBridge", "dispatchResult reqId=$requestId checkedAt=$checkedAt")
        callbacks.remove(requestId)?.invoke(results, screenshotPaths, publicIp, totalCount, checkedAt)
    }

    /** Called by Service before emitting [incoming] */
    fun registerCallback(requestId: String, cb: (List<SearchResult>, List<String>, String, Int, Long) -> Unit) {
        callbacks[requestId] = cb
    }

    fun dispatchSubmitFailure(requestId: String, message: String) {
        submitFailure.tryEmit(SubmitFailure(requestId, message))
    }

    fun dispatchSubmitSuccess(requestId: String) {
        submitSuccess.tryEmit(SubmitSuccess(requestId))
    }

    suspend fun emitSubmitFailure(requestId: String, message: String) {
        submitFailure.emit(SubmitFailure(requestId, message))
    }

    suspend fun emitSubmitSuccess(requestId: String) {
        submitSuccess.emit(SubmitSuccess(requestId))
    }

    private val callbacks = ConcurrentHashMap<String, (List<SearchResult>, List<String>, String, Int, Long) -> Unit>()
}
