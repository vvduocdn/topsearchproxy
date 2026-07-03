package com.topsearch.app

import com.topsearch.app.BuildConfig
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.topsearch.app.MainActivity

private const val TAG        = "SearchService"
private const val CHANNEL_ID = "topsearch_socket"
private const val NOTIF_ID   = 1001

class SearchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enqueueMutex = Mutex()

    // Shared across reconnects — avoids spawning a new thread pool every cycle.
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // long-lived connection, no read timeout
        .build()

    // @Volatile: written from connectLoop (IO thread), read from registerResultCallback (IO thread).
    @Volatile private var activeClient: SignalRClient? = null

    private var sourceName:    String  = ""
    private var workersStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        sourceName = "${Build.MANUFACTURER} ${Build.MODEL} (${androidId.take(8)})"
        Log.d(TAG, "sourceName=$sourceName")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotif("Dang ket noi..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, buildNotif("Dang ket noi..."))
        }

        // Service can be started multiple times by MainActivity; start workers once only.
        if (!workersStarted) {
            workersStarted = true
            scope.launch { connectLoop() }
            scope.launch { observeResumeRequests() }
            scope.launch { observeTestRequests() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Cancel scope first so connectLoop exits cleanly at the next suspension point.
        scope.cancel()
        activeClient?.disconnect()
        activeClient = null
        // Shut down the shared OkHttpClient so its thread pool is released.
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    // ── Connection loop with exponential backoff ──────────────────────────────

    private suspend fun connectLoop() {
        var retryDelay = 1_000L                  // starts at 1 s, doubles up to 60 s

        while (scope.isActive) {
            val gone       = kotlinx.coroutines.CompletableDeferred<Unit>()
            var didConnect = false                // true once handshake completes

            val client = SignalRClient(
                url         = BuildConfig.SOCKET_URL,
                http        = http,
                onConnected = {
                    // Fires on OkHttp thread after handshake — MutableStateFlow is thread-safe.
                    didConnect = true
                    SearchBridge.isConnected.value = true
                    showNotif("Da ket noi - dang cho keyword")
                    Log.d(TAG, "SignalR handshake OK")
                },
                onBatch        = ::onBatch,
                onDisconnected = { gone.complete(Unit) },
            )

            activeClient = client
            client.connect()

            // Suspend until onDisconnected fires (or scope is cancelled → CancellationException).
            gone.await()

            activeClient = null
            SearchBridge.isConnected.value = false

            if (!scope.isActive) break           // destroyed — exit without retry

            if (didConnect) {
                // Successful session ended; reset backoff so next attempt is fast.
                retryDelay = 1_000L
                showNotif("Mat ket noi - dang ket noi lai...")
            } else {
                // Failed before handshake; back off to avoid hammering the server.
                showNotif("Ket noi that bai - thu lai sau ${retryDelay / 1000}s...")
            }

            Log.w(TAG, "Disconnected (didConnect=$didConnect) — retry in ${retryDelay}ms")
            delay(retryDelay)
            if (!didConnect) retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
        }
    }

    // ── Keyword dispatching ───────────────────────────────────────────────────

    private fun onBatch(requests: List<SearchBridge.SocketRequest>) {
        enqueueRequests(requests, publishBatch = true)
    }

    private suspend fun observeResumeRequests() {
        SearchBridge.resumeRequest.collect { requests ->
            Log.d(TAG, "Resume: re-queue ${requests.size} pending keyword(s)")
            enqueueRequests(requests, publishBatch = false, bringToFront = false)
        }
    }

    private suspend fun observeTestRequests() {
        SearchBridge.testRequest.collect { req ->
            Log.d(TAG, "TestRequest: keyword='${req.keyword}' proxy='${req.proxy}' country=${req.country}")
            enqueueRequests(listOf(req), publishBatch = true, bringToFront = false)
        }
    }

    private fun enqueueRequests(
        requests:      List<SearchBridge.SocketRequest>,
        publishBatch:  Boolean,
        bringToFront:  Boolean = true,
    ) {
        val valid = requests.filter { it.requestId.isNotBlank() && it.keyword.isNotBlank() }
        if (valid.isEmpty()) {
            Log.w(TAG, "enqueueRequests ignored empty/invalid batch size=${requests.size}")
            return
        }

        // Register callback before enqueue so ViewModel can dispatch safely.
        valid.forEach { req ->
            Log.d(TAG, "Queue keyword '${req.keyword}' reqId=${req.requestId} proxy=${req.proxy} country=${req.country}")
            registerResultCallback(req)
        }

        val alreadyProcessing = SearchBridge.isProcessing.value
        showNotif("Dang search: ${valid.first().keyword}${if (valid.size > 1) " (+${valid.size - 1})" else ""}")
        if (alreadyProcessing) {
            Log.d(TAG, "Already processing; appended ${valid.size} keyword(s) to queue only")
        } else if (bringToFront) {
            startMainActivity()
        }

        scope.launch {
            enqueueMutex.withLock {
                // Give Activity/ViewModel a moment to start collecting SharedFlow when newly opened.
                if (!alreadyProcessing) delay(300)
                if (publishBatch) SearchBridge.incomingBatch.emit(valid)
                valid.forEach { req ->
                    Log.d(TAG, "Emit to ViewModel '${req.keyword}' reqId=${req.requestId}")
                    SearchBridge.incoming.emit(req)
                }
            }
        }
    }

    private fun registerResultCallback(req: SearchBridge.SocketRequest) {
        val requestId = req.requestId
        SearchBridge.registerCallback(requestId) { results, screenshotPaths, publicIp, totalCount, checkedAt ->
            scope.launch {
                withTimeoutOrNull(90_000L) {
                    Log.d(TAG, "CALLBACK reqId=$requestId keyword='${req.keyword}' publicIp=$publicIp totalParsed=$totalCount checkedAt=$checkedAt")
                    Log.d(TAG, "  results=${results.size} screenshots=${screenshotPaths.size}")
                    if (screenshotPaths.isEmpty()) {
                        failSubmit(requestId, req.keyword, "Submit fail: thieu anh")
                        return@withTimeoutOrNull
                    }
                    if (results.isEmpty()) {
                        failSubmit(requestId, req.keyword, "Submit fail: thieu ket qua")
                        return@withTimeoutOrNull
                    }
                    if (BuildConfig.DEBUG) {
                        screenshotPaths.forEachIndexed { i, p ->
                            val file = java.io.File(p)
                            Log.d(TAG, "  screenshot[$i]=$p exists=${file.exists()} size=${file.length()}B")
                        }
                    }

                    val validResults = results.filter { it.domain.isNotBlank() && it.url.isNotBlank() }
                    if (validResults.isEmpty()) {
                        failSubmit(requestId, req.keyword, "Submit fail: top missing domain/url")
                        return@withTimeoutOrNull
                    }
                    val toSubmit = if (totalCount < 10) validResults else validResults.take(10)
                    val duplicateReason = duplicateTopReason(toSubmit)
                    if (duplicateReason != null) {
                        failSubmit(requestId, req.keyword, duplicateReason)
                        return@withTimeoutOrNull
                    }

                    val imageUrls = mutableListOf<String>()
                    val message   = TelegramUploader.buildResultMessage(req.keyword, toSubmit)
                    Log.d(TAG, "TELEGRAM messageLen=${message.length} lines=${toSubmit.size}")
                    screenshotPaths.take(1).forEachIndexed { i, path ->
                        Log.d(TAG, "  upload[$i] $path")
                        val url = TelegramUploader.upload(path)
                        if (url.isNotBlank()) {
                            imageUrls.add(url)
                            Log.d(TAG, "  upload[$i] OK -> $url")
                        } else {
                            Log.w(TAG, "  upload[$i] FAILED path=$path")
                        }
                    }
                    if (imageUrls.isEmpty()) {
                        failSubmit(requestId, req.keyword, "Submit fail: upload anh loi")
                        return@withTimeoutOrNull
                    }
                    if (message.isNotBlank()) {
                        if (imageUrls.isNotEmpty()) {
                            Log.d(TAG, "TELEGRAM send text after image upload")
                            val sentText = TelegramUploader.sendMessage(message)
                            Log.d(TAG, "TELEGRAM textMessage sent=$sentText")
                        } else {
                            Log.w(TAG, "TELEGRAM skip text message because image upload failed")
                        }
                    }

                    Log.d(TAG, "SUBMIT reqId=$requestId totalParsed=$totalCount submitCount=${toSubmit.size} images=${imageUrls.size} publicIp=$publicIp")
                    if (BuildConfig.DEBUG) {
                        toSubmit.forEachIndexed { i, r ->
                            Log.d(TAG, "  item[$i] top=${r.rank} domain=${r.domain} url=${r.url}")
                        }
                    }

                    if (req.isTest) {
                        Log.d(TAG, "TEST keyword - skip socket submit, mark done")
                        SearchBridge.emitSubmitSuccess(requestId)
                        showNotif("Test done: ${req.keyword}")
                    } else {
                        val sent = activeClient?.submit(
                            requestId, toSubmit, imageUrls, publicIp, sourceName, checkedAt,
                        ) ?: false
                        if (sent) {
                            SearchBridge.emitSubmitSuccess(requestId)
                            showNotif("Da gui ket qua - cho keyword tiep theo")
                        } else {
                            failSubmit(requestId, req.keyword, "Submit fail: socket send loi")
                        }
                    }
                } ?: failSubmit(requestId, req.keyword, "Submit fail: timeout 90s")
            }
        }
    }

    private suspend fun failSubmit(requestId: String, keyword: String, reason: String) {
        Log.w(TAG, "$reason reqId=$requestId keyword='$keyword'")
        SearchBridge.emitSubmitFailure(requestId, reason)
        showNotif(reason)
    }

    private fun duplicateTopReason(items: List<SearchResult>): String? {
        val topItems = items.take(10)
        if (topItems.size < 5) return null

        val counts = topItems
            .map { normalizeDomain(it.domain) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        val worst = counts.maxByOrNull { it.value } ?: return null

        val duplicateLimit = if (topItems.size >= 8) 7 else topItems.size
        if (worst.value >= duplicateLimit) {
            return "Submit fail: top trung domain ${worst.key} ${worst.value}/${topItems.size}"
        }
        if (topItems.size >= 8 && counts.size <= 2 && worst.value >= 5) {
            return "Submit fail: top lap bat thuong ${worst.key} ${worst.value}/${topItems.size}"
        }
        return null
    }

    private fun normalizeDomain(domain: String): String =
        domain.trim()
            .lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .removePrefix("www.")

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun startMainActivity() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TopSearch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun showNotif(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "TopSearch Socket",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    companion object {
        fun start(ctx: Context) {
            val intent = Intent(ctx, SearchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
