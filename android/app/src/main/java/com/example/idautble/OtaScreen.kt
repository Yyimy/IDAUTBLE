package com.example.idautble

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.net.Inet4Address
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SelectFirmwareColor = Color(0xFFFFC107) // Amarillo
private val StartServerColor = Color(0xFF2E7D32) // Verde
private val StopServerColor = Color(0xFFC62828) // Rojo

enum class OtaUiState {
    IDLE,
    MODE_STARTING, // Se envió OTA=START
    WAITING_CLIENT, // AP activo esperando cliente
    WAITING_PARAMS, // Esperando URL/SHA
    DOWNLOADING, // HTTP_GET o PCT
    FLASHING, // Últimos % o HASH
    DONE,
    ERROR,
    ABORTING,
}

fun deriveOtaUiState(
    otaStatus: String?,
    otaPct: Int?,
    otaErr: String?,
    otaDone: Boolean,
): OtaUiState {

    if (otaErr != null) return OtaUiState.ERROR
    if (otaDone) return OtaUiState.DONE
    if (otaStatus?.contains("ABORT_REQ") == true) return OtaUiState.ABORTING

    if (otaStatus == null && otaPct == null) {
        return OtaUiState.IDLE
    }

    if (otaStatus?.contains("WAIT_CLIENT") == true) {
        return OtaUiState.WAITING_CLIENT
    }

    if (otaStatus?.contains("WAIT_PARAMS") == true) {
        return OtaUiState.WAITING_PARAMS
    }

    if (otaStatus?.contains("HTTP_GET") == true || otaPct != null && otaPct < 95) {
        return OtaUiState.DOWNLOADING
    }

    if (otaPct != null && otaPct >= 95 || otaStatus?.contains("HASH=") == true) {
        return OtaUiState.FLASHING
    }

    return OtaUiState.MODE_STARTING
}

@Composable
fun OtaScreen(
    otaApSsid: String?,
    onSendOta: (String) -> Unit,
    otaPct: Int?,
    otaStatus: String?,
    otaErr: String?,
    otaDone: Boolean,
    bleLinkState: BleLinkState,
    session: OtaSession,
    onSelectFirmware: (Uri) -> Unit,
    onAbortOta: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val otaUiState = deriveOtaUiState(otaStatus, otaPct, otaErr, otaDone)
    val fileName = session.fileName
    val fileSize = session.firmware?.size
    val sha256 = session.firmware?.sha256
    val serverRunning = session.serverRunning
    val calculating = session.calculating
    val deviceIp = session.deviceIp

    // ---------- Helpers ----------
    fun getActiveWifiIpv4(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // WiFi without Internet may not be activeNetwork when mobile data exists.
            val wifiAddresses =
                cm.allNetworks.flatMap { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) emptyList()
                    else
                        cm.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull {
                            (it.address as? Inet4Address)?.hostAddress
                        }
                }
            wifiAddresses.firstOrNull { it.startsWith("192.168.4.") } ?: wifiAddresses.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun refreshIpNow() {
        session.deviceIp = getActiveWifiIpv4(context)
    }

    fun isOnPlcAp(ip: String?): Boolean = ip?.startsWith("192.168.4.") == true

    // ---------- Refrescar IP al volver desde Ajustes ----------
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    repeat(10) { // 10 * 500ms = 5s
                        refreshIpNow()
                        delay(500)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Refresco inicial cuando se arranca/parar servidor
    LaunchedEffect(serverRunning) { refreshIpNow() }

    // File reading and hashing belong to Activity, so changing tabs cannot cancel them.
    val filePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) {
            uri: Uri? ->
            if (uri != null) onSelectFirmware(uri)
        }

    // ---------- UI ----------
    val scrollState = rememberScrollState()

    val canStartServer = session.firmware != null && !session.busy
    val canComputeCmd = serverRunning && (fileSize ?: 0L) > 0L && sha256 != null
    val ipNow = deviceIp
    val onPlc = isOnPlcAp(ipNow)

    val urlNow = if (serverRunning && ipNow != null) "http://$ipNow:8080/firmware.bin" else null
    val cmdPhase2 =
        if (canComputeCmd && urlNow != null) {
            "OTA=URL=$urlNow;SIZE=$fileSize;SHA256=$sha256"
        } else null

    val otaButtonText =
        when {
            !serverRunning -> "Start HTTP Server"
            !onPlc -> "1) Start OTA (enable PLC WiFi)"
            else -> "2) Send URL (download + flash)"
        }

    // ===== OTA STATE DERIVED FLAGS =====

    val otaModeActive =
        otaUiState == OtaUiState.WAITING_CLIENT ||
            otaUiState == OtaUiState.WAITING_PARAMS ||
            otaUiState == OtaUiState.DOWNLOADING ||
            otaUiState == OtaUiState.FLASHING ||
            otaUiState == OtaUiState.ABORTING

    val uploadLocked =
        otaUiState == OtaUiState.DOWNLOADING ||
            otaUiState == OtaUiState.FLASHING ||
            otaUiState == OtaUiState.ABORTING ||
            otaUiState == OtaUiState.DONE

    val canAbort =
        otaUiState == OtaUiState.WAITING_CLIENT ||
            otaUiState == OtaUiState.WAITING_PARAMS ||
            otaUiState == OtaUiState.DOWNLOADING ||
            otaUiState == OtaUiState.FLASHING

    val abortEnabled = canAbort && bleLinkState == BleLinkState.CONNECTED

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        // ===== BLE LINK STATUS =====
        val (bleText, bleColor) =
            when (bleLinkState) {
                BleLinkState.CONNECTED -> "BLE: CONNECTED" to Color(0xFF2E7D32)
                BleLinkState.CONNECTING -> "BLE: CONNECTING..." to Color(0xFF1976D2)
                BleLinkState.RECONNECTING -> "BLE: RECONNECTING..." to Color(0xFFF9A825)
                BleLinkState.DISCONNECTED -> "BLE: DISCONNECTED" to Color(0xFFC62828)
            }

        MaintenanceCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).background(bleColor, CircleShape))
                Text(
                    bleText,
                    color = bleColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        session.confirmationText
            ?.takeIf { otaUiState == OtaUiState.IDLE }
            ?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (session.confirmation == OtaConfirmation.CONFIRMED) Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
        session.localError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        MaintenanceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "FIRMWARE & HTTP SERVER",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                // ===== FILA SUPERIOR =====

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        shape = RoundedCornerShape(6.dp),
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !session.busy && !serverRunning && !calculating,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = SelectFirmwareColor,
                                contentColor = Color.Black,
                            ),
                    ) {
                        Text("Select Firmware")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = fileName ?: "No firmware selected",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ===== INFO TECNICA =====

                if (calculating) {
                    PlcIndeterminateBar(modifier = Modifier.fillMaxWidth())
                    Text("Calculating SHA256...", style = MaterialTheme.typography.bodySmall)
                }

                Column {
                    Text(
                        text = "Size: ${fileSize ?: 0} bytes     SHA256: ${sha256 ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 13.sp,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top, // <-- CAMBIO CLAVE
                    ) {
                        Text(
                            text = "IP: ${deviceIp ?: "-"}     URL: ${urlNow ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // ===== BOTONES: SERVER + REFRESH IP (MISMA LINEA) =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {

                    // Botón principal (ocupa el espacio)
                    Button(
                        shape = RoundedCornerShape(6.dp),
                        onClick = {
                            if (serverRunning) session.stopServer() else session.startServer()
                            refreshIpNow()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !session.busy && (serverRunning || canStartServer),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    if (serverRunning) StopServerColor else StartServerColor,
                                contentColor = Color.White,
                            ),
                    ) {
                        Text(
                            if (serverRunning) "Stop HTTP Server" else "Run HTTP Server",
                            maxLines = 1,
                        )
                    }

                    // Botón secundario (compacto)
                    OutlinedButton(
                        shape = RoundedCornerShape(6.dp),
                        onClick = { refreshIpNow() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("Refresh IP", maxLines = 1)
                    }
                }
            }
        }

        // ===== STEPS EN PARALELO =====

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ===== STEP 1 CARD =====
            MaintenanceCard(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "STEP 1",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )

                        Text(
                            "Start OTA and connect to PLC AP",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Text(
                        "PLC network: ${otaApSsid ?: "PLC_OTA_<ID>"}. Use the OTA WiFi password configured in the firmware. It is different from the BLE PIN; run info over USB to view it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Start with empty forks and no pending scans.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            shape = RoundedCornerShape(6.dp),
                            onClick = { onSendOta("OTA=START") },
                            enabled =
                                serverRunning &&
                                    !onPlc &&
                                    !otaModeActive &&
                                    !session.busy &&
                                    bleLinkState == BleLinkState.CONNECTED,
                            modifier = Modifier.weight(1f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        if (otaModeActive) Color(0xFFBDBDBD)
                                        else MaterialTheme.colorScheme.primary,
                                    contentColor =
                                        if (otaModeActive) Color.DarkGray else Color.White,
                                ),
                        ) {
                            Text(if (otaModeActive) "OTA Active" else "Start OTA", maxLines = 1)
                        }

                        OutlinedButton(
                            shape = RoundedCornerShape(6.dp),
                            onClick = {
                                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Open WiFi Settings", maxLines = 1)
                        }
                    }
                }
            }

            // ===== STEP 2 CARD =====
            MaintenanceCard(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "STEP 2",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )

                        Text(
                            "Once connected to PLC WiFi, upload firmware",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Button(
                        shape = RoundedCornerShape(6.dp),
                        onClick = {
                            val cmd = cmdPhase2
                            if (cmd == null) {
                                Toast.makeText(context, "Missing SHA256 or URL", Toast.LENGTH_SHORT)
                                    .show()
                                return@Button
                            }
                            onSendOta(cmd)
                        },
                        enabled =
                            serverRunning &&
                                onPlc &&
                                !uploadLocked &&
                                otaUiState == OtaUiState.WAITING_PARAMS &&
                                bleLinkState == BleLinkState.CONNECTED,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                uploadLocked -> "Uploading..."
                                else -> "Upload Firmware"
                            }
                        )
                    }
                }
            }
        }

        val otaModeActive =
            otaUiState == OtaUiState.WAITING_CLIENT ||
                otaUiState == OtaUiState.WAITING_PARAMS ||
                otaUiState == OtaUiState.DOWNLOADING ||
                otaUiState == OtaUiState.FLASHING ||
                otaUiState == OtaUiState.ABORTING

        // ===== OTA PROGRESS =====
        val showOtaPanel = otaUiState != OtaUiState.IDLE

        // ===== HTTP SERVER STATUS (NO OTA BAR) =====
        if (serverRunning && !showOtaPanel) {
            Text(
                text = "HTTP Server: RUNNING (ready). Press Start OTA to continue.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (showOtaPanel) {
            MaintenanceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val statusText =
                        when {
                            otaErr != null -> "OTA ERROR: $otaErr"

                            otaUiState == OtaUiState.MODE_STARTING -> "Starting OTA mode..."

                            otaUiState == OtaUiState.WAITING_CLIENT -> "Waiting PLC WiFi client..."

                            otaUiState == OtaUiState.WAITING_PARAMS ->
                                "Waiting firmware parameters..."

                            otaUiState == OtaUiState.DOWNLOADING -> "Downloading firmware..."

                            otaUiState == OtaUiState.FLASHING -> "Flashing firmware..."

                            otaUiState == OtaUiState.ABORTING -> "Aborting OTA..."

                            otaUiState == OtaUiState.DONE ->
                                session.confirmationText
                                    ?: "Firmware accepted. Waiting for restart..."

                            session.busy -> "Waiting PLC response..."

                            else -> ""
                        }

                    Text(text = "OTA: $statusText", style = MaterialTheme.typography.bodySmall)

                    // Barra indeterminada si no hay porcentaje aún
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {

                        // ===== PROGRESS BAR =====
                        Box(modifier = Modifier.weight(1f)) {
                            when (otaUiState) {
                                OtaUiState.IDLE -> {
                                    // ✅ NO barra
                                    Spacer(Modifier.height(0.dp))
                                }

                                OtaUiState.ABORTING -> {
                                    // ✅ si quieres, tampoco barra al abortar
                                    Spacer(Modifier.height(0.dp))
                                }

                                OtaUiState.DOWNLOADING,
                                OtaUiState.FLASHING -> {
                                    if (otaPct == null) {
                                        PlcIndeterminateBar(modifier = Modifier.fillMaxWidth())
                                    } else {
                                        val p = (otaPct.coerceIn(0, 100)) / 100f
                                        PlcProgressBar(
                                            progress = p,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                OtaUiState.ERROR -> {
                                    // ✅ En error no hay progreso: sin barra
                                    Spacer(Modifier.height(0.dp))
                                }

                                else -> {
                                    PlcIndeterminateBar(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }

                    if (canAbort) {

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            shape = RoundedCornerShape(6.dp),
                            onClick = onAbortOta,
                            enabled = abortEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFC62828)
                                ),
                        ) {
                            Text(if (abortEnabled) "Abort OTA" else "Reconnect BLE to Abort")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlcProgressBar(
    progress: Float, // 0..1
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    trackColor: Color = Color(0xFFE0E0E0),
    barColor: Color = Color(0xFF1976D2),
    radius: Dp = 6.dp,
) {
    val p = progress.coerceIn(0f, 1f)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(trackColor, shape = RoundedCornerShape(radius))
    ) {
        Box(
            modifier =
                Modifier.fillMaxHeight()
                    .fillMaxWidth(p)
                    .background(barColor, shape = RoundedCornerShape(radius))
        )
    }
}

@Composable
private fun PlcIndeterminateBar(
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    trackColor: Color = Color(0xFFE0E0E0),
    barColor: Color = Color(0xFF1976D2),
    radius: Dp = 6.dp,
) {
    // Animación simple: un “bloque” que se desplaza
    val infinite = rememberInfiniteTransition(label = "ota_indeterminate")
    val x by
        infinite.animateFloat(
            initialValue = -0.30f,
            targetValue = 1.00f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "ota_indeterminate_x",
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(trackColor, shape = RoundedCornerShape(radius))
                .clip(RoundedCornerShape(radius))
    ) {
        // Bloque móvil (30% del ancho)
        Box(
            modifier =
                Modifier.fillMaxHeight()
                    .fillMaxWidth(0.30f)
                    .offset(x = (x * 3000).dp) // truco: offset grande para que recorra pantalla
                    .background(barColor.copy(alpha = 0.85f))
        )
    }
}
