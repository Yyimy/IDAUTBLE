package com.example.idautble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val readerTimeoutOptions = listOf(1000, 2000, 3000, 5000)

internal data class ReaderTimeoutState(
    val currentMs: Int? = null,
    val pendingMs: Int? = null,
    val message: String = "",
)

internal fun parseReaderTimeout(value: String): Int? =
    value.toIntOrNull()?.takeIf { it in readerTimeoutOptions && value == it.toString() }

@Composable
internal fun ReaderTimeoutSettings(state: ReaderTimeoutState, onSave: (Int) -> Unit) {
    val current = state.currentMs ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider()
    MaintenanceActionButton(
        text = "Reader timeout · ${current / 1000} s",
        onClick = { open = true },
        tone = MaintenanceActionTone.INFO,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.pendingMs == null,
    )
    if (state.message.isNotEmpty()) Text(state.message, fontSize = 11.sp)
    if (open) {
        var selected by rememberSaveable(current) { mutableIntStateOf(current) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Reader timeout") },
            text = {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    Text("Maximum wait for the barcode reader.")
                    readerTimeoutOptions.forEachIndexed { index, option ->
                        ListItem(
                            modifier = Modifier.selectable(selected == option, role = Role.RadioButton,
                                onClick = { selected = option }),
                            headlineContent = { Text("${option / 1000} s") },
                            trailingContent = { RadioButton(selected == option, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (index < readerTimeoutOptions.lastIndex) HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false; onSave(selected) }, enabled = selected != current) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
