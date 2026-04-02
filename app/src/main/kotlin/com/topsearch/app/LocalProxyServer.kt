package com.topsearch.app

import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "LocalProxy"
private const val BUF  = 8192

/**
 * Local HTTP proxy chạy trên 127.0.0.1:random_port.
 *
 * Problem: Android WebView KHÔNG gọi onReceivedHttpAuthRequest cho HTTPS CONNECT tunnel.
 * Solution: WebView trỏ vào proxy này (no auth) → proxy tự thêm Proxy-Authorization
 *           khi forward tới remote proxy (IPFoxy).
 *
 * Flow HTTPS:
 *   WebView → CONNECT google.com:443 → LocalProxy
 *   LocalProxy → CONNECT google.com:443 + Proxy-Authorization → IPFoxy
 *   IPFoxy → 200 Connection established
 *   LocalProxy → 200 Connection established → WebView
 *   [Tunnel relay bidirectional]
 */
class LocalProxyServer(
    private val remoteHost: String,
    private val remotePort: Int,
    private val user: String,
    private val pass: String,
) {
    private var serverSocket: ServerSocket? = null

    val localPort: Int get() = serverSocket?.localPort ?: 0

    private val authHeader: String by lazy {
        val b64 = Base64.encodeToString(
            "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        "Proxy-Authorization: Basic $b64"
    }

    fun start() {
        val ss = ServerSocket(0)   // OS chọn port trống
        serverSocket = ss
        Log.d(TAG, "Started :${ss.localPort} → $remoteHost:$remotePort")
        thread(isDaemon = true, name = "lproxy-accept") {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    thread(isDaemon = true, name = "lproxy-conn") { handle(client) }
                } catch (_: Exception) {
                    if (!ss.isClosed) Log.w(TAG, "Accept error")
                }
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        Log.d(TAG, "Stopped")
    }

    // ── Per-connection handler ────────────────────────────────────────────────

    private fun handle(client: Socket) {
        try {
            client.use {
                val cin  = client.getInputStream()
                val cout = client.getOutputStream()
                val hdrs = readHeaders(cin)
                if (hdrs.isEmpty()) return
                val req = hdrs[0]
                if (req.startsWith("CONNECT ")) handleConnect(req, cin, cout)
                else                            handleHttp(req, hdrs, cin, cout)
            }
        } catch (_: Exception) {}
    }

    /** HTTPS CONNECT tunnel */
    private fun handleConnect(req: String, cin: InputStream, cout: OutputStream) {
        val target = req.split(" ").getOrNull(1) ?: return
        try {
            Socket(remoteHost, remotePort).use { remote ->
                val rin  = remote.getInputStream()
                val rout = remote.getOutputStream()

                // CONNECT + auth → remote proxy
                rout.write("$req\r\n$authHeader\r\nHost: $target\r\n\r\n".toByteArray())
                rout.flush()

                // Đọc response từ IPFoxy
                val resp = readHeaders(rin)
                val status = resp.firstOrNull() ?: ""
                Log.d(TAG, "CONNECT $target → $status")

                if (!status.contains("200")) {
                    cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); cout.flush()
                    return
                }

                // Báo WebView tunnel OK
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()

                // Relay bytes 2 chiều
                relay(cin, cout, rin, rout)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CONNECT error: $target", e)
        }
    }

    /** HTTP plain request */
    private fun handleHttp(req: String, hdrs: List<String>, cin: InputStream, cout: OutputStream) {
        try {
            Socket(remoteHost, remotePort).use { remote ->
                val rout = remote.getOutputStream()
                val rin  = remote.getInputStream()

                val sb = StringBuilder()
                sb.append("$req\r\n").append("$authHeader\r\n")
                for (i in 1 until hdrs.size) {
                    if (!hdrs[i].startsWith("Proxy-Authorization:", ignoreCase = true))
                        sb.append("${hdrs[i]}\r\n")
                }
                sb.append("\r\n")
                rout.write(sb.toString().toByteArray()); rout.flush()

                relay(cin, cout, rin, rout)
            }
        } catch (_: Exception) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun relay(cin: InputStream, cout: OutputStream,
                      rin: InputStream, rout: OutputStream) {
        val t = thread(isDaemon = true) {
            try { cin.copyTo(rout, BUF) } catch (_: Exception) {}
            runCatching { rout.close() }
        }
        try { rin.copyTo(cout, BUF) } catch (_: Exception) {}
        runCatching { cout.close() }
        t.join(1_000)
    }

    /** Đọc HTTP headers cho đến dòng trống */
    private fun readHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val sb    = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                if (line.isEmpty()) break
                lines.add(line)
                sb.clear()
            } else {
                sb.append(b.toChar())
            }
        }
        return lines
    }
}
