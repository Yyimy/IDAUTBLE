package com.example.idautble

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HidOutputSection(state: HidOutputState, connected: Boolean, production: Boolean, onAction: (HidOutputConfig?) -> Unit) {
    if (!state.supported) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fields by remember { mutableStateOf("PU") }
    var pattern by remember { mutableStateOf("{TAB}") }
    var ending by remember { mutableStateOf("ENTER") }
    var layout by remember { mutableStateOf("US") }
    // Only an actual PLC response replaces the editable draft.
    LaunchedEffect(state.revision) {
        state.config?.let { fields = it.fields; pattern = hidSeparatorPattern(it.separator); ending = it.ending; layout = it.layout }
    }
    val separator = decodeHidSeparator(pattern)
    val draft = separator?.let { HidOutputConfig(fields, it, ending, layout) }
    val enabled = connected && !state.busy
    CollapsibleSettingsSection(if (production) "HID OUTPUT" else "HID OUTPUT · PROTOTYPE", "Pallet / location · keyboard format", expanded, { expanded = !expanded }) {
        Text("Fields", fontSize = 11.sp)
        HidOutputChoices(listOf("P" to "Pallet", "U" to "Location", "PU" to "Both"), fields, enabled) { fields = it }
        OutlinedTextField(value = pattern, onValueChange = { pattern = it }, label = { Text("Separator between fields", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(), enabled = enabled && fields == "PU", singleLine = true, isError = separator == null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("{TAB}", "{ENTER}", "{SPACE}").forEach { token ->
                TextButton(onClick = { pattern += token }, enabled = enabled && fields == "PU") { Text(token, fontSize = 10.sp) }
            }
            TextButton(onClick = { pattern = "" }, enabled = enabled && fields == "PU") { Text("Clear", fontSize = 10.sp) }
        }
        Text("Combine keys and ASCII text, e.g. {ENTER}{ENTER}. Max 32 characters/keys. Use {{ for a literal {.", fontSize = 11.sp)
        Text("Final key", fontSize = 11.sp)
        HidOutputChoices(listOf("ENTER" to "Enter", "TAB" to "Tab", "NONE" to "None"), ending, enabled) { ending = it }
        Text("Receiver keyboard layout", fontSize = 11.sp)
        HidOutputChoices(listOf("US" to "US", "ES" to "Spanish"), layout, enabled) { layout = it }
        Text("Must match the physical keyboard layout set on the receiving Android or PC.", fontSize = 11.sp)
        Text("Preview: ${draft?.preview() ?: "Invalid separator"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (state.message.isNotBlank()) Text(state.message, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaintenanceActionButton("Refresh", { onAction(null) }, MaintenanceActionTone.INFO, Modifier.weight(1f), enabled)
            MaintenanceActionButton("Save format", { draft?.let(onAction) }, MaintenanceActionTone.SUCCESS, Modifier.weight(1f), enabled && draft != null && state.config != null)
        }
        Text(if (production) "Save requires PIN / ACCESS. The assigned receiver uses the saved format for complete reads." else "Save requires PIN / ACCESS. Send test text in HID RECEIVER uses the saved format. Real PLC readings are not enabled in this prototype.", fontSize = 11.sp)
    }
}

@Composable
private fun HidOutputChoices(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, title) ->
            FilterChip(selected = selected == key, onClick = { onSelect(key) }, label = { Text(title, fontSize = 11.sp) }, enabled = enabled)
        }
    }
}
