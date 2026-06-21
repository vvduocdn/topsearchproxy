package com.topsearch.app

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topsearch.app.ui.VietnamCity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

class SearchViewModel(appContext: Application) : AndroidViewModel(appContext) {

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** true = bỏ qua proxy, search thẳng qua mạng điện thoại */
    private val _skipProxy = MutableStateFlow(false)
    val skipProxy: StateFlow<Boolean> = _skipProxy.asStateFlow()

    /** Trạng thái socket hiện tại — hiện trên SearchScreen */
    private val _socketInfo = MutableStateFlow("")
    val socketInfo: StateFlow<String> = _socketInfo.asStateFlow()

    /** Trạng thái kết nối WebSocket từ Service */
    val isConnected: StateFlow<Boolean> = SearchBridge.isConnected

    /** Danh sách keyword trong batch hiện tại kèm trạng thái xử lý */
    private val _keywordBatch = MutableStateFlow<List<KeywordBatchItem>>(emptyList())
    val keywordBatch: StateFlow<List<KeywordBatchItem>> = _keywordBatch.asStateFlow()

    /** true = hiện dialog hỏi user có muốn tiếp tục queue chưa xong sau khi app bị kill */
    private val _pendingQueuePrompt = MutableStateFlow(false)
    val pendingQueuePrompt: StateFlow<Boolean> = _pendingQueuePrompt.asStateFlow()

    fun setSkipProxy(skip: Boolean) { _skipProxy.value = skip }

    // requestId của CheckKeyword đang xử lý (null nếu search thủ công từ UI)
    private var socketRequestId: String? = null
    // Job auto-reset về Idle sau khi hiện kết quả socket
    private var socketDoneJob: Job? = null

    // Sequential queue — keywords from socket are processed one at a time
    private val requestQueue = Channel<SearchBridge.SocketRequest>(Channel.UNLIMITED)
    private var currentDone: CompletableDeferred<Unit>? = null
    private var pendingQueueCount = 0

    // Cache kết quả theo keyword+country — tránh search lại cùng keyword
    private val resultCache = mutableMapOf<String, List<SearchResult>>()
    private fun cacheKey(keyword: String, country: Int) = "${keyword.trim().lowercase()}_$country"

    // Lookup requestId → SocketRequest để auto-retry sau khi batch kết thúc
    private val batchRequests = mutableMapOf<String, SearchBridge.SocketRequest>()

    // Kết quả đã check theo requestId — user nhấn keyword row để xem lại
    private val _keywordResults = MutableStateFlow<Map<String, List<SearchResult>>>(emptyMap())
    val keywordResults: StateFlow<Map<String, List<SearchResult>>> = _keywordResults.asStateFlow()

    init {
        // Populate batch status list when server sends a new batch; persist to disk for crash recovery
        viewModelScope.launch {
            SearchBridge.incomingBatch.collect { requests ->
                _pendingQueuePrompt.value = false  // dismiss resume dialog if server sends fresh batch
                val newItems = requests.map { KeywordBatchItem(it.requestId, it.keyword) }
                _keywordBatch.value = newItems + _keywordBatch.value
                val ctx = getApplication<Application>()
                launch(Dispatchers.IO) {
                    val existing = KeywordQueueStore.load(ctx) ?: emptyList()
                    val newEntries = requests.map {
                        KeywordQueueStore.Entry(it.requestId, it.keyword, it.proxy, it.country)
                    }
                    KeywordQueueStore.save(ctx, newEntries + existing)
                }
                requests.forEach { batchRequests[it.requestId] = it }
            }
        }
        // Forward incoming socket requests to the sequential queue
        viewModelScope.launch {
            SearchBridge.incoming.collect { req ->
                pendingQueueCount++
                requestQueue.send(req)
                Log.d("TopSearch", "Enqueued '${req.keyword}' pending=$pendingQueueCount reqId=${req.requestId}")
            }
        }
        // Sequential processor — waits for each search to complete before starting the next
        viewModelScope.launch {
            for (req in requestQueue) {
                pendingQueueCount = (pendingQueueCount - 1).coerceAtLeast(0)
                processSocketRequest(req)
                // When queue drains, auto-retry any ERROR keywords (up to 3 times each)
                if (pendingQueueCount == 0 && requestQueue.isEmpty) {
                    retryErrorKeywords()
                }
            }
        }
    }

    private suspend fun processSocketRequest(req: SearchBridge.SocketRequest) {
        SearchBridge.isProcessing.value = true
        try {
            processSocketRequestInternal(req)
        } finally {
            SearchBridge.isProcessing.value = false
        }
    }

    private suspend fun processSocketRequestInternal(req: SearchBridge.SocketRequest) {
        val kw = req.keyword.trim().ifBlank { return }
        socketDoneJob?.cancel()
        socketRequestId = req.requestId
        updateBatchStatus(req.requestId, CheckStatus.IN_PROGRESS)

        val effectiveProxy = if (_skipProxy.value) "" else req.proxy
        Log.d("TopSearch", "processSocketRequest: kw='$kw' proxy='${req.proxy}' skipProxy=${_skipProxy.value} effective='$effectiveProxy' country=${req.country}")

        val queueSuffix = if (pendingQueueCount > 0) " (còn $pendingQueueCount đang chờ)" else ""
        _socketInfo.value = "Đang xử lý: \"$kw\"$queueSuffix"

        val proxyIp = if (effectiveProxy.isNotBlank()) {
            withTimeoutOrNull(12_000L) {
                try {
                    resolveIpViaProxy(effectiveProxy)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("TopSearch", "resolveIpViaProxy failed: ${e.message}")
                    ""
                }
            } ?: run {
                Log.w("TopSearch", "resolveIpViaProxy timeout (12s)")
                ""
            }
        } else ""

        // Trả kết quả cache ngay nếu đã check keyword này trước đó
        val cacheKey = cacheKey(kw, req.country)
        val cachedResults = resultCache[cacheKey]
        if (cachedResults != null) {
            Log.d("TopSearch", "Cache HIT '$kw' country=${req.country} → ${cachedResults.size} results")
            socketRequestId = null
            updateBatchStatus(req.requestId, CheckStatus.DONE)
            SearchBridge.dispatchResult(req.requestId, cachedResults, emptyList(), proxyIp)
            val suffix = if (pendingQueueCount > 0) " (còn $pendingQueueCount đang chờ)" else ""
            _socketInfo.value = "[Cache] \"$kw\" → ${cachedResults.size} kết quả$suffix"
            _state.value = SearchState.Idle
            return
        }

        val googleUrl = when (req.country) {
            2    -> "https://www.google.co.th/?hl=th&gl=th&pws=0"
            else -> "https://www.google.com.vn/?hl=vi&gl=vn&pws=0"
        }

        val done = CompletableDeferred<Unit>()
        currentDone = done

        _state.value = SearchState.WebCapturing(
            keyword   = kw,
            city      = "",
            url       = googleUrl,
            spoofLat  = 0.0,
            spoofLng  = 0.0,
            proxyHost = effectiveProxy,
            proxyIp   = proxyIp,
            country   = req.country,
        )

        // Suspend until WebCapture finishes (onWebCaptureDone / onWebCaptureError)
        withTimeoutOrNull(3 * 60 * 1_000L) { done.await() } ?: run {
            Log.w("TopSearch", "processSocketRequest TIMEOUT '$kw'")
            currentDone = null
            if (socketRequestId == req.requestId) socketRequestId = null
            updateBatchStatus(req.requestId, CheckStatus.ERROR, "Timeout khi search")
            _state.value = SearchState.Idle
            val suffix = if (pendingQueueCount > 0) " — $pendingQueueCount keyword đang chờ" else ""
            _socketInfo.value = "Hết giờ: \"$kw\"$suffix"
        }
    }

    private fun signalDone() {
        currentDone?.complete(Unit)
        currentDone = null
    }

    private fun updateBatchStatus(requestId: String, status: CheckStatus, errorMessage: String = "") {
        val completedAt = if (status == CheckStatus.DONE || status == CheckStatus.ERROR) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } else {
            ""
        }
        _keywordBatch.update { list ->
            list.map {
                if (it.requestId == requestId) {
                    it.copy(
                        status = status,
                        errorMessage = if (status == CheckStatus.ERROR) errorMessage else "",
                        completedAt = completedAt,
                    )
                } else {
                    it
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            KeywordQueueStore.updateStatus(ctx, requestId, status)
            if (_keywordBatch.value.all { it.status == CheckStatus.DONE }) {
                KeywordQueueStore.clear(ctx)
            }
        }
    }

    fun startSearch(
        keyword: String,
        city:    VietnamCity = VietnamCity("🌐 Toàn quốc", ""),
    ) {
        val kw = keyword.trim().ifBlank { return }

        viewModelScope.launch {
            val cityLabel = city.label
            val proxyPick = if (_skipProxy.value) "" else city.proxyHostPort

            val proxyIp = if (proxyPick.isNotBlank()) {
                Log.d("TopSearch", "Proxy chọn cho $cityLabel → ${proxyPick.substringBefore(":")}")
                withTimeoutOrNull(4000L) { resolveIpViaProxy(proxyPick) } ?: ""
            } else ""

            _state.value = SearchState.WebCapturing(
                keyword   = kw,
                city      = cityLabel,
                url       = "https://www.google.com.vn/?hl=vi&gl=vn&pws=0",
                spoofLat  = 0.0,
                spoofLng  = 0.0,
                proxyHost = proxyPick,
                proxyIp   = proxyIp,
            )
        }
    }

    fun onWebCaptureDone(
        keyword:         String,
        screenshotPaths: List<String>,
        jsResults:       List<SearchResult>,
        detectedCity:    String = "",
    ) {
        val capturing  = _state.value as? SearchState.WebCapturing
        val city       = detectedCity.ifBlank { capturing?.city ?: "" }
        val proxyIp    = capturing?.proxyIp ?: ""
        val proxyFull  = capturing?.proxyHost ?: ""
        val country    = capturing?.country ?: 1
        val reqId      = socketRequestId?.also { socketRequestId = null }
        val firstPath  = screenshotPaths.firstOrNull() ?: ""

        if (jsResults.isNotEmpty()) {
            Log.d("TopSearch", buildResultJson(keyword, city, jsResults))
            resultCache[cacheKey(keyword, country)] = jsResults
            reqId?.let { _keywordResults.value = _keywordResults.value + (it to jsResults) }
            if (reqId != null) {
                updateBatchStatus(reqId, CheckStatus.DONE)
                SearchBridge.dispatchResult(reqId, jsResults, screenshotPaths, proxyIp)
                showSocketDone(jsResults, keyword, firstPath, city, proxyIp, proxyFull, country)
            } else {
                _state.value = SearchState.Done(keyword, jsResults, firstPath, city, proxyIp)
                uploadAsync(screenshotPaths)
            }
            signalDone()
            return
        }
        if (screenshotPaths.isEmpty()) {
            _state.value = SearchState.Error("Không lấy được kết quả", keyword)
            if (reqId != null) {
                updateBatchStatus(reqId, CheckStatus.ERROR, "Capture không có ảnh/kết quả")
                showSocketDone(emptyList(), keyword, "", city, proxyIp, proxyFull, country)
            }
            signalDone()
            return
        }
        _state.value = SearchState.Analyzing(keyword)
        viewModelScope.launch {
            try {
                val results = OcrHelper.extractSearchResults(firstPath)
                Log.d("TopSearch", buildResultJson(keyword, city, results))
                if (results.isNotEmpty()) {
                    resultCache[cacheKey(keyword, country)] = results
                    reqId?.let { _keywordResults.value = _keywordResults.value + (it to results) }
                }
                if (reqId != null) {
                    if (results.isNotEmpty()) {
                        updateBatchStatus(reqId, CheckStatus.DONE)
                        SearchBridge.dispatchResult(reqId, results, screenshotPaths, proxyIp)
                    } else {
                        updateBatchStatus(reqId, CheckStatus.ERROR, "OCR không đọc được top")
                    }
                    showSocketDone(results, keyword, firstPath, city, proxyIp, proxyFull, country)
                } else {
                    _state.value = SearchState.Done(keyword, results, firstPath, city, proxyIp)
                    uploadAsync(screenshotPaths)
                }
            } catch (e: Exception) {
                _state.value = if (reqId != null) SearchState.Idle else SearchState.Error("OCR thất bại: ${e.message}", keyword)
                reqId?.let {
                    updateBatchStatus(it, CheckStatus.ERROR, "OCR lỗi: ${e.message ?: "unknown"}")
                    showSocketDone(emptyList(), keyword, "", city, proxyIp, proxyFull, country)
                }
            }
            signalDone()
        }
    }

    fun onWebCaptureError(keyword: String, error: String) {
        val capturing = _state.value as? SearchState.WebCapturing
        val reqId     = socketRequestId?.also { socketRequestId = null }
        val proxyIp   = capturing?.proxyIp ?: ""
        if (reqId != null) {
            updateBatchStatus(reqId, CheckStatus.ERROR, error)
            showSocketDone(emptyList(), keyword, "", "", proxyIp,
                capturing?.proxyHost ?: "", capturing?.country ?: 1)
        } else {
            _state.value = SearchState.Error(error, keyword)
        }
        signalDone()
    }

    fun reset() {
        _state.value = SearchState.Idle
        _socketInfo.value = ""
    }

    private fun uploadAsync(screenshotPaths: List<String>) {
        if (screenshotPaths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            TelegramUploader.uploadAll(screenshotPaths)
        }
    }

    private fun showSocketDone(
        results:        List<SearchResult>,
        keyword:        String,
        screenshotPath: String,
        city:           String,
        proxyIp:        String,
        proxyFull:      String = "",
        country:        Int    = 1,
    ) {
        socketDoneJob?.cancel()
        val ts   = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val lang = if (country == 2) "(th)" else "(vn)"
        val info = buildString {
            appendLine("Thời gian: $ts")
            appendLine("Từ khoá: $keyword - $lang")
            if (proxyFull.isNotBlank()) {
                val parts = proxyFull.split(":")
                appendLine("Proxy: ${parts.getOrNull(0) ?: ""}:${parts.getOrNull(1) ?: ""}:${parts.getOrNull(2) ?: ""} - $country")
            }
            if (proxyIp.isNotBlank()) appendLine("PUBLIC IP: $proxyIp")
            append("Trạng thái: ${if (results.isNotEmpty()) "Success - OK" else "Không có kết quả"}")
        }

        val queueEmpty = pendingQueueCount == 0 && requestQueue.isEmpty
        if (results.isNotEmpty()) {
            _socketInfo.value = "Đã gửi ${results.size} kết quả"
            if (queueEmpty) {
                _state.value = SearchState.Done(keyword, results, screenshotPath, city, proxyIp, info)
            }
        } else {
            val msg = "Không có kết quả — chờ keyword tiếp theo"
            _socketInfo.value = msg
            if (queueEmpty) {
                _state.value = SearchState.Idle
            }
            socketDoneJob = viewModelScope.launch {
                delay(4_000)
                if (_socketInfo.value == msg) _socketInfo.value = ""
            }
        }
    }

    // ── Crash-recovery queue ──────────────────────────────────────────────────

    fun checkPendingQueue() {
        if (_pendingQueuePrompt.value || _keywordBatch.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (KeywordQueueStore.hasPending(getApplication())) {
                _pendingQueuePrompt.value = true
            }
        }
    }

    fun resumePendingQueue() {
        _pendingQueuePrompt.value = false
        val ctx = getApplication<Application>()
        val entries = KeywordQueueStore.load(ctx) ?: return
        val pending = entries.filter { it.status != CheckStatus.DONE }
        _keywordBatch.value = entries.map {
            KeywordBatchItem(
                requestId = it.requestId,
                keyword   = it.keyword,
                status    = if (it.status == CheckStatus.DONE) CheckStatus.DONE else CheckStatus.PENDING,
            )
        }
        entries.forEach { e ->
            batchRequests[e.requestId] = SearchBridge.SocketRequest(e.requestId, e.keyword, e.proxy, e.country)
        }
        viewModelScope.launch {
            SearchBridge.resumeRequest.emit(
                pending.map { SearchBridge.SocketRequest(it.requestId, it.keyword, it.proxy, it.country) }
            )
            Log.d("TopSearch", "Resumed ${pending.size}/${entries.size} keywords from persistent store")
        }
    }

    fun dismissPendingPrompt() {
        _pendingQueuePrompt.value = false
        viewModelScope.launch(Dispatchers.IO) {
            KeywordQueueStore.clear(getApplication())
        }
    }

    fun retryBatchKeyword(requestId: String) {
        val req = batchRequests[requestId] ?: run {
            Log.w("TopSearch", "retryBatchKeyword: request not found reqId=$requestId")
            return
        }
        _keywordBatch.update { list ->
            list.map {
                if (it.requestId == requestId) {
                    it.copy(
                        status = CheckStatus.PENDING,
                        retryCount = it.retryCount + 1,
                        errorMessage = "",
                        completedAt = "",
                    )
                } else {
                    it
                }
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                KeywordQueueStore.updateStatus(getApplication(), requestId, CheckStatus.PENDING)
            }
            SearchBridge.resumeRequest.emit(listOf(req))
            Log.d("TopSearch", "Manual retry '${req.keyword}' reqId=$requestId")
        }
    }

    private suspend fun retryErrorKeywords() {
        val errorItems = _keywordBatch.value
            .filter { it.status == CheckStatus.ERROR && it.retryCount < 3 && it.isAutoRetryableError() }
        if (errorItems.isEmpty()) return
        val retryReqs = errorItems.mapNotNull { batchRequests[it.requestId] }
        if (retryReqs.isEmpty()) return
        Log.d("TopSearch", "Auto-retry ${retryReqs.size} retryable ERROR keywords")
        errorItems.forEach { item ->
            _keywordBatch.update { list ->
                list.map {
                    if (it.requestId == item.requestId)
                        it.copy(
                            status = CheckStatus.PENDING,
                            retryCount = it.retryCount + 1,
                            errorMessage = "",
                            completedAt = "",
                        )
                    else it
                }
            }
        }
        withContext(Dispatchers.IO) {
            errorItems.forEach { item ->
                KeywordQueueStore.updateStatus(getApplication(), item.requestId, CheckStatus.PENDING)
            }
        }
        SearchBridge.resumeRequest.emit(retryReqs)
    }

    private fun KeywordBatchItem.isAutoRetryableError(): Boolean {
        val msg = errorMessage.lowercase()
        if ("proxy" in msg || "captcha" in msg || "block" in msg) return false
        return true
    }

    companion object {
        const val COUNTDOWN_SEC = 15

        suspend fun resolveIpViaProxy(proxyHostPort: String): String = withContext(Dispatchers.IO) {
            try {
                val info = ProxyHelper.parse(proxyHostPort) ?: run {
                    Log.w("TopSearch", "resolveIpViaProxy: invalid proxy format '$proxyHostPort'")
                    return@withContext ""
                }
                val builder = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(info.host, info.port)))
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                if (info.requiresAuth) {
                    val credential = Credentials.basic(info.user, info.pass)
                    builder.proxyAuthenticator { _, response ->
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
                val client  = builder.build()
                val request = Request.Builder().url("https://api64.ipify.org?format=json").build()
                val body    = client.newCall(request).execute().use { it.body?.string()?.trim() ?: "" }
                val ip      = org.json.JSONObject(body).optString("ip", "")
                Log.d("TopSearch", "resolveIpViaProxy OK → $ip (proxy=${info.host}:${info.port} auth=${info.requiresAuth})")
                ip
            } catch (e: Exception) {
                Log.w("TopSearch", "resolveIpViaProxy failed: ${e.message}")
                ""
            }
        }

        fun buildResultJson(keyword: String, city: String, results: List<SearchResult>): String {
            val arr = JSONArray()
            results.forEach { r ->
                arr.put(JSONObject().apply {
                    put("rank",   r.rank)
                    put("domain", r.domain)
                    put("url",    r.url)
                    put("title",  r.title)
                    put("isAd",   r.isAd)
                })
            }
            return JSONObject().apply {
                put("keyword", keyword)
                put("city",    city)
                put("results", arr)
            }.toString(2)
        }

        fun buildUule(lat: Double, lng:Double): String {
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
