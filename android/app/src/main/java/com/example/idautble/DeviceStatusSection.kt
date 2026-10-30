package com.example.idautble

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        MaintenanceActionButton(
            text = if (busy) "Querying..." else "Refresh status",
            onClick = onRefresh,
            tone = MaintenanceActionTone.INFO,
            modifier = Modifier.fillMaxWidth(),
            enabled = ready && !busy,
        )
        if (message.isNotBlank()) Text(message, fontSize = 12.sp)
        if (!ready) Text("Connect and wait for BLE to be ready.", fontSize = 12.sp)
        status?.let { s ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.id, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Firmware ${s.firmware}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color =
                        statusColour(if (fresh) StatusTone.INFO else StatusTone.WARNING)
                            .copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        if (fresh) "Snapshot • ${age}s" else "Out of date",
                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColour(if (fresh) StatusTone.INFO else StatusTone.WARNING),
                    )
                }
            }
            if (!fresh)
                Text(
                    "Out-of-date snapshot. Refresh to update the indicators.",
                    color = statusColour(StatusTone.WARNING),
                    fontSize = 12.sp,
                )

            StatusPanel {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LedPatternPreview(s.led, receivedMs, fresh && expanded)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "PLC LED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            ledDescription(s.led),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColour(ledStatusTone(s.led, fresh)),
                        )
                        Text(ledReasonDescription(s.reason), fontSize = 12.sp)
                    }
                }
                Text(
                    if (fresh) "Pattern preview • based on the last snapshot"
                    else "Pattern preview paused • snapshot out of date",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "BATTERIES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Stack in a narrow panel or at large font sizes rather than squeezing the values.
            BoxWithConstraints {
                val stacked =
                    maxWidth < 300.dp ||
                        androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.batteries.forEachIndexed { index, b ->
                            BatteryLevelCard(index + 1, b, s.lowest, fresh, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Text("Lowest present: $lowestLabel", fontSize = 12.sp)
            Text(
                "Battery alerts: 3 s confirmation; recovery at 13% / 23%.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "CONNECTIONS & INPUTS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusTile(
                    "WiFi",
                    if (s.wifi) "Enabled" else "Disabled",
                    if (s.wifi) StatusTone.INFO else StatusTone.NEUTRAL,
                    fresh,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                StatusTile(
                    "TCP",
                    if (s.tcp) "Up" else "Down",
                    if (s.tcp) StatusTone.GOOD
                    else if (s.wifi) StatusTone.WARNING else StatusTone.NEUTRAL,
                    fresh,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                StatusTile(
                    "Secure BLE",
                    if (s.ble) "Ready" else "Not ready",
                    if (s.ble) StatusTone.GOOD else StatusTone.WARNING,
                    fresh,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusTile(
                    "Photocell",
                    if (s.photo == "-") "Stabilising" else "Input ${s.photo}",
                    if (s.photo == "-") StatusTone.WARNING else StatusTone.INFO,
                    fresh,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                StatusTile(
                    "RTC",
                    s.rtc,
                    if (s.rtc == "OK") StatusTone.GOOD else StatusTone.FAULT,
                    fresh,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                StatusTile(
                    "Output expander",
                    s.outputs,
                    if (s.outputs == "OK") StatusTone.GOOD else StatusTone.FAULT,
                    fresh,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
            StatusPanel {
                val tone =
                    if (!fresh) StatusTone.NEUTRAL
                    else
                        when (s.cut) {
                            "NO" -> StatusTone.GOOD
                            "PENDING" -> StatusTone.WARNING
                            else -> StatusTone.FAULT
                        }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(statusColour(tone))
                    Text("Power cut", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        when (s.cut) {
                            "NO" -> "No cut"
                            "PENDING" -> "Pending"
                            else -> "Confirmed"
                        },
                        color = statusColour(tone),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (s.cut != "NO") Text(s.cutReason, fontSize = 11.sp)
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
        Modifier.size(54.dp).semantics {
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border =
            BorderStroke(
                1.dp,
                if (isLowest && fresh) colour.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(colour)
                Text("Battery $number", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                if (level != null) "${battery.percent}%" else "—",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = colour,
            )
            LinearProgressIndicator(
                progress = { level ?: 0f },
                modifier =
                    Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).clearAndSetSemantics {},
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
                fontSize = 11.sp,
            )
            if (!fresh)
                Text(
                    "Last snapshot",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            battery.ageMs?.let {
                Text(
                    "Sample at query: $it ms",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusTile(
    label: String,
    value: String,
    tone: StatusTone,
    fresh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colour = statusColour(if (fresh) tone else StatusTone.NEUTRAL)
    Surface(
        modifier.semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusDot(colour)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colour)
        }
    }
}

@Composable
private fun StatusDot(colour: Color) {
    Canvas(Modifier.size(8.dp)) { drawCircle(colour) }
}
