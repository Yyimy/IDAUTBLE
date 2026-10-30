package com.example.idautble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun MaintenancePinDialog(
    pin: String,
    onPinChanged: (String) -> Unit,
    remember: Boolean,
    onRememberChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Maintenance access") },
        text = {
            Column {
                Text(
                    "Enter the six-digit PLC_MAINTENANCE_PIN configured in the firmware. Android asks for the same PIN when pairing."
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        onPinChanged(value.filter { it in '0'..'9' }.take(6))
                    },
                    label = { Text("PLC PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Row {
                    Checkbox(checked = remember, onCheckedChange = onRememberChanged)
                    Text("Remember on this device (encrypted storage)")
                }
                Text(
                    "Authorisation remains active until you select Lock access or disconnect the PLC. Queries do not require authorisation."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = validMaintenancePin(pin)) {
                Text("Authorise")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
