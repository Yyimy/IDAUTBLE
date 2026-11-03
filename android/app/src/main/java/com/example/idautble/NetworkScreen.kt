package com.example.idautble

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun NetworkScreen(session: NetworkSession) {
    val state = session.state
    val draft = state.draft
    val status = state.status
    val errors = remember(draft) { networkDraftErrors(draft) }
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
    val good = Color(0xFF2E7D32)
    val warning = Color(0xFFB66A00)
    val age = state.statusReceivedAt?.let { ((now - it).coerceAtLeast(0) / 1000) }
    val stale = !state.available || age == null || age > 15
    val controls = state.available && !state.busy
    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
            .verticalScroll(rememberScrollState())
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!state.ready || state.paused)
            Text(
                if (state.paused) "Network controls are paused during OTA."
                else "Connect Bluetooth and wait for the PLC to be ready.",
                fontSize = 12.sp,
                color = warning,
            )
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                NetworkHeading("LIVE CONNECTION")
                NetworkValue(
                    "WiFi",
                    when (status?.wifiEnabled) {
                        true -> "Enabled"
                        false -> "Disabled"
                        null -> "Unknown"
                    },
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
                )
                NetworkValue(
                    "Saved server",
                    state.config?.let { "${it.host}:${it.port}" } ?: "Not read",
                )
                NetworkValue("RSSI", status?.rssi?.let { "$it dBm" } ?: "—")
                Text(
                    if (age == null) "No status received"
                    else "Snapshot · ${age}s ago${if (stale) " · stale" else ""}",
                    fontSize = 10.sp,
                    color = if (stale) warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
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
                                else ->
                                    if (status?.wifiEnabled == true) "Disable WiFi"
                                    else "Enable WiFi"
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
                            if (state.pending?.operation == NetworkOperation.READ_STATUS)
                                "Refreshing…"
                            else "Refresh status",
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkHeading("PLC CONFIGURATION")
                Text(
                    when {
                        state.config == null ->
                            "Reading saved settings over Bluetooth works with WiFi disabled."
                        state.dirty ->
                            "Unsaved edits · connection indicators still show the PLC state."
                        state.saved -> "Saved and confirmed by PLC."
                        else -> "Saved settings loaded from PLC."
                    },
                    fontSize = 11.sp,
                    color = if (state.dirty) warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    for (mode in listOf("DHCP", "STATIC")) {
                        FilterChip(
                            selected = draft.mode == mode,
                            onClick = { session.edit { it.copy(mode = mode) } },
                            enabled = state.canEdit && state.config != null,
                            label = {
                                Text(mode, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            },
                        )
                    }
                }
                val editable = state.canEdit && state.config != null
                NetworkInput("WiFi network (SSID)", draft.ssid, editable) { value ->
                    session.edit { it.copy(ssid = value) }
                }
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
                Text(
                    if (draft.pwd == "***")
                        "Stored password is hidden and will be kept. Type a new value to replace it."
                    else if (draft.pwd.isEmpty()) "An empty password will be saved as empty."
                    else "New password · not saved yet",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.mode == "STATIC") {
                    NetworkInput(
                        "Static PLC IP",
                        draft.ip,
                        editable,
                        keyboard = KeyboardType.Decimal,
                    ) { value ->
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
                        "DHCP assigns the actual PLC IP shown above.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkInput(
                        "Server IP / hostname",
                        draft.host,
                        editable,
                        Modifier.weight(1f),
                    ) { value ->
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
                if (state.dirty)
                    errors.forEach {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
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
                        if (state.pending?.operation == NetworkOperation.SAVE)
                            "Waiting for confirmation…"
                        else "Save configuration",
                        fontSize = 12.sp,
                    )
                }
                Text(
                    state.notice,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
