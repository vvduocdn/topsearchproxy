package com.topsearch.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG       = "KeywordQueueStore"
private const val FILE_NAME = "keyword_queue.json"

object KeywordQueueStore {

    data class Entry(
        val requestId: String,
        val keyword:   String,
        val proxy:     String,
        val country:   Int,
        val status:    CheckStatus = CheckStatus.PENDING,
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
                })
            }
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    fun load(ctx: Context): List<Entry>? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    requestId = o.getString("requestId"),
                    keyword   = o.getString("keyword"),
                    proxy     = o.optString("proxy", ""),
                    country   = o.optInt("country", 1),
                    status    = runCatching {
                        CheckStatus.valueOf(o.getString("status"))
                    }.getOrElse { CheckStatus.PENDING },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
            null
        }
    }

    fun updateStatus(ctx: Context, requestId: String, status: CheckStatus) {
        val entries = load(ctx) ?: return
        save(ctx, entries.map { if (it.requestId == requestId) it.copy(status = status) else it })
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }

    fun hasPending(ctx: Context): Boolean =
        load(ctx)?.any { it.status != CheckStatus.DONE } == true
}
