package com.example.idautble

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime

/** A single complete V2 response. Null fields were not supplied in that response. */
data class PlcReading(
    val plcTime: String,
    val clockValid: Boolean,
    val deviceId: String,
    val pallet: String?,
    val location: String?,
    val photocell: Boolean?,
    val heightMetres: String?,
    val battery1: Int?,
    val battery2: Int?,
)

fun parsePlcReading(message: PlcMessage): PlcReading? {
    if (!message.isFrame || !message.acknowledged || message.bytes.size > 512) return null
    val text =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(message.bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return null
        }
    if (text.getOrNull(20) != '\u0002' || text.lastOrNull() != '\u0003') return null
    val timestamp = text.substring(1, 20)
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}").matches(timestamp))
        return null
    val fields = text.substring(21, text.lastIndex).split('\u001b')
    if (fields.size != 7 || fields.last() != "" || !validEquipmentId(fields[0])) return null
    val battery = fields[1]
    if (battery != "-" && !Regex("[0-9]{4}").matches(battery)) return null
    val height = fields[2]
    if (height != "-" && !Regex("[0-9]{2}\\.[0-9]{2}").matches(height)) return null
    if (fields[3] !in setOf("0", "1", "-")) return null
    if (fields.slice(4..5).any { code -> code.any { it.isISOControl() } }) return null
    fun code(value: String): String? = value.takeUnless { it == "-" || it.isEmpty() }
    return PlcReading(
        timestamp,
        runCatching { LocalDateTime.parse(timestamp) }.isSuccess,
        fields[0],
        code(fields[4]),
        code(fields[5]),
        when (fields[3]) {
            "1" -> true
            "0" -> false
            else -> null
        },
        height.takeUnless { it == "-" },
        if (battery == "-") null else battery.substring(0, 2).toInt(),
        if (battery == "-") null else battery.substring(2, 4).toInt(),
    )
}

/** Never merge fields from different commands; missing values clear earlier readings. */
data class PlcReadingsState(
    val reading: PlcReading? = null,
    val receivedMs: Long? = null,
    val invalidFrame: Boolean = false,
) {
    fun receive(message: PlcMessage, nowMs: Long): PlcReadingsState {
        if (!message.isFrame) return this
        val parsed = parsePlcReading(message) ?: return copy(invalidFrame = true)
        return PlcReadingsState(parsed, nowMs, false)
    }
}
