package com.topsearch.app

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "SignalR"

// ASCII 30 — SignalR JSON protocol message delimiter
private const val RS = ''

/**
 * Minimal SignalR JSON protocol client over OkHttp WebSocket.
 *
 * Handshake: client sends {"protocol":"json","version":1}RS, server replies {}RS.
 * Invocations: type=1 messages with "target" and "arguments".
 * Ping/Pong: type=6 kept alive with 20-second OkHttp ping interval.
 */
class SignalRClient(
    private val url:            String,
    private val onKeyword:      (requestId: String, keyword: String, proxy: String, country: Int) -> Unit,
    private val onBatch:        (List<SearchBridge.SocketRequest>) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // no timeout — long-lived connection
        .build()

    private var ws:            WebSocket? = null
    private var handshakeDone: Boolean   = false

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, Listener())
    }

    fun submit(
        requestId:  String,
        items:      List<SearchResult>,
        imageUrls:  List<String> = emptyList(),
        publicIp:   String       = "",
        sourceName: String       = "",
    ) {
        val top10 = items.take(10)
        val itemsArr = JSONArray()
        top10.forEach { r ->
            itemsArr.put(JSONObject().apply {
                put("top",    r.rank)
                put("url",    r.url)
                put("domain", r.domain)
            })
        }

        val imageUrl = imageUrls.firstOrNull() ?: ""
        val payload = JSONObject().apply {
            put("requestId",    requestId)
            put("items",        itemsArr)
            put("mobileImageUrl", if (imageUrl.isNotBlank()) imageUrl else JSONObject.NULL)
            put("publicIp",     if (publicIp.isNotBlank())   publicIp   else JSONObject.NULL)
            put("sourceName",   if (sourceName.isNotBlank()) sourceName else JSONObject.NULL)
        }

        val msg = JSONObject().apply {
            put("type",      1)
            put("target",    "SubmitMobileResult")
            put("arguments", JSONArray().apply { put(payload) })
        }.toString() + RS

        Log.d(TAG, "▶ SUBMIT FRAME reqId=$requestId items=${top10.size} imageUrl=$imageUrl publicIp=$publicIp sourceName=$sourceName")
        top10.forEachIndexed { i, r -> Log.d(TAG, "  [${i+1}] rank=${r.rank} domain=${r.domain} url=${r.url}") }
        Log.d(TAG, "  RAW MSG = ${msg.take(400)}")
        val sent = ws?.send(msg) ?: false
        Log.d(TAG, if (sent) "  ✓ ws.send OK" else "  ✗ ws.send FAILED (ws=${ws})")
    }


    fun disconnect() {
        ws?.close(1000, "shutdown")
    }

    // ── WebSocket listener ─────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected → handshake")
            webSocket.send("""{"protocol":"json","version":1}$RS""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "← RAW: $text")
            text.split(RS).filter { it.isNotBlank() }.forEach(::handleFrame)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            handshakeDone = false
            onDisconnected()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed $code: $reason")
            handshakeDone = false
            if (code != 1000) onDisconnected()
        }
    }

    // ── Frame handling ─────────────────────────────────────────────────────────

    private fun handleFrame(raw: String) {
        try {
            val json = JSONObject(raw)

            if (!handshakeDone) {
                handshakeDone = true
                Log.d(TAG, "Handshake OK — server: $raw")
                return
            }

            val type = json.optInt("type")
            Log.d(TAG, "Frame type=$type target=${json.optString("target")}")
            when (type) {
                1 -> handleInvocation(json)
                6 -> { Log.d(TAG, "Ping → pong"); ws?.send("""{"type":6}$RS""") }
                else -> Log.d(TAG, "Unhandled frame: $raw")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame error: ${e.message} | raw=$raw")
        }
    }

    private fun handleInvocation(json: JSONObject) {
        when (json.optString("target")) {
            "CheckKeywords" -> handleCheckKeywords(json)
            "CheckKeyword"  -> handleCheckKeyword(json)
        }
    }

    private fun handleCheckKeywords(json: JSONObject) {
        val args = json.optJSONArray("arguments") ?: return
        val arr  = args.optJSONArray(0) ?: return
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
        Log.d(TAG, "CheckKeywords: ${batch.size} keywords")
        batch.forEachIndexed { i, r -> Log.d(TAG, "  [$i] \"${r.keyword}\" proxy=${r.proxy} reqId=${r.requestId}") }
        // Emit batch first so UI sets PENDING state before keywords arrive in queue
        onBatch(batch)
        batch.forEach { r -> onKeyword(r.requestId, r.keyword, r.proxy, r.country) }
    }

    private fun handleCheckKeyword(json: JSONObject) {
        val args    = json.optJSONArray("arguments") ?: return
        val payload = args.optJSONObject(0) ?: return
        val requestId = payload.optString("requestId")
        val keyword   = payload.optString("keyword")
        val proxy     = payload.optString("proxy")
        val country   = payload.optInt("country", 1)
        Log.d(TAG, "CheckKeyword (single): \"$keyword\" proxy=$proxy reqId=$requestId")
        onBatch(listOf(SearchBridge.SocketRequest(requestId, keyword, proxy, country)))
        onKeyword(requestId, keyword, proxy, country)
    }
}
