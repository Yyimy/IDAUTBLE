package com.example.idautble

/** Firmware uses the literal IP delimiter so equipment IDs may contain spaces. */
internal fun parseOtaApSsid(status: String): String? {
    val prefix = "AP_ON SSID="
    if (!status.startsWith(prefix)) return null
    val end = status.indexOf(" IP=", prefix.length)
    if (end < 0) return null
    val ssid = status.substring(prefix.length, end)
    return ssid.takeIf {
        it.isNotEmpty() &&
            it.toByteArray(Charsets.UTF_8).size <= 32 &&
            it.none { character -> character.isISOControl() }
    }
}
