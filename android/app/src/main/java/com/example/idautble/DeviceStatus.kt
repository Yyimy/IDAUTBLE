package com.example.idautble

data class BatteryStatus(val state: String, val volts: Double?, val percent: Int?, val ageMs: Long?)

data class DeviceStatus(
    val firmware: String,
    val id: String,
    val led: String,
    val reason: String,
    val batteries: List<BatteryStatus>,
    val lowest: String,
    val wifi: Boolean,
    val tcp: Boolean,
    val ble: Boolean,
    val photo: String,
    val rtc: String,
    val outputs: String,
    val cut: String,
    val cutReason: String,
)

/** Strict, versioned snapshot. A partial or malformed response never replaces good data. */
fun parseDeviceStatus(text: String): DeviceStatus? {
    if (!text.startsWith("STATUS=")) return null
    val pairs = text.split(';').map { it.split('=', limit = 2) }
    if (pairs.any { it.size != 2 } || pairs.map { it[0] }.distinct().size != pairs.size) return null
    val f = pairs.associate { it[0] to it[1] }
    if (f["STATUS"] != "1") return null
    val firmware = f["FW"]?.takeIf { it.isNotBlank() } ?: return null
    val id = f["ID"]?.takeIf { validEquipmentId(it) } ?: return null
    val led =
        f["LED"]?.takeIf {
            it in setOf("READY", "DISCONNECTED", "BAT_LOW", "BAT_CRITICAL", "ERROR", "OTA")
        } ?: return null
    val reason = f["WHY"]?.takeIf { it.isNotBlank() } ?: return null
    val batteries =
        (1..2).map { i ->
            val state = f["B$i"] ?: return null
            if (state !in setOf("PRESENT", "ABSENT", "INVALID", "STALE")) return null
            val v = f["B${i}V"] ?: return null
            val p = f["B${i}P"] ?: return null
            val a = f["B${i}AGE"] ?: return null
            val volts =
                if (v == "-") null
                else v.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..60.0 } ?: return null
            val percent =
                if (p == "-") null else p.toIntOrNull()?.takeIf { it in 0..99 } ?: return null
            val age =
                if (a == "-") null
                else a.toLongOrNull()?.takeIf { it in 0..4294967295L } ?: return null
            when (state) {
                "PRESENT" ->
                    if (
                        volts == null ||
                            volts <= 2.0 ||
                            percent == null ||
                            age == null ||
                            age > 3000
                    )
                        return null
                "ABSENT" ->
                    if (
                        volts == null || volts > 2.0 || percent != null || age == null || age > 3000
                    )
                        return null
                "STALE" ->
                    if (volts != null || percent != null || age == null || age <= 3000) return null
                "INVALID" -> if (volts != null || percent != null || age != null) return null
            }
            BatteryStatus(state, volts, percent, age)
        }
    val lowest = f["LOWEST"]?.takeIf { it in setOf("NONE", "B1", "B2", "BOTH") } ?: return null
    fun flag(key: String): Boolean? =
        when (f[key]) {
            "1" -> true
            "0" -> false
            else -> null
        }
    val photo = f["PHOTO"]?.takeIf { it in setOf("0", "1", "-") } ?: return null
    val rtc = f["RTC"]?.takeIf { it in setOf("OK", "FAIL") } ?: return null
    val outputs = f["OUT"]?.takeIf { it in setOf("OK", "FAIL") } ?: return null
    val cut = f["CUT"]?.takeIf { it in setOf("NO", "PENDING", "CONFIRMED") } ?: return null
    return DeviceStatus(
        firmware,
        id,
        led,
        reason,
        batteries,
        lowest,
        flag("WIFI") ?: return null,
        flag("TCP") ?: return null,
        flag("BLE") ?: return null,
        photo,
        rtc,
        outputs,
        cut,
        f["REASON"] ?: return null,
    )
}

fun deviceStatusAge(receivedMs: Long?, nowMs: Long): Long? =
    receivedMs?.let { ((nowMs - it).coerceAtLeast(0)) / 1000 }

fun ledDescription(mode: String): String =
    when (mode) {
        "READY" -> "Ready — steady light"
        "DISCONNECTED" -> "Required link missing — slow flashing"
        "BAT_LOW" -> "Low battery — 2 flashes every 4 s"
        "BAT_CRITICAL" -> "Critical battery — 3 flashes every 2 s"
        "ERROR" -> "Fault / power cut — fast flashing"
        "OTA" -> "OTA — 800 ms on / 200 ms off"
        else -> mode
    }

fun ledReasonDescription(reason: String): String =
    when (reason) {
        "LINK_READY" -> "Required communication link is ready"
        "BLE_REQUIRED" -> "Waiting for a secure BLE subscriber"
        "TCP_REQUIRED" -> "WiFi is enabled; waiting for the TCP server"
        "BATTERY_LOW" -> "Lowest present battery: low band"
        "BATTERY_CRITICAL" -> "Lowest present battery: critical band"
        "POWER_CUT" -> "Power cut requested"
        "RTC_INVALID" -> "RTC time is invalid or unavailable"
        "OUTPUT_FAULT" -> "Output expander fault"
        "B1_INVALID" -> "Battery 1 measurement is invalid"
        "B2_INVALID" -> "Battery 2 measurement is invalid"
        "B1_STALE" -> "Battery 1 measurement is out of date"
        "B2_STALE" -> "Battery 2 measurement is out of date"
        "NO_BATTERY" -> "No battery detected"
        "OTA_ACTIVE" -> "OTA mode is active"
        else -> reason
    }
