package com.topsearch.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CapSolverHelper {

    private const val TAG      = "CapSolver"
    private const val API_KEY  = "CAP-FC72311FEDC53913F1FE4BA3F705EBD11B8D52503D7FF63D52C89B79E6AF5E38"
    private const val BASE_URL = "https://api.capsolver.com"
    private const val GOOGLE_SORRY_SITEKEY = "6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-"

    private val JSON_TYPE = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Giải reCAPTCHA v2.
     * - Có proxy → ReCaptchaV2Task với proxy string (cùng IP WebView → Google accept cookie)
     * - Không proxy → ReCaptchaV2TaskProxyless
     * - dataS: giá trị data-s từ DOM trang sorry (one-time token của Google)
     */
    suspend fun solve(
        websiteUrl: String,
        siteKey:    String?    = null,
        dataS:      String?    = null,
        proxy:      ProxyInfo? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val key = siteKey?.takeIf { it.isNotBlank() } ?: GOOGLE_SORRY_SITEKEY
                Log.d(TAG, "solve → url=$websiteUrl siteKey=$key dataS=${dataS?.take(20)} proxy=${proxy?.hostPort ?: "none"}")

                val taskId = createTask(websiteUrl, key, dataS, proxy) ?: return@withContext null
                Log.d(TAG, "taskId=$taskId, polling…")

                repeat(40) {
                    delay(3_000)
                    val token = getResult(taskId)
                    if (token != null) {
                        Log.d(TAG, "solved! token length=${token.length}")
                        return@withContext token
                    }
                }

                Log.w(TAG, "timeout — không giải được sau 120s")
                null
            } catch (e: Exception) {
                Log.e(TAG, "solve error: ${e.message}")
                null
            }
        }

    private fun createTask(
        websiteUrl: String,
        siteKey:    String,
        dataS:      String?,
        proxy:      ProxyInfo?,
    ): String? {
        val taskObj = JSONObject().apply {
            if (proxy != null) {
                // Format proxy string giống bên web: "http:ip:port:user:pass"
                val proxyStr = if (proxy.requiresAuth)
                    "http:${proxy.host}:${proxy.port}:${proxy.user}:${proxy.pass}"
                else
                    "http:${proxy.host}:${proxy.port}"
                put("type", "ReCaptchaV2Task")
                put("proxy", proxyStr)
            } else {
                put("type", "ReCaptchaV2TaskProxyless")
            }
            put("websiteURL", websiteUrl)
            put("websiteKey", siteKey)
            if (!dataS.isNullOrBlank()) put("recaptchaDataSValue", dataS)
        }

        val body = JSONObject().apply {
            put("clientKey", API_KEY)
            put("task", taskObj)
        }.toString()

        Log.d(TAG, "createTask body=$body")

        val req = Request.Builder()
            .url("$BASE_URL/createTask")
            .post(body.toRequestBody(JSON_TYPE))
            .build()

        val resp = client.newCall(req).execute().use { JSONObject(it.body?.string() ?: return null) }

        if (resp.optInt("errorId", 0) != 0) {
            Log.w(TAG, "createTask error: ${resp.optString("errorDescription")}")
            return null
        }
        return resp.optString("taskId").takeIf { it.isNotBlank() }
    }

    private fun getResult(taskId: String): String? {
        val body = JSONObject().apply {
            put("clientKey", API_KEY)
            put("taskId", taskId)
        }.toString()

        val req = Request.Builder()
            .url("$BASE_URL/getTaskResult")
            .post(body.toRequestBody(JSON_TYPE))
            .build()

        val resp = client.newCall(req).execute().use { JSONObject(it.body?.string() ?: return null) }

        if (resp.optString("status") != "ready") return null
        return resp.optJSONObject("solution")
            ?.optString("gRecaptchaResponse")
            ?.takeIf { it.isNotBlank() }
    }
}
