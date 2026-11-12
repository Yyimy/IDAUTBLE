package com.example.idautble

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

internal data class DiagnosticEvent(val sequence: Long, val boot: Long, val seconds: Long, val reason: String)

private val diagnosticReason = Regex("[A-Z0-9_]{1,23}")

internal fun parseDiagnosticHistory(text: String): List<DiagnosticEvent>? {
    val payload = text.removePrefix("HISTORY=")
    if (payload == text) return null
    if (payload.isEmpty()) return emptyList()
    val rows = payload.split('|', limit = 9)
    if (rows.size > 8) return null
    return rows.map { row ->
        val fields = row.split(',', limit = 4)
        if (fields.size != 4 || !diagnosticReason.matches(fields[3])) return null
        val sequence = fields[0].toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL } ?: return null
        val boot = fields[1].toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL } ?: return null
        val seconds = fields[2].toLongOrNull()?.takeIf { it in 0..4_294_967L } ?: return null
        DiagnosticEvent(sequence, boot, seconds, fields[3])
    }
}

private fun diagnosticLabel(reason: String) = when (reason) {
    "TCP_CONNECTED" -> "TCP connected"
    "PONG_TIMEOUT" -> "TCP disconnected · PONG timeout"
    "SOCKET_CLOSED" -> "TCP disconnected · Socket closed"
    "WIFI_LOST" -> "TCP disconnected · Wi-Fi link lost"
    "WIFI_DISABLED" -> "TCP disconnected · Wi-Fi disabled"
    "CONFIG_CHANGED" -> "TCP disconnected · Network restarted"
    "OTA" -> "TCP disconnected · Firmware update"
    "WRITE_FAILED" -> "TCP disconnected · Write failed"
    "WRITE_TIMEOUT" -> "TCP disconnected · Write timeout"
    "EVENT_QUEUE_FULL" -> "Reading event queue full"
    else -> "Shutdown requested · ${shutdownReasonLabel(reason)}"
}

@Composable
internal fun DiagnosticHistorySection(
    events: List<DiagnosticEvent>?,
    supported: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    CollapsibleSettingsSection(
        "PLC HISTORY", events?.let { "${it.size} recent events" } ?: "Critical events from the PLC",
        expanded, onToggle,
    ) {
        Text("Newest first. Time is measured from the start of each boot.")
        if (!supported) Text("Connect to a PLC with compatible firmware to retrieve its history.")
        else if (events == null) Text("Waiting for PLC history.")
        else if (events.isEmpty()) Text("No events recorded.")
        events.orEmpty().forEach { event ->
            HorizontalDivider()
            Text("#${event.sequence} · Boot ${event.boot} · +${event.seconds} s", fontFamily = FontFamily.Monospace)
            Text(diagnosticLabel(event.reason))
        }
        MaintenanceActionButton(
            "Refresh history", onRefresh, MaintenanceActionTone.INFO, Modifier.fillMaxWidth(),
            enabled = supported,
        )
    }
}
