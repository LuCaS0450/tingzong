package cn.musicpilgrimage.app

import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.DeviceLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceLocationTest {
    private fun shell(command: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
    @Suppress("DEPRECATION")
    @Test fun mockProviderCannotCertifyAVisit() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<PilgrimageApplication>()
        shell("pm grant cn.musicpilgrimage.app android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant cn.musicpilgrimage.app android.permission.ACCESS_FINE_LOCATION")
        shell("appops set cn.musicpilgrimage.app android:mock_location allow")
        val manager = app.getSystemService(LocationManager::class.java)
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply { latitude = 48.8593; longitude = 2.3422; accuracy = 10f; time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() })
            val result = kotlinx.coroutines.withTimeoutOrNull(2000) { DeviceLocation(app).locate() }
            org.junit.Assert.assertNull("Mock locations must not be accepted", result)
        } finally {
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            shell("appops set cn.musicpilgrimage.app android:mock_location default")
        }
    }
}
