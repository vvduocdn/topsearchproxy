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

    private val screenRecorder = ScreenRecorder(appContext)

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    val isRecording: StateFlow<Boolean> = SearchBridge.isRecording

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

    private val _historyEntries = MutableStateFlow<List<KeywordQueueStore.Entry>>(emptyList())
    val historyEntries: StateFlow<List<KeywordQueueStore.Entry>> = _historyEntries.asStateFlow()

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

    // requestId có proxy nhưng không resolve được IP → gắn note "Thiếu ip" khi thành công
    private val missingIpRequestIds = mutableSetOf<String>()

    private var captureSeq = 0

    // Kết quả đã check theo requestId — user nhấn keyword row để xem lại
    private val _keywordResults = MutableStateFlow<Map<String, List<SearchResult>>>(emptyMap())
    val keywordResults: StateFlow<Map<String, List<SearchResult>>> = _keywordResults.asStateFlow()

    private val _keywordImagePaths = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val keywordImagePaths: StateFlow<Map<String, List<String>>> = _keywordImagePaths.asStateFlow()

    init {
        refreshHistory()
        // Populate batch status list when server sends a new batch; persist to disk for crash recovery
        viewModelScope.launch {
            SearchBridge.incomingBatch.collect { requests ->
                _pendingQueuePrompt.value = false  // dismiss resume dialog if server sends fresh batch
                val requestIds = requests.map { it.requestId }.toSet()
                val currentById = _keywordBatch.value.associateBy { it.requestId }
                val newItems = requests.map { req ->
                    currentById[req.requestId] ?: KeywordBatchItem(req.requestId, req.keyword)
                }
                _keywordBatch.value = newItems + _keywordBatch.value.filterNot { it.requestId in requestIds }
                val ctx = getApplication<Application>()
                launch(Dispatchers.IO) {
                    val existing = KeywordQueueStore.load(ctx) ?: emptyList()
                    val newEntries = requests.map {
                        KeywordQueueStore.Entry(it.requestId, it.keyword, it.proxy, it.country)
                    }
                    KeywordQueueStore.save(ctx, (newEntries + existing).distinctBy { it.requestId })
                    refreshHistory()
                }
                requests.forEach { batchRequests[it.requestId] = it }
                startRecording()
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
            SearchBridge.submitFailure.collect { failure ->
                updateBatchStatus(failure.requestId, CheckStatus.ERROR, failure.message)
                Log.w("TopSearch", "Submit failed reqId=${failure.requestId}: ${failure.message}")
                delay(500)
                if (pendingQueueCount == 0 && requestQueue.isEmpty && !SearchBridge.isProcessing.value) {
                    retryErrorKeywords()
                }
            }
        }
        viewModelScope.launch {
            SearchBridge.submitSuccess.collect { success ->
                updateBatchStatus(success.requestId, CheckStatus.DONE)
                if (missingIpRequestIds.remove(success.requestId)) {
                    addNoteToBatchItem(success.requestId, "Thiếu ip")
                }
                _socketInfo.value = "Submit thành công"
                Log.d("TopSearch", "Submit success reqId=${success.requestId}")
            }
        }
        viewModelScope.launch {
            for (req in requestQueue) {
                pendingQueueCount = (pendingQueueCount - 1).coerceAtLeast(0)
                processSocketRequest(req)
                // When queue drains, auto-retry ERROR keywords (normal: up to 3×; proxy/captcha/block: 1×)
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
            withTimeoutOrNull(IP_RESOLVE_TOTAL_TIMEOUT_MS) {
                try {
                    resolveIpViaProxy(effectiveProxy)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("TopSearch", "resolveIpViaProxy failed: ${e.message}")
                    ""
                }
            } ?: run {
                Log.w("TopSearch", "resolveIpViaProxy timeout (${IP_RESOLVE_TOTAL_TIMEOUT_MS / 1000}s)")
                ""
            }
        } else ""

        if (effectiveProxy.isNotBlank() && proxyIp.isEmpty()) {
            missingIpRequestIds += req.requestId
        }

        val googleUrl = when (req.country) {
            2    -> "https://www.google.co.th/?hl=th&gl=th&pws=0"
            else -> "https://www.google.com.vn/?hl=vi&gl=vn&pws=0"
        }

        val done = CompletableDeferred<Unit>()
        currentDone = done

        _state.value = SearchState.WebCapturing(
            keyword    = kw,
            city       = "",
            url        = googleUrl,
            spoofLat   = 0.0,
            spoofLng   = 0.0,
            proxyHost  = effectiveProxy,
            proxyIp    = proxyIp,
            country    = req.country,
            captureSeq = ++captureSeq,
        )

        // Suspend until WebCapture finishes (onWebCaptureDone / onWebCaptureError)
        withTimeoutOrNull(3 * 60 * 1_000L) { done.await() } ?: run {
            Log.w("TopSearch", "processSocketRequest TIMEOUT '$kw'")
            currentDone = null
            if (socketRequestId == req.requestId) socketRequestId = null
            updateBatchStatus(req.requestId, CheckStatus.ERROR, "Timeout khi search")
            _state.value = SearchState.Idle
            val suffix = if (pendingQueueCount > 0) " - $pendingQueueCount keyword dang cho" else ""
            _socketInfo.value = "Hết giờ: \"$kw\"$suffix"
        }
    }

    private fun signalDone() {
        currentDone?.complete(Unit)
        currentDone = null
    }

    private fun addNoteToBatchItem(requestId: String, note: String) {
        _keywordBatch.update { list ->
            list.map {
                if (it.requestId == requestId) it.copy(notes = note) else it
            }
        }
    }

    private fun updateBatchStatus(requestId: String, status: CheckStatus, errorMessage: String = "") {
        val completedAt = if (status == CheckStatus.DONE || status == CheckStatus.ERROR) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } else {
            ""
        }
        var updatedItem: KeywordBatchItem? = null
        _keywordBatch.update { list ->
            list.map {
                if (it.requestId == requestId) {
                    it.copy(
                        status = status,
                        errorMessage = if (status == CheckStatus.ERROR) errorMessage else "",
                        completedAt = completedAt,
                    ).also { updatedItem = it }
                } else {
                    it
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val item = updatedItem
            if (item != null) {
                KeywordQueueStore.updateEntry(
                    ctx = ctx,
                    requestId = requestId,
                    status = item.status,
                    retryCount = item.retryCount,
                    errorMessage = item.errorMessage,
                    completedAt = item.completedAt,
                )
            } else {
                KeywordQueueStore.updateStatus(ctx, requestId, status)
            }
            refreshHistory()
        }
    }

    fun refreshHistory(reason: String = "refreshHistory") {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = KeywordQueueStore.load(getApplication()) ?: emptyList()
            Log.d("TopSearch", "History load [$reason]: total=${entries.size}")
            entries.forEachIndexed { index, item ->
                Log.d(
                    "TopSearch",
                    "  history[$index] reqId=${item.requestId} keyword='${item.keyword}' " +
                        "status=${item.status} retry=${item.retryCount} country=${item.country} " +
                        "queuedAt=${item.queuedAt} completedAt='${item.completedAt}' " +
                        "error='${item.errorMessage}'",
                )
            }
            _historyEntries.value = entries
        }
    }

    fun openHistory() {
        refreshHistory("open history")
    }

    fun deleteHistoryAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val before = KeywordQueueStore.load(getApplication())?.size ?: 0
            Log.w("TopSearch", "History delete all: before=$before")
            KeywordQueueStore.clear(getApplication())
            _historyEntries.value = emptyList()
            _keywordBatch.value = emptyList()
        }
    }

    fun deleteHistoryDay(day: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val beforeEntries = KeywordQueueStore.load(getApplication()) ?: emptyList()
            Log.w(
                "TopSearch",
                "History delete day=$day before=${beforeEntries.size} remove=${beforeEntries.count { it.queuedAt == day }}",
            )
            KeywordQueueStore.deleteDay(getApplication(), day)
            _historyEntries.value = KeywordQueueStore.load(getApplication()) ?: emptyList()
            _keywordBatch.update { items ->
                items.filterNot { item ->
                    _historyEntries.value.none { it.requestId == item.requestId }
                }
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
                withTimeoutOrNull(IP_RESOLVE_TOTAL_TIMEOUT_MS) { resolveIpViaProxy(proxyPick) } ?: ""
            } else ""

            _state.value = SearchState.WebCapturing(
                keyword    = kw,
                city       = cityLabel,
                url        = "https://www.google.com.vn/?hl=vi&gl=vn&pws=0",
                spoofLat   = 0.0,
                spoofLng   = 0.0,
                proxyHost  = proxyPick,
                proxyIp    = proxyIp,
                captureSeq = ++captureSeq,
            )
        }
    }

    fun addTestKeyword(keyword: String, proxy: String = "", country: Int = 1) {
        val kw = keyword.trim().ifBlank { return }
        val requestId = "test_${System.currentTimeMillis()}"
        val req = SearchBridge.SocketRequest(requestId, kw, proxy, country, isTest = true)
        viewModelScope.launch {
            Log.d("TopSearch", "addTestKeyword: kw='$kw' proxy='$proxy' country=$country reqId=$requestId")
            SearchBridge.testRequest.emit(req)
        }
    }

    fun onWebCaptureDone(
        keyword:         String,
        screenshotPaths: List<String>,
        jsResults:       List<SearchResult>,
        detectedCity:    String = "",
        checkedAt:       Long   = 0L,
    ) {
        Log.d("TopSearch", "onWebCaptureDone keyword=$keyword checkedAt=$checkedAt")
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
            reqId?.let { if (screenshotPaths.isNotEmpty()) _keywordImagePaths.value = _keywordImagePaths.value + (it to screenshotPaths) }
            if (reqId != null) {
                SearchBridge.dispatchResult(reqId, jsResults, screenshotPaths, proxyIp, jsResults.size, checkedAt)
                showSocketDone(jsResults, keyword, firstPath, city, proxyIp, proxyFull, country)
            } else {
                _state.value = SearchState.Done(keyword, jsResults, firstPath, city, proxyIp)
                uploadAsync(screenshotPaths, keyword, jsResults)
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
                    reqId?.let { if (screenshotPaths.isNotEmpty()) _keywordImagePaths.value = _keywordImagePaths.value + (it to screenshotPaths) }
                }
                if (reqId != null) {
                    if (results.isNotEmpty()) {
                        SearchBridge.dispatchResult(reqId, results, screenshotPaths, proxyIp, results.size)
                    } else {
                        updateBatchStatus(reqId, CheckStatus.ERROR, "OCR khong doc duoc top")
                    }
                    showSocketDone(results, keyword, firstPath, city, proxyIp, proxyFull, country)
                } else {
                    _state.value = SearchState.Done(keyword, results, firstPath, city, proxyIp)
                    uploadAsync(screenshotPaths, keyword, results)
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

    private fun uploadAsync(
        screenshotPaths: List<String>,
        keyword: String,
        results: List<SearchResult>,
    ) {
        if (screenshotPaths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val url = TelegramUploader.uploadFirstThenSendResults(screenshotPaths, keyword, results)
            Log.d("TopSearch", "Telegram manual upload urlBlank=${url.isBlank()}")
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
            appendLine("Thoi gian: $ts")
            appendLine("Tu khoa: $keyword - $lang")
            if (proxyFull.isNotBlank()) {
                val parts = proxyFull.split(":")
                appendLine("Proxy: ${parts.getOrNull(0) ?: ""}:${parts.getOrNull(1) ?: ""}:${parts.getOrNull(2) ?: ""} - $country")
            }
            if (proxyIp.isNotBlank()) appendLine("PUBLIC IP: $proxyIp")
            append("Trạng thái: ${if (results.isNotEmpty()) "Success - OK" else "Không có kết quả"}")
        }

        val queueEmpty = pendingQueueCount == 0 && requestQueue.isEmpty
        if (results.isNotEmpty()) {
            _socketInfo.value = "Đã capture ${results.size} kết quả - đang submit"
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

    // ── Screen recording ──────────────────────────────────────────────────────

    private fun startRecording() {
        val projection = SearchBridge.mediaProjection ?: run {
            Log.w("TopSearch", "startRecording: no MediaProjection, skipping")
            return
        }
        val started = screenRecorder.start(projection)
        SearchBridge.isRecording.value = started
        Log.d("TopSearch", "startRecording: started=$started")
    }

    private suspend fun stopRecordingAsync() {
        if (!SearchBridge.isRecording.value) return
        SearchBridge.isRecording.value = false
        withContext(Dispatchers.IO) {
            val path = screenRecorder.stop()
            Log.d("TopSearch", "stopRecordingAsync: path=$path")
        }
    }

    fun stopRecording() {
        viewModelScope.launch { stopRecordingAsync() }
    }

    override fun onCleared() {
        super.onCleared()
        if (SearchBridge.isRecording.value) {
            SearchBridge.isRecording.value = false
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { screenRecorder.stop() }
        }
    }

    // ── Crash-recovery queue ──────────────────────────────────────────────────

    fun checkPendingQueue() {
        if (_pendingQueuePrompt.value || _keywordBatch.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val entries = KeywordQueueStore.load(getApplication()) ?: emptyList()
            val unfinishedToday = entries.filter {
                KeywordQueueStore.isToday(it) && it.status != CheckStatus.DONE
            }
            if (unfinishedToday.isNotEmpty()) {
                Log.d("TopSearch", "Pending local unfinished keywords today=${unfinishedToday.size}")
                unfinishedToday.forEachIndexed { index, item ->
                    Log.d(
                        "TopSearch",
                        "  pending[$index] reqId=${item.requestId} keyword='${item.keyword}' " +
                            "status=${item.status} retry=${item.retryCount} country=${item.country} " +
                            "error='${item.errorMessage}' queuedAt=${item.queuedAt}",
                    )
                }
                _pendingQueuePrompt.value = true
            }
        }
    }

    fun resumePendingQueue() {
        _pendingQueuePrompt.value = false
        val ctx = getApplication<Application>()
        val entries = KeywordQueueStore.load(ctx) ?: return
        val unfinishedToday = entries.filter {
            KeywordQueueStore.isToday(it) && it.status != CheckStatus.DONE
        }
        val pending = unfinishedToday.filter { KeywordQueueStore.canResume(it) }
        val pendingIds = pending.map { it.requestId }.toSet()
        Log.d(
            "TopSearch",
            "Resume pending dialog: unfinishedToday=${unfinishedToday.size}, willResume=${pending.size}",
        )
        unfinishedToday.forEachIndexed { index, item ->
            Log.d(
                "TopSearch",
                "  resume[$index] willResume=${item.requestId in pendingIds} " +
                    "reqId=${item.requestId} keyword='${item.keyword}' status=${item.status} " +
                    "retry=${item.retryCount} country=${item.country} error='${item.errorMessage}'",
            )
        }
        _keywordBatch.value = unfinishedToday.map {
            KeywordBatchItem(
                requestId = it.requestId,
                keyword   = it.keyword,
                status    = when {
                    it.status == CheckStatus.DONE -> CheckStatus.DONE
                    it.requestId in pendingIds -> CheckStatus.PENDING
                    else -> CheckStatus.ERROR
                },
                retryCount = it.retryCount,
                errorMessage = if (it.status == CheckStatus.ERROR && it.requestId !in pendingIds) it.errorMessage else "",
                completedAt = if (it.status == CheckStatus.DONE || it.requestId !in pendingIds) it.completedAt else "",
            )
        }
        unfinishedToday.forEach { e ->
            batchRequests[e.requestId] = SearchBridge.SocketRequest(e.requestId, e.keyword, e.proxy, e.country)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                pending.forEach {
                    KeywordQueueStore.updateEntry(
                        ctx = ctx,
                        requestId = it.requestId,
                        status = CheckStatus.PENDING,
                        errorMessage = "",
                        completedAt = "",
                    )
                }
            }
            if (pending.isNotEmpty()) {
                SearchBridge.resumeRequest.emit(
                    pending.map { SearchBridge.SocketRequest(it.requestId, it.keyword, it.proxy, it.country) }
                )
            }
            Log.d("TopSearch", "Resumed ${pending.size}/${unfinishedToday.size} unfinished keywords from persistent store")
        }
    }

    fun dismissPendingPrompt() {
        _pendingQueuePrompt.value = false
        viewModelScope.launch(Dispatchers.IO) {
            KeywordQueueStore.clear(getApplication())
            _historyEntries.value = emptyList()
            _keywordBatch.value = emptyList()
        }
    }

    fun retryBatchKeyword(requestId: String) {
        val req = batchRequests[requestId] ?: run {
            Log.w("TopSearch", "retryBatchKeyword: request not found reqId=$requestId")
            return
        }
        val currentItem = _keywordBatch.value.firstOrNull { it.requestId == requestId }
        if (currentItem?.status == CheckStatus.PENDING || currentItem?.status == CheckStatus.IN_PROGRESS) {
            Log.d("TopSearch", "retryBatchKeyword ignored reqId=$requestId status=${currentItem.status}")
            return
        }

        var updatedItem: KeywordBatchItem? = null
        _keywordBatch.update { list ->
            list.map {
                if (it.requestId == requestId) {
                    it.copy(
                        status = CheckStatus.PENDING,
                        retryCount = it.retryCount + 1,
                        errorMessage = "",
                        completedAt = "",
                    ).also { updatedItem = it }
                } else {
                    it
                }
            }
        }
        _keywordResults.update { it - requestId }
        _keywordImagePaths.update { it - requestId }
        resultCache.remove(cacheKey(req.keyword, req.country))

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val item = updatedItem
                if (item != null) {
                    KeywordQueueStore.updateEntry(
                        ctx = getApplication(),
                        requestId = requestId,
                        status = item.status,
                        retryCount = item.retryCount,
                        errorMessage = item.errorMessage,
                        completedAt = item.completedAt,
                    )
                } else {
                    KeywordQueueStore.updateStatus(getApplication(), requestId, CheckStatus.PENDING)
                }
                refreshHistory()
            }
            SearchBridge.resumeRequest.emit(listOf(req))
            Log.d("TopSearch", "Manual retry '${req.keyword}' reqId=$requestId previousStatus=${currentItem?.status}")
        }
    }

    private suspend fun retryErrorKeywords() {
        val errorItems = _keywordBatch.value
            .filter { it.status == CheckStatus.ERROR && it.retryCount < 3 && it.isAutoRetryableError() }
        if (errorItems.isEmpty()) {
            stopRecordingAsync()
            return
        }
        val retryReqs = errorItems.mapNotNull { batchRequests[it.requestId] }
        if (retryReqs.isEmpty()) return
        Log.d("TopSearch", "Auto-retry ${retryReqs.size} retryable ERROR keywords")
        val updatedItems = mutableMapOf<String, KeywordBatchItem>()
        errorItems.forEach { item ->
            _keywordBatch.update { list ->
                list.map {
                    if (it.requestId == item.requestId)
                        it.copy(
                            status = CheckStatus.PENDING,
                            retryCount = it.retryCount + 1,
                            errorMessage = "",
                            completedAt = "",
                        ).also { updatedItems[item.requestId] = it }
                    else it
                }
            }
        }
        withContext(Dispatchers.IO) {
            errorItems.forEach { item ->
                val updated = updatedItems[item.requestId]
                if (updated != null) {
                    KeywordQueueStore.updateEntry(
                        ctx = getApplication(),
                        requestId = item.requestId,
                        status = updated.status,
                        retryCount = updated.retryCount,
                        errorMessage = updated.errorMessage,
                        completedAt = updated.completedAt,
                    )
                } else {
                    KeywordQueueStore.updateStatus(getApplication(), item.requestId, CheckStatus.PENDING)
                }
            }
            refreshHistory()
        }
        SearchBridge.resumeRequest.emit(retryReqs)
    }

    private fun KeywordBatchItem.isAutoRetryableError(): Boolean {
        val msg = errorMessage.lowercase()
        // proxy/captcha/block: vẫn retry nhưng tối đa 1 lần
        if ("proxy" in msg || "captcha" in msg || "block" in msg) return retryCount < 1
        return true
    }

    companion object {
        const val COUNTDOWN_SEC = 15
        private const val IP_RESOLVE_TOTAL_TIMEOUT_MS = 35_000L
        private const val IP_PROVIDER_TIMEOUT_SEC = 4L
        private val IP_SERVICES: List<Pair<String, (String) -> String>> = listOf(
            "https://api64.ipify.org?format=json" to { body -> JSONObject(body).optString("ip", "") },
            "https://api.ipify.org?format=json" to { body -> JSONObject(body).optString("ip", "") },
            "https://api.my-ip.io/v2/ip.json" to { body -> JSONObject(body).optString("ip", "") },
            "https://ipinfo.io/json" to { body -> JSONObject(body).optString("ip", "") },
        )

    suspend fun resolveIpViaProxy(
        proxyHostPort: String,
        retries: Int = 2,
    ): String = withContext(Dispatchers.IO) {
        val info = ProxyHelper.parse(proxyHostPort) ?: run {
            Log.w("TopSearch", "resolveIpViaProxy: invalid proxy format '$proxyHostPort'")
            return@withContext ""
        }

        val credential = if (info.requiresAuth) Credentials.basic(info.user, info.pass) else null

        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(info.host, info.port)))
            .connectTimeout(IP_PROVIDER_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(IP_PROVIDER_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(IP_PROVIDER_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(IP_PROVIDER_TIMEOUT_SEC, TimeUnit.SECONDS)
            .apply {
                if (credential != null) {
                    proxyAuthenticator { _, response ->
                        // Prevent infinite auth loop
                        if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            }
            .build()

        for ((url, parseIp) in IP_SERVICES) {
            repeat(retries) { attempt ->
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    val (ok, body) = client.newCall(request).execute().use { resp ->
                        resp.isSuccessful to (resp.body?.string()?.trim() ?: "")
                    }

                    if (!ok || body.isEmpty()) {
                        Log.w("TopSearch", "resolveIpViaProxy: HTTP error url=$url attempt=${attempt + 1}/$retries")
                        return@repeat
                    }

                    val ip = parseIp(body)
                    if (ip.isNotEmpty()) {
                        Log.d("TopSearch", "resolveIpViaProxy OK → $ip (proxy=${info.host}:${info.port}, url=$url, attempt=${attempt + 1}/$retries)")
                        return@withContext ip
                    }
                } catch (e: Exception) {
                    Log.w("TopSearch", "resolveIpViaProxy failed url=$url attempt=${attempt + 1}/$retries: ${e.message}")
                }
            }
        }

        Log.e("TopSearch", "resolveIpViaProxy: all services/retries exhausted for proxy ${info.host}:${info.port}")
        ""
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
