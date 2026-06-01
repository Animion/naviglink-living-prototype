package cz.naviglink.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import cz.naviglink.driver.ui.HomeScreen
import cz.naviglink.driver.ui.NaviglinkTheme
import cz.naviglink.driver.ui.DriverViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DriverViewModel by viewModels {
        DriverViewModel.factory(application as NaviglinkApp)
    }

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onLocationPermissionResult(granted)
        }

    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onNotificationPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NaviglinkTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onRequestLocationPermission = ::requestLocation,
                    onRequestNotificationPermission = ::requestNotifications,
                )
            }
        }

        // Při startu okamžitě požádat o location, pokud chybí
        if (!hasLocationPermission()) requestLocation()

        // Pokud nás otevřela notifikace s deep linkem naviglink://alert/<id>,
        // rovnou spustíme check, aby driver viděl detail bez dalšího kliku.
        handleAlertIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun handleAlertIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "naviglink" || data.host != "alert") return
        // Pro pilot stačí spustit běžný check; konkrétní subject_id v cestě
        // (data.lastPathSegment) je k dispozici pro pozdější iteraci.
        if (hasLocationPermission()) viewModel.checkNow()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
