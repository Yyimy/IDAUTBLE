package com.example.idautble

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ReadingResultsSection(state: PlcReadingsState) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.receivedMs) {
        if (state.receivedMs == null) return@LaunchedEffect
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val age = deviceStatusAge(state.receivedMs, now)
    val reading = state.reading
    CollapsibleSettingsSection(
        title = "READINGS",
        summary = if (reading == null) "No reading received" else "Latest frame • ${age ?: 0}s ago",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        ProvideTextStyle(
            MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp)
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (state.invalidFrame) {
                    Text(
                        if (reading == null) "Invalid V2 frame. No reading available."
                        else "Invalid V2 frame. Showing the last valid reading.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (reading == null) {
                    Text(
                        "Send a reading command to view the PLC response.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ReadingCode("Pallet", reading.pallet)
                    ReadingCode("Location", reading.location)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.weight(1f).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val colour =
                                if (reading.photocell == null)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                            Canvas(Modifier.size(6.dp)) { drawCircle(colour) }
                            Text("Photocell", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when (reading.photocell) {
                                    true -> "1"
                                    false -> "0"
                                    null -> "—"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Row(
                            Modifier.weight(1f).semantics(mergeDescendants = true) {},
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Height", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                reading.heightMetres?.let { "$it m" } ?: "—",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReportedBattery(1, reading.battery1, Modifier.weight(1f))
                        ReportedBattery(2, reading.battery2, Modifier.weight(1f))
                    }
                    Text(
                        "${reading.deviceId} • ${if (reading.clockValid) reading.plcTime else "PLC clock unavailable"}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "— = not provided in this frame. Battery values as reported; slot presence is shown in Device status.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingCode(label: String, value: String?) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, Modifier.width(52.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    value ?: "Not provided",
                    fontFamily = if (value == null) FontFamily.Default else FontFamily.Monospace,
                    fontWeight = if (value == null) FontWeight.Normal else FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color =
                        if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ReportedBattery(number: Int, percent: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("B$number: ${percent?.let { "$it%" } ?: "—"}", fontWeight = FontWeight.Medium)
        LinearProgressIndicator(
            progress = { (percent ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clearAndSetSemantics {},
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
        )
    }
}
