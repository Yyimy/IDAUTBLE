package com.example.idautble

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun DeviceStatusSection(
    status: DeviceStatus?,
    receivedMs: Long?,
    ready: Boolean,
    busy: Boolean,
    message: String,
    onRefresh: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(receivedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val age = deviceStatusAge(receivedMs, now)
    val old = age != null && age > 15
    val summary =
        when {
            status == null -> if (busy) "Querying the PLC..." else "Status not available"
            old -> "Out of date — ${age}s ago"
            else -> "Snapshot — ${age ?: 0}s ago"
        }
    CollapsibleSettingsSection(
        title = "DEVICE STATUS",
        summary = summary,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        MaintenanceActionButton(
            text = if (busy) "Querying..." else "Refresh status",
            onClick = onRefresh,
            tone = MaintenanceActionTone.INFO,
            modifier = Modifier.fillMaxWidth(),
            enabled = ready && !busy,
        )
        if (message.isNotBlank()) Text(message, fontSize = 12.sp)
        if (!ready) Text("Connect and wait for BLE to be ready.", fontSize = 12.sp)
        if (old)
            Text(
                "Out-of-date snapshot. Refresh before making decisions.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        status?.let { s ->
            Text("Firmware: ${s.firmware}   •   ID: ${s.id}", fontSize = 12.sp)
            val ledColour =
                when (s.led) {
                    "READY" -> Color(0xFF2E7D32)
                    "BAT_LOW",
                    "DISCONNECTED" -> Color(0xFFBF5600)
                    "BAT_CRITICAL",
                    "ERROR" -> Color(0xFFC62828)
                    else -> Color(0xFF1976D2)
                }
            Text(ledDescription(s.led), color = ledColour, fontSize = 13.sp)
            Text(ledReasonDescription(s.reason), fontSize = 12.sp)
            s.batteries.forEachIndexed { index, b ->
                val reading =
                    when (b.state) {
                        "PRESENT" ->
                            String.format(Locale.UK, "%.2f V • %d%% estimated", b.volts, b.percent)
                        "ABSENT" -> "Not fitted"
                        "STALE" -> "Out-of-date measurement"
                        else -> "Invalid measurement"
                    }
                Text("Battery ${index + 1}: $reading", fontSize = 12.sp)
                b.ageMs?.let { Text("Sample age at query: ${it} ms", fontSize = 11.sp) }
            }
            val lowest =
                when (s.lowest) {
                    "B1" -> "Battery 1"
                    "B2" -> "Battery 2"
                    "BOTH" -> "Both (equal)"
                    else -> "None available"
                }
            Text("Lowest present battery: $lowest", fontSize = 12.sp)
            Text("Battery alerts: 3 s confirmation; recovery at 13% / 23%.", fontSize = 11.sp)
            Text(
                "WiFi: ${if (s.wifi) "enabled" else "disabled"} • TCP: ${if (s.tcp) "up" else "down"}",
                fontSize = 12.sp,
            )
            Text(
                "Secure BLE notifications: ${if (s.ble) "ready" else "not ready"}",
                fontSize = 12.sp,
            )
            Text(
                "Photocell: ${if (s.photo == "-") "stabilising" else s.photo} • RTC: ${s.rtc} • Output expander: ${s.outputs}",
                fontSize = 12.sp,
            )
            Text(
                "Power cut: ${s.cut}${if (s.cut == "NO") "" else " • ${s.cutReason}"}",
                fontSize = 12.sp,
            )
        }
    }
}
