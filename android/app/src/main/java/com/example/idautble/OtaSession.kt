package com.example.idautble

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class OtaFirmware(val uri: String, val name: String, val size: Long, val sha256: String)

interface OtaServer {
    fun startServing()

    fun stopServing()
}

enum class OtaConfirmation {
    NONE,
    TRANSFERRING,
    WAITING_REBOOT,
    WAITING_VERSION,
    CONFIRMED,
    UNCONFIRMED,
    FAILED,
}

/** Activity-owned resources: removing the OTA tab must never stop or recreate the server. */
class OtaSession(private val serverFactory: (OtaFirmware) -> OtaServer) {
    var firmware by mutableStateOf<OtaFirmware?>(null)
        private set

    var fileName by mutableStateOf<String?>(null)
        private set

    var calculating by mutableStateOf(false)
        private set

    var serverRunning by mutableStateOf(false)
        private set

    var localError by mutableStateOf<String?>(null)
        private set

    var confirmation by mutableStateOf(OtaConfirmation.NONE)
        private set

    var versionBefore by mutableStateOf<String?>(null)
        private set

    var versionAfter by mutableStateOf<String?>(null)
        private set

    var deviceIp by mutableStateOf<String?>(null)
    private var server: OtaServer? = null
    private var selection = 0L
    var attempt = 0L
        private set

    private var targetAddress: String? = null
    private var sawRebootDisconnect = false
    private var readyAfterReboot = false

    val busy: Boolean
        get() =
            confirmation in
                setOf(
                    OtaConfirmation.TRANSFERRING,
                    OtaConfirmation.WAITING_REBOOT,
                    OtaConfirmation.WAITING_VERSION,
                )

    val confirmationText: String?
        get() =
            when (confirmation) {
                OtaConfirmation.WAITING_REBOOT ->
                    "Firmware accepted. Waiting for PLC restart and BLE reconnection..."
                OtaConfirmation.WAITING_VERSION -> "BLE reconnected. Checking running firmware..."
                OtaConfirmation.CONFIRMED ->
                    "PLC restarted. Running FW: $versionAfter. Previous FW: ${versionBefore ?: "unknown"}."
                OtaConfirmation.UNCONFIRMED ->
                    "Firmware accepted; running version not confirmed. Reconnect and refresh Device Status."
                else -> null
            }

    fun beginSelection(name: String): Long? {
        if (busy || serverRunning) return null
        firmware = null
        fileName = name
        calculating = true
        localError = null
        return ++selection
    }

    fun finishSelection(ticket: Long, file: OtaFirmware?) {
        if (ticket != selection) return
        calculating = false
        firmware = file?.takeIf { it.size > 0 && it.sha256.matches(Regex("[a-fA-F0-9]{64}")) }
        firmware?.let { fileName = it.name }
        if (firmware == null)
            localError = "Could not read the firmware file or calculate SHA256. Select it again."
    }

    fun startServer() {
        if (busy || server != null || firmware == null) return
        var candidate: OtaServer? = null
        try {
            candidate = serverFactory(firmware!!)
            candidate.startServing()
            server = candidate
            serverRunning = true
            localError = null
        } catch (_: Exception) {
            try {
                candidate?.stopServing()
            } catch (_: Exception) {}
            localError = "Could not start HTTP server. Retry or restart the app."
        }
    }

    fun stopServer() {
        if (!busy) releaseServer()
    }

    fun startSent(address: String?, currentVersion: String?) {
        ++attempt
        targetAddress = address
        versionBefore = currentVersion
        versionAfter = null
        sawRebootDisconnect = false
        readyAfterReboot = false
        localError = null
        confirmation = OtaConfirmation.TRANSFERRING
    }

    fun doneReceived() {
        if (confirmation == OtaConfirmation.TRANSFERRING)
            confirmation = OtaConfirmation.WAITING_REBOOT
    }

    fun disconnected(address: String?) {
        if (
            address == targetAddress &&
                confirmation in
                    setOf(
                        OtaConfirmation.WAITING_REBOOT,
                        OtaConfirmation.WAITING_VERSION,
                        OtaConfirmation.UNCONFIRMED,
                    )
        ) {
            sawRebootDisconnect = true
            readyAfterReboot = false
            if (confirmation == OtaConfirmation.WAITING_VERSION)
                confirmation = OtaConfirmation.WAITING_REBOOT
            // DONE means the PLC has consumed the file. Cleanup is independent of the visible tab.
            releaseServer()
        }
    }

    fun ready(address: String?): Boolean {
        if (
            targetAddress == null ||
                address != targetAddress ||
                !sawRebootDisconnect ||
                confirmation !in setOf(OtaConfirmation.WAITING_REBOOT, OtaConfirmation.UNCONFIRMED)
        )
            return false
        confirmation = OtaConfirmation.WAITING_VERSION
        readyAfterReboot = true
        return true
    }

    fun reportedVersion(address: String?, version: String): Boolean {
        if (
            targetAddress == null ||
                address != targetAddress ||
                !sawRebootDisconnect ||
                !readyAfterReboot ||
                confirmation !in
                    setOf(OtaConfirmation.WAITING_VERSION, OtaConfirmation.UNCONFIRMED) ||
                version.isBlank()
        )
            return false
        versionAfter = version
        confirmation = OtaConfirmation.CONFIRMED
        releaseServer()
        return true
    }

    fun confirmationTimedOut(expectedAttempt: Long = attempt) {
        if (expectedAttempt != attempt) return
        if (
            confirmation in setOf(OtaConfirmation.WAITING_REBOOT, OtaConfirmation.WAITING_VERSION)
        ) {
            confirmation = OtaConfirmation.UNCONFIRMED
            releaseServer()
        }
    }

    fun failed(error: String) {
        localError = error
        if (error in setOf("BUSY", "BAD_PARAMS"))
            return // An existing PLC task can still be running.
        confirmation = OtaConfirmation.FAILED
        releaseServer()
    }

    fun aborted() {
        confirmation = OtaConfirmation.NONE
        releaseServer()
    }

    fun close() {
        ++selection // Ignore a file read that finishes after Activity destruction.
        calculating = false
        releaseServer()
    }

    private fun releaseServer() {
        val previous = server
        server = null
        serverRunning = false
        try {
            previous?.stopServing()
        } catch (_: Exception) {}
    }
}
