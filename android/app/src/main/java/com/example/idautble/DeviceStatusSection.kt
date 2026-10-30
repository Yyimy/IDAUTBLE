package com.example.idautble

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(receivedMs) {
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val age = deviceStatusAge(receivedMs, now)
    val fresh = deviceStatusIsFresh(receivedMs, now, ready)
    val summary =
        when {
            status == null -> if (busy) "Querying the PLC..." else "Status not available"
            !fresh -> "Out of date — ${age ?: 0}s ago"
            else -> "${ledDescription(status.led).substringBefore(" — ")} • ${age}s ago"
        }
    CollapsibleSettingsSection(
        title = "DEVICE STATUS",
        summary = summary,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        ProvideTextStyle(
            MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp)
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text(
                            status?.id ?: "Device status",
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        status?.let {
                            Text(
                                "FW ${it.firmware}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (fresh) "Snapshot • ${age}s" else "Out of date • ${age ?: 0}s",
                                fontSize = 10.sp,
                                color =
                                    statusColour(if (fresh) StatusTone.INFO else StatusTone.WARNING),
                            )
                        }
                    }
                    MaintenanceActionButton(
                        text = if (busy) "Querying..." else "Refresh",
                        onClick = onRefresh,
                        tone = MaintenanceActionTone.INFO,
                        enabled = ready && !busy,
                    )
                }
                if (message.isNotBlank()) {
                    Text(message, fontSize = 11.sp)
                }
                if (!ready) {
                    Text("Connect and wait for BLE to be ready.", fontSize = 11.sp)
                }
                status?.let { s ->
                    if (!fresh) {
                        Text(
                            "Out of date. Refresh to update the indicators.",
                            fontSize = 11.sp,
                            color = statusColour(StatusTone.WARNING),
                        )
                    }
                    StatusPanel {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            LedPatternPreview(s.led, receivedMs, fresh && expanded)
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    "PLC LED • ${ledDescription(s.led)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = statusColour(ledStatusTone(s.led, fresh)),
                                )
                                Text(ledReasonDescription(s.reason), fontSize = 11.sp)
                            }
                        }
                    }
                    BoxWithConstraints {
                        val stacked =
                            maxWidth < 220.dp ||
                                androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                        if (stacked) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                s.batteries.forEachIndexed { index, b ->
                                    BatteryLevelCard(
                                        index + 1,
                                        b,
                                        s.lowest,
                                        fresh,
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                s.batteries.forEachIndexed { index, b ->
                                    BatteryLevelCard(
                                        index + 1,
                                        b,
                                        s.lowest,
                                        fresh,
                                        Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                    val lowestLabel =
                        when (s.lowest) {
                            "B1" -> "Battery 1"
                            "B2" -> "Battery 2"
                            "BOTH" -> "Both (equal)"
                            else -> "None available"
                        }
                    Text(
                        "Lowest present: $lowestLabel",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusPanel {
                        Text(
                            "CONNECTIONS & INPUTS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ConnectionIndicators(s, fresh)
                    }
                    StatusPanel {
                        val tone =
                            when (s.cut) {
                                "NO" -> StatusTone.GOOD
                                "PENDING" -> StatusTone.WARNING
                                else -> StatusTone.FAULT
                            }
                        val cutLabel =
                            when (s.cut) {
                                "NO" -> "No cut"
                                "PENDING" -> "Pending"
                                else -> "Confirmed"
                            }
                        CompactStatusIndicator(
                            StatusItem("Power cut", cutLabel, tone),
                            fresh,
                            Modifier.fillMaxWidth(),
                        )
                        if (s.cut != "NO") {
                            Text(s.cutReason, fontSize = 11.sp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 32.dp)
                            .semantics {
                                stateDescription = if (detailsExpanded) "Expanded" else "Collapsed"
                            }
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Toggle diagnostic details",
                            ) {
                                detailsExpanded = !detailsExpanded
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            if (detailsExpanded) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("Diagnostic details", fontSize = 11.sp)
                    }
                    if (detailsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            s.batteries.forEachIndexed { index, b ->
                                val ageLabel = b.ageMs?.let { "$it ms" } ?: "unavailable"
                                Text(
                                    "Battery ${index + 1}: ${b.state} • sample at query: $ageLabel",
                                    fontSize = 10.sp,
                                )
                            }
                            Text(
                                "Battery alerts: 3 s confirmation; recovery at 13% / 23%.",
                                fontSize = 10.sp,
                            )
                            Text(
                                if (fresh)
                                    "LED pattern preview • based on the last snapshot; phase not synchronised."
                                else "LED preview paused • snapshot out of date.",
                                fontSize = 10.sp,
                            )
                            Text("Output status refers to the expander feedback.", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColour(tone: StatusTone): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (tone) {
        StatusTone.GOOD -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        StatusTone.WARNING -> if (dark) Color(0xFFFFB74D) else Color(0xFFBF5600)
        StatusTone.FAULT -> if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
        StatusTone.INFO -> if (dark) Color(0xFF90CAF9) else Color(0xFF1976D2)
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun StatusPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
private fun LedPatternPreview(mode: String, receivedMs: Long?, fresh: Boolean) {
    var on by remember(mode, receivedMs, fresh) { mutableStateOf(ledPreviewOn(mode, 0, fresh)) }
    LaunchedEffect(mode, receivedMs, fresh) {
        if (fresh && mode != "READY" && receivedMs != null) {
            while (isActive) {
                on = ledPreviewOn(mode, SystemClock.elapsedRealtime() - receivedMs, true)
                delay(25)
            }
        }
    }
    val colour = statusColour(ledStatusTone(mode, fresh))
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    Canvas(
        Modifier.size(22.dp).semantics {
            // Stable across individual flashes: TalkBack should not announce every pulse.
            contentDescription =
                "LED pattern preview. ${ledDescription(mode)}. ${if (fresh) "Snapshot available" else "Paused, out of date"}."
        }
    ) {
        drawCircle(if (on) colour.copy(alpha = 0.12f) else off.copy(alpha = 0.35f))
        drawCircle(if (on) colour else off, radius = size.minDimension * 0.32f)
        drawCircle(
            colour.copy(alpha = if (on) 1f else 0.25f),
            radius = size.minDimension * 0.32f,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
private fun BatteryLevelCard(
    number: Int,
    battery: BatteryStatus,
    lowest: String,
    fresh: Boolean,
    modifier: Modifier = Modifier,
) {
    val level = batteryBarLevel(battery)
    val colour = statusColour(batteryStatusTone(battery, fresh))
    val isLowest = battery.state == "PRESENT" && (lowest == "B$number" || lowest == "BOTH")
    Surface(
        modifier.semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border =
            BorderStroke(
                1.dp,
                if (isLowest && fresh) colour.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusDot(colour)
                Text(
                    "Battery $number",
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (level != null) "${battery.percent}%" else "—",
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colour,
                )
            }
            LinearProgressIndicator(
                progress = { level ?: 0f },
                modifier =
                    Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).clearAndSetSemantics {},
                color = colour,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
            )
            Text(
                when (battery.state) {
                    "PRESENT" -> String.format(Locale.UK, "%.2f V • estimated", battery.volts)
                    "ABSENT" -> "Not fitted"
                    "STALE" -> "Old measurement"
                    else -> "Invalid reading"
                },
                fontSize = 10.sp,
            )
            if (!fresh) {
                Text(
                    "Last snapshot",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class StatusItem(val label: String, val value: String, val tone: StatusTone)

@Composable
private fun ConnectionIndicators(s: DeviceStatus, fresh: Boolean) {
    val items =
        listOf(
            StatusItem(
                "WiFi",
                if (s.wifi) "Enabled" else "Disabled",
                if (s.wifi) StatusTone.INFO else StatusTone.NEUTRAL,
            ),
            StatusItem(
                "TCP",
                if (s.tcp) "Up" else "Down",
                if (s.tcp) StatusTone.GOOD
                else if (s.wifi) StatusTone.WARNING else StatusTone.NEUTRAL,
            ),
            StatusItem(
                "BLE secure",
                if (s.ble) "Ready" else "Not ready",
                if (s.ble) StatusTone.GOOD else StatusTone.WARNING,
            ),
            StatusItem(
                "Photocell",
                if (s.photo == "-") "Stabilising" else "Input ${s.photo}",
                if (s.photo == "-") StatusTone.WARNING else StatusTone.INFO,
            ),
            StatusItem("RTC", s.rtc, if (s.rtc == "OK") StatusTone.GOOD else StatusTone.FAULT),
            StatusItem(
                "Output expander",
                s.outputs,
                if (s.outputs == "OK") StatusTone.GOOD else StatusTone.FAULT,
            ),
        )
    BoxWithConstraints {
        val columns =
            if (
                maxWidth < 240.dp ||
                    androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
            )
                1
            else 2
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.chunked(columns).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { item ->
                        CompactStatusIndicator(item, fresh, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactStatusIndicator(
    item: StatusItem,
    fresh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colour = statusColour(if (fresh) item.tone else StatusTone.NEUTRAL)
    Row(
        modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusDot(colour)
        Text(
            item.label,
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(item.value, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colour)
    }
}

@Composable
private fun StatusDot(colour: Color) {
    Canvas(Modifier.size(6.dp)) { drawCircle(colour) }
}
