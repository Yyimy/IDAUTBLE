package com.example.idautble

// Ejemplo esperado:
// NET=MODE=DHCP;SSID=MIRED;PWD=1234;IP=192.168.1.10;GW=192.168.1.1;MASK=255.255.255.0;HOST=192.168.1.50;PORT=9000
fun parseNetConfigLine(line: String): NetConfig? {
    val start = line.indexOf("NET=")
    if (start == -1) return null

    val body = line.substring(start + 4)

    val map =
        body
            .split(";")
            .mapNotNull {
                val idx = it.indexOf("=")
                if (idx > 0) {
                    val key = it.substring(0, idx)
                    val value = it.substring(idx + 1)
                    key to value
                } else null
            }
            .toMap()

    return NetConfig(
        mode = map["MODE"] ?: "DHCP",
        ssid = map["SSID"] ?: "",
        pwd = map["PWD"] ?: "",
        ip = map["IP"] ?: "",
        gw = map["GW"] ?: "",
        mask = map["MASK"] ?: "",
        host = map["HOST"] ?: "",
        port = map["PORT"]?.toIntOrNull() ?: 0,
    )
}

// Ejemplo esperado:
// NETSTAT=WIFI=1;LINK=1;IP=192.168.1.10;RSSI=-62
fun parseNetStatusLine(line: String): NetStatus? {

    if (!line.contains("NETSTAT=")) return null

    val payload = line.substringAfter("NETSTAT=")
    val fields = payload.split(";")

    var wifiEnabled = false
    var linkUp = false
    var tcpUp = false
    var ip = ""
    var rssi: Int? = null

    fields.forEach { field ->
        val key = field.substringBefore("=")
        val value = field.substringAfter("=", "")

        when (key.trim()) {
            "WIFI" -> wifiEnabled = value.trim() == "1"

            "LINK" -> linkUp = value.trim().equals("UP", ignoreCase = true)

            "TCP" -> tcpUp = value.trim().equals("UP", ignoreCase = true)

            "IP" -> ip = value.trim()

            "RSSI" -> rssi = value.trim().toIntOrNull()
        }
    }

    return NetStatus(
        wifiEnabled = wifiEnabled,
        linkUp = linkUp,
        tcpUp = tcpUp,
        ip = ip,
        rssi = rssi,
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
