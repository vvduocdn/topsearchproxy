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

    /** Upload all local JPEGs, return list of public URLs (skips failures). */
    fun uploadAll(paths: List<String>): List<String> =
        paths.mapNotNull { p -> upload(p).takeIf { it.isNotBlank() } }

    /** Upload local JPEG to Telegram channel, return public HTTP URL. Blank on failure. */
    fun upload(filePath: String): String {
        if (filePath.isBlank()) return ""
        val file = File(filePath)
        if (!file.exists()) { Log.e(TAG, "File not found: $filePath"); return "" }
        Log.d(TAG, "upload: ${file.name}  size=${file.length()}B")
        return try {
            val fileId = sendPhoto(file) ?: return ""
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

    private fun sendPhoto(file: File): String? {
        val boundary = "TgBound${System.currentTimeMillis()}"
        val conn = (URL("$BOT_BASE/sendPhoto").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput       = true
            connectTimeout = 15_000
            readTimeout    = 30_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        conn.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$CHAT_ID\r\n".toByteArray())
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"${file.name}\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }

        val code = conn.responseCode
        val body = if (code == 200) conn.inputStream.bufferedReader().readText()
                   else conn.errorStream?.bufferedReader()?.readText() ?: ""
        Log.d(TAG, "  sendPhoto HTTP $code body_len=${body.length}")
        val json = JSONObject(body)
        if (!json.optBoolean("ok")) { Log.e(TAG, "sendPhoto($code): $body"); return null }

        val photos = json.getJSONObject("result").getJSONArray("photo")
        return photos.getJSONObject(photos.length() - 1).getString("file_id")
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
