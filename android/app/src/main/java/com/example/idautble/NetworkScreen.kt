package com.example.idautble

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// --- Compact UI constants ---
private val COMPACT_FIELD_HEIGHT = 34.dp
private val FIELD_BORDER = 1.dp

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .height(COMPACT_FIELD_HEIGHT)
                .border(FIELD_BORDER, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle =
                MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions =
                KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onNext = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                    onSearch = { onImeAction?.invoke() },
                    onSend = { onImeAction?.invoke() },
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompactPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    show: Boolean,
    onToggleShow: () -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .height(COMPACT_FIELD_HEIGHT)
                .border(FIELD_BORDER, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    enabled = enabled,
                    textStyle =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    visualTransformation =
                        if (show) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = imeAction,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { onImeAction?.invoke() },
                            onNext = { onImeAction?.invoke() },
                            onGo = { onImeAction?.invoke() },
                            onSearch = { onImeAction?.invoke() },
                            onSend = { onImeAction?.invoke() },
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            IconButton(onClick = onToggleShow, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (show) "Hide password" else "Show password",
                )
            }
        }
    }
}

@Composable
private fun NetworkValue(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    indicator: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (indicator) Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun NetworkHeading(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
}

@Composable
private fun NetworkInput(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompactField(
            value,
            onChange,
            label,
            Modifier.fillMaxWidth(),
            keyboardType = keyboard,
            enabled = enabled,
            onImeAction = { focus.clearFocus() },
        )
    }
}

@Composable
private fun NetworkPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaintenanceCard(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
internal fun NetworkScreen(session: NetworkSession, textSize: TerminalTextSize, logColors: LogColors) {
    val state = session.state
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    DisposableEffect(session) {
        session.setVisible(true)
        onDispose { session.setVisible(false) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val focus = LocalFocusManager.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        Modifier.fillMaxSize().padding(6.dp).pointerInput(Unit) {
            detectTapGestures { focus.clearFocus() }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!state.ready || state.paused) {
            MaintenanceCard(Modifier.fillMaxWidth()) {
                Text(
                    if (state.paused) "Network controls are paused during OTA."
                    else "Connect Bluetooth and wait for the PLC to be ready.",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (landscape && maxWidth >= 700.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LiveConnectionPanel(state, session, now, Modifier.fillMaxWidth())
                        NetworkConfigurationPanel(state, session, Modifier.fillMaxWidth())
                    }
                    TcpMonitorPanel(state, now, textSize, logColors, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiveConnectionPanel(state, session, now, Modifier.fillMaxWidth())
                    NetworkConfigurationPanel(state, session, Modifier.fillMaxWidth())
                    TcpMonitorPanel(state, now, textSize, logColors, Modifier.fillMaxWidth().height(320.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveConnectionPanel(
    state: NetworkUiState,
    session: NetworkSession,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val status = state.status
    val good = Color(0xFF2E7D32)
    val warning = Color(0xFFB66A00)
    val age = state.statusReceivedAt?.let { ((now - it).coerceAtLeast(0) / 1000) }
    val stale = !state.available || age == null || age > 15
    val controls = state.available && !state.controlsBusy
    NetworkPanel(modifier) {
        NetworkHeading("LIVE CONNECTION")
        if (!stale && state.tcpAdvanced != null && status != null) {
            if (state.tcpAdvanced.heartbeat) {
                NetworkValue("Last PONG", status.pongAgeSeconds?.let { "${it + (age ?: 0)} s ago" } ?: "—")
            }
            NetworkValue("Pending events", status.pendingEvents?.toString() ?: "—")
        }
        NetworkValue(
            "WiFi",
            when (status?.wifiEnabled) {
                true -> "Enabled"
                false -> "Disabled"
                null -> "Unknown"
            },
            color =
                if (status?.wifiEnabled == true && !stale) good
                else MaterialTheme.colorScheme.onSurfaceVariant,
            indicator = true,
        )
        NetworkValue(
            "WiFi link",
            when (status?.linkUp) {
                true -> "Connected"
                false -> "Down"
                null -> "Unknown"
            },
            if (status?.linkUp == true && !stale) good
            else MaterialTheme.colorScheme.onSurfaceVariant,
            indicator = true,
        )
        NetworkValue(
            "PLC IP (actual)",
            when {
                status == null -> "Unknown"
                !status.wifiEnabled || !status.linkUp -> "Not assigned"
                usableNetworkIp(status.ip) -> status.ip!!
                else -> "Not assigned"
            },
        )
        NetworkValue(
            "TCP server",
            when (status?.tcpUp) {
                true -> "Connected"
                false -> "Down"
                null -> "Unknown"
            },
            if (status?.tcpUp == true && !stale) good
            else MaterialTheme.colorScheme.onSurfaceVariant,
            indicator = true,
        )
        NetworkValue("Saved server", state.config?.let { "${it.host}:${it.port}" } ?: "Not read")
        NetworkValue("RSSI", status?.rssi?.let { "$it dBm" } ?: "—")
        Text(
            if (age == null) "No status received"
            else "Snapshot · ${age}s ago${if (stale) " · stale" else ""}",
            fontSize = 10.sp,
            color = if (stale) warning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = session::toggleWifi,
                enabled = controls && state.config != null && status != null,
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(10.dp, 4.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            if (status?.wifiEnabled == true) Color(0xFFC62828) else good
                    ),
            ) {
                Text(
                    when (state.pending?.operation) {
                        NetworkOperation.WIFI_ON -> "Enabling…"
                        NetworkOperation.WIFI_OFF -> "Disabling…"
                        else -> if (status?.wifiEnabled == true) "Disable WiFi" else "Enable WiFi"
                    },
                    fontSize = 11.sp,
                )
            }
            Button(
                onClick = { session.refreshStatus() },
                enabled = controls,
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(10.dp, 4.dp),
            ) {
                Text(
                    if (
                        state.pending?.operation == NetworkOperation.READ_STATUS &&
                            state.pending.automatic == false
                    )
                        "Refreshing…"
                    else "Refresh status",
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private data class TcpMonitorEntry(val ageSeconds: Long, val label: String, val message: String, val color: Color)

@Composable
private fun TcpMonitorPanel(
    state: NetworkUiState,
    now: Long,
    textSize: TerminalTextSize,
    colors: LogColors,
    modifier: Modifier,
) {
    val snapshot = state.status?.tcpMonitor
    val snapshotAge = state.statusReceivedAt?.let { (now - it).coerceAtLeast(0) / 1000 } ?: 0
    val entries = remember(snapshot, colors) {
        buildList {
            snapshot?.replies?.forEach { reply ->
                add(TcpMonitorEntry(reply.ageSeconds, "↓ RX", reply.text + if (reply.truncated) "…" else "", colors.rxGreen))
            }
            snapshot?.connectionAgeSeconds?.let {
                add(TcpMonitorEntry(it, "TCP UP", "Connected to server", colors.txBlue))
            }
            snapshot?.disconnection?.let {
                add(TcpMonitorEntry(it.ageSeconds, "TCP DOWN", tcpDisconnectLabel(it.reason), colors.errRed))
            }
        }.sortedBy { it.ageSeconds }
    }
    MaintenanceCard(modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NetworkHeading("TCP MONITOR")
            val status = when {
                state.status == null -> "Waiting for network status."
                snapshot == null -> "TCP monitoring requires compatible PLC firmware."
                !state.available || snapshotAge > 15 -> "Last available snapshot"
                else -> "Server replies and connection events"
            }
            Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (snapshot != null && entries.isEmpty()) Text("No TCP activity recorded.")
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries) { entry ->
                    MaintenanceCard(Modifier.fillMaxWidth(), containerColor = entry.color.copy(alpha = 0.06f)) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                "${tcpMonitorAge(entry.ageSeconds + snapshotAge)}  ${entry.label}",
                                color = entry.color,
                                fontFamily = FontFamily.Monospace,
                                fontSize = textSize.fontSizeSp.sp,
                                lineHeight = textSize.lineHeightSp.sp,
                            )
                            Text(
                                entry.message,
                                color = colors.neutralText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = textSize.fontSizeSp.sp,
                                lineHeight = textSize.lineHeightSp.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tcpDisconnectLabel(reason: String) = when (reason) {
    "PONG_TIMEOUT" -> "PONG timeout"
    "WIFI_LOST" -> "Wi-Fi link lost"
    "WIFI_DISABLED" -> "Wi-Fi disabled"
    "SOCKET_CLOSED" -> "Socket closed"
    "CONFIG_CHANGED" -> "Network restarted"
    "OTA" -> "Firmware update"
    "WRITE_FAILED" -> "Write failed"
    "WRITE_TIMEOUT" -> "Write timeout"
    else -> "Unknown reason"
}

private fun tcpMonitorAge(seconds: Long) = when {
    seconds < 60 -> "$seconds s ago"
    seconds < 3600 -> "${seconds / 60} min ago"
    seconds < 86400 -> "${seconds / 3600} h ago"
    else -> "${seconds / 86400} d ago"
}

@Composable
private fun NetworkConfigurationPanel(
    state: NetworkUiState,
    session: NetworkSession,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val errors = remember(draft) { networkDraftErrors(draft) }
    val focus = LocalFocusManager.current
    val good = Color(0xFF2E7D32)
    val warning = Color(0xFFB66A00)
    val controls = state.available && !state.controlsBusy
    val editable = state.canEdit && state.config != null
    var wifiExpanded by rememberSaveable { mutableStateOf(true) }
    var ipExpanded by rememberSaveable { mutableStateOf(false) }
    var serverExpanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NetworkPanel(Modifier.fillMaxWidth()) {
            NetworkHeading("PLC CONFIGURATION")
            Text(
                when {
                    state.config == null ->
                        "Reading saved settings over Bluetooth works with WiFi disabled."
                    state.dirty -> "Unsaved edits · connection indicators still show the PLC state."
                    state.saved -> "Saved and confirmed by PLC."
                    else -> "Saved settings loaded from PLC."
                },
                fontSize = 11.sp,
                color = if (state.dirty) warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CollapsibleSettingsSection(
            "WI-FI", draft.ssid.ifBlank { "Network name and password" },
            wifiExpanded, { wifiExpanded = !wifiExpanded },
        ) {
            NetworkInput("WiFi network (SSID)", draft.ssid, editable) { value ->
                session.edit { it.copy(ssid = value) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "WiFi password",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactPasswordField(
                    draft.pwd,
                    { value -> session.edit { it.copy(pwd = value) } },
                    "Password",
                    state.showPassword,
                    { session.showPassword(!state.showPassword) },
                    Modifier.fillMaxWidth(),
                    enabled = editable,
                )
            }
            Text(
                if (draft.pwd == "***")
                    "Stored password is hidden and will be kept. Type a new value to replace it."
                else if (draft.pwd.isEmpty()) "An empty password will be saved as empty."
                else "New password · not saved yet",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CollapsibleSettingsSection(
            "IP CONFIGURATION", draft.mode,
            ipExpanded, { ipExpanded = !ipExpanded },
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                for (mode in listOf("DHCP", "STATIC")) {
                    FilterChip(
                        selected = draft.mode == mode,
                        onClick = { session.edit { it.copy(mode = mode) } },
                        enabled = state.canEdit && state.config != null,
                        label = { Text(mode, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    )
                }
            }
            if (draft.mode == "STATIC") {
                NetworkInput("Static PLC IP", draft.ip, editable, keyboard = KeyboardType.Decimal) {
                    value ->
                    session.edit { it.copy(ip = value) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkInput(
                        "Gateway",
                        draft.gw,
                        editable,
                        Modifier.weight(1f),
                        KeyboardType.Decimal,
                    ) { value ->
                        session.edit { it.copy(gw = value) }
                    }
                    NetworkInput(
                        "Subnet mask",
                        draft.mask,
                        editable,
                        Modifier.weight(1f),
                        KeyboardType.Decimal,
                    ) { value ->
                        session.edit { it.copy(mask = value) }
                    }
                }
            } else
                Text(
                    "DHCP assigns the actual PLC IP shown in LIVE CONNECTION.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        CollapsibleSettingsSection(
            "TCP SERVER", if (draft.host.isBlank()) "Address and port" else "${draft.host} · ${draft.port}",
            serverExpanded, { serverExpanded = !serverExpanded },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkInput("Server IP / hostname", draft.host, editable, Modifier.weight(1f)) { value
                    ->
                    session.edit { it.copy(host = value) }
                }
                NetworkInput(
                    "Port",
                    draft.port,
                    editable,
                    Modifier.width(80.dp),
                    KeyboardType.Number,
                ) { value ->
                    session.edit { it.copy(port = value) }
                }
            }
        }
        NetworkPanel(Modifier.fillMaxWidth()) {
            if (state.dirty)
                errors.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Button(
                    onClick = session::readConfiguration,
                    enabled = controls,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(10.dp, 4.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFC107),
                            contentColor = Color(0xFF212121),
                        ),
                ) {
                    Text(
                        if (state.pending?.operation == NetworkOperation.READ_CONFIG) "Reading…"
                        else "Read configuration",
                        fontSize = 11.sp,
                    )
                }
                if (state.dirty)
                    OutlinedButton(
                        onClick = session::discardEdits,
                        enabled = controls,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(10.dp, 4.dp),
                    ) {
                        Text("Discard edits", fontSize = 11.sp)
                    }
            }
            Button(
                onClick = {
                    focus.clearFocus()
                    session.save()
                },
                enabled = controls && state.dirty && errors.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = if (state.dirty) Color(0xFFF57C00) else good
                    ),
            ) {
                Text(
                    if (state.pending?.operation == NetworkOperation.SAVE) "Waiting for confirmation…"
                    else "Save configuration",
                    fontSize = 12.sp,
                )
            }
            Text(state.notice, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
        state.tcpAdvanced?.let {
            TcpAdvancedSettings(it, controls, session::saveTcpAdvanced)
        }
    }
}

@Composable
private fun TcpAdvancedSettings(
    config: TcpAdvancedConfig,
    enabled: Boolean,
    onSave: (TcpAdvancedConfig) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var heartbeat by rememberSaveable(config) { mutableStateOf(config.heartbeat) }
    var acknowledgements by rememberSaveable(config) { mutableStateOf(config.acknowledgements) }
    var interval by rememberSaveable(config) { mutableStateOf(config.intervalSeconds.toString()) }
    var timeout by rememberSaveable(config) { mutableStateOf(config.timeoutSeconds.toString()) }
    val draft = TcpAdvancedConfig(
        heartbeat, acknowledgements, interval.toIntOrNull() ?: 0, timeout.toIntOrNull() ?: 0,
    )
    val focus = LocalFocusManager.current
    CollapsibleSettingsSection(
        "ADVANCED NETWORK SETTINGS", "Heartbeat and event acknowledgements",
        expanded, { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable heartbeat", Modifier.weight(1f))
            Switch(checked = heartbeat, onCheckedChange = { heartbeat = it }, enabled = enabled)
        }
        Text("The server must reply PONG to PING.", fontSize = 12.sp)
        NetworkInput("Ping interval (s)", interval, enabled, keyboard = KeyboardType.Number) {
            interval = it.filter { c -> c in '0'..'9' }.take(3)
        }
        NetworkInput("Response timeout (s)", timeout, enabled, keyboard = KeyboardType.Number) {
            timeout = it.filter { c -> c in '0'..'9' }.take(3)
        }
        if (!draft.valid) Text(
            "Interval must be 5–120 s. Timeout must exceed the interval and be at most 300 s.",
            color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
        )
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Require event acknowledgement", Modifier.weight(1f))
            Switch(
                checked = acknowledgements,
                onCheckedChange = { acknowledgements = it },
                enabled = enabled,
            )
        }
        Text("Enable only if the server stores and deduplicates events and replies with ACK=<id>.", fontSize = 12.sp)
        MaintenanceActionButton(
            text = "Save advanced settings",
            onClick = {
                focus.clearFocus()
                onSave(draft)
            },
            tone = MaintenanceActionTone.SUCCESS,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && draft.valid && draft != config,
        )
    }
}
