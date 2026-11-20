package com.example.idautble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class CcGap(val keys: String, val waitMs: Int)

internal data class CcOutput(val start: String, val end: String, val gaps: List<CcGap>) {
    fun wire(): String = (listOf(encodeHidSequence(start), encodeHidSequence(end)) +
        gaps.map { "${encodeHidSequence(it.keys)},${it.waitMs}" }).joinToString("|", prefix = "HIDCCCFG=")
}

internal fun parseCcOutput(line: String): CcOutput? {
    if (!line.startsWith("HIDCCCFG=")) return null
    val parts = line.substringAfter('=').split('|')
    if (parts.size != 7) return null
    val start = decodeHidSequence(parts[0]) ?: return null
    val end = decodeHidSequence(parts[1]) ?: return null
    val gaps = parts.drop(2).map { gap ->
        val keys = decodeHidSequence(gap.substringBefore(',')) ?: return null
        val wait = gap.substringAfter(',', "").toIntOrNull()?.takeIf { it in 0..60000 } ?: return null
        CcGap(keys, wait)
    }
    return CcOutput(start, end, gaps)
}

internal data class WorkModeState(
    val supported: Boolean = false,
    val mode: String? = null,
    val output: CcOutput? = null,
    val interrupted: Boolean = false,
    val request: String? = null,
    val revision: Long = 0,
    val message: String = "",
)

@Composable
internal fun WorkModeSection(state: WorkModeState, authorised: Boolean, send: (String) -> Unit) {
    if (!state.supported) return
    val enabled = authorised && state.request == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Work mode", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in listOf("PCC", "CC")) {
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { send("HIDWORK=$mode") },
                    label = { Text(mode) },
                    enabled = enabled && state.mode != null && state.mode != mode,
                )
            }
        }
        if (state.interrupted) {
            Text(
                "HID batch interrupted. Check the client screen before repeating.",
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(enabled = enabled, onClick = { send("HIDCCALERT=0") }) { Text("Acknowledge") }
        }
        if (state.message.isNotEmpty()) Text(state.message)
        TextButton(enabled = state.request == null, onClick = { send("HIDWORK?") }) {
            Text("Refresh work mode")
        }
        if (state.mode == "CC") {
            if (!authorised) Text("Unlock PIN / ACCESS to change CC settings.")
            state.output?.let { CcOutputEditor(it, state.revision, enabled, send) }
        }
    }
}

@Composable
private fun CcOutputEditor(config: CcOutput, revision: Long, enabled: Boolean, send: (String) -> Unit) {
    var start by remember(revision) { mutableStateOf(hidSeparatorPattern(config.start)) }
    var end by remember(revision) { mutableStateOf(hidSeparatorPattern(config.end)) }
    val keys = remember(revision) { mutableStateListOf(*config.gaps.map { hidSeparatorPattern(it.keys) }.toTypedArray()) }
    val waits = remember(revision) { mutableStateListOf(*config.gaps.map { it.waitMs.toString() }.toTypedArray()) }
    val gapDrafts = keys.indices.map { index ->
        val gapKeys = ccSequence(keys[index])
        val wait = waits[index].toIntOrNull()?.takeIf { it in 0..60000 }
        if (gapKeys == null || wait == null) null else CcGap(gapKeys, wait)
    }
    val startBytes = ccSequence(start)
    val endBytes = ccSequence(end)
    val draft = if (startBytes != null && endBytes != null && gapDrafts.none { it == null })
        CcOutput(startBytes, endBytes, gapDrafts.filterNotNull()) else null
    Text("CC · HID only. Use {ENTER}, {TAB} or leave a key sequence empty. Waits are measured after each separator.")
    CcKeysField("Before first code", start, enabled) { start = it }
    for (index in keys.indices) {
        Text("Code ${index + 1} → ${index + 2}", style = MaterialTheme.typography.labelLarge)
        CcKeysField("Separator", keys[index], enabled) { keys[index] = it }
        OutlinedTextField(
            value = waits[index], onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) waits[index] = it },
            label = { Text("Wait (ms)") }, singleLine = true, enabled = enabled,
            isError = waits[index].toIntOrNull()?.let { it !in 0..60000 } ?: true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    CcKeysField("After last code", end, enabled) { end = it }
    Button(enabled = enabled && draft != null && draft != config, onClick = { draft?.let { send(it.wire()) } }) {
        Text("Save CC settings")
    }
}

private fun ccSequence(value: String): String? = decodeHidSeparator(value)?.takeIf { it.length <= 20 }

@Composable
private fun CcKeysField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = change,
        label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = ccSequence(value) == null, modifier = Modifier.fillMaxWidth(),
    )
}
