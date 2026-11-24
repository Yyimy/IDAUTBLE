package com.example.idautble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal val soundDurationRange = 50..500
internal const val defaultSoundDurationMs = 150

internal enum class PlcSound(val label: String) {
    STARTUP("Startup melody"),
    PCC_READ("Reading captured"),
    PCC_ERROR("Error"),
    CC_READ("Reading captured"),
    CC_ERROR("Error"),
}

internal data class PlcSoundConfig(val flags: Int, val durations: List<Int>) {
    fun enabled(sound: PlcSound) = flags and (1 shl sound.ordinal) != 0
    fun wire() = (listOf(flags) + durations).joinToString(",", prefix = "SOUNDCFG=")
}

internal fun parsePlcSoundConfig(line: String): PlcSoundConfig? {
    if (!line.startsWith("SOUNDCFG=")) return null
    val fields = line.substringAfter('=').split(',')
    if (fields.size != PlcSound.entries.size) return null
    val values = fields.map {
        if (it.isEmpty() || it.length > 3 || it.any { digit -> digit !in '0'..'9' }) return null
        it.toInt()
    }
    val durations = values.drop(1)
    val maxFlags = (1 shl PlcSound.entries.size) - 1
    if (values[0] !in 0..maxFlags || durations.any { it !in soundDurationRange }) return null
    return PlcSoundConfig(values[0], durations)
}

internal data class PlcSoundState(
    val supported: Boolean = false,
    val config: PlcSoundConfig? = null,
    val request: String? = null,
    val message: String = "",
)

@Composable
internal fun PlcSoundSection(
    state: PlcSoundState,
    authorised: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    send: (String) -> Unit,
) {
    if (!state.supported) return
    CollapsibleSettingsSection(
        title = "PLC SOUNDS",
        summary = "Startup, reading and error sounds",
        expanded = expanded,
        onToggle = onToggle,
    ) {
        state.config?.let { config ->
            PlcSoundEditor(config, authorised && state.request == null, send)
        }
        if (!authorised) Text("Unlock PIN / ACCESS to save or test sounds.")
        if (state.message.isNotEmpty()) Text(state.message)
        MaintenanceActionButton(
            text = "Refresh",
            onClick = { send("SOUNDCFG?") },
            tone = MaintenanceActionTone.INFO,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.request == null,
        )
    }
}

@Composable
private fun PlcSoundEditor(config: PlcSoundConfig, enabled: Boolean, send: (String) -> Unit) {
    var flags by remember(config) { mutableStateOf(config.flags) }
    val durations = remember(config) { mutableStateListOf(*config.durations.map(Int::toString).toTypedArray()) }
    val parsed = durations.map { it.toIntOrNull()?.takeIf { ms -> ms in soundDurationRange } }
    val draft = if (parsed.all { it != null }) PlcSoundConfig(flags, parsed.filterNotNull()) else null

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (sound in PlcSound.entries) {
            val group = when (sound) {
                PlcSound.STARTUP -> "General"
                PlcSound.PCC_READ -> "PCC"
                PlcSound.CC_READ -> "CC"
                else -> null
            }
            if (group != null) {
                if (sound != PlcSound.STARTUP) HorizontalDivider()
                Text(group, style = MaterialTheme.typography.titleSmall)
            }
            val bit = 1 shl sound.ordinal
            val index = sound.ordinal - 1
            val ms = if (index < 0) defaultSoundDurationMs else parsed[index]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sound.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                val controlLabel = when (sound) {
                    PlcSound.STARTUP -> sound.label
                    PlcSound.PCC_READ, PlcSound.PCC_ERROR -> "PCC ${sound.label}"
                    PlcSound.CC_READ, PlcSound.CC_ERROR -> "CC ${sound.label}"
                }
                Switch(
                    checked = flags and bit != 0,
                    onCheckedChange = { flags = flags xor bit },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = controlLabel },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index >= 0) {
                    OutlinedTextField(
                        value = durations[index],
                        onValueChange = { input ->
                            durations[index] = input.filter { it in '0'..'9' }.take(3)
                        },
                        label = { Text("Duration (50–500 ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = ms == null,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                MaintenanceActionButton(
                    text = "Test",
                    onClick = { ms?.let { send("SOUNDTEST=${sound.ordinal},$it") } },
                    tone = MaintenanceActionTone.INFO,
                    enabled = enabled && ms != null,
                    modifier = if (index < 0) Modifier.fillMaxWidth() else Modifier,
                )
            }
        }
        MaintenanceActionButton(
            text = "Save sounds",
            onClick = { draft?.let { send(it.wire()) } },
            tone = MaintenanceActionTone.SUCCESS,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && draft != null && draft != config,
        )
    }
}
