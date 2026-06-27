package com.topsearch.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SignalR"

// ASCII 30 - SignalR JSON protocol message delimiter
private const val RS = '\u001E'

/**
 * Minimal SignalR JSON protocol client over OkHttp WebSocket.
 *
 * Handshake: client sends {"protocol":"json","version":1}RS, server replies {}RS.
 * Invocations: type=1 messages with "target" and "arguments".
 * Ping/Pong: OkHttp 20s TCP ping + client-initiated SignalR type=6 every 15s after handshake.
 *
 * Thread-safety notes:
 *  - [ws] and [handshakeDone] are @Volatile: written on OkHttp callbacks, read on any caller thread.
 *  - [http] is shared from outside; caller must not shut it down while this client is active.
 *  - [onConnected] / [onDisconnected] fire on OkHttp's dispatcher thread — callers must be thread-safe.
 */
class SignalRClient(
    private val url:            String,
    private val http:           OkHttpClient,
    private val onConnected:    ()                                      -> Unit = {},
    private val onBatch:        (List<SearchBridge.SocketRequest>)      -> Unit = {},
    private val onDisconnected: ()                                      -> Unit = {},
) {
    // @Volatile: visible across threads without synchronization.
    @Volatile private var ws:            WebSocket? = null
    @Volatile private var handshakeDone: Boolean    = false

    // One scope per SignalRClient instance — cancelled in disconnect().
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        handshakeDone = false
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, Listener())
    }

    fun submit(
        requestId:  String,
        items:      List<SearchResult>,
        imageUrls:  List<String> = emptyList(),
        publicIp:   String       = "",
        sourceName: String       = "",
        checkedAt:  Long         = 0L,
    ): Boolean {
        val top10    = items.take(10)
        val itemsArr = JSONArray()
        top10.forEach { r ->
            itemsArr.put(JSONObject().apply {
                put("top",    r.rank)
                put("url",    r.url)
                put("domain", r.domain)
            })
        }

        val payload = JSONObject().apply {
            put("requestId",      requestId)
            put("items",          itemsArr)
            put("mobileImageUrl", imageUrls.firstOrNull()?.ifBlank { null } ?: JSONObject.NULL)
            put("publicIp",       publicIp.ifBlank  { null } ?: JSONObject.NULL)
            put("sourceName",     sourceName.ifBlank { null } ?: JSONObject.NULL)
            put("checkedAt",      if (checkedAt > 0L) checkedAt else JSONObject.NULL)
        }

        val msg = JSONObject().apply {
            put("type",      1)
            put("target",    "SubmitMobileResult")
            put("arguments", JSONArray().apply { put(payload) })
        }.toString() + RS

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Submit reqId=$requestId items=${top10.size} checkedAt=$checkedAt")
            top10.forEachIndexed { i, r ->
                Log.d(TAG, "  [${i + 1}] rank=${r.rank} domain=${r.domain} url=${r.url}")
            }
        }

        val sent = ws?.send(msg) ?: false
        if (!sent) Log.e(TAG, "Submit FAILED reqId=$requestId (ws=${if (ws == null) "null" else "closed"})")
        return sent
    }

    fun disconnect() {
        scope.cancel()
        ws?.close(1000, "shutdown")
        ws = null
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Mask query params (contains secret key) to avoid leaking into logcat.
            val safeUrl = response.request.url.let { "${it.scheme}://${it.host}${it.encodedPath}?***" }
            Log.d(TAG, "WS open (${response.code}) $safeUrl — sending handshake")
            webSocket.send("""{"protocol":"json","version":1}$RS""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onMessage len=${text.length} preview=${text.take(120).replace(RS.toString(), "<RS>")}")
            }
            text.split(RS).filter { it.isNotBlank() }.forEach(::handleFrame)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            handshakeDone = false
            ws = null
            onDisconnected()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS closed ($code) $reason")
            handshakeDone = false
            ws = null
            onDisconnected()
        }
    }

    // ── Frame handling ────────────────────────────────────────────────────────

    private fun handleFrame(raw: String) {
        try {
            val json = JSONObject(raw)

            // First frame after onOpen is always the handshake response.
            if (!handshakeDone) {
                val error = json.optString("error")
                if (error.isNotBlank()) {
                    // Server rejected the protocol (e.g. version mismatch).
                    Log.e(TAG, "Handshake rejected by server: $error")
                    ws?.close(1002, "handshake rejected")
                    return
                }
                handshakeDone = true
                Log.d(TAG, "Handshake OK")
                // Client-initiated SignalR pings every 15 s — server closes if it hears nothing.
                scope.launch {
                    while (isActive) {
                        delay(15_000)
                        if (handshakeDone) {
                            ws?.send("""{"type":6}$RS""")
                            if (BuildConfig.DEBUG) Log.d(TAG, "Client → Ping")
                        }
                    }
                }
                onConnected()
                return
            }

            when (json.optInt("type")) {
                1 -> handleInvocation(json)
                6 -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Ping → Pong")
                    ws?.send("""{"type":6}$RS""")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame parse error: ${e.message}")
        }
    }

    private fun handleInvocation(json: JSONObject) {
        when (val target = json.optString("target")) {
            "CheckKeywords" -> handleCheckKeywords(json)
            "CheckKeyword"  -> handleCheckKeyword(json)
            else            -> Log.w(TAG, "Unknown invocation target: $target")
        }
    }

    private fun handleCheckKeywords(json: JSONObject) {
        val args  = json.optJSONArray("arguments") ?: return
        val arr   = args.optJSONArray(0)           ?: return
        val batch = mutableListOf<SearchBridge.SocketRequest>()
        for (i in 0 until arr.length()) {
            val item      = arr.optJSONObject(i) ?: continue
            val requestId = item.optString("requestId")
            val keyword   = item.optString("keyword")
            val proxy     = item.optString("proxy")
            val country   = item.optInt("country", 1)
            if (keyword.isBlank() || requestId.isBlank()) continue
            batch += SearchBridge.SocketRequest(requestId, keyword, proxy, country)
        }
        if (batch.isEmpty()) return

        Log.d(TAG, "CheckKeywords: ${batch.size} item(s)")
        if (BuildConfig.DEBUG) {
            batch.forEachIndexed { i, r ->
                Log.d(TAG, "  [$i] kw=\"${r.keyword}\" proxy=${r.proxy} country=${r.country} reqId=${r.requestId}")
            }
        }
        onBatch(batch)
    }

    private fun handleCheckKeyword(json: JSONObject) {
        val args    = json.optJSONArray("arguments") ?: return
        val payload = args.optJSONObject(0)          ?: return
        val requestId = payload.optString("requestId")
        val keyword   = payload.optString("keyword")
        val proxy     = payload.optString("proxy")
        val country   = payload.optInt("country", 1)
        if (keyword.isBlank() || requestId.isBlank()) return
        Log.d(TAG, "CheckKeyword: kw=\"$keyword\" reqId=$requestId")
        onBatch(listOf(SearchBridge.SocketRequest(requestId, keyword, proxy, country)))
    }
}
