package com.example.idautble

/** One complete V2 frame (ETX) or a complete protocol line (CRLF). */
data class PlcMessage(val bytes: ByteArray, val isFrame: Boolean) {
    val acknowledged: Boolean
        get() = bytes.firstOrNull() == 0x06.toByte()

    val rejected: Boolean
        get() = bytes.firstOrNull() == 0x15.toByte()

    val text: String
        get() =
            bytes
                .toString(Charsets.UTF_8)
                .removePrefix("\u0006")
                .removePrefix("\u0015")
                .trimEnd('\r', '\n')
}

class PlcStreamDecoder(private val maxBufferedBytes: Int = 8192) {
    private val buffer = ArrayList<Byte>()
    var discardedBuffers: Int = 0
        private set

    fun clear() = buffer.clear()

    fun feed(chunk: ByteArray): List<PlcMessage> {
        val messages = mutableListOf<PlcMessage>()
        // Process bytewise so arbitrarily coalesced packets cannot grow the buffer unbounded.
        for (byte in chunk) {
            // Codes emitted by firmware contain no control characters: ACK/NAK starts a new record.
            if ((byte == 0x06.toByte() || byte == 0x15.toByte()) && buffer.isNotEmpty()) {
                buffer.clear()
                discardedBuffers++
            }
            if (buffer.size >= maxBufferedBytes) {
                buffer.clear()
                discardedBuffers++
            }
            buffer.add(byte)
            val frameEnd = byte == 0x03.toByte()
            val lineEnd =
                buffer.size >= 2 &&
                    buffer[buffer.lastIndex - 1] == 13.toByte() &&
                    byte == 10.toByte()
            if (frameEnd || lineEnd) {
                messages += PlcMessage(buffer.toByteArray(), frameEnd)
                buffer.clear()
            }
        }
        return messages
    }
}

fun validMaintenancePin(pin: String): Boolean = pin.length == 6 && pin.all { it in '0'..'9' }

fun requiresMaintenanceAuthorization(data: ByteArray): Boolean {
    if (data.contentEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 13, 10))) return true
    val text = data.toString(Charsets.UTF_8).trim()
    return listOf("ID=", "RTC=", "CFG=", "NET=", "WIFI=", "ALT=", "OTA=", "ACKMODE=", "HEARTBEAT=")
        .any { text.startsWith(it) } || text == "OTA!"
}

/** Uses monotonic time; the phone's clock or a new connection cannot extend authorization. */
class MaintenanceSession(private val now: () -> Long) {
    private var expiresAt = 0L
    private var sessionGrant = false

    fun authorizeSession() {
        sessionGrant = true
        expiresAt = 0L
    }

    fun authorize(ttlSeconds: Int) {
        sessionGrant = false
        expiresAt = now() + ttlSeconds.coerceIn(1, 300) * 1000L
    }

    fun reset() {
        sessionGrant = false
        expiresAt = 0L
    }

    fun isAuthorized(): Boolean = sessionGrant || expiresAt - now() > 5000L
}

fun redactProtocolText(text: String): String =
    text
        .replace(Regex("(?m)(AUTH=)[^\\r\\n]*"), "$1******")
        .replace(Regex("(PWD=)[^;\\r\\n]*"), "$1***")

fun hasProtocolSecret(data: ByteArray): Boolean {
    val text = data.toString(Charsets.UTF_8)
    return text.contains("AUTH=") || text.contains("PWD=")
}

/** Read-only startup queries run once per connection; STATUS already includes the ID. */
class ConnectionHandshake {
    private var infoRequested = false

    fun onConnected(): List<String> {
        if (infoRequested) return emptyList()
        infoRequested = true
        return listOf("INFO?", "STATUS?")
    }

    fun reset() {
        infoRequested = false
    }
}

/** IDs use ASCII so the same bytes fit BLE names and the V2 delimiter field. */
fun normalizedEquipmentId(value: String): String = value.trim().uppercase(java.util.Locale.ROOT)

fun validEquipmentId(value: String): Boolean =
    value.length in 1..20 &&
        value == value.trim() &&
        value.all { it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '-' || it == '_' }

data class EquipmentIdentity(val id: String, val hardwareId: String, val bluetoothName: String)

fun parseEquipmentIdentity(text: String): EquipmentIdentity? {
    if (!text.startsWith("ID=")) return null
    val fields = text.split(';').map { it.split('=', limit = 2) }
    if (fields.any { it.size != 2 } || fields.map { it[0] }.distinct().size != fields.size)
        return null
    val values = fields.associate { it[0] to it[1] }
    val id = values["ID"] ?: return null
    val hardwareId = values["HWID"] ?: return null
    val name = values["NAME"] ?: return null
    if (!validEquipmentId(id) || !Regex("[0-9A-F]{12}").matches(hardwareId)) return null
    if (name != id && !(id == hardwareId && name == "ISP-$hardwareId")) return null
    return EquipmentIdentity(id, hardwareId, name)
}
