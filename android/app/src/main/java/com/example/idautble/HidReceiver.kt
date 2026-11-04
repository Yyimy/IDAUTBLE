package com.example.idautble

data class HidReceiverInfo(
    val identity: String?,
    val ready: Boolean,
    val window: Boolean,
    val storageOk: Boolean,
)

data class HidReceiverState(
    val supported: Boolean = false,
    val info: HidReceiverInfo? = null,
    val busy: Boolean = false,
    val message: String = "",
)

fun supportsPersistentHidReceiver(text: String): Boolean {
    if (!text.startsWith("FW=")) return false
    val version = text.split(';').firstOrNull()?.removePrefix("FW=") ?: return false
    val match = Regex("HID-PROBE-(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(version) ?: return false
    val major = match.groupValues[1].toIntOrNull() ?: return false
    val minor = match.groupValues[2].toIntOrNull() ?: return false
    return major > 0 || minor >= 2
}

fun parseHidReceiver(text: String): HidReceiverInfo? {
    if (text.length > 200 || !text.startsWith("HIDRECEIVER=1;")) return null
    val fields = linkedMapOf<String, String>()
    for (field in text.substringAfter(';').split(';')) {
        val parts = field.split('=', limit = 2)
        if (parts.size != 2 || fields.put(parts[0], parts[1]) != null) return null
    }
    if (fields.keys != setOf("SAVED", "READY", "WINDOW", "STORE", "ID")) return null
    fun flag(key: String): Boolean? = when (fields[key]) { "1" -> true; "0" -> false; else -> null }
    val saved = flag("SAVED") ?: return null
    val ready = flag("READY") ?: return null
    val window = flag("WINDOW") ?: return null
    val storageOk = when (fields["STORE"]) { "OK" -> true; "ERROR" -> false; else -> return null }
    val identity = fields["ID"] ?: return null
    if (saved && !Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(identity)) return null
    if (!saved && identity != "-") return null
    return HidReceiverInfo(if (saved) identity else null, ready, window, storageOk)
}
