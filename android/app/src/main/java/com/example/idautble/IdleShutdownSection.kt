package com.example.idautble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

internal data class IdleShutdownState(
    val minutes: Int? = null,
    val saving: Boolean = false,
    val message: String = "",
)

internal fun shutdownReasonLabel(reason: String): String = when (reason) {
    "COMMAND" -> "Power-off command"
    "BAT_VOLTAGE_COLLAPSE" -> "Battery voltage collapsed"
    "BAT_VOLTAGE_LOW" -> "Low battery voltage"
    else -> {
        val minutes = reason.takeIf { it.startsWith("INACTIVITY_") && it.endsWith("_MIN") }
            ?.removePrefix("INACTIVITY_")?.removeSuffix("_MIN")?.toIntOrNull()
        if (minutes != null) "Photocell inactivity ($minutes min)" else reason.ifBlank { "Unknown reason" }
    }
}

@Composable
internal fun IdleShutdownSection(
    state: IdleShutdownState,
    notice: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val minutes = state.minutes
    var open by rememberSaveable(minutes) { mutableStateOf(false) }
    CollapsibleSettingsSection(
        "POWER",
        minutes?.let { "Inactivity timeout · $it min" } ?: "Automatic shutdown",
        expanded, onToggle,
    ) {
        if (minutes == null) {
            Text("Connect to a PLC with compatible firmware to configure automatic shutdown.")
        } else {
            MaintenanceActionButton(
                text = "Inactivity timeout · $minutes min",
                onClick = { open = true },
                tone = MaintenanceActionTone.INFO,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving,
            )
        }
        if (state.message.isNotEmpty()) Text(state.message)
        notice?.let { Text(it) }
    }
    if (open && minutes != null) {
        var input by rememberSaveable(minutes) { mutableStateOf(minutes.toString()) }
        val value = input.toIntOrNull()?.takeIf { it in 1..1440 }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Inactivity timeout") },
            text = {
                Column {
                    Text("Time the photocell must remain clear before automatic shutdown. Saving restarts the countdown.")
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { digit -> digit in '0'..'9' }.take(4) },
                        label = { Text("Minutes (1–1440)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = input.isNotEmpty() && value == null,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = value ?: return@TextButton
                        open = false
                        onSave(selected)
                    },
                    enabled = value != null && value != minutes,
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
