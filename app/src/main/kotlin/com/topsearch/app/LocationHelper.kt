package com.topsearch.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LatLng(val lat: Double, val lng: Double)

object LocationHelper {

    /** Lấy vị trí thiết bị. Trả null nếu không có quyền hoặc timeout. */
    suspend fun getDeviceLocation(context: Context): LatLng? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Thử lấy last known location (nhanh, không tốn pin)
        val last = getBestLastLocation(lm)
        if (last != null && isFresh(last)) {
            return@withContext LatLng(last.latitude, last.longitude)
        }

        // Không có last known → request single update, timeout 5s
        val fresh = withTimeoutOrNull(5_000) {
            requestSingleUpdate(lm)
        }
        fresh?.let { LatLng(it.latitude, it.longitude) }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ── Private ───────────────────────────────────────────────────────────────

    /** Lấy location mới nhất từ tất cả providers */
    @Suppress("MissingPermission")
    private fun getBestLastLocation(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers
            .filter { lm.isProviderEnabled(it) }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Location mới hơn 10 phút → còn dùng được */
    private fun isFresh(loc: Location): Boolean {
        val ageMs = SystemClock.elapsedRealtimeNanos() / 1_000_000 -
                    loc.elapsedRealtimeNanos / 1_000_000
        return ageMs < 10 * 60 * 1_000
    }

    /** Request 1 lần update từ NETWORK (nhanh) hoặc GPS */
    @Suppress("MissingPermission")
    private suspend fun requestSingleUpdate(lm: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                else -> { cont.resume(null); return@suspendCancellableCoroutine }
            }

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(loc)
                }
                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            }

            runCatching {
                lm.requestLocationUpdates(provider, 0L, 0f, listener,
                    android.os.Looper.getMainLooper())
            }.onFailure { cont.resume(null) }

            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }
}
