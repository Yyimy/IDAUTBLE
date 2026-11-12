package com.example.idautble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val MAINTENANCE = "BLE Maintenance"
private const val MAINTENANCE_PIN = "BLE Maintenance · PIN / ACCESS"
private const val MAINTENANCE_TCP = "BLE Maintenance · TCP"
private const val READ_CHANNELS = "BLE Maintenance · BLE Client · TCP"
private const val HID_SHORTCUT = "BLE HID receiver · Shortcut app"
private const val USB = "USB Serial"

private data class CommandEntry(
    val syntax: String,
    val channel: String,
    val description: String,
)

private val commandGroups = linkedMapOf(
    "Binary commands" to listOf(
        CommandEntry("L (4C)", READ_CHANNELS, "Trigger a full reading and return the PLC data frame."),
        CommandEntry("B (42)", READ_CHANNELS, "Read the battery levels."),
        CommandEntry("A (41)", READ_CHANNELS, "Measure height when the height sensor is enabled."),
        CommandEntry("P (50)", READ_CHANNELS, "Read the current photocell state."),
        CommandEntry("D (44)", READ_CHANNELS, "Trigger a barcode scan and return the pallet field."),
        CommandEntry("I (49)", READ_CHANNELS, "Trigger a barcode scan and return the location field."),
        CommandEntry("C (43)", MAINTENANCE_TCP, "Request the connection greeting. The Android app uses this during its handshake."),
        CommandEntry("O (4F)", MAINTENANCE_PIN, "Request a PLC power-off. Use only when the equipment can be shut down."),
    ),
    "Status and diagnostics" to listOf(
        CommandEntry("INFO?", MAINTENANCE_TCP, "Read firmware and protocol versions and enabled options. BLE also reports maintenance capabilities and runtime settings."),
        CommandEntry("STATUS?", MAINTENANCE_TCP, "Read the current PLC status, including the clock and connection state."),
        CommandEntry("DIAG?", MAINTENANCE_TCP, "Read peripheral health, battery voltages, queued events, communication errors and shutdown state."),
        CommandEntry("COUNTERS?", MAINTENANCE_TCP, "Read boot, operation and event counters."),
        CommandEntry("HISTORY?", MAINTENANCE, "Read the eight newest diagnostic events in the legacy format."),
        CommandEntry("HISTORY?=<cursor>", MAINTENANCE, "Read history in pages with uptime. Start with 0 and use the next cursor from the response until it is 0."),
        CommandEntry("HISTORYTIME?=<cursor>", MAINTENANCE, "Read paged history with RTC timestamps where recorded. Start with 0."),
        CommandEntry("TCPTRACE?", MAINTENANCE, "Read the PLC's recent TCP traffic and connection trace over BLE."),
    ),
    "Device and reader configuration" to listOf(
        CommandEntry("ID?", MAINTENANCE_TCP, "Read the configured PLC identifier."),
        CommandEntry("ID=<name>", MAINTENANCE_PIN, "Set the identifier used in PLC data frames and the Bluetooth name."),
        CommandEntry("RTC=YYYY-MM-DDThh:mm:ss", MAINTENANCE_PIN, "Set the PLC clock while the scanner is idle."),
        CommandEntry("CFG?", MAINTENANCE_TCP, "Read the pallet and location barcode prefixes."),
        CommandEntry("CFG=PFXP=<pallet>;PFXU=<location>", MAINTENANCE_PIN, "Save the barcode prefixes while the scanner is idle."),
        CommandEntry("ALT?", MAINTENANCE_TCP, "Check whether height measurement is enabled."),
        CommandEntry("ALT=0 or ALT=1", MAINTENANCE_PIN, "Disable or enable height measurement while the scanner is idle."),
        CommandEntry("READERTIMEOUT=<ms>", MAINTENANCE_PIN, "Set the barcode reader timeout to 1000, 2000, 3000 or 5000 ms."),
        CommandEntry("IDLEMIN=<minutes>", MAINTENANCE_PIN, "Set the photocell inactivity shutdown delay from 1 to 1440 minutes. Read the current value with INFO? over BLE."),
    ),
    "Network and server" to listOf(
        CommandEntry("NET?", MAINTENANCE_TCP, "Read saved network settings, including advanced TCP options. The Wi-Fi password is masked."),
        CommandEntry("NET=<key>=<value>[;<key>=<value>…]", MAINTENANCE_PIN, "Save network settings while idle and reapply Wi-Fi. Keys are MODE (DHCP or STATIC), SSID, PWD, IP, GW, MASK, HOST and PORT."),
        CommandEntry("NETSTAT?", MAINTENANCE_TCP, "Read live Wi-Fi and TCP status, IP address and signal strength."),
        CommandEntry("WIFI?", MAINTENANCE_TCP, "Check whether Wi-Fi is enabled."),
        CommandEntry("WIFI=0 or WIFI=1", MAINTENANCE_PIN, "Disable or enable Wi-Fi while the scanner is idle. Disabling Wi-Fi also closes TCP."),
        CommandEntry("ACKMODE=0 or ACKMODE=1", MAINTENANCE_PIN, "Disable or enable event acknowledgements. Change this when the scan and event queues are empty."),
        CommandEntry("ACK=<event_id>", MAINTENANCE_TCP, "Confirm the oldest pending event after receipt when ACK mode is enabled. Use the event ID sent by the PLC."),
        CommandEntry("HEARTBEAT=0 or HEARTBEAT=1", MAINTENANCE_PIN, "Disable or enable the TCP heartbeat. Change this when the scan and event queues are empty."),
        CommandEntry("TCPADV=<heartbeat>,<ack>,<interval>,<timeout>[,<request_hex>,<response_hex>]", MAINTENANCE_PIN, "Set heartbeat and ACK flags (0 or 1), interval (5–120 s) and timeout (greater than the interval, up to 300 s). Optional hex values encode the 1–16 printable ASCII characters of each heartbeat message. Change this when the queues are empty."),
        CommandEntry("PONG or PONG;<text>", "TCP · Server response", "Reply when the PLC sends its default PING heartbeat. If a custom response is configured, send its exact text with matching case."),
    ),
    "BLE sessions and access" to listOf(
        CommandEntry("AUTH=<PIN>", MAINTENANCE, "Authorise protected commands for the current maintenance session with the maintenance access PIN."),
        CommandEntry("AUTH=OFF", MAINTENANCE, "Revoke maintenance access for this session."),
        CommandEntry("MAINTPING", MAINTENANCE, "Keep the maintenance session active. The app sends this automatically while in use."),
        CommandEntry("MAINTBYE", MAINTENANCE, "Release the maintenance session when the app disconnects."),
        CommandEntry("CLIENT", "BLE Client", "Register a secured client after subscribing to FFE1 notifications. Wait for CLIENT=READY before requesting readings."),
    ),
    "HID keyboard and shortcuts" to listOf(
        CommandEntry("HIDRECEIVER?", MAINTENANCE, "Read saved receiver, readiness and pairing window status."),
        CommandEntry("HIDRECEIVER2?", MAINTENANCE, "Read receiver status with the pairing method and Android pairing code request flag."),
        CommandEntry("HIDCFG?", MAINTENANCE, "Read legacy HID fields, separator, final key and layout."),
        CommandEntry("HIDCFG2?", MAINTENANCE, "Read the HID format including the initial key sequence."),
        CommandEntry("HIDCFG3?", MAINTENANCE, "Read the HID format including the Windows shortcut."),
        CommandEntry("HIDCFG4?", MAINTENANCE, "Read the HID format including the final key sequence."),
        CommandEntry("HIDCFG5?", MAINTENANCE, "Read the full HID format including field order and key timing."),
        CommandEntry("HIDCFG=1;FIELDS=<P|U|PU>;SEPHEX=<hex>;ENDHEX=<hex>;LAYOUT=<ES|US>", MAINTENANCE_PIN, "Save the HID format while idle. Optional keys are PREHEX, HOTKEY, ORDER (PU or UP) and TIMING (report and navigation pause in ms). Legacy clients can use END instead of ENDHEX."),
        CommandEntry("HIDPAIR=1", MAINTENANCE_PIN, "Open the assignment window for a Windows HID receiver."),
        CommandEntry("HIDPAIR=ANDROID", MAINTENANCE_PIN, "Open the Android HID receiver assignment window."),
        CommandEntry("HIDPIN=<6 digits>", MAINTENANCE_PIN, "Submit the code shown by the Android HID receiver while its pairing request is pending."),
        CommandEntry("HIDPAIR=0", MAINTENANCE_PIN, "Close the current HID receiver assignment window."),
        CommandEntry("HIDFORGET=1", MAINTENANCE_PIN, "Remove the assigned HID receiver and its Bluetooth bond from the PLC."),
        CommandEntry("HIDTYPE=TEST", MAINTENANCE_PIN, "Type a test message on the assigned HID receiver when it is ready."),
        CommandEntry("HIDHOTKEY?", HID_SHORTCUT, "Read the configured Windows reading shortcut over FFE1 from the assigned, ready HID receiver."),
        CommandEntry("HIDREAD", HID_SHORTCUT, "Queue a full reading over FFE1 from the assigned, ready HID receiver. Requires an enabled shortcut and an idle scanner."),
    ),
    "Firmware update" to listOf(
        CommandEntry("OTA?", MAINTENANCE_TCP, "Read the firmware update state, progress and last error."),
        CommandEntry("OTA=START", MAINTENANCE_PIN, "Start the OTA access point with the scanner idle and the photocell stable and clear."),
        CommandEntry("OTA=URL=<url>;SIZE=<bytes>;SHA256=<hex>", MAINTENANCE_PIN, "Provide the download URL, size and checksum when the OTA access point is waiting for parameters."),
        CommandEntry("OTA!", MAINTENANCE_PIN, "Request cancellation of the current OTA session."),
    ),
    "USB service console" to listOf(
        CommandEntry("info", USB, "Show the firmware version and local service credentials. Keep this output private."),
        CommandEntry("counters", USB, "Print the operation counters."),
        CommandEntry("counters reset", USB, "Reset the stored operation counters."),
        CommandEntry("counters help", USB, "Show the service console help."),
        CommandEntry("rtc", USB, "Print the PLC clock."),
        CommandEntry("i2c", USB, "Scan for devices on the internal I²C bus."),
        CommandEntry("i2c recover", USB, "Attempt to recover the internal I²C bus."),
        CommandEntry("ble forget <MAC>", USB, "Remove a specific Bluetooth bond using its address, for example AA:BB:CC:DD:EE:FF."),
        CommandEntry("ble maintenance", USB, "Enable maintenance-only Bluetooth advertising for service pairing."),
        CommandEntry("ble normal", USB, "Restore normal Bluetooth advertising."),
    ),
)

@Composable
internal fun CommandReferencePage() {
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    val groups = commandGroups.mapValues { (title, entries) ->
        entries.filter { entry ->
            title.contains(search, ignoreCase = true) ||
                entry.syntax.contains(search, ignoreCase = true) ||
                entry.channel.contains(search, ignoreCase = true) ||
                entry.description.contains(search, ignoreCase = true)
        }
    }.filterValues { it.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search commands or channels") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            if (search.isEmpty()) {
                item {
                    Text(
                        "BLE uses FFE0 / FFE1 with notifications enabled. TCP uses the connection opened by the PLC to the server. " +
                            "End text commands with CRLF. Binary commands use four bytes, for example 1B 4C 0D 0A for L. " +
                            "BLE Maintenance also accepts comma-separated hex bytes such as 0x1B,0x4C,0x0D,0x0A. " +
                            "The USB console runs at 115200 baud. Older firmware may support fewer commands.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            if (groups.isEmpty()) {
                item { Text("No matching commands", modifier = Modifier.padding(vertical = 8.dp)) }
            }
            groups.forEach { (title, entries) ->
                item(key = title) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                entries.forEach { entry ->
                    item(key = entry.syntax) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(entry.syntax, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text(entry.channel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(entry.description, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
