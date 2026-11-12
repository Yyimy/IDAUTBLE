package com.example.idautble

internal data class TcpTraceEntry(val sequence: Long, val dateTime: String, val direction: String, val detail: String) {
    val time get() = dateTime.takeIf { it.length == 19 }?.substring(11) ?: "Time unavailable"
    val heartbeat get() = direction == "T" && detail in listOf("PING", "HEARTBEAT TX") ||
        direction == "R" && detail in listOf("PONG", "HEARTBEAT RX")
}

internal val plcTimestampPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")
private val traceDetail = Regex("[A-Z0-9_= ]{1,23}")

internal fun parseTcpTrace(text: String): List<TcpTraceEntry>? {
    val payload = text.removePrefix("TCPTRACE=")
    if (payload == text) return null
    if (payload.isEmpty()) return emptyList()
    val rows = payload.split('|', limit = 9)
    if (rows.size > 8) return null
    return rows.map { row ->
        val f = row.split(',', limit = 4)
        if (f.size != 4 || f[2] !in listOf("T", "R", "U", "D", "F") || !traceDetail.matches(f[3])) return null
        val seq = f[0].toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL } ?: return null
        if (f[1].isNotEmpty() && !plcTimestampPattern.matches(f[1])) return null
        TcpTraceEntry(seq, f[1], f[2], f[3])
    }
}
