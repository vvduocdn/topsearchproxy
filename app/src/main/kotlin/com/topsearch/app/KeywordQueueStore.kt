package com.topsearch.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG       = "KeywordQueueStore"
private const val FILE_NAME = "keyword_queue.json"

private fun todayString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

object KeywordQueueStore {

    data class Entry(
        val requestId: String,
        val keyword:   String,
        val proxy:     String,
        val country:   Int,
        val status:    CheckStatus = CheckStatus.PENDING,
        val retryCount: Int         = 0,
        val errorMessage: String    = "",
        val completedAt: String     = "",
        val queuedAt: String        = todayString(),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun save(ctx: Context, entries: List<Entry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("requestId", e.requestId)
                    put("keyword",   e.keyword)
                    put("proxy",     e.proxy)
                    put("country",   e.country)
                    put("status",    e.status.name)
                    put("retryCount", e.retryCount)
                    put("errorMessage", e.errorMessage)
                    put("completedAt", e.completedAt)
                    put("queuedAt", e.queuedAt)
                })
            }
            Log.d(TAG, "save entries=${entries.size}")
            entries.forEachIndexed { index, e ->
                Log.d(
                    TAG,
                    "  save[$index] reqId=${e.requestId} keyword='${e.keyword}' " +
                        "status=${e.status} retry=${e.retryCount} queuedAt=${e.queuedAt}",
                )
            }
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    fun load(ctx: Context): List<Entry>? {
        val f = file(ctx)
        if (!f.exists()) {
            Log.d(TAG, "load: file not found")
            return null
        }
        return try {
            val arr = JSONArray(f.readText())
            val entries = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    requestId = o.getString("requestId"),
                    keyword   = o.getString("keyword"),
                    proxy     = o.optString("proxy", ""),
                    country   = o.optInt("country", 1),
                    status    = runCatching {
                        CheckStatus.valueOf(o.getString("status"))
                    }.getOrElse { CheckStatus.PENDING },
                    retryCount = o.optInt("retryCount", 0),
                    errorMessage = o.optString("errorMessage", ""),
                    completedAt = o.optString("completedAt", ""),
                    queuedAt = o.optString("queuedAt", todayString()),
                )
            }
            Log.d(TAG, "load entries=${entries.size}")
            entries
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
            null
        }
    }

    fun updateStatus(ctx: Context, requestId: String, status: CheckStatus) {
        val entries = load(ctx) ?: return
        save(ctx, entries.map { if (it.requestId == requestId) it.copy(status = status) else it })
    }

    fun updateEntry(
        ctx: Context,
        requestId: String,
        status: CheckStatus? = null,
        retryCount: Int? = null,
        errorMessage: String? = null,
        completedAt: String? = null,
    ) {
        val entries = load(ctx) ?: return
        save(ctx, entries.map {
            if (it.requestId == requestId) {
                it.copy(
                    status = status ?: it.status,
                    retryCount = retryCount ?: it.retryCount,
                    errorMessage = errorMessage ?: it.errorMessage,
                    completedAt = completedAt ?: it.completedAt,
                )
            } else {
                it
            }
        })
    }

    fun clear(ctx: Context) {
        try {
            val deleted = file(ctx).delete()
            Log.w(TAG, "clear deleted=$deleted")
        } catch (e: Exception) {
            Log.e(TAG, "clear failed: ${e.message}")
        }
    }

    fun deleteDay(ctx: Context, queuedAt: String) {
        val entries = load(ctx) ?: return
        val next = entries.filterNot { it.queuedAt == queuedAt }
        Log.w(TAG, "deleteDay queuedAt=$queuedAt before=${entries.size} after=${next.size}")
        save(ctx, next)
    }

    fun canResume(e: Entry): Boolean {
        if (!isToday(e)) return false
        if (e.status == CheckStatus.DONE) return false
        if (e.status != CheckStatus.ERROR) return true
        val msg = e.errorMessage.lowercase()
        // proxy/captcha/block: vẫn cho resume nhưng tối đa 1 lần
        if ("proxy" in msg || "captcha" in msg || "block" in msg) return e.retryCount < 1
        return e.retryCount < 3
    }

    fun isToday(e: Entry): Boolean = e.queuedAt == todayString()

    fun hasPending(ctx: Context): Boolean =
        load(ctx)?.any { isToday(it) && it.status != CheckStatus.DONE } == true
}
