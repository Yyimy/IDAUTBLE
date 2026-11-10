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
private enum class HidSequence(val title: String) {
    Prefix("Keys before the first code"), Separator("Separator between fields"),
}

@Composable
fun HidOutputSection(state: HidOutputState, connected: Boolean, production: Boolean, onAction: (HidOutputConfig?) -> Unit) {
    if (!state.supported) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fields by remember { mutableStateOf("PU") }
    var prefixPattern by remember { mutableStateOf("") }
    var hotkeyPattern by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("{TAB}") }
    var ending by remember { mutableStateOf("ENTER") }
    var layout by remember { mutableStateOf("US") }
    var editingSequence by remember { mutableStateOf<HidSequence?>(null) }
    LaunchedEffect(state.revision) {
        state.config?.let {
            fields = it.fields
            prefixPattern = hidSeparatorPattern(it.prefix)
            hotkeyPattern = if (it.hotkey == "NONE") "" else it.hotkey
            pattern = hidSeparatorPattern(it.separator)
            ending = it.ending
            layout = it.layout
        }
    }
    val endings = hidEndingOptions(state.extendedKeys)
    val allowArrows = state.arrowSeparators || state.extendedKeys
    val prefix = if (state.prefixKeys) decodeHidSeparator(prefixPattern, allowArrows, state.extendedKeys) else ""
    val separator = decodeHidSeparator(pattern, allowArrows, state.extendedKeys)
    val hotkey = if (state.readHotkey) hotkeyPattern.filterNot(Char::isWhitespace).uppercase().ifEmpty { "NONE" } else "NONE"
    val hotkeyValid = validHidHotkey(hotkey)
    val draft = if (prefix != null && separator != null && hotkeyValid)
        HidOutputConfig(fields, separator, ending, layout, prefix, hotkey) else null
    val enabled = connected && !state.busy
    CollapsibleSettingsSection(
        if (production) "HID OUTPUT" else "HID OUTPUT · PROTOTYPE",
        "Pallet / location · keyboard format", expanded, { expanded = !expanded },
    ) {
        HidSettingRow("Fields") { HidValueMenu(hidFields, fields, enabled) { fields = it } }
        if (state.prefixKeys) HidSettingRow("Initial keys") {
            HidPickerButton(prefixPattern.ifEmpty { "None" }, enabled) { editingSequence = HidSequence.Prefix }
        }
        HidSettingRow("Separator") {
            HidPickerButton(pattern.ifEmpty { "None" }, enabled && fields == "PU") { editingSequence = HidSequence.Separator }
        }
        HidSettingRow("Final key") { HidValueMenu(endings, ending, enabled) { ending = it } }
        HidSettingRow("Keyboard layout") { HidValueMenu(hidLayouts, layout, enabled) { layout = it } }
        if (state.readHotkey) {
            OutlinedTextField(
                value = hotkeyPattern,
                onValueChange = { hotkeyPattern = it },
                modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, isError = !hotkeyValid,
                label = { Text("Read shortcut (Windows)") },
                placeholder = { Text("CTRL+ALT+L") },
                supportingText = { Text("Leave blank to disable. Enter Ctrl, Alt and Shift in that order, then a letter, digit or F1–F12. Use at least Ctrl or Alt.") },
            )
        }
        Text("Preview: ${draft?.preview() ?: "Invalid key sequence"}", fontFamily = FontFamily.Monospace,
            fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.message.isNotBlank()) Text(state.message, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaintenanceActionButton("Refresh", { onAction(null) }, MaintenanceActionTone.INFO, Modifier.weight(1f), enabled)
            MaintenanceActionButton("Save format", { draft?.let(onAction) }, MaintenanceActionTone.SUCCESS,
                Modifier.weight(1f), enabled && draft != null && state.config != null)
        }
        Text("Match the receiver keyboard layout. Saving requires PIN / ACCESS.", fontSize = 11.sp)
    }
    val sequence = editingSequence
    if (sequence != null && enabled) HidSequenceDialog(
        title = sequence.title,
        initial = if (sequence == HidSequence.Prefix) prefixPattern else pattern,
        allowArrows = allowArrows,
        extended = state.extendedKeys,
        onDismiss = { editingSequence = null },
        onApply = {
            if (sequence == HidSequence.Prefix) prefixPattern = it else pattern = it
            editingSequence = null
        },
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
private fun HidSequenceDialog(title: String, initial: String, allowArrows: Boolean, extended: Boolean, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf(false) }
    val decoded = decodeHidSeparator(draft, allowArrows, extended)
    val keys = hidSeparatorOptions(allowArrows, extended)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
