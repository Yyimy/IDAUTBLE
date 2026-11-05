package com.example.idautble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val hidFields = mapOf("P" to "Pallet", "U" to "Location", "PU" to "Both")
private val hidLayouts = mapOf("US" to "US", "ES" to "Spanish")

@Composable
fun HidOutputSection(state: HidOutputState, connected: Boolean, production: Boolean, onAction: (HidOutputConfig?) -> Unit) {
    if (!state.supported) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fields by remember { mutableStateOf("PU") }
    var pattern by remember { mutableStateOf("{TAB}") }
    var ending by remember { mutableStateOf("ENTER") }
    var layout by remember { mutableStateOf("US") }
    var editSeparator by remember { mutableStateOf(false) }
    LaunchedEffect(state.revision) {
        state.config?.let {
            fields = it.fields
            pattern = hidSeparatorPattern(it.separator)
            ending = it.ending
            layout = it.layout
        }
    }
    val endings = hidEndingOptions(state.extendedKeys)
    val draft = decodeHidSeparator(pattern, allowArrows = state.arrowSeparators || state.extendedKeys, extended = state.extendedKeys)
        ?.let { HidOutputConfig(fields, it, ending, layout) }
    val enabled = connected && !state.busy
    CollapsibleSettingsSection(
        if (production) "HID OUTPUT" else "HID OUTPUT · PROTOTYPE",
        "Pallet / location · keyboard format", expanded, { expanded = !expanded },
    ) {
        HidSettingRow("Fields") { HidValueMenu(hidFields, fields, enabled) { fields = it } }
        HidSettingRow("Separator") {
            HidPickerButton(pattern.ifEmpty { "None" }, enabled && fields == "PU") { editSeparator = true }
        }
        HidSettingRow("Final key") { HidValueMenu(endings, ending, enabled) { ending = it } }
        HidSettingRow("Keyboard layout") { HidValueMenu(hidLayouts, layout, enabled) { layout = it } }
        Text("Preview: ${draft?.preview() ?: "Invalid separator"}", fontFamily = FontFamily.Monospace,
            fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.message.isNotBlank()) Text(state.message, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaintenanceActionButton("Refresh", { onAction(null) }, MaintenanceActionTone.INFO, Modifier.weight(1f), enabled)
            MaintenanceActionButton("Save format", { draft?.let(onAction) }, MaintenanceActionTone.SUCCESS,
                Modifier.weight(1f), enabled && draft != null && state.config != null)
        }
        Text("Match the receiver keyboard layout. Saving requires PIN / ACCESS.", fontSize = 11.sp)
    }
    if (editSeparator && enabled) HidSeparatorDialog(
        initial = pattern,
        allowArrows = state.arrowSeparators || state.extendedKeys,
        extended = state.extendedKeys,
        onDismiss = { editSeparator = false },
        onApply = { pattern = it; editSeparator = false },
    )
}

@Composable
private fun HidSettingRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.4f), fontSize = 11.sp)
        Box(Modifier.weight(0.6f)) { content() }
    }
}

@Composable
private fun HidPickerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("▾", fontSize = 12.sp)
    }
}

@Composable
private fun HidValueMenu(options: Map<String, String>, value: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HidPickerButton(options[value] ?: value, enabled) { open = true }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(key); open = false })
            }
        }
    }
}

@Composable
private fun HidSeparatorDialog(initial: String, allowArrows: Boolean, extended: Boolean, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf(false) }
    val decoded = decodeHidSeparator(draft, allowArrows, extended)
    val keys = hidSeparatorOptions(allowArrows, extended)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Separator between fields") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3, isError = decoded == null,
                    label = { Text("Sequence") },
                    supportingText = {
                        Text(if (decoded == null) "Use valid keys or ASCII text, up to $HID_SEPARATOR_LIMIT items."
                            else "${decoded.length} / $HID_SEPARATOR_LIMIT keys or characters")
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        HidPickerButton("Add key", decoded != null && decoded.length < HID_SEPARATOR_LIMIT) { open = true }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                            keys.forEach { key ->
                                DropdownMenuItem(text = { Text(key) }, onClick = { draft += key; open = false })
                            }
                        }
                    }
                    TextButton(onClick = { draft = "" }) { Text("Clear") }
                }
                Text("Keys are sent in order. You can also type ASCII text. Use {{ for a literal {.", fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }, enabled = decoded != null) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
