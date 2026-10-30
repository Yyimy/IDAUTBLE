package com.example.idautble

/** Presentation only: colours do not change PLC priorities or battery protection. */
enum class StatusTone {
    GOOD,
    WARNING,
    FAULT,
    INFO,
    NEUTRAL,
}

fun ledStatusTone(mode: String, fresh: Boolean): StatusTone =
    if (!fresh) StatusTone.NEUTRAL
    else
        when (mode) {
            "READY" -> StatusTone.GOOD
            "DISCONNECTED",
            "BAT_LOW" -> StatusTone.WARNING
            "BAT_CRITICAL",
            "ERROR" -> StatusTone.FAULT
            "OTA" -> StatusTone.INFO
            else -> StatusTone.NEUTRAL
        }

/** Cadence from firmware ledPatternOn. Phase is a preview, not a hardware timestamp. */
fun ledPreviewOn(mode: String, elapsedMs: Long, fresh: Boolean): Boolean {
    if (!fresh) return false
    val elapsed = elapsedMs.coerceAtLeast(0L)
    return when (mode) {
        "READY" -> true
        "DISCONNECTED" -> elapsed % 1000 < 500
        "BAT_LOW" -> (elapsed % 4000).let { it < 200 || it in 400..599 }
        "BAT_CRITICAL" -> (elapsed % 2000).let { it < 200 || it in 400..599 || it in 800..999 }
        "ERROR" -> elapsed % 400 < 200
        "OTA" -> elapsed % 1000 < 800
        else -> false
    }
}

/** Null means no usable level, never an empty battery at zero percent. */
fun batteryBarLevel(battery: BatteryStatus): Float? =
    if (battery.state == "PRESENT") battery.percent?.takeIf { it in 0..99 }?.div(100f) else null

fun batteryStatusTone(battery: BatteryStatus, fresh: Boolean): StatusTone {
    if (!fresh) return StatusTone.NEUTRAL
    return when (battery.state) {
        "ABSENT" -> StatusTone.NEUTRAL
        "STALE" -> StatusTone.WARNING
        "PRESENT" ->
            when (battery.percent) {
                null -> StatusTone.FAULT
                in 0..10 -> StatusTone.FAULT
                in 11..20 -> StatusTone.WARNING
                in 21..99 -> StatusTone.GOOD
                else -> StatusTone.FAULT
            }
        else -> StatusTone.FAULT
    }
}

/** Monotonic receipt time; stale snapshots must not animate or appear healthy. */
fun deviceStatusIsFresh(receivedMs: Long?, nowMs: Long, ready: Boolean): Boolean =
    ready && receivedMs != null && (nowMs - receivedMs).coerceAtLeast(0L) <= 60_000L
