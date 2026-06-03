package cz.naviglink.driver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modal: zobrazí 12-slovní BIP39 frázi ke klíči. Driver si ji má napsat na papír.
 *
 * Použít: po prvním spuštění (auto-open při freshly generated klíči) nebo na
 * vyžádání z IdlePane.
 */
@Composable
fun BackupMnemonicDialog(
    mnemonic: String?,
    publicKeyHex: String,
    onDismiss: () -> Unit,
) {
    if (mnemonic == null) {
        // Legacy klíč bez BIP39 zálohy — informativní dialog
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Tento klíč nemá zálohu") },
            text = {
                Text(
                    "Klíč v tomto telefonu byl vygenerován ve starší verzi aplikace bez " +
                        "BIP39 zálohy. Pokud telefon ztratíš, ztratíš i identitu.\n\n" +
                        "Doporučení: smaž tento klíč v Nastavení a vytvoř novou identitu, " +
                        "která dostane 12-slovní zálohu."
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Rozumím") }
            },
        )
        return
    }

    val words = mnemonic.split(Regex("\\s+"))
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zálohovací fráze (12 slov)") },
        text = {
            Column {
                Text(
                    "Napiš si těchto 12 slov a ulož bezpečně. Kdokoliv s touto frází " +
                        "získá tvou identitu.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(12.dp))
                MnemonicGrid(words = words)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Identita: did:key:" + publicKeyHex.take(16) + "…",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Mám zapsáno") }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(mnemonic))
                copied = true
            }) { Text(if (copied) "✓ Zkopírováno" else "Kopírovat") }
        },
    )
}

@Composable
private fun MnemonicGrid(words: List<String>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(200.dp),
    ) {
        items(words.size) { i ->
            Row(
                modifier = Modifier
                    .background(Color(0xFFEDF2F7), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${i + 1}.",
                    fontSize = 10.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    text = words[i],
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Modal: vstup pro 12-slovní frázi. Validuje a obnoví klíč.
 */
@Composable
fun RestoreMnemonicDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Obnovit z 12 slov") },
        text = {
            Column {
                Text(
                    text = "Tímto nahradíš aktuální klíč. Reakce a park snapshots " +
                        "z minulosti zůstanou v backendu, ale aplikace už nebude " +
                        "podepisovat za předchozí identitu.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFCBD5E0), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it; error = null },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (input.isEmpty()) {
                        Text(
                            text = "word1 word2 word3 …",
                            color = Color(0xFFA0AEC0),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error ?: "",
                        fontSize = 12.sp,
                        color = Color(0xFFB03030),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitting = true
                    try {
                        onConfirm(input.trim())
                    } catch (e: Exception) {
                        error = e.message ?: "Fráze není validní."
                        submitting = false
                    }
                },
                enabled = !submitting && input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB03030),
                    contentColor = Color.White,
                ),
            ) { Text(if (submitting) "Obnovuji…" else "Obnovit") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !submitting,
            ) { Text("Zavřít") }
        },
    )
}
