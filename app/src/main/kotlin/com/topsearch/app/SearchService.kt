package com.topsearch.app

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

private const val TAG        = "SearchService"
private const val CHANNEL_ID = "topsearch_socket"
private const val NOTIF_ID   = 1001
private const val WS_URL     =
    "wss://api.domainstatus.live/hubs/mobile-check?secret=ds-socket-9k3m7x2q5w8e1r4t6y0u"

class SearchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeClient: SignalRClient? = null
    private var sourceName: String = ""

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        sourceName = "${Build.MANUFACTURER} ${Build.MODEL} (${androidId.take(8)})"
        Log.d(TAG, "sourceName = $sourceName")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif("Đang kết nối..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotif("Đang kết nối..."))
        }
        scope.launch { connectLoop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        activeClient?.disconnect()
        super.onDestroy()
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    private suspend fun connectLoop() {
        while (scope.isActive) {
            val gone = kotlinx.coroutines.CompletableDeferred<Unit>()
            val client = SignalRClient(
                url            = WS_URL,
                onKeyword      = ::onKeyword,
                onDisconnected = { gone.complete(Unit) },
            )
            activeClient = client
            client.connect()
            SearchBridge.isConnected.value = true
            showNotif("Đã kết nối — đang chờ keyword")
            gone.await()   // block until WS closes unexpectedly
            SearchBridge.isConnected.value = false
            showNotif("Mất kết nối — đang kết nối lại...")
            Log.w(TAG, "Disconnected — retry in 5s")
            delay(5_000)
        }
    }

    // ── Keyword handler ────────────────────────────────────────────────────────

    private fun onKeyword(requestId: String, keyword: String, proxy: String, country: Int) {
        Log.d(TAG, "CheckKeyword: \"$keyword\" reqId=$requestId proxy=$proxy")
        showNotif("Đang search: $keyword")

        // Register result callback before emitting so ViewModel can call it
        SearchBridge.registerCallback(requestId) { results, screenshotPaths, publicIp ->
            scope.launch {
                Log.d(TAG, "▶ CALLBACK fired reqId=$requestId publicIp=$publicIp")
                Log.d(TAG, "  results count = ${results.size}")
                Log.d(TAG, "  screenshotPaths count = ${screenshotPaths.size}")
                screenshotPaths.forEachIndexed { i, p ->
                    val file = java.io.File(p)
                    Log.d(TAG, "  path[$i] = $p  exists=${file.exists()}  size=${file.length()}B")
                }

                Log.d(TAG, "▶ TELEGRAM UPLOAD START (${screenshotPaths.size} files)")
                val imageUrls = mutableListOf<String>()
                screenshotPaths.forEachIndexed { i, path ->
                    Log.d(TAG, "  uploading[$i] $path …")
                    val url = TelegramUploader.upload(path)
                    if (url.isNotBlank()) {
                        Log.d(TAG, "  ✓ uploaded[$i] → $url")
                        imageUrls += url
                    } else {
                        Log.w(TAG, "  ✗ upload FAILED[$i] path=$path")
                    }
                }
                Log.d(TAG, "▶ TELEGRAM UPLOAD DONE: ${imageUrls.size}/${screenshotPaths.size} succeeded")
                imageUrls.forEachIndexed { i, u -> Log.d(TAG, "  url[$i] = $u") }

                Log.d(TAG, "▶ SUBMIT reqId=$requestId items=${results.size} images=${imageUrls.size} publicIp=$publicIp")
                results.forEachIndexed { i, r ->
                    Log.d(TAG, "  result[$i] rank=${r.rank} domain=${r.domain} url=${r.url}")
                }
                activeClient?.submit(requestId, results, imageUrls, publicIp, sourceName)
                    ?: Log.e(TAG, "  ✗ activeClient is null — result NOT sent!")
                showNotif("Đã gửi kết quả — chờ keyword tiếp theo")
            }
        }

        scope.launch {
            SearchBridge.incoming.emit(
                SearchBridge.SocketRequest(requestId, keyword, proxy, country)
            )
        }

        // Bring MainActivity to foreground so WebView can run
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun showNotif(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text))
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
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
