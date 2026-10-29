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
        title = { Text("Acceso de mantenimiento") },
        text = {
            Column {
                Text(
                    "Introduce las seis cifras de PLC_MAINTENANCE_PIN configuradas en el firmware. Android solicita ese mismo PIN al emparejar."
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        onPinChanged(value.filter { it in '0'..'9' }.take(6))
                    },
                    label = { Text("PIN del PLC") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Row {
                    Checkbox(checked = remember, onCheckedChange = onRememberChanged)
                    Text("Recordar en este dispositivo (almacenamiento cifrado)")
                }
                Text(
                    "La autorización se mantiene hasta que pulses Bloquear acceso o desconectes el PLC. No necesitas autorizar para consultar datos."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = validMaintenancePin(pin)) {
                Text("Autorizar")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}
