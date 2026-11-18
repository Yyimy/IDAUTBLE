package com.example.idautble

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
internal fun RestoreFactoryDefaultsAction(
    authorised: Boolean,
    restoring: Boolean,
    pendingReadings: Int,
    checkingCounters: Boolean,
    message: String,
    onCheckCounters: () -> Unit,
    onRestore: () -> Unit,
) {
    var confirming by remember(authorised, pendingReadings) { mutableStateOf(false) }
    val canRestore = authorised && !restoring && pendingReadings == 0
    MaintenanceActionButton(
        text = "Restore factory defaults",
        onClick = { confirming = true },
        tone = MaintenanceActionTone.DANGER,
        enabled = canRestore,
        modifier = Modifier.fillMaxWidth(),
    )
    if (pendingReadings > 0) {
        Text("Pending readings must be delivered before restoring.")
        TextButton(onClick = onCheckCounters, enabled = authorised && !checkingCounters) {
            Text("Check pending readings")
        }
    } else if (message.isNotEmpty()) {
        Text(message)
    }

    if (confirming && canRestore) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Restore factory defaults?") },
            text = {
                Text(
                    "The PLC will apply the IDAUT standard for the reader, height, power, network, " +
                        "data frames and HID output. Wi-Fi will be switched off. Saved Wi-Fi credentials, " +
                        "equipment ID, clock, HID receiver, Bluetooth pairings and counters will be kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onRestore()
                }) { Text("Restore defaults", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}
