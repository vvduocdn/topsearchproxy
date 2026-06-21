package com.topsearch.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG       = "TelegramUploader"
private const val BOT_TOKEN = "8642171462:AAEq1woSJt7G7P-VfBPe6KDdzH1xSFQd5oo"
private const val CHAT_ID   = "-1003983539533"
private const val FILE_BASE = "https://api.telegram.org/file/bot$BOT_TOKEN"
private const val BOT_BASE  = "https://api.telegram.org/bot$BOT_TOKEN"

object TelegramUploader {

    /**
     * Upload all local JPEGs, return list of public URLs (skips failures).
     * Sends with optional caption for the first image.
     */
    fun uploadAll(paths: List<String>, caption: String = ""): List<String> =
        paths.mapIndexedNotNull { i, p ->
            upload(p, if (i == 0) caption else "").takeIf { it.isNotBlank() }
        }

    /**
     * Upload local JPEG to Telegram channel, return public HTTP URL.
     * If caption is non-blank, sends it with the first image via sendDocument caption.
     * Blank on failure.
     */
    fun upload(filePath: String, caption: String = ""): String {
        if (filePath.isBlank()) return ""
        val file = File(filePath)
        if (!file.exists()) { Log.e(TAG, "File not found: $filePath"); return "" }
        Log.d(TAG, "upload: ${file.name}  size=${file.length()}B")
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
