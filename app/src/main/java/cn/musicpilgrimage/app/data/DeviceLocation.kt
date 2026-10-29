package cn.musicpilgrimage.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class DeviceLocation(private val context: Context) {
    @Suppress("DEPRECATION")
    private fun Location.fix() = LocationFix(latitude, longitude, accuracy, time, ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0), isFromMockProvider)
    @SuppressLint("MissingPermission")
    suspend fun locate(): LocationFix {
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "请允许精确位置权限，才能验证到场" }
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        require(providers.isNotEmpty()) { "请先打开手机定位服务" }
        providers.mapNotNull { manager.getLastKnownLocation(it)?.fix() }.filter { !it.isMock && it.ageMillis <= 30_000 && it.accuracy > 0 && it.accuracy <= 100 }.minByOrNull { it.accuracy }?.let { return it }
        return withTimeoutOrNull(35_000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val fix = location.fix()
                        if (continuation.isActive && !fix.isMock && fix.accuracy > 0 && fix.accuracy <= 100 && fix.ageMillis <= 120_000) {
                            manager.removeUpdates(this); continuation.resume(fix)
                        }
                    }
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                providers.forEach { manager.requestLocationUpdates(it, 1000, 0f, listener, Looper.getMainLooper()) }
            }
        } ?: throw IllegalStateException("暂时无法取得足够精确的位置，请到开阔处重试")
    }
}
