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
fun HidReceiverSection(state: HidReceiverState, connected: Boolean, authorised: Boolean, onAction: (String) -> Unit, configurableFormat: Boolean = false) {
    if (!state.supported) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val info = state.info
    val saved = info?.identity != null
    val enabled = connected && !state.busy
    CollapsibleSettingsSection(
        title = "HID RECEIVER · PROTOTYPE",
        summary = when { info?.ready == true -> "Receiver ready"; saved -> "Saved · waiting for receiver"; else -> "No receiver assigned" },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(info?.identity ?: "Not assigned", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("The saved receiver regains its role after a secure reconnection. The receiver must connect from its own Bluetooth settings.", fontSize = 11.sp)
        if (!authorised) Text("Enter the PIN in PIN / ACCESS to make changes.", fontSize = 11.sp)
        if (info?.window == true) Text("Assignment open · connect only the intended receiver.", fontSize = 11.sp)
        if (info?.storageOk == false) Text("Receiver storage is unavailable.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        if (saved) Text("Forget the current assignment before choosing another receiver.", fontSize = 11.sp)
        if (state.message.isNotBlank()) Text(state.message, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaintenanceActionButton("Refresh", { onAction("HIDRECEIVER?") }, MaintenanceActionTone.INFO, modifier = Modifier.weight(1f), enabled = enabled)
            MaintenanceActionButton("Assign receiver", { onAction("HIDPAIR=1") }, MaintenanceActionTone.SUCCESS, modifier = Modifier.weight(1f),
                enabled = enabled && !saved && info != null && info.storageOk && !info.window)
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
