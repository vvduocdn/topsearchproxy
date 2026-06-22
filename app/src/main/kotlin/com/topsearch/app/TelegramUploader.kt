package com.topsearch.app

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TAG       = "TelegramUploader"
private const val BOT_TOKEN = "8642171462:AAEq1woSJt7G7P-VfBPe6KDdzH1xSFQd5oo"
private const val CHAT_ID   = "-1003983539533"
private const val FILE_BASE = "https://api.telegram.org/file/bot$BOT_TOKEN"
private const val BOT_BASE  = "https://api.telegram.org/bot$BOT_TOKEN"

object TelegramUploader {

    private val uploadMutex = Mutex()

    fun buildResultMessage(keyword: String, items: List<SearchResult>, maxItems: Int = 10): String {
        if (items.isEmpty()) return ""
        val lines = mutableListOf("Keyword: $keyword")
        for (r in items.take(maxItems)) {
            val line = "[${r.rank}, ${r.domain}, ${r.url}]"
            val next = (lines + line).joinToString("\n")
            if (next.length > 950) {
                lines += "..."
                break
            }
            lines += line
        }
        return lines.joinToString("\n")
    }

    suspend fun uploadFirstThenSendResults(paths: List<String>, keyword: String, items: List<SearchResult>): String {
        val firstPath = paths.firstOrNull().orEmpty()
        val imageUrl = upload(firstPath)
        val message = buildResultMessage(keyword, items)
        if (imageUrl.isNotBlank() && message.isNotBlank()) {
            val sent = sendMessage(message)
            Log.d(TAG, "result message sent=$sent")
        } else if (message.isNotBlank()) {
            Log.w(TAG, "skip result message because image upload failed")
        }
        return imageUrl
    }

    /**
     * Upload all local JPEGs, return list of public URLs (skips failures).
     * Sends with optional caption for the first image.
     */
    suspend fun uploadAll(paths: List<String>, caption: String = ""): List<String> =
        paths.mapIndexedNotNull { i, p ->
            upload(p, if (i == 0) caption else "").takeIf { it.isNotBlank() }
        }

    /**
     * Upload local JPEG to Telegram channel, return public HTTP URL.
     * Serialized via mutex to avoid concurrent uploads hitting Telegram rate limits.
     * Retries once with 3s delay on failure. Blank on final failure.
     */
    suspend fun upload(filePath: String, caption: String = ""): String {
        if (filePath.isBlank()) return ""
        val file = File(filePath)
        if (!file.exists()) { Log.e(TAG, "File not found: $filePath"); return "" }
        Log.d(TAG, "upload: ${file.name}  size=${file.length()}B captionLen=${caption.length}")
        return uploadMutex.withLock {
            doUpload(file, caption).let { result ->
                if (result.isNotBlank()) result
                else {
                    Log.w(TAG, "upload retry after 3s: ${file.name}")
                    kotlinx.coroutines.delay(3_000)
                    doUpload(file, caption)
                }
            }
        }
    }

    private fun doUpload(file: File, caption: String): String {
        return try {
            val fileId = sendDocument(file, caption) ?: return ""
            Log.d(TAG, "  file_id = $fileId")
            val tgPath = getFilePath(fileId) ?: return ""
            Log.d(TAG, "  tg_path = $tgPath")
            val url = "$FILE_BASE/$tgPath"
            Log.d(TAG, "  public_url = $url")
            url
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}")
            ""
        }
    }

    fun sendMessage(text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val body = JSONObject()
                .put("chat_id", CHAT_ID)
                .put("text", text)
                .put("disable_web_page_preview", true)
                .toString()
            val conn = (URL("$BOT_BASE/sendMessage").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val response = if (code == 200) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: ""
            Log.d(TAG, "sendMessage HTTP $code body=${response.take(500)}")
            val json = JSONObject(response)
            if (!json.optBoolean("ok")) {
                Log.e(TAG, "sendMessage($code): $response")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
            false
        }
    }

    // sendDocument thay vì sendPhoto — không có giới hạn dimensions, hỗ trợ ảnh full page cao
    // caption: gửi kèm text hiển thị dưới ảnh trên Telegram
    private fun sendDocument(file: File, caption: String = ""): String? {
        val boundary = "TgBound${System.currentTimeMillis()}"
        val conn = (URL("$BOT_BASE/sendDocument").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput       = true
            connectTimeout = 15_000
            readTimeout    = 60_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        conn.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$CHAT_ID\r\n".toByteArray())
            if (caption.isNotBlank()) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n${caption}\r\n".toByteArray())
            }
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }

        val code = conn.responseCode
        val body = if (code == 200) conn.inputStream.bufferedReader().readText()
                   else conn.errorStream?.bufferedReader()?.readText() ?: ""
        Log.d(TAG, "  sendDocument HTTP $code body_len=${body.length}")
        val json = JSONObject(body)
        if (!json.optBoolean("ok")) { Log.e(TAG, "sendDocument($code): $body"); return null }

        return json.getJSONObject("result").getJSONObject("document").getString("file_id")
    }

    private fun getFilePath(fileId: String): String? {
        val conn = (URL("$BOT_BASE/getFile?file_id=$fileId").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout    = 10_000
        }
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        if (!json.optBoolean("ok")) { Log.e(TAG, "getFile: $body"); return null }
        return json.getJSONObject("result").getString("file_path")
    }

}
