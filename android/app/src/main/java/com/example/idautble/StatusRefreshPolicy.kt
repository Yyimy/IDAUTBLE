package com.example.idautble

/**
 * Monotonic times, scoped to one BLE connection. Foreground and transport gates live in Activity.
 */
internal class StatusRefreshPolicy {
    private var lastRequest: Long? = null
    private var lastSuccess: Long? = null
    private var readingSince: Long? = null
    private var afterReading = false
    // A user preference, deliberately retained across connection resets.
    var autoRefreshEnabled = true
    var refreshAfterReads = true
        set(value) {
            field = value
            if (!value) afterReading = false
        }

    var otaActive = false
        private set

    fun otaStarted() {
        otaActive = true
        cancelReading()
    }

    fun otaMessage(text: String) {
        if (text == "ERR=NOT_IDLE") {
            otaActive = false
        } else if (text.startsWith("OTA_ERR=")) {
            // BUSY/BAD_PARAMS may leave an existing OTA task running.
            otaActive = text.substringAfter('=') in setOf("BUSY", "BAD_PARAMS")
        } else if (text.startsWith("OTA=STATE=")) {
            val state = text.substringAfter("OTA=STATE=").substringBefore(';')
            otaActive = state != "0" && state != "7"
        } else if (text.startsWith("OTA=")) {
            otaStarted()
        }
    }

    fun reset() {
        otaActive = false
        lastRequest = null
        lastSuccess = null
        cancelReading()
    }

    fun requested(now: Long) {
        lastRequest = now
        afterReading = false
    }

    fun received(now: Long) {
        lastSuccess = now
    }

    fun readSent(now: Long) {
        readingSince = now
    }

    fun cancelReading() {
        readingSince = null
        afterReading = false
    }

    fun readPending(now: Long): Boolean {
        val since = readingSince ?: return false
        if (now - since < 8_000L) return true
        readingSince = null
        return false
    }

    // Any valid V2 reading can change counters, including unsolicited photocell events.
    // V2 has no request ID: it also completes any outstanding manual read wait.
    fun readReceived(): Boolean {
        readingSince = null
        if (otaActive || !refreshAfterReads) return false
        afterReading = true
        return true
    }

    fun shouldRefresh(now: Long): Boolean {
        if (otaActive || readPending(now)) return false
        if (afterReading) {
            // Keep pending work during the cooldown; continuous sensor traffic must
            // not postpone it forever or retry a failed query on every frame.
            if (
                lastRequest?.let { now - it < 5_000L } == true ||
                    lastSuccess?.let { now - it < 5_000L } == true
            )
                return false
            return true // requested() consumes the pending refresh, not this check.
        }
        // The initial query is independent of periodic refresh (including resume after pairing).
        return lastRequest?.let { autoRefreshEnabled && now - it >= 30_000L } ?: true
    }
}

internal fun isReadingCommand(data: ByteArray): Boolean =
    data.size == 4 &&
        data[0] == 0x1B.toByte() &&
        data[1].toInt().toChar() in "LBAPDI" &&
        data[2] == 0x0D.toByte() &&
        data[3] == 0x0A.toByte()
