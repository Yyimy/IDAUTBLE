package com.example.idautble

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
private val hidFieldOrders = mapOf(false to "Pallet → Location", true to "Location → Pallet")
private val hidLayouts = mapOf("US" to "US", "ES" to "Spanish")
private val shortcutModifiers = listOf("CTRL" to "Ctrl", "ALT" to "Alt", "SHIFT" to "Shift")
private val shortcutKeys = mapOf(
    "Letters" to ('A'..'Z').map(Char::toString),
    "Numbers" to ('0'..'9').map(Char::toString),
    "Function keys" to (1..12).map { "F$it" },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HidOutputSection(
    state: HidOutputState,
    connected: Boolean,
    production: Boolean,
    onAction: (HidOutputConfig?) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    workMode: WorkModeState,
    maintenanceAuthorised: Boolean,
    onWorkModeAction: (String) -> Unit,
) {
    if (!state.supported) return
    val showPcc = !workMode.supported || workMode.mode == "PCC"
    val showCc = workMode.mode == "CC"
    val showKeyboardSettings = showPcc || showCc
    var fields by remember { mutableStateOf("PU") }
    var locationFirst by remember { mutableStateOf(false) }
    var reportMs by remember { mutableIntStateOf(30) }
    var navigationPauseMs by remember { mutableIntStateOf(0) }
    var prefixPattern by remember { mutableStateOf("") }
    var shortcut by remember { mutableStateOf("NONE") }
    var pattern by remember { mutableStateOf("{TAB}") }
    var endingPattern by remember { mutableStateOf("{ENTER}") }
    var layout by remember { mutableStateOf("US") }
    var editingShortcut by remember { mutableStateOf(false) }
    LaunchedEffect(state.revision, showPcc) {
        state.config?.let {
            fields = it.fields
            locationFirst = it.locationFirst
            reportMs = it.reportMs
            navigationPauseMs = it.navigationPauseMs
            prefixPattern = hidSeparatorPattern(it.prefix)
            shortcut = it.hotkey
            pattern = hidSeparatorPattern(it.separator)
            endingPattern = hidSeparatorPattern(it.ending)
            layout = it.layout
        }
    }
    val allowArrows = state.arrowSeparators || state.extendedKeys
    val prefix = if (state.prefixKeys) decodeHidSeparator(prefixPattern, allowArrows, state.extendedKeys) else ""
    val separator = decodeHidSeparator(pattern, allowArrows, state.extendedKeys)
    val ending = decodeHidSeparator(endingPattern, allowArrows, state.extendedKeys)
    val endingValid = ending != null && (state.endingSequence || legacyHidEnding(ending, state.extendedKeys) != null)
    val hotkey = if (state.readHotkey) shortcut else "NONE"
    val draft = if (showCc) {
        state.config?.copy(layout = layout, hotkey = hotkey, reportMs = reportMs)
    } else if (prefix != null && separator != null && ending != null && endingValid && validHidHotkey(hotkey))
        HidOutputConfig(fields, separator, ending, layout, prefix, hotkey,
            state.outputOptions && locationFirst,
            if (state.outputOptions) reportMs else 30,
            if (state.outputOptions) navigationPauseMs else 0) else null
    val enabled = connected && !state.busy && workMode.request == null
    CollapsibleSettingsSection(
        if (production) "HID OUTPUT" else "HID OUTPUT · PROTOTYPE",
        when {
            showCc -> "CC · Multiple codes"
            workMode.supported -> workMode.mode ?: "Reading work mode"
            else -> "Pallet / location · keyboard format"
        }, expanded, onToggle,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkModeSection(workMode, connected && maintenanceAuthorised, onWorkModeAction)
            if (!showKeyboardSettings) return@Column
            if (showPcc) HidSettingsGroup("Fields") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                    hidFields.forEach { (value, label) ->
                        FilterChip(fields == value, { fields = value }, { Text(label) }, enabled = enabled)
                    }
                }
                if (state.outputOptions && fields == "PU") HidSettingRow("Field order") {
                    HidValueMenu(hidFieldOrders, locationFirst, enabled) { locationFirst = it }
                }
            }
            if (showPcc) HidSettingsGroup("Prefix, separator and suffix") {
                if (state.prefixKeys) {
                    HidSequenceField("Prefix", prefixPattern, enabled, allowArrows, state.extendedKeys) {
                        prefixPattern = it
                    }
                }
                HidSequenceField("Separator", pattern, enabled && fields == "PU", allowArrows, state.extendedKeys) {
                    pattern = it
                }
                HidSequenceField("Suffix", endingPattern, enabled, allowArrows, state.extendedKeys,
                    legacyEnding = !state.endingSequence) { endingPattern = it }
                Text("Preview", style = MaterialTheme.typography.labelMedium)
                Text(draft?.preview() ?: "Invalid key sequence", fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HidSettingsGroup("Language") {
                HidSettingRow("Keyboard layout") { HidValueMenu(hidLayouts, layout, enabled) { layout = it } }
                Text("Match the receiver keyboard layout.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.outputOptions) HidSettingsGroup("Typing") {
                HidSettingRow("Typing speed") {
                    HidValueMenu(hidTypingSpeeds, reportMs, enabled) { reportMs = it }
                }
                if (showPcc) HidSettingRow("Pause after Tab / Enter") {
                    HidValueMenu(hidNavigationPauses, navigationPauseMs, enabled) { navigationPauseMs = it }
                }
            }
            if (state.readHotkey) HidSettingsGroup("Shortcuts") {
                HidSettingRow("Read shortcut (Windows)") {
                    HidPickerButton(shortcutLabel(hotkey), enabled) { editingShortcut = true }
                }
            }
        }
        if (showKeyboardSettings) {
            if (state.message.isNotBlank()) Text(state.message, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MaintenanceActionButton(if (showCc) "Refresh keyboard settings" else "Refresh",
                    { onAction(null) }, MaintenanceActionTone.INFO, Modifier.weight(1f), enabled)
                MaintenanceActionButton(if (showCc) "Save keyboard settings" else "Save format",
                    { draft?.let(onAction) }, MaintenanceActionTone.SUCCESS, Modifier.weight(1f),
                    enabled && draft != null && state.config != null)
            }
            Text("Saving requires PIN / ACCESS.", fontSize = 11.sp)
        }
    }
    if (editingShortcut && enabled && state.readHotkey && showKeyboardSettings) HidShortcutDialog(shortcut, { editingShortcut = false }) {
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
private fun <T> HidValueMenu(options: Map<T, String>, value: T, enabled: Boolean, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HidPickerButton(options[value] ?: value.toString(), enabled) { open = true }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(key); open = false })
            }
        }
    }
}

@Composable
internal fun HidSequenceField(
    label: String,
    pattern: String,
    enabled: Boolean,
    allowArrows: Boolean = true,
    extended: Boolean = true,
    legacyEnding: Boolean = false,
    maxLength: Int = HID_SEPARATOR_LIMIT,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HidSettingRow(label) {
        HidPickerButton(pattern.ifEmpty { "None" }, enabled) { open = true }
    }
    if (open && enabled) {
        HidSequenceDialog(label, pattern, allowArrows, extended, legacyEnding, maxLength, { open = false }) {
            onChange(it)
            open = false
        }
    }
}

@Composable
private fun HidSequenceDialog(
    title: String,
    initial: String,
    allowArrows: Boolean,
    extended: Boolean,
    legacyEnding: Boolean,
    maxLength: Int,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf(false) }
    val decoded = decodeHidSeparator(draft, allowArrows, extended)
    val limit = if (legacyEnding) 1 else maxLength
    val valid = decoded != null && decoded.length <= limit &&
        (!legacyEnding || legacyHidEnding(decoded, extended) != null)
    val keys = hidSeparatorOptions(allowArrows, extended).filter {
        !legacyEnding || legacyHidEnding(decodeHidSeparator(it, allowArrows, extended).orEmpty(), extended) != null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3, isError = !valid,
                    label = { Text("Sequence") },
                    supportingText = {
                        Text(when {
                            legacyEnding -> "This firmware supports one suffix key."
                            !valid -> "Use valid keys or ASCII text, up to $limit items."
                            else -> "${decoded?.length} / $limit keys or characters"
                        })
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        HidPickerButton("Add key", decoded != null && decoded.length < limit) { open = true }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                            keys.forEach { key ->
                                DropdownMenuItem(text = { Text(key) }, onClick = { draft += key; open = false })
                            }
                        }
                    }
                    TextButton(onClick = { draft = "" }) { Text("Clear") }
                }
                if (!legacyEnding) Text("Keys are sent in order. You can also type ASCII text. Use {{ for a literal {.", fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }, enabled = valid) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

