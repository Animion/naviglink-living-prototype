package cz.naviglink.driver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import cz.naviglink.driver.crypto.NaviglinkKeystore
import cz.naviglink.driver.data.LocationRepository
import cz.naviglink.driver.data.NaviglinkClient
import cz.naviglink.driver.work.AlertsPollWorker

/**
 * Application singleton — drží instance core komponent.
 *
 * Žádný DI framework (Hilt/Koin) — pro malou aplikaci stačí service locator
 * pattern přes Application class.
 */
class NaviglinkApp : Application() {

    val keystore: NaviglinkKeystore by lazy { NaviglinkKeystore(this) }
    val client: NaviglinkClient by lazy { NaviglinkClient(keystore) }
    val location: LocationRepository by lazy { LocationRepository(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        AlertsPollWorker.schedule(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LOCATION_SERVICE,
                getString(R.string.service_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Foreground service pro sledování polohy" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.alert_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Upozornění na blokové čištění a další omezení"
                enableVibration(true)
                enableLights(true)
            }
        )
    }

    companion object {
        const val CHANNEL_LOCATION_SERVICE = "naviglink_location_service"
        const val CHANNEL_ALERT = "naviglink_alert"
    }
}
