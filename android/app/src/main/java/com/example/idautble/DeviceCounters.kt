package com.example.idautble

data class DeviceCounters(
    val boots: Long,
    val readerTriggers: Long,
    val palletsLoaded: Long,
    val palletsUnloaded: Long,
    val failedReads: Long,
    val nextEventId: Long,
    val pendingEvents: Int,
    val oldestEventId: Long?,
    val ackMode: Boolean,
)

/** Unsigned firmware counters must not overflow Android's signed Int. */
fun parseDeviceCounters(text: String): DeviceCounters? {
    if (!text.startsWith("COUNTERS=")) return null
    val pairs = text.split(';').map { it.split('=', limit = 2) }
    if (pairs.any { it.size != 2 } || pairs.map { it[0] }.distinct().size != pairs.size) return null
    val f = pairs.associate { it[0] to it[1] }
    if (f["COUNTERS"] != "1") return null
    fun uint(key: String): Long? {
        val value = f[key] ?: return null
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toLongOrNull()?.takeIf { it in 0..4294967295L }
    }
    val next = uint("EVT_NEXT")?.takeIf { it > 0 } ?: return null
    val pending = uint("EVT_PENDING")?.takeIf { it <= 8 }?.toInt() ?: return null
    val oldest =
        when {
            pending == 0 && f["EVT_OLDEST"] == "-" -> null
            pending > 0 -> uint("EVT_OLDEST")?.takeIf { it in 1 until next } ?: return null
            else -> return null
        }
    val ack =
        when (f["ACKMODE"]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
    return DeviceCounters(
        uint("BOOT") ?: return null,
        uint("PON") ?: return null,
        uint("PAL_C") ?: return null,
        uint("PAL_D") ?: return null,
        uint("FAIL") ?: return null,
        next,
        pending,
        oldest,
        ack,
    )
}

/** Independent age and failure state: old counters are never presented as a new snapshot. */
data class CountersSnapshot(
    val counters: DeviceCounters? = null,
    val receivedMs: Long? = null,
    val busy: Boolean = false,
    val message: String = "",
) {
    fun querying() = copy(busy = true, message = "")

    fun receive(text: String, nowMs: Long): CountersSnapshot {
        val parsed = parseDeviceCounters(text)
        return if (parsed != null) CountersSnapshot(parsed, nowMs)
        else
            copy(
                busy = false,
                message =
                    if (counters == null) "Invalid counters response."
                    else "Invalid counters response. Previous snapshot retained.",
            )
    }

    fun timedOut() =
        copy(
            busy = false,
            message = "No counters response. Check the connection and refresh again.",
        )
}
