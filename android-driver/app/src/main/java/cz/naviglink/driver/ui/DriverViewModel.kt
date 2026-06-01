package cz.naviglink.driver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.naviglink.driver.NaviglinkApp
import cz.naviglink.driver.data.SignedSubject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * UI state machine pro řidičovu obrazovku.
 *
 *   Idle  →  CheckingLocation  →  Querying  →  (NoAlert | Alert)
 *
 *                                              ┌→ NoAlert
 *                                              └→ Alert  →  SendingReaction  →  ReactionSent
 *                                                       │
 *                                                       └→ Error
 */
sealed interface DriverUiState {
    data object Idle : DriverUiState
    data object NeedsLocationPermission : DriverUiState
    data object CheckingLocation : DriverUiState
    data class Querying(val lon: Double, val lat: Double) : DriverUiState
    data class NoAlert(val checkedAt: Instant, val lon: Double, val lat: Double) : DriverUiState
    data class Alert(
        val checkedAt: Instant,
        val matches: List<SignedSubject>,
    ) : DriverUiState
    data object SendingReaction : DriverUiState
    data class ReactionSent(val reaction: String) : DriverUiState
    data class Error(val message: String) : DriverUiState
}

class DriverViewModel(private val app: NaviglinkApp) : ViewModel() {

    private val _state = MutableStateFlow<DriverUiState>(DriverUiState.Idle)
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    /** Vrací hex public klíče identitu pro zobrazení v UI ("did:key:abc..."). */
    val publicKeyHex: String get() = app.keystore.publicKeyHex

    fun onResume() {
        if (!app.location.hasPermission()) {
            _state.value = DriverUiState.NeedsLocationPermission
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) {
            _state.value = DriverUiState.Idle
            checkNow()
        } else {
            _state.value = DriverUiState.NeedsLocationPermission
        }
    }

    fun onNotificationPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) {
        // Notifikace nejsou blokující pro flow — uložit a pokračovat
    }

    /** Manual trigger — uživatel klikl "Zkontrolovat teď". */
    fun checkNow() {
        if (!app.location.hasPermission()) {
            _state.value = DriverUiState.NeedsLocationPermission
            return
        }
        viewModelScope.launch {
            _state.value = DriverUiState.CheckingLocation
            val loc = app.location.currentLocation()
            if (loc == null) {
                _state.value = DriverUiState.Error("Nedostupná poloha. Zkus to znovu venku.")
                return@launch
            }
            _state.value = DriverUiState.Querying(loc.longitude, loc.latitude)
            try {
                val now = Instant.now()
                val matches = app.client.queryActive(loc.longitude, loc.latitude, now)
                _state.value = if (matches.isEmpty()) {
                    DriverUiState.NoAlert(now, loc.longitude, loc.latitude)
                } else {
                    DriverUiState.Alert(now, matches)
                }
            } catch (e: Exception) {
                _state.value = DriverUiState.Error("Server: ${e.message ?: "neznámá chyba"}")
            }
        }
    }

    /** Reagovat na alert — "jsem na cestě" / "nemohu, nechte mě". */
    fun reactToAlert(subjectId: String, reaction: String) {
        viewModelScope.launch {
            _state.value = DriverUiState.SendingReaction
            try {
                val result = app.client.submitClaim(aboutSubjectId = subjectId, state = reaction)
                if (result.verified && result.stored) {
                    _state.value = DriverUiState.ReactionSent(reaction)
                } else {
                    _state.value = DriverUiState.Error("Server reakci nepřijal")
                }
            } catch (e: Exception) {
                _state.value = DriverUiState.Error("Reakce: ${e.message ?: "neznámá chyba"}")
            }
        }
    }

    fun resetToIdle() {
        _state.value = DriverUiState.Idle
    }

    companion object {
        fun factory(app: NaviglinkApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DriverViewModel(app) as T
            }
    }
}
