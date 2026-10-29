package com.example.idautble

data class NetConfig(
    val mode: String,
    val ssid: String,
    val pwd: String,
    val ip: String,
    val gw: String,
    val mask: String,
    val host: String,
    val port: Int,
)

data class NetStatus(
    val wifiEnabled: Boolean,
    val linkUp: Boolean,
    val tcpUp: Boolean,
    val ip: String?,
    val rssi: Int?,
)

/** Independent revisions prevent Compose from losing NET when NETSTAT arrives in the same burst. */
data class NetworkSnapshot(
    val config: NetConfig? = null,
    val status: NetStatus? = null,
    val configRevision: Long = 0,
    val statusRevision: Long = 0,
) {
    fun receive(line: String): NetworkSnapshot {
        parseNetConfigLine(line)?.let {
            return copy(config = it, configRevision = configRevision + 1)
        }
        parseNetStatusLine(line)?.let {
            return copy(status = it, statusRevision = statusRevision + 1)
        }
        return this
    }
}
