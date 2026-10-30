package com.example.idautble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun EquipmentIdDialog(
    current: EquipmentIdentity?,
    draft: String,
    busy: Boolean,
    status: String,
    onDraftChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val normalized = normalizedEquipmentId(draft)
    val valid = validEquipmentId(normalized)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Device ID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current ID: ${current?.id ?: "not queried"}")
                current?.let { Text("Hardware ID: ${it.hardwareId}") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("New ID") },
                    supportingText = {
                        Text("1–20 characters: A-Z, 0-9, spaces, - and _. Example: E 1234 BCD.")
                    },
                    isError = draft.isNotEmpty() && !valid,
                )
                Text(
                    "This ID will be shown over Bluetooth and included in data frames. It is kept after power-off. Use a unique ID for each device."
                )
                if (status.isNotEmpty()) Text(status)
            }
        },
        confirmButton = {
            MaintenanceActionButton(
                text = if (busy) "Waiting..." else "Save ID",
                onClick = onSave,
                tone = MaintenanceActionTone.SUCCESS,
                enabled = valid && !busy && current != null,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}
