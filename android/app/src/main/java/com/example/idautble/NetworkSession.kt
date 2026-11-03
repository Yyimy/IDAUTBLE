package com.example.idautble

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class NetworkDraft(
    val mode: String = "DHCP",
    val ssid: String = "",
    val pwd: String = "",
    val ip: String = "",
    val gw: String = "",
    val mask: String = "",
    val host: String = "",
    val port: String = "9000",
) {
    fun config() =
        NetConfig(
            mode,
            ssid,
            pwd,
            ip.trim(),
            gw.trim(),
            mask.trim(),
            host.trim(),
            port.trim().toIntOrNull() ?: 0,
        )

    companion object {
        fun from(c: NetConfig) =
            NetworkDraft(c.mode, c.ssid, c.pwd, c.ip, c.gw, c.mask, c.host, c.port.toString())
    }
}

internal fun ipv4Value(value: String): Long? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    var result = 0L
    for (part in parts) {
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}

fun usableNetworkIp(value: String?): Boolean =
    value != null && ipv4Value(value)?.let { it != 0L && it != 0xffffffffL } == true

fun networkDraftErrors(d: NetworkDraft): List<String> = buildList {
    val c = d.config()
    if (c.mode !in setOf("DHCP", "STATIC")) add("Select DHCP or STATIC.")
    if (
        listOf(d.ssid, d.pwd, d.host, d.ip, d.gw, d.mask).any { v ->
            v.any { it == ';' || it.isISOControl() }
        }
    )
        add("Fields cannot contain semicolons or control characters.")
    if (d.ssid.toByteArray().size > 32) add("SSID is too long (maximum 32 bytes).")
    if (d.pwd.toByteArray().size > 64) add("Password is too long (maximum 64 bytes).")
    if (
        c.host.isEmpty() ||
            c.host.length > 100 ||
            c.host.any { it.isWhitespace() || it in "/\\:;" } ||
            !c.host.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"))
    )
        add("Enter a server IP or hostname, without http://, a path or a port.")
    if (c.host.matches(Regex("[0-9.]+")) && !usableNetworkIp(c.host))
        add("Invalid server IP address.")
    if (d.port.trim().isEmpty() || d.port.trim().any { it !in '0'..'9' } || c.port !in 1..65535)
        add("Port must be between 1 and 65535.")
    if (c.mode == "STATIC") {
        val ip = ipv4Value(c.ip)
        val gw = ipv4Value(c.gw)
        val mask = ipv4Value(c.mask)
        if (ip == null || !usableNetworkIp(c.ip)) add("Invalid PLC IP address.")
        if (gw == null || !usableNetworkIp(c.gw)) add("Invalid gateway address.")
        val inverse = mask?.xor(0xffffffffL)
        val validMask =
            mask != null && mask != 0L && mask != 0xffffffffL && (inverse!! and (inverse + 1)) == 0L
        if (!validMask) add("Invalid subnet mask.")
        if (validMask && ip != null && gw != null) {
            if ((ip and mask!!) != (gw and mask))
                add("PLC IP and gateway must be in the same subnet.")
            if ((ip and inverse!!) == 0L || (ip and inverse) == inverse)
                add("PLC IP cannot be the subnet or broadcast address.")
        }
    }
    if ((buildNetSetCommand(c) + "\r\n").toByteArray().size >= 384)
        add("Configuration is too long for the PLC.")
}

enum class NetworkOperation {
    READ_CONFIG,
    READ_STATUS,
    SAVE,
    WIFI_ON,
    WIFI_OFF,
}

data class NetworkPending(
    val id: Long,
    val operation: NetworkOperation,
    val deadline: Long,
    val sentAt: Long? = null,
    val expected: NetConfig? = null,
    val okSeen: Boolean = false,
    val readback: NetConfig? = null,
    val statusReadback: NetStatus? = null,
)

data class NetworkUiState(
    val ready: Boolean = false,
    val paused: Boolean = false,
    val config: NetConfig? = null,
    val draft: NetworkDraft = NetworkDraft(),
    val configReceivedAt: Long? = null,
    val status: NetStatus? = null,
    val statusReceivedAt: Long? = null,
    val pending: NetworkPending? = null,
    val saved: Boolean = false,
    val notice: String = "Open NETWORK after Bluetooth is ready to read the PLC.",
    val error: String? = null,
    val showPassword: Boolean = false,
) {
    val available
        get() = ready && !paused

    val busy
        get() = pending != null

    val dirty
        get() = config?.let { draft != NetworkDraft.from(it) } ?: false

    val canEdit
        get() =
            available &&
                pending?.operation !in
                    setOf(
                        NetworkOperation.READ_CONFIG,
                        NetworkOperation.SAVE,
                        NetworkOperation.WIFI_ON,
                        NetworkOperation.WIFI_OFF,
                    )
}

/**
 * Activity-owned network transaction state; tab removal never loses a pending operation or draft.
 */
class NetworkSession(private val now: () -> Long, private val send: (String, Long) -> Unit) {
    var state by mutableStateOf(NetworkUiState())
        private set

    private var sequence = 0L
    private var visible = false
    private var foreground = false
    private var lastPoll = 0L
    private var watchUntil: Long? = null
    private var connectionFailure = false

    fun reset() {
        ++sequence
        watchUntil = null
        connectionFailure = false
        state = NetworkUiState()
        lastPoll = now()
    }

    fun setReady(ready: Boolean) {
        if (state.ready == ready) return
        state = state.copy(ready = ready)
        if (ready && visible && foreground && !state.busy) readConfiguration()
    }

    fun setPaused(paused: Boolean) {
        if (state.paused == paused) return
        state =
            state.copy(
                paused = paused,
                pending = if (paused) null else state.pending,
                notice = if (paused) "Network controls are paused during OTA." else state.notice,
            )
        if (!paused && visible && foreground && state.ready && !state.busy) readConfiguration()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value && visible && state.available && !state.busy && state.config == null)
            readConfiguration()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value && foreground && state.available && !state.busy) readConfiguration()
    }

    fun edit(change: (NetworkDraft) -> NetworkDraft) {
        if (!state.canEdit) return
        state = state.copy(draft = change(state.draft), saved = false, error = null)
    }

    fun showPassword(value: Boolean) {
        state = state.copy(showPassword = value)
    }

    fun discardEdits() {
        if (state.busy) return
        state.config?.let {
            state =
                state.copy(
                    draft = NetworkDraft.from(it),
                    saved = false,
                    showPassword = false,
                    error = null,
                    notice = "Unsaved edits discarded.",
                )
        }
    }

    fun readConfiguration() {
        if (!state.available || state.busy) return
        state = state.copy(error = null, notice = "Reading PLC configuration...")
        issue(NetworkOperation.READ_CONFIG, "NET?")
    }

    fun refreshStatus(automatic: Boolean = false) {
        if (!state.available || state.busy) return
        if (!automatic) state = state.copy(error = null)
        issue(NetworkOperation.READ_STATUS, "NETSTAT?")
    }

    fun save() {
        if (!state.available || state.busy || !state.dirty || state.config == null) return
        val errors = networkDraftErrors(state.draft)
        if (errors.isNotEmpty()) {
            state = state.copy(error = errors.first())
            return
        }
        state =
            state.copy(
                error = null,
                saved = false,
                notice = "Waiting for authorisation and PLC confirmation...",
            )
        issue(NetworkOperation.SAVE, buildNetSetCommand(state.draft.config()), state.draft.config())
    }

    fun toggleWifi() {
        if (!state.available || state.busy || state.config == null || state.status == null) return
        val enable = !state.status!!.wifiEnabled
        if (enable && state.config!!.ssid.isEmpty()) {
            state = state.copy(error = "Set and save a WiFi network name before enabling WiFi.")
            return
        }
        state =
            state.copy(error = null, notice = "Waiting for authorisation and PLC confirmation...")
        issue(
            if (enable) NetworkOperation.WIFI_ON else NetworkOperation.WIFI_OFF,
            if (enable) "WIFI=1" else "WIFI=0",
        )
    }

    private fun issue(op: NetworkOperation, command: String, expected: NetConfig? = null) {
        val id = ++sequence
        state = state.copy(pending = NetworkPending(id, op, now() + 60_000, expected = expected))
        if (op == NetworkOperation.READ_STATUS) lastPoll = now()
        send(command, id)
    }

    fun isCurrent(id: Long) =
        state.available && state.pending?.let { it.id == id && now() < it.deadline } == true

    fun commandSent(id: Long) {
        val p = state.pending?.takeIf { it.id == id } ?: return
        state = state.copy(pending = p.copy(sentAt = now(), deadline = now() + 8_000))
    }

    fun sendFailed(id: Long, reason: String) {
        if (state.pending?.id == id) fail(reason)
    }

    fun transportError(reason: String) {
        if (state.busy) fail(reason)
    }

    fun authorisationCancelled(reason: String) {
        val pending = state.pending ?: return
        // Cancelling PIN entry can revoke only a command that is still waiting to send.
        if (
            pending.sentAt == null &&
                pending.operation in
                    setOf(
                        NetworkOperation.SAVE,
                        NetworkOperation.WIFI_ON,
                        NetworkOperation.WIFI_OFF,
                    )
        )
            fail(reason)
    }

    private fun fail(reason: String) {
        connectionFailure = false
        state =
            state.copy(
                pending = null,
                error = reason,
                saved = false,
                notice = "Operation not confirmed. Reload the PLC configuration before retrying.",
            )
    }

    private fun expirePending() {
        val pending = state.pending ?: return
        if (now() < pending.deadline) return
        fail(
            when {
                pending.operation == NetworkOperation.READ_CONFIG ||
                    pending.operation == NetworkOperation.READ_STATUS ->
                    "No network reply received from the PLC. Check Bluetooth and refresh."
                pending.sentAt == null ->
                    "Authorisation or send timed out. No automatic retry was made."
                else ->
                    "No PLC confirmation received. The change may have been applied; reload the configuration."
            }
        )
    }

    fun tick() {
        val time = now()
        expirePending()
        if (watchUntil?.let { time >= it } == true) {
            watchUntil = null
            val s = state.status
            val reason =
                when {
                    s == null -> "No network status received. Refresh and check Bluetooth."
                    !s.wifiEnabled -> null
                    !s.linkUp ->
                        "WiFi is enabled but not connected. Check SSID, password and coverage."
                    !usableNetworkIp(s.ip) ->
                        "WiFi is linked but has no usable IP. Check DHCP or static settings."
                    !s.tcpUp ->
                        "WiFi has an IP, but TCP is not connected. Check the server address, port and listener."
                    else -> null
                }
            if (reason != null && state.error == null) {
                connectionFailure = true
                state = state.copy(error = reason)
            }
        }
        val interval = if (watchUntil != null) 2_000 else 5_000
        if (
            visible &&
                foreground &&
                state.available &&
                !state.busy &&
                state.config != null &&
                time - lastPoll >= interval
        )
            refreshStatus(automatic = true)
    }

    fun receive(message: PlcMessage) {
        expirePending()
        val text = message.text
        val p = state.pending
        if (message.rejected) {
            if (p?.sentAt != null)
                fail(
                    when (text) {
                        "ERR=AUTH",
                        "ERR=AUTH_REQUIRED",
                        "ERR=AUTH_LOCKED" ->
                            "PLC authorisation is required. Enter the PIN and retry."
                        "BUSY",
                        "ERR=BUSY",
                        "ERR=ID_BUSY" ->
                            "The PLC is busy. Wait for readings or OTA to finish, then retry."
                        else ->
                            "The PLC rejected the network operation. Check the fields and reload configuration."
                    }
                )
            return
        }
        if (!message.acknowledged) return
        when {
            text == "OK" -> {
                if (
                    p?.sentAt != null &&
                        p.operation in
                            setOf(
                                NetworkOperation.SAVE,
                                NetworkOperation.WIFI_ON,
                                NetworkOperation.WIFI_OFF,
                            )
                ) {
                    state = state.copy(pending = p.copy(okSeen = true))
                    completeChange()
                }
            }
            text.startsWith("NET=") -> {
                val c = parseNetConfigLine(text)
                if (c == null) {
                    if (p != null) fail("Invalid configuration response. Previous values retained.")
                    return
                }
                val keepDraft = state.dirty
                state =
                    state.copy(
                        config = c,
                        configReceivedAt = now(),
                        saved = state.saved && c == state.config,
                        draft = if (keepDraft) state.draft else NetworkDraft.from(c),
                        showPassword = false,
                    )
                if (p?.operation == NetworkOperation.SAVE && p.sentAt != null) {
                    state = state.copy(pending = p.copy(readback = c))
                    completeChange()
                } else if (p?.operation == NetworkOperation.READ_CONFIG) {
                    state =
                        state.copy(
                            pending = null,
                            notice =
                                if (keepDraft) "PLC configuration received. Unsaved edits retained."
                                else "PLC configuration loaded.",
                        )
                    if (foreground) refreshStatus(automatic = true)
                }
            }
            text.startsWith("NETSTAT=") -> {
                val s = parseNetStatusLine(text)
                if (s == null) {
                    if (p?.operation == NetworkOperation.READ_STATUS)
                        fail("Invalid network status. Previous snapshot retained.")
                    return
                }
                state = state.copy(status = s, statusReceivedAt = now())
                if (!s.wifiEnabled || s.tcpUp && usableNetworkIp(s.ip)) {
                    watchUntil = null
                    if (connectionFailure) {
                        connectionFailure = false
                        state = state.copy(error = null)
                    }
                }
                if (p?.operation == NetworkOperation.READ_STATUS) state = state.copy(pending = null)
                else if (
                    p?.sentAt != null &&
                        p.operation in setOf(NetworkOperation.WIFI_ON, NetworkOperation.WIFI_OFF)
                ) {
                    state = state.copy(pending = p.copy(statusReadback = s))
                    completeChange()
                }
            }
        }
    }

    private fun completeChange() {
        val p = state.pending ?: return
        if (!p.okSeen) return
        if (p.operation == NetworkOperation.SAVE) {
            val c = p.readback ?: return
            val expected = p.expected ?: return
            if (c.copy(pwd = "***") != expected.copy(pwd = "***")) {
                fail("The PLC returned a different configuration. Save was not confirmed.")
                return
            }
            state =
                state.copy(
                    pending = null,
                    config = c,
                    draft = NetworkDraft.from(c),
                    saved = true,
                    showPassword = false,
                    notice = "Configuration saved on PLC.",
                    error = null,
                )
            watchUntil = now() + 30_000
            if (foreground) refreshStatus(automatic = true)
        } else {
            val s = p.statusReadback ?: return
            val enable = p.operation == NetworkOperation.WIFI_ON
            if (s.wifiEnabled != enable) return
            state =
                state.copy(
                    pending = null,
                    notice =
                        if (enable) "WiFi enabled on PLC. Connection is checked separately."
                        else "WiFi disabled on PLC.",
                    error = null,
                )
            watchUntil = if (enable && !s.tcpUp) now() + 30_000 else null
        }
    }
}
