package com.example.idautble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val MANUAL_LINE_LIMIT = 12

internal data class ManualLine(
    val value: TextFieldValue = TextFieldValue(),
    val hex: Boolean = false,
)

private data class TerminalCommand(val label: String, val value: String, val hex: Boolean = false)

private val terminalCommands = listOf(
    TerminalCommand("L · Full reading", "1B 4C 0D 0A", true),
    TerminalCommand("B · Battery", "1B 42 0D 0A", true),
    TerminalCommand("A · Height", "1B 41 0D 0A", true),
    TerminalCommand("P · Photocell", "1B 50 0D 0A", true),
    TerminalCommand("D · Pallet code", "1B 44 0D 0A", true),
    TerminalCommand("I · Location code", "1B 49 0D 0A", true),
    TerminalCommand("C · Connect / handshake", "1B 43 0D 0A", true),
    TerminalCommand("O · Power cut", "1B 4F 0D 0A", true),
    TerminalCommand("Firmware info", "INFO?"),
    TerminalCommand("Device status", "STATUS?"),
    TerminalCommand("Diagnostics", "DIAG?"),
    TerminalCommand("Counters", "COUNTERS?"),
    TerminalCommand("Get device ID", "ID?"),
    TerminalCommand("Set device ID", "ID=<ID>"),
    TerminalCommand("Set clock", "RTC=<YYYY-MM-DDThh:mm:ss>"),
    TerminalCommand("Get barcode prefixes", "CFG?"),
    TerminalCommand("Set barcode prefixes", "CFG=PFXP=<PALLET_PREFIX>;PFXU=<LOCATION_PREFIX>"),
    TerminalCommand("Get network settings", "NET?"),
    TerminalCommand("Network status", "NETSTAT?"),
    TerminalCommand("Set network settings", "NET=MODE=<MODE>;SSID=<SSID>;PWD=<PASSWORD>;IP=<IP>;GW=<GATEWAY>;MASK=<MASK>;HOST=<HOST>;PORT=<PORT>"),
    TerminalCommand("Get Wi-Fi state", "WIFI?"),
    TerminalCommand("Enable Wi-Fi", "WIFI=1"),
    TerminalCommand("Disable Wi-Fi", "WIFI=0"),
    TerminalCommand("Get height sensor state", "ALT?"),
    TerminalCommand("Enable height sensor", "ALT=1"),
    TerminalCommand("Disable height sensor", "ALT=0"),
    TerminalCommand("Authorise maintenance", "AUTH=<PIN>"),
    TerminalCommand("Lock maintenance", "AUTH=OFF"),
    TerminalCommand("Acknowledge event", "ACK=<EVENT_ID>"),
    TerminalCommand("Enable event acknowledgements", "ACKMODE=1"),
    TerminalCommand("Disable event acknowledgements", "ACKMODE=0"),
    TerminalCommand("Enable heartbeat", "HEARTBEAT=1"),
    TerminalCommand("Disable heartbeat", "HEARTBEAT=0"),
    TerminalCommand("OTA status", "OTA?"),
    TerminalCommand("Start OTA access point", "OTA=START"),
    TerminalCommand("Set OTA image", "OTA=URL=<URL>;SIZE=<SIZE>;SHA256=<SHA256>"),
    TerminalCommand("Abort OTA", "OTA!"),
    TerminalCommand("Get HID receiver", "HIDRECEIVER?"),
    TerminalCommand("Assign HID receiver", "HIDPAIR=1"),
    TerminalCommand("Cancel HID assignment", "HIDPAIR=0"),
    TerminalCommand("Forget HID receiver", "HIDFORGET=1"),
    TerminalCommand("Send HID test text", "HIDTYPE=TEST"),
    TerminalCommand("Get HID format", "HIDCFG?"),
    TerminalCommand("Set HID format", "HIDCFG=1;FIELDS=<FIELDS>;SEPHEX=<SEPHEX>;END=<END>;LAYOUT=<LAYOUT>"),
    TerminalCommand("HID diagnostics · prototype", "HIDTEST?"),
    TerminalCommand("HID status · prototype", "status"),
    TerminalCommand("Maintenance access · prototype", "AUTH?"),
    TerminalCommand("Help · prototype", "help"),
)

private val manualTokens = mapOf("ESC" to "1B", "CR" to "0D", "LF" to "0A", "STX" to "02", "ETX" to "03", "ACK" to "06", "NAK" to "15")
private val templateParameters = Regex("<[^<>]+>").let { pattern ->
    terminalCommands.flatMap { command -> pattern.findAll(command.value).map { it.value }.toList() }.toSet()
}

@Composable
internal fun ManualTerminalSection(
    lines: List<ManualLine>,
    onEdit: (Int, ManualLine) -> Unit,
    onAdd: () -> Unit,
    onRemoveLast: () -> Unit,
    connected: Boolean,
    onSendText: (String) -> Unit,
    onSendHex: (String) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    var active by remember { mutableIntStateOf(0) }
    var showCommands by remember { mutableStateOf(false) }
    var showTokens by remember { mutableStateOf(false) }
    val selected = active.coerceIn(lines.indices)

    CollapsibleSettingsSection("MANUAL TERMINAL", "Prepare and send TXT / HEX commands", expanded, onToggle) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showCommands = true }) { Text("Commands", fontSize = 11.sp) }
            Box {
                TextButton(onClick = { showTokens = true }) { Text("Tokens", fontSize = 11.sp) }
                DropdownMenu(expanded = showTokens, onDismissRequest = { showTokens = false }) {
                    manualTokens.forEach { (name, byte) ->
                        val line = lines[selected]
                        val token = if (line.hex) byte else "<$name>"
                        DropdownMenuItem(text = { Text(token) }, onClick = {
                            val start = line.value.selection.min
                            val end = line.value.selection.max
                            val inserted = if (line.hex) " $token " else token
                            val value = line.value.text.replaceRange(start, end, inserted)
                            onEdit(selected, line.copy(value = TextFieldValue(value, TextRange(start + inserted.length))))
                            showTokens = false
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Line ${selected + 1}", fontSize = 11.sp)
        }
        lines.forEachIndexed { index, line ->
            val input = line.value.text
            val hasParameter = !line.hex && templateParameters.any { it in input }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = index == selected,
                    onClick = { active = index; onEdit(index, line.copy(hex = !line.hex)) },
                    label = { Text("${index + 1} ${if (line.hex) "HEX" else "TXT"}", fontSize = 10.sp) },
                )
                CompactOutlinedFieldTFV(line.value, { onEdit(index, line.copy(value = it)) },
                    Modifier.weight(1f).onFocusChanged { if (it.hasFocus) active = index })
                MaintenanceActionButton("Send", {
                    if (line.hex) onSendHex(input) else onSendText(input)
                }, MaintenanceActionTone.SUCCESS, enabled = connected && input.isNotBlank() && !hasParameter)
            }
            if (hasParameter) Text("Replace the parameters in angle brackets before sending.", fontSize = 10.sp)
        }
        Row {
            TextButton(onClick = { onAdd(); active = lines.size }, enabled = lines.size < MANUAL_LINE_LIMIT) { Text("Add line", fontSize = 11.sp) }
            TextButton(onClick = { onRemoveLast() }, enabled = lines.size > 1) { Text("Remove last", fontSize = 11.sp) }
        }
    }
    if (showCommands) TerminalCommandDialog(selected + 1, onClose = { showCommands = false }) { command ->
        onEdit(selected, ManualLine(TextFieldValue(command.value, TextRange(command.value.length)), command.hex))
        showCommands = false
    }
}

@Composable
private fun TerminalCommandDialog(line: Int, onClose: () -> Unit, onSelect: (TerminalCommand) -> Unit) {
    var search by remember { mutableStateOf("") }
    val commands = terminalCommands.filter { it.label.contains(search, true) || it.value.contains(search, true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Commands · Line $line") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Search commands") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(commands) { command ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(command.label, fontSize = 12.sp)
                                    Text(command.value, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            },
                            onClick = { onSelect(command) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
