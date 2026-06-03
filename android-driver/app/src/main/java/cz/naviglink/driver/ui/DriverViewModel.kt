package cz.naviglink.driver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.naviglink.driver.NaviglinkApp
import cz.naviglink.driver.data.SignedSubject
import cz.naviglink.driver.service.EventStreamService
import cz.naviglink.driver.work.AlertsPollWorker
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
        val driverLat: Double? = null,
        val driverLon: Double? = null,
    ) : DriverUiState
    data object SendingReaction : DriverUiState
    data class ReactionSent(val reaction: String) : DriverUiState
    data object SendingParkSnapshot : DriverUiState
    data class ParkSnapshotSent(
        val lon: Double,
        val lat: Double,
        val validForHours: Long,
    ) : DriverUiState
    data class IdentityRestored(val publicKeyHex: String) : DriverUiState
    data class Error(val message: String) : DriverUiState
}

class DriverViewModel(private val app: NaviglinkApp) : ViewModel() {

    private val _state = MutableStateFlow<DriverUiState>(DriverUiState.Idle)
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    // Reactive public key — keystore-backed, ale jako StateFlow,
    // aby Compose UI dostalo recomposition trigger při změně (restore).
    private val _publicKeyHex = MutableStateFlow(app.keystore.publicKeyHex)
    val publicKeyHexFlow: StateFlow<String> = _publicKeyHex.asStateFlow()

    /** Aktuální hex public key (snapshot, ne reactive). */
    val publicKeyHex: String get() = _publicKeyHex.value

    /** Vrací BIP39 mnemonic (12 slov), pokud existuje. Klíče ze starší verze vrátí null. */
    val mnemonic: String? get() = app.keystore.getMnemonicOrNull()

    /**
     * Obnoví klíč z 12-slovní BIP39 fráze. Po úspěchu update UI state
     * na IdentityRestored, aby uživatel viděl potvrzení a nový pubHex.
     *
     * @throws Exception při neplatné frázi (z BIP39 validate)
     */
    fun restoreFromMnemonic(mnemonic: String) {
        app.keystore.restoreFromMnemonic(mnemonic)
        val newPubHex = app.keystore.publicKeyHex
        _publicKeyHex.value = newPubHex
        _state.value = DriverUiState.IdentityRestored(newPubHex)
    }

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
                    DriverUiState.Alert(now, matches, loc.latitude, loc.longitude)
                }
            } catch (e: Exception) {
                _state.value = DriverUiState.Error("Server: ${e.message ?: "neznámá chyba"}")
            }
        }
    }

    /**
     * Oznám "Zaparkováno tady" — vezme aktuální polohu, podepíše park_snapshot,
     * pošle backendu. Od této chvíle bude WorkManager periodicky volat /alerts
     * a notifikovat o subjektech pokrývajících tuto polohu.
     *
     * @param validForHours  jak dlouho snapshot platí (default 12 h — typické
     *                       noční parkování). Po vypršení backend `/alerts`
     *                       vrátí prázdno až do dalšího `sendParkSnapshot`.
     */
    fun sendParkSnapshot(validForHours: Long = 12) {
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
            _state.value = DriverUiState.SendingParkSnapshot
            try {
                val result = app.client.submitParkSnapshot(
                    lon = loc.longitude,
                    lat = loc.latitude,
                    validForHours = validForHours,
                )
                if (result.verified && result.stored) {
                    _state.value = DriverUiState.ParkSnapshotSent(
                        loc.longitude, loc.latitude, validForHours
                    )
                    // E: Hned po uložení snapshot spusť jednorázový alerts check
                    // mimo periodic cycle. Pokud na poloze něco platí, driver
                    // dostane notifikaci do pár sekund — nečeká 15 min.
                    AlertsPollWorker.runOnce(app)
                } else {
                    _state.value = DriverUiState.Error("Server park_snapshot nepřijal")
                }
            } catch (e: Exception) {
                _state.value = DriverUiState.Error("park_snapshot: ${e.message ?: "neznámá chyba"}")
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

    /**
     * Manuálně spusť `AlertsPollWorker` mimo periodic cycle.
     *
     * Pro test (rychlá validace, nečekat 15 min) i jako *trvalá feature*:
     * driver chce kdykoli ověřit "není na mě něco vyhlášeno?".
     *
     * Notifikace přijde ze workeru, ne z UI — to je správně, ten flow je
     * stejný, jako kdyby worker běžel z periodic schedule.
     */
    fun runAlertsCheckNow() {
        AlertsPollWorker.runOnce(app)
    }

    /**
     * Spustí foreground service s SSE spojením na backend — real-time alerty.
     * Doplňuje WorkManager polling (který běží i bez tohoto): když je service
     * aktivní, alerty přijdou do několika sekund místo 15 min.
     */
    fun startEventStream() {
        EventStreamService.start(app)
    }

    /** Vypne SSE foreground service. WorkManager polling stále běží na pozadí. */
    fun stopEventStream() {
        EventStreamService.stop(app)
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
