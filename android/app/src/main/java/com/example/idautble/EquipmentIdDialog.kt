package com.example.idautble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
        title = { Text("ID de la carretilla") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ID actual: ${current?.id ?: "sin consultar"}")
                current?.let { Text("Identificador técnico: ${it.hardwareId}") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("Nuevo ID") },
                    supportingText = {
                        Text("1–20 caracteres: A-Z, 0-9, espacios, - y _. Ejemplo: E 1234 BCD.")
                    },
                    isError = draft.isNotEmpty() && !valid,
                )
                Text(
                    "Este ID se mostrará por Bluetooth y se enviará en las tramas. Se conserva al apagar. Evita repetir el mismo ID en otras carretillas."
                )
                Text("Cambia el ID con las horquillas vacías y sin operaciones pendientes.")
                if (status.isNotEmpty()) Text(status)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = valid && !busy && current != null) {
                Text(if (busy) "Esperando..." else "Guardar ID")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cerrar") } },
    )
}
