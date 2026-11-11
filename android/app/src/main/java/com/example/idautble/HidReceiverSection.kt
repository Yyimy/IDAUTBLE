package com.example.idautble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HidReceiverSection(state: HidReceiverState, connected: Boolean, authorised: Boolean, onAction: (String) -> Unit, configurableFormat: Boolean = false, expanded: Boolean, onToggle: () -> Unit) {
    if (!state.supported) return
    var confirmForget by remember { mutableStateOf(false) }
    var selectedPairMode by rememberSaveable { mutableStateOf("WINDOWS") }
    var androidPin by rememberSaveable { mutableStateOf("") }
    val info = state.info
    val saved = info?.identity != null
    val enabled = connected && !state.busy
    CollapsibleSettingsSection(
        title = if (state.production) "HID RECEIVER" else "HID RECEIVER · PROTOTYPE",
        summary = when { info?.ready == true -> "Receiver ready"; saved -> "Saved · waiting for receiver"; else -> "No receiver assigned" },
        expanded = expanded,
        onToggle = onToggle,
    ) {
        Text(info?.identity ?: "Not assigned", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("The saved receiver regains its role after a secure reconnection. The receiver must connect from its own Bluetooth settings.", fontSize = 11.sp)
        if (!authorised) Text("Enter the PIN in PIN / ACCESS to make changes.", fontSize = 11.sp)
        if (info?.window == true) Text("Assignment open · connect only the intended receiver.", fontSize = 11.sp)
        if (!saved && state.androidPairingSupported && info?.window == false) {
            Text("New HID receiver", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedPairMode == "WINDOWS", onClick = { selectedPairMode = "WINDOWS" },
                    label = { Text("Windows PC") }, enabled = enabled)
                FilterChip(selected = selectedPairMode == "ANDROID", onClick = { selectedPairMode = "ANDROID" },
                    label = { Text("Android terminal") }, enabled = enabled)
            }
        }
        if (info?.window == true && info.pairMode == "ANDROID") {
            Text("On the new Android terminal, tap IS-P in Bluetooth settings. Enter the six-digit code shown there in this app, not in Android. The code is different from the maintenance PIN.", fontSize = 11.sp)
            OutlinedTextField(
                value = androidPin,
                onValueChange = { androidPin = it.filter { c -> c in '0'..'9' }.take(6) },
                label = { Text("Android pairing code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            )
            MaintenanceActionButton(
                "Submit pairing code",
                { onAction("HIDPIN=$androidPin"); androidPin = "" },
                MaintenanceActionTone.SUCCESS,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && authorised && info.pinRequired && androidPin.length == 6,
            )
            Text(if (info.pinRequired) "The receiver is requesting its one-time pairing code." else
                "Waiting for the Android pairing prompt. Use Refresh if needed.", fontSize = 11.sp)
        }
        if (info?.storageOk == false) Text("Receiver storage is unavailable.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        if (saved) Text("Forget the current assignment before choosing another receiver.", fontSize = 11.sp)
        if (state.message.isNotBlank()) Text(state.message, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaintenanceActionButton("Refresh", { onAction("HIDRECEIVER?") }, MaintenanceActionTone.INFO, modifier = Modifier.weight(1f), enabled = enabled)
            MaintenanceActionButton("Assign receiver", { onAction(if (selectedPairMode == "ANDROID" && state.androidPairingSupported) "HIDPAIR=ANDROID" else "HIDPAIR=1") },
                MaintenanceActionTone.SUCCESS, modifier = Modifier.weight(1f),
                enabled = enabled && authorised && !saved && info != null && info.storageOk && !info.window)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaintenanceActionButton("Cancel assignment", { onAction("HIDPAIR=0") }, MaintenanceActionTone.WARNING, modifier = Modifier.weight(1f),
                enabled = enabled && info?.window == true)
            MaintenanceActionButton("Forget receiver", { confirmForget = true }, MaintenanceActionTone.DANGER, modifier = Modifier.weight(1f),
                enabled = enabled && saved)
        }
        MaintenanceActionButton("Send test text", { onAction("HIDTYPE=TEST") }, MaintenanceActionTone.INFO, modifier = Modifier.fillMaxWidth(),
            enabled = enabled && saved && info?.ready == true)
        Text(if (configurableFormat) "Test uses the saved HID OUTPUT format with PALLET123 and LOC01. Focus an empty test field on the receiver first." else "Test sends PALLET123, TAB, LOC01 and ENTER. Focus an empty test field on the receiver first.", fontSize = 11.sp)
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget HID receiver?") },
        text = { Text("Automatic receiver assignment will stop. Bluetooth pairing on the terminals is retained.") },
        confirmButton = { TextButton(onClick = { confirmForget = false; onAction("HIDFORGET=1") }) { Text("Forget receiver") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
    )
}
