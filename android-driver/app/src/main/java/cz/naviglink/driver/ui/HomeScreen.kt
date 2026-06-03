package cz.naviglink.driver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.naviglink.driver.data.SignedSubject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun HomeScreen(
    viewModel: DriverViewModel,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pubHexShort = remember(viewModel) {
        val h = viewModel.publicKeyHex
        "did:key:" + h.take(16) + "…"
    }

    var showBackup by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }

    if (showBackup) {
        BackupMnemonicDialog(
            mnemonic = viewModel.mnemonic,
            publicKeyHex = viewModel.publicKeyHex,
            onDismiss = { showBackup = false },
        )
    }
    if (showRestore) {
        RestoreMnemonicDialog(
            onConfirm = { mnemonic ->
                viewModel.restoreFromMnemonic(mnemonic)
                showRestore = false
                // Refresh activity so identity, polling worker key, ViewModel
                // and UI re-read the new pubHex from keystore.
                (viewModel as? DriverViewModel)?.let { /* no-op, recompose driven by recreation */ }
                // Quickest UX: trigger Idle so the user sees new state. Restart
                // of the activity by user (or system process) will pick up new key.
                viewModel.resetToIdle()
            },
            onDismiss = { showRestore = false },
        )
    }

    val bgColor = when (state) {
        is DriverUiState.Alert -> AlertBg
        is DriverUiState.NoAlert -> SafeBg
        else -> MaterialTheme.colorScheme.background
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeaderBar(identityLabel = pubHexShort)

            Spacer(Modifier.height(48.dp))

            StateContent(
                state = state,
                onCheckNow = viewModel::checkNow,
                onReact = viewModel::reactToAlert,
                onReset = viewModel::resetToIdle,
                onSendParkSnapshot = { viewModel.sendParkSnapshot() },
                onRunAlertsWorker = viewModel::runAlertsCheckNow,
                onShowBackup = { showBackup = true },
                onShowRestore = { showRestore = true },
                onRequestLocation = onRequestLocationPermission,
                onRequestNotifications = onRequestNotificationPermission,
            )
        }
    }
}

@Composable
private fun HeaderBar(identityLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Naviglink",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = identityLabel,
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun StateContent(
    state: DriverUiState,
    onCheckNow: () -> Unit,
    onReact: (String, String) -> Unit,
    onReset: () -> Unit,
    onSendParkSnapshot: () -> Unit,
    onRunAlertsWorker: () -> Unit,
    onShowBackup: () -> Unit,
    onShowRestore: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    when (state) {
        is DriverUiState.Idle -> IdlePane(onCheckNow, onSendParkSnapshot, onShowBackup, onShowRestore)
        is DriverUiState.NeedsLocationPermission ->
            NeedsPermissionPane("Pro zjištění upozornění je potřeba povolit polohu.", onRequestLocation)
        is DriverUiState.CheckingLocation -> Working("Zjišťuji polohu…")
        is DriverUiState.Querying -> Working("Hledám upozornění…")
        is DriverUiState.NoAlert -> NoAlertPane(onCheckNow, onSendParkSnapshot)
        is DriverUiState.Alert -> AlertPane(state, onReact)
        is DriverUiState.SendingReaction -> Working("Posílám reakci…")
        is DriverUiState.ReactionSent -> ReactionSentPane(state.reaction, onReset)
        is DriverUiState.SendingParkSnapshot -> Working("Posílám polohu…")
        is DriverUiState.ParkSnapshotSent -> ParkSnapshotSentPane(state, onRunAlertsWorker, onReset)
        is DriverUiState.Error -> ErrorPane(state.message, onCheckNow)
    }
}

@Composable
private fun IdlePane(
    onCheckNow: () -> Unit,
    onSendParkSnapshot: () -> Unit,
    onShowBackup: () -> Unit,
    onShowRestore: () -> Unit,
) {
    Text(
        text = "Připraveno.",
        fontSize = 24.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Stiskni tlačítko a zkontroluj, zda na tebe nečeká upozornění z magistrátu.",
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(40.dp))
    BigButton(text = "Zkontrolovat teď", onClick = onCheckNow)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onSendParkSnapshot,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text("Zaparkováno tady", fontSize = 16.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Po stisknutí ti přijde notifikace, pokud se na tomto místě objeví blokové čištění nebo jiné omezení.",
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
    )

    Spacer(Modifier.height(24.dp))
    // Klíč backup/restore — vždy přístupné. Doporučeno hned po prvním spuštění.
    OutlinedButton(
        onClick = onShowBackup,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Zálohovat klíč (12 slov)", fontSize = 14.sp)
    }
    Spacer(Modifier.height(6.dp))
    androidx.compose.material3.TextButton(
        onClick = onShowRestore,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Obnovit z 12 slov", fontSize = 13.sp)
    }
}

@Composable
private fun NoAlertPane(onCheckNow: () -> Unit, onSendParkSnapshot: () -> Unit) {
    Text(
        text = "✓ Bez upozornění",
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "V tvé poloze není žádný aktivní subjekt blokového čištění ani jiné omezení.",
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(40.dp))
    BigButton(text = "Zaparkováno tady", onClick = onSendParkSnapshot)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onCheckNow,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text("Zkontrolovat znovu", fontSize = 16.sp)
    }
}

@Composable
private fun ParkSnapshotSentPane(
    state: DriverUiState.ParkSnapshotSent,
    onRunAlertsWorker: () -> Unit,
    onReset: () -> Unit,
) {
    Text(
        text = "✓ Poloha uložena",
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF2D8050),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Pokud se v této oblasti objeví blokové čištění (nebo jiné omezení) v nejbližších ${state.validForHours} h, přijde ti notifikace.",
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "%.5f, %.5f".format(state.lat, state.lon),
        fontSize = 12.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
    )
    Spacer(Modifier.height(40.dp))
    BigButton(text = "Zkontrolovat upozornění teď", onClick = onRunAlertsWorker)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onReset,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text("Zpět", fontSize = 16.sp)
    }
}

@Composable
private fun AlertPane(
    state: DriverUiState.Alert,
    onReact: (String, String) -> Unit,
) {
    val subject = state.matches.first()
    val ulice = subject.payload["ulice"]?.jsonPrimitive?.contentOrNull ?: "(bez popisu)"
    val from = subject.validFrom.substringBefore('.').replace('T', ' ')
    val to = (subject.validTo ?: "").substringBefore('.').replace('T', ' ')

    Text(
        text = "⚠ POZOR",
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFB03030),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))

    // Mapa polygonu + driverovy polohy. Bez ní by driver viděl jen jméno ulice
    // a musel by si v hlavě představit, kde to je. S mapou to vidí na pár vteřin.
    SubjectMapView(
        subject = subject,
        driverLat = state.driverLat,
        driverLon = state.driverLon,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
    Spacer(Modifier.height(16.dp))

    Text(
        text = "$ulice",
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "$from → $to UTC",
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Tvé auto je v dotčené oblasti. Reaguj, prosím:",
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(32.dp))

    BigButton(
        text = "Jsem na cestě",
        onClick = { onReact(subject.id, "jsem_na_ceste") },
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { onReact(subject.id, "nemohu") },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text("Nemohu, nechte mě, prosím", fontSize = 16.sp)
    }

    Spacer(Modifier.height(24.dp))
    Text(
        text = "ID: ${subject.id}",
        fontSize = 10.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
    )
}

@Composable
private fun Working(message: String) {
    Spacer(Modifier.height(40.dp))
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(16.dp))
    Text(message, fontSize = 16.sp, textAlign = TextAlign.Center)
}

@Composable
private fun NeedsPermissionPane(message: String, onRequest: () -> Unit) {
    Text("Potřebné oprávnění", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(
        message,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(32.dp))
    BigButton(text = "Povolit", onClick = onRequest)
}

@Composable
private fun ReactionSentPane(reaction: String, onReset: () -> Unit) {
    val msg = when (reaction) {
        "jsem_na_ceste" -> "Reakce odeslána. Magistrát ví, že jsi na cestě přeparkovat."
        "nemohu" -> "Reakce odeslána. Magistrát zaznamenal, že přijdeš později."
        else -> "Reakce odeslána."
    }
    Text("✓ Hotovo", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D8050))
    Spacer(Modifier.height(16.dp))
    Text(msg, fontSize = 14.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(40.dp))
    OutlinedButton(
        onClick = onReset,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text("Zpět", fontSize = 16.sp)
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Text("Chyba", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB03030))
    Spacer(Modifier.height(12.dp))
    Text(message, fontSize = 14.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(32.dp))
    BigButton(text = "Zkusit znovu", onClick = onRetry)
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1E6091),
            contentColor = Color.White,
        ),
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val AlertBg = Color(0xFFFFF3F2)
private val SafeBg = Color(0xFFF1F8F4)
