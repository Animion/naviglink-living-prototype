package cz.naviglink.driver.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

/**
 * Tenký wrapper nad FusedLocationProviderClient.
 *
 * MVP: jedna metoda `currentLocation()` která vrátí aktuální polohu s vysokou přesností.
 * Production by přidalo continuous tracking přes ForegroundService — to je v LocationService.
 */
class LocationRepository(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        return try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
