package com.example.idautble

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val hidFields = mapOf("P" to "Pallet", "U" to "Location", "PU" to "Both")
private val hidLayouts = mapOf("US" to "US", "ES" to "Spanish")
private enum class HidSequence(val title: String) {
    Prefix("Keys before the first code"), Separator("Separator between fields"),
}
private val shortcutModifiers = listOf("CTRL" to "Ctrl", "ALT" to "Alt", "SHIFT" to "Shift")
private val shortcutKeys = mapOf(
    "Letters" to ('A'..'Z').map(Char::toString),
    "Numbers" to ('0'..'9').map(Char::toString),
    "Function keys" to (1..12).map { "F$it" },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HidOutputSection(state: HidOutputState, connected: Boolean, production: Boolean, onAction: (HidOutputConfig?) -> Unit) {
    if (!state.supported) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fields by remember { mutableStateOf("PU") }
    var prefixPattern by remember { mutableStateOf("") }
    var shortcut by remember { mutableStateOf("NONE") }
    var pattern by remember { mutableStateOf("{TAB}") }
    var ending by remember { mutableStateOf("ENTER") }
    var layout by remember { mutableStateOf("US") }
    var editingSequence by remember { mutableStateOf<HidSequence?>(null) }
    var editingEnding by remember { mutableStateOf(false) }
    var editingShortcut by remember { mutableStateOf(false) }
    LaunchedEffect(state.revision) {
        state.config?.let {
            fields = it.fields
            prefixPattern = hidSeparatorPattern(it.prefix)
            shortcut = it.hotkey
            pattern = hidSeparatorPattern(it.separator)
            ending = it.ending
            layout = it.layout
        }
    }
    val endings = hidEndingOptions(state.extendedKeys)
    val allowArrows = state.arrowSeparators || state.extendedKeys
    val prefix = if (state.prefixKeys) decodeHidSeparator(prefixPattern, allowArrows, state.extendedKeys) else ""
    val separator = decodeHidSeparator(pattern, allowArrows, state.extendedKeys)
    val hotkey = if (state.readHotkey) shortcut else "NONE"
    val draft = if (prefix != null && separator != null && validHidHotkey(hotkey))
        HidOutputConfig(fields, separator, ending, layout, prefix, hotkey) else null
    val enabled = connected && !state.busy
    CollapsibleSettingsSection(
        if (production) "HID OUTPUT" else "HID OUTPUT · PROTOTYPE",
        "Pallet / location · keyboard format", expanded, { expanded = !expanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HidSettingsGroup("Fields") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                    hidFields.forEach { (value, label) ->
                        FilterChip(fields == value, { fields = value }, { Text(label) }, enabled = enabled)
                    }
                }
            }
            HidSettingsGroup("Prefixes, separators and suffixes") {
                if (state.prefixKeys) HidSettingRow("Initial keys") {
                    HidPickerButton(prefixPattern.ifEmpty { "None" }, enabled) { editingSequence = HidSequence.Prefix }
                }
                HidSettingRow("Separator") {
                    HidPickerButton(pattern.ifEmpty { "None" }, enabled && fields == "PU") { editingSequence = HidSequence.Separator }
                }
                HidSettingRow("Final key") {
                    HidPickerButton(endings[ending] ?: ending, enabled) { editingEnding = true }
                }
                Text("Preview", style = MaterialTheme.typography.labelMedium)
                Text(draft?.preview() ?: "Invalid key sequence", fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HidSettingsGroup("Language") {
                HidSettingRow("Keyboard layout") { HidValueMenu(hidLayouts, layout, enabled) { layout = it } }
                Text("Match the receiver keyboard layout.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.readHotkey) HidSettingsGroup("Shortcuts") {
                HidSettingRow("Read shortcut (Windows)") {
                    HidPickerButton(shortcutLabel(hotkey), enabled) { editingShortcut = true }
                }
            }
        }
        if (state.message.isNotBlank()) Text(state.message, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaintenanceActionButton("Refresh", { onAction(null) }, MaintenanceActionTone.INFO, Modifier.weight(1f), enabled)
            MaintenanceActionButton("Save format", { draft?.let(onAction) }, MaintenanceActionTone.SUCCESS,
                Modifier.weight(1f), enabled && draft != null && state.config != null)
        }
        Text("Saving requires PIN / ACCESS.", fontSize = 11.sp)
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
    if (editingEnding && enabled) HidEndingDialog(endings, ending, { editingEnding = false }) {
        ending = it
        editingEnding = false
    }
    if (editingShortcut && enabled && state.readHotkey) HidShortcutDialog(shortcut, { editingShortcut = false }) {
        shortcut = it
        editingShortcut = false
    }
}

@Composable
private fun HidSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun HidEndingDialog(options: Map<String, String>, initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Final key") },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .selectable(selected == key, role = Role.RadioButton, onClick = { selected = key }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == key, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton({ onApply(selected) }, enabled = selected in options) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun shortcutLabel(value: String): String = if (value == "NONE") "Disabled" else
    value.split('+').joinToString(" + ") { key -> shortcutModifiers.firstOrNull { it.first == key }?.second ?: key }

@Composable
private fun HidShortcutDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val parts = initial.split('+')
    var ctrl by rememberSaveable { mutableStateOf("CTRL" in parts) }
    var alt by rememberSaveable { mutableStateOf("ALT" in parts) }
    var shift by rememberSaveable { mutableStateOf("SHIFT" in parts) }
    var key by rememberSaveable { mutableStateOf(parts.last().takeUnless { it == "NONE" }.orEmpty()) }
    var group by rememberSaveable { mutableStateOf(shortcutKeys.entries.firstOrNull { key in it.value }?.key ?: "Letters") }
    val draft = buildList {
        if (ctrl) add("CTRL")
        if (alt) add("ALT")
        if (shift) add("SHIFT")
        if (key.isNotEmpty()) add(key)
    }.joinToString("+")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read shortcut (Windows)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(ctrl, { ctrl = !ctrl }, { Text("Ctrl") })
                    FilterChip(alt, { alt = !alt }, { Text("Alt") })
                    FilterChip(shift, { shift = !shift }, { Text("Shift") })
                }
                HidValueMenu(shortcutKeys.keys.associateWith { it }, group, true) { group = it }
                Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    shortcutKeys.getValue(group).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { option ->
                                FilterChip(key == option, { key = option }, { Text(option) }, modifier = Modifier.weight(1f))
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (draft.isNotEmpty()) Text(shortcutLabel(draft), style = MaterialTheme.typography.bodyMedium)
                if (!validHidHotkey(draft)) {
                    Text("Choose Ctrl or Alt and a key.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ onApply("NONE") }) { Text("Disable shortcut") }
            }
        },
        confirmButton = { TextButton({ onApply(draft) }, enabled = validHidHotkey(draft)) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
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
