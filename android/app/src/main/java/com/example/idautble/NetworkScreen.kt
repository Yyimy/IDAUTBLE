package com.example.idautble

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- Compact UI constants ---
private val COMPACT_FIELD_HEIGHT = 34.dp
private val FIELD_BORDER = 1.dp
private val V_SPACING = 4.dp
private val H_SPACING = 6.dp

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
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
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
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
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle =
                        TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
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

            IconButton(onClick = onToggleShow, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                )
            }
        }
    }
}

enum class WifiState {
    OFF,
    TURNING_ON,
    ON,
    TURNING_OFF,
}

enum class SaveState {
    IDLE, // Sin cambios
    DIRTY, // Hay cambios pendientes
    SAVING, // Enviando al PLC
}

enum class ReadState {
    IDLE,
    READING_CONFIG,
    READING_STATUS,
}

@Composable
fun NetworkScreen(
    networkSnapshot: NetworkSnapshot,
    lastRxLine: String,
    onSendText: (String) -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    ssid: String,
    onSsidChange: (String) -> Unit,
    pwd: String,
    onPwdChange: (String) -> Unit,
    showPwd: Boolean,
    onShowPwdChange: (Boolean) -> Unit,
    ip: String,
    onIpChange: (String) -> Unit,
    gw: String,
    onGwChange: (String) -> Unit,
    mask: String,
    onMaskChange: (String) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
) {

    var statusText by rememberSaveable { mutableStateOf("—") }
    var wifiState by rememberSaveable { mutableStateOf(WifiState.OFF) }
    var linkUp by rememberSaveable { mutableStateOf(false) }
    var tcpUp by rememberSaveable { mutableStateOf(false) }
    var ipAddress by rememberSaveable { mutableStateOf("-") }
    var rssi by rememberSaveable { mutableStateOf<Int?>(null) }

    var saveState by rememberSaveable { mutableStateOf(SaveState.IDLE) }
    var originalConfig by remember { mutableStateOf<NetConfig?>(null) }

    var waitingNetStatAfterNet by rememberSaveable { mutableStateOf(false) }
    var readState by rememberSaveable { mutableStateOf(ReadState.IDLE) }

    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    val isStatic = mode.equals("STATIC", true)

    fun isValidIp(ipStr: String): Boolean {
        val r =
            Regex("""^((25[0-5]|2[0-4]\d|1\d\d|\d\d|\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|\d\d|\d)$""")
        return r.matches(ipStr.trim())
    }

    fun parsePort(p: String): Int? {
        val v = p.trim().toIntOrNull() ?: return null
        return if (v in 1..65535) v else null
    }

    val portInt = parsePort(port)

    val errors =
        remember(mode, ssid, pwd, ip, gw, mask, host, port) {
            mutableListOf<String>().apply {
                if (isStatic) {
                    if (!isValidIp(ip)) add("Invalid IP address")
                    if (!isValidIp(gw)) add("Invalid gateway address")
                    if (!isValidIp(mask)) add("Invalid subnet mask")
                }
                if (
                    listOf(ssid, pwd, host, ip, gw, mask).any { field ->
                        field.any { it == ';' || it == '\r' || it == '\n' }
                    }
                )
                    add("Fields cannot contain semicolons or line breaks")
                if (ssid.toByteArray(Charsets.UTF_8).size > 32)
                    add("SSID is too long (maximum 32 bytes)")
                if (pwd.toByteArray(Charsets.UTF_8).size > 64)
                    add("Password is too long (maximum 64 bytes)")
                if (host.isBlank()) add("Host is required")
                if (portInt == null) add("Invalid port number")
            }
        }

    val canSave = errors.isEmpty()

    LaunchedEffect(networkSnapshot.configRevision) {
        networkSnapshot.config?.let {
            onModeChange(it.mode)
            onSsidChange(it.ssid)
            onPwdChange(it.pwd)
            onIpChange(it.ip)
            onGwChange(it.gw)
            onMaskChange(it.mask)
            onHostChange(it.host)
            onPortChange(it.port.toString())
            originalConfig = it
            saveState = SaveState.IDLE
            if (readState == ReadState.READING_CONFIG) {
                readState = ReadState.READING_STATUS
                onSendText("NETSTAT?")
            }
        }
    }
    LaunchedEffect(networkSnapshot.statusRevision) {
        networkSnapshot.status?.let {
            wifiState = if (it.wifiEnabled) WifiState.ON else WifiState.OFF
            linkUp = it.linkUp
            tcpUp = it.tcpUp
            ipAddress = it.ip ?: "-"
            rssi = it.rssi
            statusText =
                "WiFi:${if(it.wifiEnabled)"ON" else "OFF"} | LINK:${if(linkUp)"UP" else "DOWN"} | TCP:${if(tcpUp)"UP" else "DOWN"}"
            if (readState == ReadState.READING_STATUS) readState = ReadState.IDLE
        }
    }

    val scroll = rememberScrollState()

    LaunchedEffect(mode, ssid, pwd, ip, gw, mask, host, port) {
        val current = originalConfig ?: return@LaunchedEffect

        val changed =
            current.mode != mode ||
                current.ssid != ssid ||
                current.pwd != pwd ||
                current.ip != ip ||
                current.gw != gw ||
                current.mask != mask ||
                current.host != host ||
                current.port.toString() != port

        if (saveState != SaveState.SAVING) {
            saveState = if (changed) SaveState.DIRTY else SaveState.IDLE
        }
    }

    val focusManager = LocalFocusManager.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                    .verticalScroll(scroll)
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(H_SPACING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "STATUS:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // --- WIFI ---
                AssistChip(
                    enabled = wifiState == WifiState.ON || wifiState == WifiState.OFF,
                    onClick = {
                        when (wifiState) {
                            WifiState.OFF -> {
                                wifiState = WifiState.TURNING_ON
                                onSendText("WIFI=1")
                            }
                            WifiState.ON -> {
                                wifiState = WifiState.TURNING_OFF
                                onSendText("WIFI=0")
                            }
                            else -> {}
                        }
                    },
                    label = {
                        Text(
                            when (wifiState) {
                                WifiState.ON -> "WiFi ON"
                                WifiState.OFF -> "WiFi OFF"
                                WifiState.TURNING_ON -> "Activating…"
                                WifiState.TURNING_OFF -> "Deactivating…"
                            },
                            color =
                                when (wifiState) {
                                    WifiState.ON -> Color(0xFF2E7D32)
                                    WifiState.OFF -> Color(0xFFC62828)
                                    else -> Color(0xFFF57C00)
                                },
                        )
                    },
                    colors =
                        AssistChipDefaults.assistChipColors(
                            containerColor =
                                when (wifiState) {
                                    WifiState.ON -> Color(0xFFE8F5E9)
                                    WifiState.OFF -> Color(0xFFFFEBEE)
                                    else -> Color(0xFFFFF3E0)
                                }
                        ),
                )

                // --- LINK (SOLO INFORMATIVO) ---
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (linkUp) "LINK UP" else "LINK DOWN",
                            color = if (linkUp) Color(0xFF2E7D32) else Color(0xFFC62828),
                        )
                    },
                    colors =
                        AssistChipDefaults.assistChipColors(
                            containerColor = if (linkUp) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        ),
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (tcpUp) "TCP UP" else "TCP DOWN",
                            color =
                                when {
                                    tcpUp -> Color(0xFF2E7D32)
                                    linkUp -> Color(0xFFF57C00) // WiFi ok pero servidor no
                                    else -> Color(0xFFC62828)
                                },
                        )
                    },
                    colors =
                        AssistChipDefaults.assistChipColors(
                            containerColor =
                                when {
                                    tcpUp -> Color(0xFFE8F5E9)
                                    linkUp -> Color(0xFFFFF3E0)
                                    else -> Color(0xFFFFEBEE)
                                }
                        ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(H_SPACING)) {

                // DHCP
                Button(
                    onClick = { onModeChange("DHCP") },
                    contentPadding = PaddingValues(12.dp, 4.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                if (mode == "DHCP") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor =
                                if (mode == "DHCP") Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                ) {
                    Text("DHCP", style = MaterialTheme.typography.labelMedium)
                }

                // STATIC
                Button(
                    onClick = { onModeChange("STATIC") },
                    contentPadding = PaddingValues(12.dp, 4.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                if (mode == "STATIC") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor =
                                if (mode == "STATIC") Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                ) {
                    Text("STATIC", style = MaterialTheme.typography.labelMedium)
                }
            }

            // --- Fields (compact) ---
            CompactField(
                value = ssid,
                onValueChange = { onSsidChange(it) },
                placeholder = "SSID",
                modifier = Modifier.fillMaxWidth(),
                imeAction = ImeAction.Done,
                onImeAction = { focusManager.clearFocus() },
            )

            CompactPasswordField(
                value = pwd,
                onValueChange = { onPwdChange(it) },
                placeholder = "Password",
                show = showPwd,
                onToggleShow = { onShowPwdChange(!showPwd) },
                modifier = Modifier.fillMaxWidth(),
                imeAction = ImeAction.Done,
                onImeAction = { focusManager.clearFocus() },
            )

            if (pwd == "***")
                Text(
                    "The stored password will be kept when you apply the configuration. Enter a new one to replace it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            if (isStatic) {
                CompactField(
                    value = ip,
                    onValueChange = { onIpChange(it) },
                    placeholder = "PLC IP",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    onImeAction = { focusManager.clearFocus() },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(H_SPACING)) {
                    CompactField(
                        value = gw,
                        onValueChange = { onGwChange(it) },
                        placeholder = "Gateway",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                        onImeAction = { focusManager.clearFocus() },
                    )
                    CompactField(
                        value = mask,
                        onValueChange = { onMaskChange(it) },
                        placeholder = "Mask",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                        onImeAction = { focusManager.clearFocus() },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(H_SPACING)) {
                CompactField(
                    value = host,
                    onValueChange = { onHostChange(it) },
                    placeholder = "Host",
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Done,
                    onImeAction = { focusManager.clearFocus() },
                )
                CompactField(
                    value = port,
                    onValueChange = { onPortChange(it.filter(Char::isDigit)) },
                    placeholder = "Port",
                    modifier = Modifier.width(90.dp),
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    onImeAction = { focusManager.clearFocus() },
                )
            }

            // --- Actions ---
            Row(horizontalArrangement = Arrangement.spacedBy(H_SPACING)) {
                Button(
                    onClick = {
                        readState = ReadState.READING_CONFIG
                        onSendText("NET?")
                    },
                    contentPadding = PaddingValues(12.dp, 4.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                when (readState) {
                                    ReadState.IDLE -> Color(0xFFFFC107) // Amarillo profesional
                                    else -> Color(0xFFFFA000) // Amarillo más intenso mientras lee
                                },
                            contentColor = Color(0xFF212121), // Gris muy oscuro (mejor que blanco)
                        ),
                ) {
                    Text(
                        when (readState) {
                            ReadState.IDLE -> "Read PLC Configuration"
                            else -> "Reading PLC configuration..."
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Button(
                    onClick = { onSendText("NETSTAT?") },
                    contentPadding = PaddingValues(12.dp, 4.dp),
                ) {
                    Text("Refresh Status", style = MaterialTheme.typography.labelMedium)
                }
            }

            Button(
                enabled = saveState == SaveState.DIRTY && canSave,
                onClick = {
                    saveState = SaveState.SAVING

                    val cfg = NetConfig(mode, ssid, pwd, ip, gw, mask, host, portInt ?: 0)

                    onSendText(buildNetSetCommand(cfg))
                },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            when (saveState) {
                                SaveState.DIRTY -> Color(0xFFF57C00) // naranja
                                SaveState.SAVING -> Color(0xFF1565C0) // azul
                                SaveState.IDLE -> MaterialTheme.colorScheme.primary
                            }
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (saveState) {
                        SaveState.DIRTY -> "Save New Configuration."
                        SaveState.SAVING -> "Updating Configuration..."
                        SaveState.IDLE -> "Configuration Saved"
                    }
                )
            }

            HorizontalDivider()

            errorText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("RX: $lastRxLine", style = MaterialTheme.typography.labelSmall)
        }
    }
}
