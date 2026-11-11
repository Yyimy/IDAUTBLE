package com.example.idautble

/** Complete records only: malformed replies must never turn unknown state into WiFi OFF. */
private fun networkFields(line: String, prefix: String): Map<String, String>? {
    val text = line.removePrefix("\u0006").removePrefix("<ACK>").trimEnd('\r', '\n')
    if (!text.startsWith(prefix)) return null
    val result = linkedMapOf<String, String>()
    for (field in text.removePrefix(prefix).split(';')) {
        val split = field.indexOf('=')
        if (split < 1 || field.any { it.isISOControl() }) return null
        val key = field.substring(0, split)
        if (result.put(key, field.substring(split + 1)) != null) return null
    }
    return result
}

fun parseNetConfigLine(line: String): NetConfig? {
    val fields = networkFields(line, "NET=") ?: return null
    if (!fields.keys.containsAll(listOf("MODE", "SSID", "PWD", "IP", "GW", "MASK", "HOST", "PORT")))
        return null
    val mode = fields.getValue("MODE")
    val portText = fields.getValue("PORT")
    val port = portText.toIntOrNull() ?: return null
    if (mode !in setOf("DHCP", "STATIC") || portText.any { it !in '0'..'9' } || port !in 1..65535)
        return null
    if (
        fields.getValue("SSID").toByteArray(Charsets.UTF_8).size > 32 ||
            fields.getValue("PWD").toByteArray(Charsets.UTF_8).size > 64
    )
        return null
    // DHCP can retain unused legacy static fields. Validate those only when used.
    if (
        mode == "STATIC" &&
            listOf("IP", "GW", "MASK").any { ipv4Value(fields.getValue(it)) == null }
    )
        return null
    val host = fields.getValue("HOST")
    if (host.isEmpty() || host.length > 100) return null
    return NetConfig(
        mode,
        fields.getValue("SSID"),
        fields.getValue("PWD"),
        fields.getValue("IP"),
        fields.getValue("GW"),
        fields.getValue("MASK"),
        host,
        port,
    )
}

internal fun parseTcpAdvanced(line: String): TcpAdvancedConfig? {
    val values = when {
        line.startsWith("TCPADV=") -> line.substringAfter('=')
        else -> networkFields(line, "NET=")?.get("TCPADV") ?: return null
    }.split(',')
    if (values.size != 4 || values.take(2).any { it != "0" && it != "1" }) return null
    val interval = values[2].toIntOrNull() ?: return null
    val timeout = values[3].toIntOrNull() ?: return null
    return TcpAdvancedConfig(values[0] == "1", values[1] == "1", interval, timeout)
        .takeIf { it.valid && it.command() == "TCPADV=" + values.joinToString(",") }
}

fun parseNetStatusLine(line: String): NetStatus? {
    val fields = networkFields(line, "NETSTAT=") ?: return null
    val wifi =
        when (fields["WIFI"]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
    val link =
        when (fields["LINK"]) {
            "UP" -> true
            "DOWN" -> false
            else -> return null
        }
    val tcp =
        when (fields["TCP"]) {
            "UP" -> true
            "DOWN" -> false
            else -> return null
        }
    // The firmware can briefly report an old link while disabling WiFi. Keep the
    // reported flags independent rather than inventing a connection state.
    val ip = fields["IP"]
    if (ip != null && ipv4Value(ip) == null) return null
    val rssi = fields["RSSI"]?.let { it.toIntOrNull()?.takeIf { v -> v in -127..0 } ?: return null }
    return NetStatus(
        wifi, link, tcp, ip, rssi,
        fields["PONGAGE"]?.toLongOrNull()?.takeIf { it >= 0 },
        fields["EVT_PENDING"]?.toIntOrNull()?.takeIf { it >= 0 },
    )
}

// Construcción del comando para enviar al PLC
fun buildNetSetCommand(cfg: NetConfig): String {
    return buildString {
        append("NET=")
        append("MODE=${cfg.mode};")
        append("SSID=${cfg.ssid};")
        // Firmware masks stored passwords. Omitting the field preserves the secret.
        if (cfg.pwd != "***") append("PWD=${cfg.pwd};")
        append("IP=${cfg.ip};")
        append("GW=${cfg.gw};")
        append("MASK=${cfg.mask};")
        append("HOST=${cfg.host};")
        append("PORT=${cfg.port}")
    }
}
