package com.example.idautble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.idautble.ui.theme.IDAUTBLETheme
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

private var otaReconnectAttempts = 0
private val maxOtaReconnectAttempts = 15 // ~30 segundos si cada 2s
private var otaReconnectActive = false
private var otaRebootExpected = false
private var otaReconnectDeadline = 0L

// BLE UUIDs PLC
private val SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
private val CHAR_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

private const val ACK: Byte = 0x06
private const val NAK: Byte = 0x15
private const val STX: Byte = 0x02
private const val ETX: Byte = 0x03
private const val ESC: Byte = 0x1B
private const val CR: Byte = 0x0D
private const val LF: Byte = 0x0A

// ============================
// UI CONSTANTS (ancho fijo)
// ============================
private val PFX_W: Dp = 70.dp
private val BTN_SET_W: Dp = 64.dp
private val BTN_REFRESH_W: Dp = 80.dp
private val BTN_ACTION_W: Dp = 90.dp
private val LABEL_W: Dp = 32.dp // ancho fijo para "Pallet"/"Spot"/"RTC"
private val ROW_H: Dp = 40.dp // igual que tus campos

// ============================
// LOG COLORS (tema SIEMPRE CLARO)
// ============================
private object LogColors {
    val neutralText = Color(0xFF111111)
    val txBlue = Color(0xFF1E88E5)
    val rxGreen = Color(0xFF2E7D32) // Para el header RX
    val asciiPurple = Color(0xFF7B1FA2) // NUEVO
    val rxLabelRed = Color(0xFFD32F2F)
    val rxHexOrange = Color(0xFFF57C00)
    val warnAmber = Color(0xFFF9A825)
    val errRed = Color(0xFFD32F2F)
}

data class BleDevice(val name: String, val address: String, val rssi: Int)

enum class LogLevel {
    INFO,
    TX,
    RX,
    WARN,
    ERR,
}

sealed class LogEntry(open val t: Long = System.currentTimeMillis()) {

    data class Simple(
        override val t: Long = System.currentTimeMillis(),
        val lvl: LogLevel,
        val msg: String,
    ) : LogEntry(t)

    data class Traffic(
        override val t: Long = System.currentTimeMillis(),
        val direction: LogLevel,
        val ascii: String,
        val hex: String,
        val size: Int,
    ) : LogEntry(t)
}

enum class BleLinkState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

class MainActivity : ComponentActivity() {

    // ============================
    // PERMISOS
    // ============================
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasScanPermission() && hasConnectPermission()) initBluetooth()
            else log(LogLevel.WARN, "Grant Bluetooth permissions to find and connect to the PLC")
        }

    private fun requestBlePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startOtaReconnectLoop() {

        if (otaReconnectActive || !otaRebootExpected) return
        if (otaReconnectDeadline == 0L) otaReconnectDeadline = SystemClock.elapsedRealtime() + 45000
        otaReconnectActive = true
        otaReconnectAttempts = 0

        mainHandler.post(
            object : Runnable {
                override fun run() {

                    if (!otaReconnectActive) return

                    if (uiConnected.value) {
                        otaReconnectActive = false
                        return
                    }

                    if (
                        otaReconnectAttempts >= maxOtaReconnectAttempts ||
                            SystemClock.elapsedRealtime() > otaReconnectDeadline
                    ) {
                        log(LogLevel.ERR, "OTA reconnect timeout")
                        otaReconnectActive = false
                        otaRebootExpected = false
                        otaReconnectDeadline = 0
                        uiBleLinkState.value = BleLinkState.DISCONNECTED
                        return
                    }

                    otaReconnectAttempts++
                    log(LogLevel.INFO, "OTA reconnect attempt $otaReconnectAttempts")

                    scanAndReconnectOta()
                    mainHandler.postDelayed(this, 2000)
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun scanAndReconnectOta() {

        val targetAddr = lastConnectedAddress ?: return
        if (!hasScanPermission()) return

        log(LogLevel.INFO, "OTA scan before reconnect...")

        val tempScanner = scanner ?: return

        val cb =
            object : ScanCallback() {

                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val dev = result.device ?: return
                    if (dev.address == targetAddr && otaReconnectActive && otaRebootExpected) {

                        log(LogLevel.INFO, "PLC found in scan → reconnecting")

                        try {
                            tempScanner.stopScan(this)
                        } catch (_: Exception) {}
                        otaReconnectActive = false
                        reconnectLastDevice()
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    log(LogLevel.ERR, "OTA scan failed ($errorCode)")
                }
            }

        try {
            tempScanner.startScan(cb)
        } catch (_: Exception) {
            return
        }

        // Stop scan after 2s
        mainHandler.postDelayed(
            {
                try {
                    tempScanner.stopScan(cb)
                } catch (_: Exception) {}
            },
            2000,
        )
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        else true

    private fun ensureConnectPermissionOrRequest(): Boolean {
        if (hasConnectPermission()) return true
        log(LogLevel.WARN, "BLUETOOTH_CONNECT permission missing. Requesting…")
        requestBlePermissions()
        return false
    }

    // ============================
    // BLE + GATT
    // ============================
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val foundMap = linkedMapOf<String, BleDevice>()

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var lastConnectedAddress: String? = null

    private var deviceConnected = false
    private var notifyEnabled = false
    private var readyOk = false
    private var bleChannelReady = false

    // Android 11 safe sequence
    private var servicesRequested = false
    private var mtuDoneOrTimedOut = false

    // RX buffer (para tramas largas con ETX)
    private val streamDecoder = PlcStreamDecoder()
    private val maintenanceSession = MaintenanceSession { SystemClock.elapsedRealtime() }
    private lateinit var credentialStore: PlcCredentialStore
    private var sessionPin: String? = null
    private val connectionHandshake = ConnectionHandshake()
    private var blockedByUser = false
    private var lockAwaiting = false
    private var authInFlight = false
    private var authAttempt = 0L
    private var rememberOnSuccess = false
    private val pendingProtected = ArrayDeque<PendingWrite>()
    private val writeQueue = ArrayDeque<PendingWrite>()
    private var activeWrite: PendingWrite? = null
    private var activeReliableWrite = false
    private var txTimeout: Runnable? = null
    private var setupStarted = false
    private var negotiatedMtu = 23
    private var cccdAttempts = 0
    private val uiPinDialog = mutableStateOf(false)
    private val uiPinValue = mutableStateOf("")
    private val uiRememberPin = mutableStateOf(false)
    private val uiMaintenanceStatus = mutableStateOf("Read-only / not authorised")

    private val uiDeviceStatus = mutableStateOf<DeviceStatus?>(null)
    private val uiDeviceStatusReceived = mutableStateOf<Long?>(null)
    private val uiDeviceStatusBusy = mutableStateOf(false)
    private val uiDeviceStatusMessage = mutableStateOf("")
    private val uiDeviceStatusReady = mutableStateOf(false)
    private var deviceStatusRequest = 0L

    private val uiIdentityDialog = mutableStateOf(false)
    private val uiIdentity = mutableStateOf<EquipmentIdentity?>(null)
    private val uiEquipmentId = mutableStateOf<String?>(null)
    private val uiReadings = mutableStateOf(PlcReadingsState())
    private val uiIdentityDraft = mutableStateOf("")
    private val uiIdentityBusy = mutableStateOf(false)
    private val uiIdentityStatus = mutableStateOf("")
    private var identityExpected: String? = null
    private var identityRequest = 0L

    private data class PendingWrite(
        val data: ByteArray,
        val tag: String,
        val authAttempt: Long = 0L,
    )

    // ============================
    // UI State
    // ============================
    private val uiDevices = mutableStateOf(listOf<BleDevice>())
    private val uiSelected = mutableStateOf<BleDevice?>(null)
    private val uiConnected = mutableStateOf(false)
    private val uiStatusLine = mutableStateOf("Disconnected")
    private val uiLogs = mutableStateListOf<LogEntry>()
    private val uiLastRxLine = mutableStateOf("")
    private val uiNetworkSnapshot = mutableStateOf(NetworkSnapshot())
    private val uiAltState = mutableStateOf<Boolean?>(null)
    private val uiAltKnown = mutableStateOf(false)
    private val uiScanning = mutableStateOf(false)
    // Progreso Scan 5s
    private val uiScanProgress = mutableStateOf(0f) // 0..1
    private val uiScanRemainSec = mutableStateOf(0) // 5..0

    // ============================
    // OTA UI State
    // ============================
    private val uiOtaApSsid = mutableStateOf<String?>(null)
    private val uiOtaPct = mutableStateOf<Int?>(null) // null = indeterminado
    private val uiOtaStatus = mutableStateOf<String?>(null) // última línea OTA (sin prefijos raros)
    private val uiOtaErr = mutableStateOf<String?>(null) // OTA_ERR=...
    private val uiOtaDone = mutableStateOf(false) // true cuando llega DONE
    private val uiOtaActive = mutableStateOf(false) // se activa cuando llegan mensajes OTA
    private val uiBleLinkState = mutableStateOf(BleLinkState.DISCONNECTED)

    private fun resetOtaUi() {
        uiOtaApSsid.value = null
        uiOtaPct.value = null
        uiOtaStatus.value = null
        uiOtaErr.value = null
        uiOtaDone.value = false
        uiOtaActive.value = false
    }

    // Campos de UI (CFG/RTC/manual)
    private val uiPfxP = mutableStateOf("")
    private val uiPfxU = mutableStateOf("")
    private val uiRtcIso = mutableStateOf(nowIso())
    private val uiManualText = mutableStateOf(TextFieldValue(""))
    private val uiManualHex = mutableStateOf("1B 4C 0D 0A")
    private val uiTxtAddCrlf = mutableStateOf(true)

    private fun log(lvl: LogLevel, msg: String) {
        if (uiLogs.size >= 1000) uiLogs.removeAt(uiLogs.lastIndex)
        uiLogs.add(0, LogEntry.Simple(lvl = lvl, msg = msg))

        when (lvl) {
            LogLevel.ERR,
            LogLevel.WARN,
            LogLevel.INFO -> uiStatusLine.value = msg
            else -> {}
        }
    }

    private fun logTraffic(direction: LogLevel, ascii: String, hex: String, size: Int) {
        if (uiLogs.size >= 1000) uiLogs.removeAt(uiLogs.lastIndex)
        uiLogs.add(
            0,
            LogEntry.Traffic(direction = direction, ascii = ascii, hex = hex, size = size),
        )
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth() {
        if (!hasScanPermission() || !hasConnectPermission()) return
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bm.adapter
            if (adapter == null || !adapter.isEnabled) {
                scanner = null
                log(LogLevel.WARN, "Enable Bluetooth in Android settings")
                return
            }
            scanner = adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            scanner = null
            log(LogLevel.WARN, "Bluetooth permission is pending")
        }
    }

    // ============================
    // SCAN
    // ============================
    private val scanCb =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return

                val nameRaw =
                    runCatching { result.scanRecord?.deviceName }.getOrNull()
                        ?: runCatching {
                                if (
                                    Build.VERSION.SDK_INT >= 31 &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                        ) != PackageManager.PERMISSION_GRANTED
                                )
                                    null
                                else dev.name
                            }
                            .getOrNull()

                val name = nameRaw?.trim()

                if (name.isNullOrEmpty()) return

                val rssi = result.rssi
                foundMap[addr] = BleDevice(name, addr, rssi)
                uiDevices.value = foundMap.values.sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                log(LogLevel.ERR, "Scan failed (errorCode=$errorCode)")
                stopScan()
            }
        }

    private var scanStartMs: Long = 0L
    private var scanDurationMs: Long = 0L
    private val scanTick =
        object : Runnable {
            override fun run() {
                if (!scanning) return

                val elapsed = System.currentTimeMillis() - scanStartMs
                val p = (elapsed.toFloat() / scanDurationMs.toFloat()).coerceIn(0f, 1f)
                uiScanProgress.value = p

                val remainMs = (scanDurationMs - elapsed).coerceAtLeast(0L)
                uiScanRemainSec.value = kotlin.math.ceil(remainMs / 1000.0).toInt()

                mainHandler.postDelayed(this, 100) // refresco suave
            }
        }

    @SuppressLint("MissingPermission")
    private fun startScan(durationMs: Long = 5000) {
        if (scanning) return
        if (!hasScanPermission()) {
            log(LogLevel.WARN, "SCAN permissions missing. Requesting…")
            requestBlePermissions()
            return
        }
        if (scanner == null) initBluetooth()
        if (scanner == null) {
            log(LogLevel.WARN, "Bluetooth is unavailable; check settings and permissions")
            return
        }
        scanning = true
        uiScanning.value = true
        scanStartMs = System.currentTimeMillis()
        scanDurationMs = durationMs
        uiScanProgress.value = 0f
        uiScanRemainSec.value = (durationMs / 1000L).toInt()
        mainHandler.removeCallbacks(scanTick)
        mainHandler.post(scanTick)

        log(LogLevel.INFO, "Scanning ...")
        foundMap.clear()
        uiDevices.value = emptyList()

        try {
            scanner?.startScan(scanCb)
        } catch (se: SecurityException) {
            scanning = false
            log(LogLevel.ERR, "SecurityException in startScan()")
            requestBlePermissions()
            return
        } catch (_: Exception) {
            scanning = false
            log(LogLevel.ERR, "Scan start failed")
            return
        }

        mainHandler.postDelayed({ stopScan() }, durationMs)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        uiScanning.value = false
        mainHandler.removeCallbacks(scanTick)
        uiScanProgress.value = 1f
        uiScanRemainSec.value = 0
        if (!hasScanPermission()) return
        try {
            scanner?.stopScan(scanCb)
        } catch (_: Exception) {}
        log(LogLevel.INFO, "Found: ${uiDevices.value.size}")
    }

    // ============================
    // CONNECT / DISCONNECT
    // ============================
    @SuppressLint("MissingPermission")
    private fun connectSelected() {
        if (!ensureConnectPermissionOrRequest()) return

        val d =
            uiSelected.value
                ?: run {
                    log(LogLevel.WARN, "Select a device")
                    return
                }
        lastConnectedAddress = d.address
        disconnectGatt(silentLog = true)

        resetSecureTransport()
        servicesRequested = false
        mtuDoneOrTimedOut = false
        notifyEnabled = false
        readyOk = false
        bleChannelReady = false
        streamDecoder.clear()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val remote =
            try {
                bm.adapter.getRemoteDevice(d.address)
            } catch (_: Exception) {
                log(LogLevel.ERR, "Invalid MAC address")
                return
            }

        log(LogLevel.INFO, "Connecting ... ${d.address}...")

        uiBleLinkState.value = BleLinkState.CONNECTING

        try {
            gatt = remote.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (se: SecurityException) {
            log(LogLevel.ERR, "SecurityException in connectGatt()")
            requestBlePermissions()
        } catch (_: Exception) {
            log(LogLevel.ERR, "Error in connectGatt()")
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconnectLastDevice() {

        if (!ensureConnectPermissionOrRequest()) return

        val addr = lastConnectedAddress ?: return

        // 🔴 Forzar limpieza absoluta
        gatt?.let { closeGattSafely(it) }
        gatt = null
        txChar = null

        resetSecureTransport()
        servicesRequested = false
        mtuDoneOrTimedOut = false
        notifyEnabled = false
        readyOk = false
        bleChannelReady = false
        streamDecoder.clear()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val remote =
            try {
                bm.adapter.getRemoteDevice(addr)
            } catch (_: Exception) {
                log(LogLevel.ERR, "Invalid MAC for reconnect")
                return
            }

        uiBleLinkState.value = BleLinkState.RECONNECTING
        log(LogLevel.INFO, "Reconnecting ... $addr")

        try {
            gatt = remote.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            log(LogLevel.ERR, "Reconnect failed")
        }
    }

    private fun closeGattSafely(g: BluetoothGatt) {
        if (!hasConnectPermission()) return
        try {
            g.close()
        } catch (_: SecurityException) {
            log(LogLevel.WARN, "Bluetooth permission was revoked while closing the connection")
        } catch (_: Exception) {}
    }

    private fun handleBluetoothPermissionLoss() {
        failWrite("Bluetooth permission was revoked. Grant access and reconnect.")
        disconnectGatt(silentLog = true)
    }

    private fun completeReliableWrite(g: BluetoothGatt) {
        if (!hasConnectPermission()) {
            handleBluetoothPermissionLoss()
            return
        }
        try {
            if (!g.executeReliableWrite()) failWrite("Could not confirm the long write")
        } catch (_: SecurityException) {
            handleBluetoothPermissionLoss()
        }
    }

    private fun disconnectGatt(silentLog: Boolean = false, keepBleLinkState: Boolean = false) {
        resetSecureTransport()
        if (!keepBleLinkState) {
            otaReconnectActive = false
            otaRebootExpected = false
            otaReconnectDeadline = 0
        }
        val g = gatt
        if (g != null) {
            if (hasConnectPermission()) {
                try {
                    g.disconnect()
                } catch (_: SecurityException) {
                    log(LogLevel.WARN, "Bluetooth permission was revoked while disconnecting")
                } catch (_: Exception) {}
            }
            closeGattSafely(g)
        }
        gatt = null
        txChar = null
        streamDecoder.clear()

        deviceConnected = false
        notifyEnabled = false
        readyOk = false

        uiConnected.value = false
        uiStatusLine.value = "Disconnected"
        if (!silentLog) log(LogLevel.INFO, "Disconnected")

        if (!keepBleLinkState) {
            uiBleLinkState.value = BleLinkState.DISCONNECTED
        }
    }

    // ============================
    // NOTIFY enable
    // ============================
    @SuppressLint("MissingPermission")
    private fun startEnableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
        val ok1 =
            try {
                g.setCharacteristicNotification(ch, true)
            } catch (_: Exception) {
                false
            }
        if (!ok1) return false

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok =
            try {
                g.writeDescriptor(cccd)
            } catch (_: Exception) {
                false
            }
        if (ok) return true

        cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return try {
            g.writeDescriptor(cccd)
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestServicesOnce(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        val ok =
            try {
                g.discoverServices()
            } catch (_: Exception) {
                false
            }
        if (!ok) {
            servicesRequested = false
            log(LogLevel.WARN, "Android is busy; retrying service discovery")
            mainHandler.postDelayed(
                { if (gatt === g && !servicesRequested) requestServicesOnce(g) },
                1000,
            )
        }
    }

    // ============================
    // TX helpers
    // ============================
    private fun sendEscCmd(cmd: Char) {
        val raw = byteArrayOf(ESC, cmd.code.toByte(), CR, LF)
        sendBytes(raw, "CMD $cmd")
    }

    private fun expandTokens(input: String): ByteArray {

        var text = input

        text = text.replace("<ESC>", "\u001B")
        text = text.replace("<CR>", "\r")
        text = text.replace("<LF>", "\n")
        text = text.replace("<STX>", "\u0002")
        text = text.replace("<ETX>", "\u0003")
        text = text.replace("<ACK>", "\u0006")
        text = text.replace("<NAK>", "\u0015")

        return text.toByteArray(Charsets.UTF_8)
    }

    private fun sendText(text: String, addCrlf: Boolean) {
        val payload =
            if (addCrlf) {
                // Si el usuario ya puso CRLF, no lo dupliques
                if (text.endsWith("\r\n")) text else "$text\r\n"
            } else {
                // Envío RAW (sin CRLF)
                // Si el usuario escribió \r o \n manualmente, se respeta tal cual
                text
            }
        val bytes = expandTokens(payload)
        sendBytes(bytes, "TXT")
    }

    private fun sendHexString(hex: String) {
        val bytes =
            parseHexBytes(hex)
                ?: run {
                    log(LogLevel.WARN, "Invalid HEX")
                    return
                }
        sendBytes(bytes, "HEX")
    }

    private fun resetSecureTransport() {
        txTimeout?.let { mainHandler.removeCallbacks(it) }
        txTimeout = null
        maintenanceSession.reset()
        connectionHandshake.reset()
        deviceStatusRequest++
        uiDeviceStatus.value = null
        uiDeviceStatusReceived.value = null
        uiDeviceStatusBusy.value = false
        uiDeviceStatusMessage.value = ""
        uiDeviceStatusReady.value = false
        blockedByUser = false
        lockAwaiting = false
        authInFlight = false
        authAttempt++
        rememberOnSuccess = false
        pendingProtected.clear()
        writeQueue.clear()
        activeWrite = null
        activeReliableWrite = false
        sessionPin = null
        uiPinDialog.value = false
        uiPinValue.value = ""
        uiRememberPin.value = false
        uiMaintenanceStatus.value = "Read-only / not authorised"
        bleChannelReady = false
        setupStarted = false
        negotiatedMtu = 23
        cccdAttempts = 0
        streamDecoder.clear()
        uiNetworkSnapshot.value = NetworkSnapshot()
        uiIdentityDialog.value = false
        uiIdentity.value = null
        uiEquipmentId.value = null
        uiReadings.value = PlcReadingsState()
        uiIdentityBusy.value = false
        identityExpected = null
        identityRequest++
    }

    private fun openPinDialog() {
        if (!bleChannelReady) {
            log(LogLevel.WARN, "Wait for the BLE connection to complete")
            return
        }
        val address = lastConnectedAddress ?: return
        val remembered = credentialStore.load(address)
        uiPinValue.value = sessionPin ?: remembered ?: ""
        uiRememberPin.value = remembered != null
        uiPinDialog.value = true
    }

    private fun forgetSavedPin() {
        lastConnectedAddress?.let { credentialStore.remove(it) }
        sessionPin = null
        lockMaintenance()
        uiPinValue.value = ""
        uiRememberPin.value = false
        pendingProtected.clear()
        authInFlight = false
        uiMaintenanceStatus.value = "Saved PIN removed from this app"
        log(LogLevel.INFO, "Saved PIN removed and access lock requested.")
    }

    private fun lockMaintenance() {
        blockedByUser = true
        maintenanceSession.reset()
        authInFlight = false
        authAttempt++
        pendingProtected.clear()
        uiPinDialog.value = false
        writeQueue.removeAll { it.tag == "AUTH" || requiresMaintenanceAuthorization(it.data) }
        if (bleChannelReady && deviceConnected) {
            lockAwaiting = true
            uiMaintenanceStatus.value = "Locking maintenance access..."
            writeQueue.addFirst(PendingWrite("AUTH=OFF\r\n".toByteArray(Charsets.UTF_8), "LOCK"))
            pumpWrites()
            val g = gatt
            mainHandler.postDelayed(
                {
                    if (gatt === g && lockAwaiting) {
                        log(LogLevel.WARN, "Lock not confirmed: disconnecting to revoke access")
                        disconnectGatt(silentLog = true)
                    }
                },
                10000,
            )
        } else uiMaintenanceStatus.value = "Read-only / maintenance locked"
    }

    private fun startAuthorization(pin: String, remember: Boolean) {
        if (!validMaintenancePin(pin)) {
            log(LogLevel.WARN, "The PIN must contain six digits")
            return
        }
        if (!bleChannelReady) {
            log(LogLevel.WARN, "The BLE channel is not ready yet")
            return
        }
        if (authInFlight) return
        blockedByUser = false
        sessionPin = pin
        rememberOnSuccess = remember
        authInFlight = true
        authAttempt++
        uiMaintenanceStatus.value = "Checking PIN..."
        enqueueWrite(PendingWrite("AUTH=$pin\r\n".toByteArray(Charsets.UTF_8), "AUTH", authAttempt))
    }

    private fun authorizationFailed(reason: String, incorrectPin: Boolean = false) {
        authInFlight = false
        maintenanceSession.reset()
        pendingProtected.clear()
        sessionPin = null
        uiPinValue.value = ""
        uiMaintenanceStatus.value = reason
        if (incorrectPin) lastConnectedAddress?.let { credentialStore.remove(it) }
        log(LogLevel.WARN, reason)
    }

    @SuppressLint("MissingPermission")
    private fun sendBytes(data: ByteArray, tag: String) {
        if (!ensureConnectPermissionOrRequest()) return
        if (!deviceConnected || gatt == null || txChar == null) {
            log(LogLevel.WARN, "Not connected")
            return
        }
        val ready = data.contentEquals(byteArrayOf(ESC, 'C'.code.toByte(), CR, LF))
        if (!bleChannelReady && !ready) {
            log(LogLevel.WARN, "Wait for pairing and the BLE channel to be ready")
            return
        }
        if (data.isEmpty() || data.size >= 384) {
            log(LogLevel.WARN, "The firmware accepts commands of up to 383 bytes")
            return
        }
        val text = data.toString(Charsets.UTF_8).trim()
        if (text == "AUTH=OFF") {
            lockMaintenance()
            return
        }
        if (text.startsWith("AUTH=")) {
            startAuthorization(text.substringAfter("AUTH="), false)
            return
        }
        val packet = PendingWrite(data.copyOf(), tag)
        if (requiresMaintenanceAuthorization(data) && !maintenanceSession.isAuthorized()) {
            if (pendingProtected.size >= 8) {
                log(LogLevel.WARN, "Too many changes are waiting for authorisation")
                return
            }
            pendingProtected.addLast(packet)
            if (!authInFlight) {
                val pin = sessionPin ?: lastConnectedAddress?.let { credentialStore.load(it) }
                if (pin != null && !blockedByUser)
                    startAuthorization(
                        pin,
                        lastConnectedAddress?.let { credentialStore.load(it) != null } == true,
                    )
                else openPinDialog()
            }
            return
        }
        enqueueWrite(packet)
    }

    private fun enqueueWrite(packet: PendingWrite): Boolean {
        if (writeQueue.size >= 24) {
            log(LogLevel.ERR, "Send queue is full: command not sent")
            if (packet.tag == "AUTH")
                authorizationFailed("Could not queue the authorisation request")
            return false
        }
        writeQueue.addLast(packet)
        pumpWrites()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrites() {
        if (activeWrite != null || writeQueue.isEmpty()) return
        if (!hasConnectPermission()) {
            handleBluetoothPermissionLoss()
            return
        }
        val g = gatt ?: return
        val ch = txChar ?: return
        val packet = writeQueue.removeFirst()
        activeWrite = packet
        activeReliableWrite = packet.data.size > negotiatedMtu - 3
        val secret = hasProtocolSecret(packet.data)
        logTraffic(
            LogLevel.TX,
            redactProtocolText(asciiPretty(packet.data)),
            if (secret) "[sensitive content hidden]" else bytesToHex(packet.data),
            packet.data.size,
        )
        try {
            if (activeReliableWrite && !g.beginReliableWrite()) {
                failWrite("Could not start a long write")
                return
            }
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = packet.data
            val ok =
                if (Build.VERSION.SDK_INT >= 33)
                    g.writeCharacteristic(
                        ch,
                        packet.data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                else {
                    @Suppress("DEPRECATION") g.writeCharacteristic(ch)
                }
            if (!ok) {
                failWrite("Android rejected the send request; try again")
                return
            }
            val text = packet.data.toString(Charsets.UTF_8).trim()
            if (text == "OTA=START" || text.startsWith("OTA=URL=")) {
                uiOtaPct.value = null
                uiOtaErr.value = null
                uiOtaDone.value = false
                uiOtaActive.value = true
                uiOtaStatus.value =
                    if (text == "OTA=START") "START sent. Waiting for the PLC..."
                    else "URL sent. Waiting for HTTP_GET..."
            }
            val timeout = Runnable {
                if (gatt === g && activeWrite === packet) {
                    failWrite("GATT timeout; reconnect before retrying the command")
                    disconnectGatt(silentLog = true)
                }
            }
            txTimeout = timeout
            mainHandler.postDelayed(timeout, 10000)
        } catch (_: SecurityException) {
            handleBluetoothPermissionLoss()
            requestBlePermissions()
        } catch (_: Exception) {
            failWrite("Failed to send the BLE command")
        }
    }

    private fun failWrite(reason: String) {
        if (activeReliableWrite && hasConnectPermission())
            try {
                gatt?.abortReliableWrite()
            } catch (_: SecurityException) {
                // Permission may be revoked between the check and the abort.
            } catch (_: Exception) {}
        txTimeout?.let { mainHandler.removeCallbacks(it) }
        txTimeout = null
        activeWrite = null
        activeReliableWrite = false
        writeQueue.clear()
        pendingProtected.clear()
        authInFlight = false
        maintenanceSession.reset()
        uiMaintenanceStatus.value = "Access not confirmed"
        if (uiOtaActive.value) {
            uiOtaErr.value = reason
            uiOtaActive.value = false
        }
        log(LogLevel.ERR, reason)
    }

    private fun completeWrite() {
        val packet = activeWrite ?: return
        txTimeout?.let { mainHandler.removeCallbacks(it) }
        txTimeout = null
        activeWrite = null
        activeReliableWrite = false
        val g = gatt
        if (packet.tag == "AUTH")
            mainHandler.postDelayed(
                {
                    if (gatt === g && authInFlight && authAttempt == packet.authAttempt) {
                        authorizationFailed("The PLC did not confirm authorisation")
                        disconnectGatt(silentLog = true)
                    }
                },
                6000,
            )
        mainHandler.postDelayed({ if (gatt === g) pumpWrites() }, 80)
    }

    private fun requestDeviceStatus() {
        if (!bleChannelReady || uiDeviceStatusBusy.value) return
        val request = ++deviceStatusRequest
        uiDeviceStatusBusy.value = true
        uiDeviceStatusMessage.value = ""
        sendText("STATUS?", true)
        mainHandler.postDelayed(
            {
                if (request == deviceStatusRequest && uiDeviceStatusBusy.value) {
                    uiDeviceStatusBusy.value = false
                    uiDeviceStatusMessage.value =
                        "No status response. Firmware 3.0.6 or later is required. Check the connection and retry."
                }
            },
            6000,
        )
    }

    private fun handleProtocolControl(message: PlcMessage) {
        val text = message.text
        if (message.acknowledged && text.startsWith("STATUS=")) {
            val status = parseDeviceStatus(text)
            uiDeviceStatusBusy.value = false
            deviceStatusRequest++
            if (status != null) {
                uiDeviceStatus.value = status
                uiEquipmentId.value = status.id
                if (uiIdentity.value?.id != status.id) uiIdentity.value = null
                uiDeviceStatusReceived.value = SystemClock.elapsedRealtime()
                uiDeviceStatusMessage.value = ""
            } else {
                uiDeviceStatusMessage.value =
                    if (uiDeviceStatus.value == null) "Invalid or unsupported status response."
                    else "Invalid or unsupported status response. Previous snapshot retained."
            }
            return
        }
        val identity = if (message.acknowledged) parseEquipmentIdentity(text) else null
        if (identity != null) {
            uiIdentity.value = identity
            uiEquipmentId.value = identity.id
            uiSelected.value =
                uiSelected.value?.let {
                    if (it.address == lastConnectedAddress) it.copy(name = identity.bluetoothName)
                    else it
                }
            lastConnectedAddress?.let { address ->
                foundMap[address]?.let {
                    foundMap[address] = it.copy(name = identity.bluetoothName)
                }
                uiDevices.value = foundMap.values.sortedByDescending { it.rssi }
            }
            if (identityExpected == identity.id) {
                uiIdentityStatus.value =
                    "ID saved on the PLC. The next scan will show it as ${identity.bluetoothName}."
                identityExpected = null
                uiIdentityBusy.value = false
                identityRequest++
                uiIdentityDialog.value = false
                log(LogLevel.INFO, uiIdentityStatus.value)
                requestDeviceStatus()
            } else if (identityExpected == null && uiIdentityBusy.value) {
                uiIdentityDraft.value = identity.id
                uiIdentityStatus.value = "Enter the device ID."
                uiIdentityBusy.value = false
                identityRequest++
            }
            return
        }
        if (message.rejected && uiIdentityBusy.value) {
            uiIdentityBusy.value = false
            identityExpected = null
            identityRequest++
            uiIdentityStatus.value =
                when (text) {
                    "ERR=ID_BUSY" ->
                        "Wait for scans, event delivery and OTA to finish. You do not need to unload the pallet."
                    "ERR=ID_INVALID" ->
                        "Invalid ID: use 1–20 characters: A-Z, numbers, spaces, hyphens or underscores."
                    "ERR=ID_STORAGE" ->
                        "The PLC could not save the ID. Query it again before retrying."
                    "ERR=ID_BLUETOOTH" ->
                        "Could not update the Bluetooth name. Query the ID and try again."
                    else ->
                        "Change or query not confirmed: $text. Check that the firmware supports ID?."
                }
        }
        if (message.acknowledged && text == "CONECTADO") {
            readyOk = true
            bleChannelReady = true
            uiDeviceStatusReady.value = true
            log(LogLevel.INFO, "BLE ready. Queries are available.")
            for (query in connectionHandshake.onConnected()) {
                if (query == "STATUS?") requestDeviceStatus() else sendText(query, true)
            }
            return
        }
        if (message.acknowledged && text.startsWith("AUTH=OK")) {
            if (!authInFlight) return
            authInFlight = false
            val ttlToken = text.substringAfter("TTL=", "300").substringBefore(';')
            val ttl = ttlToken.toIntOrNull() ?: 300
            val sessionGrant = ttlToken == "SESSION"
            if (sessionGrant) maintenanceSession.authorizeSession()
            else maintenanceSession.authorize(ttl)
            uiMaintenanceStatus.value =
                if (sessionGrant) "Maintenance authorised"
                else "Temporary access: older firmware ($ttl s)"
            val address = lastConnectedAddress
            if (address != null && rememberOnSuccess && sessionPin != null)
                try {
                    credentialStore.save(address, sessionPin!!)
                } catch (_: Exception) {
                    log(
                        LogLevel.WARN,
                        "PIN accepted, but it could not be saved in encrypted storage",
                    )
                }
            else if (address != null && !rememberOnSuccess) credentialStore.remove(address)
            val g = gatt
            if (!sessionGrant)
                mainHandler.postDelayed(
                    {
                        if (gatt === g && !blockedByUser && !maintenanceSession.isAuthorized())
                            uiMaintenanceStatus.value =
                                "Access expired on older firmware; update the PLC"
                    },
                    ttl.coerceIn(1, 300) * 1000L,
                )
            while (pendingProtected.isNotEmpty()) enqueueWrite(pendingProtected.removeFirst())
            return
        }
        if (message.acknowledged && text == "AUTH=OFF") {
            lockAwaiting = false
            maintenanceSession.reset()
            uiMaintenanceStatus.value = "Read-only / maintenance locked"
            return
        }
        if (message.rejected) {
            if (lockAwaiting && text.startsWith("ERR=AUTH")) {
                log(
                    LogLevel.WARN,
                    "Older firmware does not support locking: disconnecting to revoke access",
                )
                disconnectGatt(silentLog = true)
                return
            }
            when {
                text == "ERR=AUTH" ->
                    authorizationFailed(
                        "Incorrect PIN. Check PLC_MAINTENANCE_PIN.",
                        incorrectPin = true,
                    )
                text == "ERR=AUTH_LOCKED" ->
                    authorizationFailed("PIN temporarily locked. Wait one minute.")
                text == "ERR=AUTH_REQUIRED" ->
                    authorizationFailed(
                        "Authorisation required. Open PIN / ACCESS and retry the operation."
                    )
                text == "BUSY" || text == "ERR=BUSY" ->
                    log(
                        LogLevel.WARN,
                        "PLC is busy; operation rejected. Try again when it finishes.",
                    )
                text == "ERR=NOT_IDLE" -> {
                    uiOtaErr.value =
                        "Unload the forks and wait for scans to finish before starting OTA."
                    uiOtaActive.value = false
                }
                text.startsWith("ERR=") -> log(LogLevel.WARN, "PLC rejected the operation: $text")
            }
        }
        if (text.startsWith("FW=") && text.contains("ACKMODE=1"))
            log(
                LogLevel.WARN,
                "PLC is in ACKMODE=1: this maintenance app does not acknowledge business records. Use ACKMODE=0 unless an integrated receiver handles acknowledgements.",
            )
    }

    @SuppressLint("MissingPermission")
    private fun ensureBonded(g: BluetoothGatt) {
        when (g.device.bondState) {
            BluetoothDevice.BOND_BONDED -> beginGattSetup(g)
            BluetoothDevice.BOND_BONDING ->
                log(LogLevel.INFO, "Waiting for pairing. Enter the PIN in the Android dialog.")
            else -> {
                log(
                    LogLevel.INFO,
                    "Pair using the PIN configured in the firmware (Android dialog).",
                )
                if (!g.device.createBond())
                    log(LogLevel.WARN, "Could not start pairing; open Bluetooth settings.")
            }
        }
        mainHandler.postDelayed(
            {
                if (gatt === g && !setupStarted) {
                    log(
                        LogLevel.WARN,
                        "Pairing is pending. Forget the PLC in Android if you have changed its PIN.",
                    )
                    disconnectGatt(silentLog = true)
                }
            },
            45000,
        )
    }

    @SuppressLint("MissingPermission")
    private fun beginGattSetup(g: BluetoothGatt) {
        if (gatt !== g || setupStarted || !deviceConnected) return
        setupStarted = true
        mainHandler.postDelayed(
            {
                if (gatt === g && !bleChannelReady) {
                    log(
                        LogLevel.WARN,
                        "The BLE channel is not ready. Check the pairing/PIN and reconnect.",
                    )
                    disconnectGatt(silentLog = true)
                }
            },
            15000,
        )
        try {
            g.requestMtu(185)
        } catch (_: Exception) {}
        mainHandler.postDelayed(
            {
                if (gatt === g && !mtuDoneOrTimedOut) {
                    mtuDoneOrTimedOut = true
                    requestServicesOnce(g)
                }
            },
            1500,
        )
    }

    private val bondReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (
                    intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED ||
                        !hasConnectPermission()
                )
                    return
                val device =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    }
                val g = gatt ?: return
                if (device?.address != g.device.address) return
                when (
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                ) {
                    BluetoothDevice.BOND_BONDED ->
                        mainHandler.postDelayed({ if (gatt === g) beginGattSetup(g) }, 300)
                    BluetoothDevice.BOND_NONE -> {
                        log(
                            LogLevel.WARN,
                            "Pairing cancelled or rejected. If the PIN has changed, forget the PLC and pair again.",
                        )
                        disconnectGatt(silentLog = true)
                    }
                }
            }
        }

    // ============================
    // RX handling (NO DUPLICATES)
    // ============================
    private fun handleIncomingChunk(chunk: ByteArray) {
        val before = streamDecoder.discardedBuffers
        for (message in streamDecoder.feed(chunk)) {
            logTraffic(
                LogLevel.RX,
                asciiPretty(message.bytes),
                bytesToHex(message.bytes),
                message.bytes.size,
            )
            if (message.isFrame) {
                uiReadings.value = uiReadings.value.receive(message, SystemClock.elapsedRealtime())
                continue
            }
            val first = message.bytes.firstOrNull()
            val payloadText = message.text
            uiNetworkSnapshot.value = uiNetworkSnapshot.value.receive(payloadText)
            handleProtocolControl(message)
            // ============================
            // CFG parsing (PLC -> Android)
            // ============================
            if (payloadText.contains("PFXP=") && payloadText.contains("PFXU=")) {

                val body = payloadText.substringAfter("CFG=").trim()

                // Esperamos algo tipo: PFXP=]d1;PFXU=]Q1
                val parts = body.split(";")

                parts.forEach { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) {
                        val key = kv[0].trim().uppercase()
                        val value = kv[1].trim()

                        when (key) {
                            "PFXP" -> uiPfxP.value = value
                            "PFXU" -> uiPfxU.value = value
                        }
                    }
                }

                log(LogLevel.INFO, "CFG received = PFXP=${uiPfxP.value}, PFXU=${uiPfxU.value}")
            }

            // Esto sigue siendo útil para UI/log
            uiLastRxLine.value =
                when (first) {
                    ACK -> "<ACK>$payloadText"
                    NAK -> "<NAK>$payloadText"
                    else -> payloadText
                }

            val line = uiLastRxLine.value

            // ============================
            // OTA parsing (PLC -> Android)
            // Formatos esperados:
            //  - <ACK>OTA=... \r\n
            //  - <NAK>OTA_ERR=... \r\n
            //  - OTA=PCT=xx
            //  - OTA=DONE REBOOT
            // ============================

            val clean = line.replace("<ACK>", "").replace("<NAK>", "").trim()

            // ============================
            // OTA parsing (PLC -> Android)
            // ============================
            if (
                payloadText.startsWith("OTA=", ignoreCase = true) ||
                    payloadText.startsWith("OTA_ERR=", ignoreCase = true)
            ) {

                uiOtaActive.value = true

                if (payloadText.startsWith("OTA_ERR=", ignoreCase = true)) {
                    uiOtaErr.value = payloadText.substringAfter("OTA_ERR=").trim()
                    uiOtaStatus.value = "ERROR: ${uiOtaErr.value}"
                } else {
                    val body = payloadText.substringAfter("OTA=").trim()
                    Regex("SSID=([^ ]+)").find(body)?.groupValues?.get(1)?.let {
                        uiOtaApSsid.value = it
                    }
                    uiOtaStatus.value = body

                    // Progreso
                    if (body.startsWith("PCT=", ignoreCase = true)) {
                        val pctVal = body.substringAfter("PCT=").trim().toIntOrNull()
                        if (pctVal != null) uiOtaPct.value = pctVal.coerceIn(0, 100)
                    }

                    // Done
                    if (body.contains("DONE", ignoreCase = true)) {
                        uiOtaDone.value = true
                        uiOtaPct.value = 100
                        otaRebootExpected = true // 🔥 activar reconexión automática
                    }
                }
            }

            val altIndex = line.indexOf("ALT=")

            if (altIndex >= 0) {

                val value = line.substring(altIndex + 4).trim()

                when {
                    value.startsWith("1") -> {
                        uiAltState.value = true
                        uiAltKnown.value = true
                        log(LogLevel.INFO, "ALT state = ENABLED")
                    }
                    value.startsWith("0") -> {
                        uiAltState.value = false
                        uiAltKnown.value = true
                        log(LogLevel.INFO, "ALT state = DISABLED")
                    }
                }
            }
        }
        if (streamDecoder.discardedBuffers != before)
            log(LogLevel.WARN, "Incomplete RX discarded; channel resynchronised")
    }

    // ============================
    // GATT callbacks (Android 11 safe)
    // ============================
    private val gattCb =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                mainHandler.post {
                    if (gatt !== g) {
                        closeGattSafely(g)
                        return@post
                    }
                    if (
                        status == BluetoothGatt.GATT_SUCCESS &&
                            newState == BluetoothProfile.STATE_CONNECTED
                    ) {
                        otaRebootExpected = false
                        otaReconnectActive = false
                        deviceConnected = true
                        uiConnected.value = true
                        uiBleLinkState.value = BleLinkState.CONNECTED
                        ensureBonded(g)
                    } else if (
                        newState == BluetoothProfile.STATE_DISCONNECTED ||
                            status != BluetoothGatt.GATT_SUCCESS
                    ) {
                        val shouldReconnect = otaRebootExpected
                        log(LogLevel.INFO, "BLE disconnected (status=$status)")
                        resetSecureTransport()
                        closeGattSafely(g)
                        gatt = null
                        txChar = null
                        deviceConnected = false
                        notifyEnabled = false
                        readyOk = false
                        uiConnected.value = false
                        uiBleLinkState.value =
                            if (shouldReconnect) BleLinkState.RECONNECTING
                            else BleLinkState.DISCONNECTED
                        if (shouldReconnect)
                            mainHandler.postDelayed({ startOtaReconnectLoop() }, 2500)
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                mainHandler.post {
                    if (gatt === g) {
                        negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                        mtuDoneOrTimedOut = true
                        log(LogLevel.INFO, "MTU: $negotiatedMtu")
                        requestServicesOnce(g)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                mainHandler.post {
                    if (gatt === g) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            log(LogLevel.ERR, "Could not discover BLE services")
                            return@post
                        }
                        val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                        if (ch == null) {
                            log(LogLevel.ERR, "The PLC does not provide FFE0/FFE1")
                            return@post
                        }
                        txChar = ch
                        cccdAttempts = 1
                        if (!startEnableNotify(g, ch))
                            log(LogLevel.ERR, "Could not enable notifications")
                    }
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                mainHandler.post {
                    if (gatt === g && descriptor.uuid == CCCD_UUID) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            notifyEnabled = true
                            log(LogLevel.INFO, "Notifications ready; checking the PLC...")
                            sendEscCmd('C')
                        } else if ((status == 5 || status == 15) && cccdAttempts < 2) {
                            cccdAttempts++
                            mainHandler.postDelayed(
                                { if (gatt === g) txChar?.let { startEnableNotify(g, it) } },
                                1000,
                            )
                        } else {
                            notifyEnabled = false
                            log(
                                LogLevel.ERR,
                                "Notifications rejected ($status). Check the pairing and PIN.",
                            )
                        }
                    }
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                mainHandler.post {
                    if (gatt !== g || ch.uuid != CHAR_UUID || activeWrite == null) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failWrite("BLE write rejected ($status). Check the pairing/PIN.")
                        return@post
                    }
                    if (activeReliableWrite) {
                        @Suppress("DEPRECATION") val echo = ch.value
                        if (echo != null && !echo.contentEquals(activeWrite!!.data)) {
                            failWrite("The long write does not match the command")
                            return@post
                        }
                        completeReliableWrite(g)
                    } else completeWrite()
                }
            }

            override fun onReliableWriteCompleted(g: BluetoothGatt, status: Int) {
                mainHandler.post {
                    if (gatt === g && activeWrite != null) {
                        if (status == BluetoothGatt.GATT_SUCCESS) completeWrite()
                        else failWrite("Long write rejected ($status)")
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT >= 33) return
                val bytes = ch.value?.copyOf() ?: return
                mainHandler.post {
                    if (gatt === g && ch.uuid == CHAR_UUID) handleIncomingChunk(bytes)
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                val bytes = value.copyOf()
                mainHandler.post {
                    if (gatt === g && ch.uuid == CHAR_UUID) handleIncomingChunk(bytes)
                }
            }
        }

    // ============================
    // Helpers
    // ============================
    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    private fun parseHexBytes(hex: String): ByteArray? {
        val cleaned =
            hex.trim()
                .replace(",", " ")
                .replace("0x", "", ignoreCase = true)
                .replace("\n", " ")
                .replace("\t", " ")
        if (cleaned.isEmpty()) return byteArrayOf()

        val parts = cleaned.split(" ").filter { it.isNotBlank() }
        val out = ArrayList<Byte>(parts.size)
        try {
            for (p in parts) {
                if (p.length > 2) return null
                val v = p.toInt(16)
                out.add(v.toByte())
            }
        } catch (_: Exception) {
            return null
        }
        return out.toByteArray()
    }

    private fun indexOfByte(list: List<Byte>, value: Byte): Int {
        for (i in list.indices) if (list[i] == value) return i
        return -1
    }

    private fun indexOfCrlf(buf: List<Byte>): Int {
        for (i in 0 until buf.size - 1) {
            if (buf[i] == CR && buf[i + 1] == LF) return i
        }
        return -1
    }

    private fun asciiPretty(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            when (b) {
                ACK -> sb.append("<ACK>")
                NAK -> sb.append("<NAK>")
                STX -> sb.append("<STX>")
                ETX -> sb.append("<ETX>")
                ESC -> sb.append("<ESC>")
                CR -> sb.append("<CR>")
                LF -> sb.append("<LF>")
                else -> {
                    val v = b.toInt() and 0xFF
                    if (v in 32..126) sb.append(v.toChar()) else sb.append("·")
                }
            }
        }
        return sb.toString()
    }

    private fun formatTime(t: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(t))

    // ============================
    // EQUIPMENT ID
    // ============================
    private fun showIdentityAuthorizationMessage() {
        Toast.makeText(this, "Enter the PIN in PIN / ACCESS first.", Toast.LENGTH_LONG).show()
    }

    private fun openIdentityDialog() {
        if (!bleChannelReady) {
            log(LogLevel.WARN, "Wait for the BLE connection to complete")
            return
        }
        if (!maintenanceSession.isAuthorized()) {
            showIdentityAuthorizationMessage()
            return
        }
        uiIdentityDialog.value = true
        uiIdentityDraft.value = uiEquipmentId.value ?: ""
        uiIdentityStatus.value = "Querying the PLC ID..."
        uiIdentityBusy.value = true
        identityExpected = null
        sendText("ID?", true)
        scheduleIdentityTimeout()
    }

    private fun saveIdentity() {
        val id = normalizedEquipmentId(uiIdentityDraft.value)
        if (!validEquipmentId(id) || uiIdentityBusy.value || !bleChannelReady) return
        if (!maintenanceSession.isAuthorized()) {
            uiIdentityDialog.value = false
            showIdentityAuthorizationMessage()
            return
        }
        uiIdentityDraft.value = id
        uiIdentityBusy.value = true
        identityExpected = id
        uiIdentityStatus.value = "Waiting for PLC confirmation..."
        sendText("ID=$id", true)
        scheduleIdentityTimeout()
    }

    private fun scheduleIdentityTimeout() {
        val request = ++identityRequest
        val currentGatt = gatt
        mainHandler.postDelayed(
            {
                if (gatt === currentGatt && identityRequest == request && uiIdentityBusy.value) {
                    uiIdentityBusy.value = false
                    identityExpected = null
                    uiIdentityStatus.value =
                        "No confirmation received. Close and reopen to query the current ID. Requires firmware 3.0.4 or later."
                }
            },
            10000,
        )
    }

    // ============================
    // LIFECYCLE
    // ============================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        credentialStore = PlcCredentialStore(this)
        ContextCompat.registerReceiver(
            this,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        requestBlePermissions()
        initBluetooth()

        enableEdgeToEdge()
        setContent {
            IDAUTBLETheme {
                PlcBleTesterLandscape(
                    networkSnapshot = uiNetworkSnapshot.value,
                    maintenanceStatus = uiMaintenanceStatus.value,
                    maintenanceAuthorised = maintenanceSession.isAuthorized(),
                    deviceStatus = uiDeviceStatus.value,
                    deviceStatusReceived = uiDeviceStatusReceived.value,
                    deviceStatusReady = uiDeviceStatusReady.value,
                    deviceStatusBusy = uiDeviceStatusBusy.value,
                    deviceStatusMessage = uiDeviceStatusMessage.value,
                    onStatusRefresh = { requestDeviceStatus() },
                    readings = uiReadings.value,
                    equipmentId = uiEquipmentId.value,
                    onIdentityClick = { openIdentityDialog() },
                    onMaintenanceClick = { openPinDialog() },
                    onForgetPin = { forgetSavedPin() },
                    onLockMaintenance = { lockMaintenance() },
                    devices = uiDevices.value,
                    selected = uiSelected.value,
                    connected = uiConnected.value,
                    scanning = uiScanning.value,
                    scanProgress = uiScanProgress.value,
                    scanRemainSec = uiScanRemainSec.value,
                    statusLine = uiStatusLine.value,
                    logs = uiLogs,
                    timeFmt = { formatTime(it) },
                    pfxP = uiPfxP.value,
                    pfxU = uiPfxU.value,
                    rtcIso = uiRtcIso.value,
                    manualText = uiManualText.value,
                    manualHex = uiManualHex.value,
                    txtAddCrlf = uiTxtAddCrlf.value,
                    onTxtAddCrlfChanged = { uiTxtAddCrlf.value = it },
                    onSelectDevice = { uiSelected.value = it },
                    onScan5s = { startScan(5000) },
                    onConnect = { connectSelected() },
                    onDisconnect = {
                        disconnectGatt()
                        uiSelected.value = null
                    },
                    onCmd = { c -> sendEscCmd(c) },
                    altState = uiAltState.value,
                    altKnown = uiAltKnown.value,
                    onAltQuery = { sendText("ALT?", addCrlf = true) },
                    onAltEnable = {
                        sendText("ALT=1", addCrlf = true)

                        // Esperamos un poco a que el PLC procese y luego pedimos estado real
                        mainHandler.postDelayed({ sendText("ALT?", addCrlf = true) }, 300)
                    },
                    onAltDisable = {
                        sendText("ALT=0", addCrlf = true)

                        mainHandler.postDelayed({ sendText("ALT?", addCrlf = true) }, 300)
                    },
                    onCfgQuery = { sendText("CFG?", addCrlf = true) },
                    onSetCfg = {
                        sendText("CFG=PFXP=${uiPfxP.value};PFXU=${uiPfxU.value}", addCrlf = true)
                    },
                    onSetRtc = { sendText("RTC=${uiRtcIso.value}", addCrlf = true) },
                    onRefreshRtc = { uiRtcIso.value = nowIso() },
                    onManualTextChanged = { uiManualText.value = it },
                    onManualHexChanged = { uiManualHex.value = it },
                    onSendText = { text -> sendText(text, addCrlf = true) },
                    onSendHex = { sendHexString(uiManualHex.value) },
                    onPfxPChanged = { uiPfxP.value = it },
                    onPfxUChanged = { uiPfxU.value = it },
                    onRtcIsoChanged = { uiRtcIso.value = it },
                    onClearLog = {
                        uiLogs.clear()
                        log(LogLevel.INFO, "Log cleared")
                    },
                    lastRxLine = uiLastRxLine.value,
                    onSendOta = { cmd -> sendText(cmd, addCrlf = false) },
                    otaApSsid = uiOtaApSsid.value,
                    otaPct = uiOtaPct.value,
                    otaStatus = uiOtaStatus.value,
                    otaErr = uiOtaErr.value,
                    otaDone = uiOtaDone.value,
                    bleLinkState = uiBleLinkState.value,
                    onResetOtaUi = { resetOtaUi() },
                )
                if (uiIdentityDialog.value)
                    EquipmentIdDialog(
                        current = uiIdentity.value,
                        draft = uiIdentityDraft.value,
                        busy = uiIdentityBusy.value,
                        status = uiIdentityStatus.value,
                        onDraftChanged = { uiIdentityDraft.value = it },
                        onSave = { saveIdentity() },
                        onDismiss = { uiIdentityDialog.value = false },
                    )
                if (uiPinDialog.value)
                    MaintenancePinDialog(
                        pin = uiPinValue.value,
                        onPinChanged = { uiPinValue.value = it },
                        remember = uiRememberPin.value,
                        onRememberChanged = { uiRememberPin.value = it },
                        onConfirm = {
                            uiPinDialog.value = false
                            startAuthorization(uiPinValue.value, uiRememberPin.value)
                        },
                        onCancel = {
                            uiPinDialog.value = false
                            pendingProtected.clear()
                            uiMaintenanceStatus.value = "Authorisation cancelled"
                        },
                    )
            }
        }
    }

    override fun onDestroy() {
        stopScan()
        disconnectGatt(silentLog = true)
        try {
            unregisterReceiver(bondReceiver)
        } catch (_: Exception) {}
        otaReconnectActive = false
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlcBleTesterLandscape(
    networkSnapshot: NetworkSnapshot,
    maintenanceStatus: String,
    maintenanceAuthorised: Boolean,
    deviceStatus: DeviceStatus?,
    deviceStatusReceived: Long?,
    deviceStatusReady: Boolean,
    deviceStatusBusy: Boolean,
    deviceStatusMessage: String,
    onStatusRefresh: () -> Unit,
    readings: PlcReadingsState,
    equipmentId: String?,
    onIdentityClick: () -> Unit,
    onMaintenanceClick: () -> Unit,
    onForgetPin: () -> Unit,
    onLockMaintenance: () -> Unit,
    devices: List<BleDevice>,
    selected: BleDevice?,
    connected: Boolean,
    scanning: Boolean,
    statusLine: String,
    scanProgress: Float,
    scanRemainSec: Int,
    logs: List<LogEntry>,
    timeFmt: (Long) -> String,
    pfxP: String,
    pfxU: String,
    rtcIso: String,
    manualHex: String,
    txtAddCrlf: Boolean,
    altState: Boolean?,
    altKnown: Boolean,
    onAltQuery: () -> Unit,
    onAltEnable: () -> Unit,
    onAltDisable: () -> Unit,
    onSelectDevice: (BleDevice) -> Unit,
    onScan5s: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCmd: (Char) -> Unit,
    onCfgQuery: () -> Unit,
    onSetCfg: () -> Unit,
    onSetRtc: () -> Unit,
    onRefreshRtc: () -> Unit,
    manualText: TextFieldValue,
    onManualTextChanged: (TextFieldValue) -> Unit,
    onManualHexChanged: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSendHex: () -> Unit,
    onTxtAddCrlfChanged: (Boolean) -> Unit,
    onPfxPChanged: (String) -> Unit,
    onPfxUChanged: (String) -> Unit,
    onRtcIsoChanged: (String) -> Unit,
    onClearLog: () -> Unit,
    lastRxLine: String,
    onSendOta: (String) -> Unit,
    otaApSsid: String?,
    otaPct: Int?,
    otaStatus: String?,
    otaErr: String?,
    otaDone: Boolean,
    bleLinkState: BleLinkState,
    onResetOtaUi: () -> Unit,
) {
    var showDevices by remember { mutableStateOf(false) } // oculto por defecto
    var binarySectionExpanded by rememberSaveable { mutableStateOf(true) }
    var equipmentSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var pinSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var connectionSectionExpanded by rememberSaveable { mutableStateOf(true) }
    var heightSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var textSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var manualSectionExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(connected) {
        if (connected) {
            showDevices = false
        }
    }

    // ---------------------------
    // NETWORK UI STATE (hoisted)
    // ---------------------------
    var netMode by rememberSaveable { mutableStateOf("DHCP") }

    var netSsid by rememberSaveable { mutableStateOf("") }
    var netPwd by rememberSaveable { mutableStateOf("") }
    var netShowPwd by rememberSaveable { mutableStateOf(false) }

    var netIp by rememberSaveable { mutableStateOf("") }
    var netGw by rememberSaveable { mutableStateOf("") }
    var netMask by rememberSaveable { mutableStateOf("") }

    var netHost by rememberSaveable { mutableStateOf("") }
    var netPort by rememberSaveable { mutableStateOf("9000") }

    var selectedTab by remember { mutableStateOf(0) }
    var showPrefixDialogFor by remember { mutableStateOf<String?>(null) }

    var manualMode by remember { mutableStateOf("TXT") }

    val leftScroll = rememberScrollState()

    fun insertToken(token: String) {

        val current = manualText
        val start = current.selection.start
        val end = current.selection.end

        val newText = current.text.replaceRange(start, end, token)

        onManualTextChanged(
            TextFieldValue(
                text = newText,
                selection = androidx.compose.ui.text.TextRange(start + token.length),
            )
        )
    }

    fun appendHex(byte: String) {
        val newValue =
            if (manualHex.isBlank()) {
                byte
            } else {
                "$manualHex $byte"
            }
        onManualHexChanged(newValue)
    }

    // TopBar eliminado + sin insets reservados
    Scaffold(topBar = {}, contentWindowInsets = WindowInsets.safeDrawing) { inner ->
        Column(modifier = Modifier.padding(inner).padding(6.dp).fillMaxSize()) {

            // =========================
            // TABS
            // =========================
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("TERMINAL") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("NETWORK") },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("OTA") },
                )
            }

            Spacer(Modifier.height(6.dp))

            // =========================
            // MONITOR TAB
            // =========================
            if (selectedTab == 0) {

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val wDp = this.maxWidth
                    val hDp = this.maxHeight

                    val scale =
                        when {
                            wDp < 700.dp -> 0.60f
                            wDp < 820.dp -> 0.86f
                            else -> 1.0f
                        }
                    val inv = 1f / scale

                    Box(
                        modifier =
                            Modifier.requiredSize(wDp * inv, hDp * inv)
                                .align(Alignment.Center)
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    transformOrigin = TransformOrigin(0.5f, 0.5f),
                                )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {

                            // LEFT scroll
                            Column(
                                modifier =
                                    Modifier.weight(0.40f)
                                        .fillMaxHeight()
                                        .verticalScroll(leftScroll),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CollapsibleSettingsSection(
                                    title = "BLUETOOTH CONNECTION",
                                    summary =
                                        if (scanning) "Scanning... ${scanRemainSec}s"
                                        else if (connected) "Connected: ${selected?.name ?: "PLC"}"
                                        else "Disconnected",
                                    expanded = connectionSectionExpanded,
                                    onToggle = {
                                        connectionSectionExpanded = !connectionSectionExpanded
                                    },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                showDevices = true
                                                onScan5s()
                                            },
                                            enabled = !scanning,
                                            contentPadding = PaddingValues(10.dp, 6.dp),
                                            colors =
                                                ButtonDefaults.buttonColors(
                                                    containerColor =
                                                        if (!scanning)
                                                            Color(0xFFFFC107) // Amarillo normal
                                                        else
                                                            Color(
                                                                0xFFFFA000
                                                            ), // Amarillo oscuro mientras
                                                    // escanea
                                                    contentColor = Color(0xFF212121),
                                                ),
                                        ) {
                                            Text(
                                                if (!scanning) "Scan 5s" else "Scanning...",
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = { showDevices = !showDevices },
                                            contentPadding = PaddingValues(10.dp, 6.dp),
                                        ) {
                                            Text(
                                                if (showDevices) "Hide Devices" else "Show Devices",
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                        Button(
                                            onClick = { onConnect() },
                                            enabled = (!connected && selected != null),
                                            contentPadding = PaddingValues(10.dp, 6.dp),
                                            colors =
                                                ButtonDefaults.buttonColors(
                                                    containerColor =
                                                        Color(0xFF2E7D32), // Verde técnico
                                                    contentColor = Color.White,
                                                ),
                                        ) {
                                            Text(
                                                "Connect",
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }

                                        Button(
                                            onClick = onDisconnect,
                                            enabled = connected,
                                            contentPadding = PaddingValues(10.dp, 6.dp),
                                            colors =
                                                ButtonDefaults.buttonColors(
                                                    containerColor =
                                                        Color(0xFFC62828), // Rojo industrial
                                                    contentColor = Color.White,
                                                ),
                                        ) {
                                            Text(
                                                "Disconnect",
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }

                                    if (selected != null) {
                                        Text(
                                            text =
                                                "${selected.name} | ${selected.address} | RSSI:${selected.rssi}",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }

                                    if (showDevices) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors =
                                                CardDefaults.cardColors(
                                                    containerColor =
                                                        MaterialTheme.colorScheme.surface
                                                ),
                                        ) {
                                            Column(
                                                Modifier.padding(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                if (scanning) {
                                                    Text(
                                                        text =
                                                            "Scanning, Please Wait... ${scanRemainSec}s",
                                                        fontSize = 12.sp,
                                                    )
                                                    Box(
                                                        modifier =
                                                            Modifier.fillMaxWidth()
                                                                .height(6.dp)
                                                                .background(
                                                                    Color(0xFFE0E0E0),
                                                                    RoundedCornerShape(3.dp),
                                                                )
                                                    ) {
                                                        Box(
                                                            modifier =
                                                                Modifier.fillMaxHeight()
                                                                    .fillMaxWidth(
                                                                        scanProgress.coerceIn(
                                                                            0f,
                                                                            1f,
                                                                        )
                                                                    )
                                                                    .background(
                                                                        Color(0xFF1976D2),
                                                                        RoundedCornerShape(3.dp),
                                                                    )
                                                        )
                                                    }
                                                    Spacer(Modifier.height(4.dp))
                                                }
                                                Text(
                                                    "Visible devices: ${devices.size}",
                                                    fontSize = 12.sp,
                                                )
                                                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                                                    itemsIndexed(devices) { _, d ->
                                                        Row(
                                                            Modifier.fillMaxWidth()
                                                                .clickable { onSelectDevice(d) }
                                                                .padding(vertical = 4.dp),
                                                            horizontalArrangement =
                                                                Arrangement.SpaceBetween,
                                                        ) {
                                                            Text(
                                                                d.name,
                                                                fontSize = 12.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                            Text(d.address, fontSize = 12.sp)
                                                            Text("RSSI:${d.rssi}", fontSize = 12.sp)
                                                        }
                                                        Divider()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                CollapsibleSettingsSection(
                                    title = "BINARY COMMANDS",
                                    summary = "Commands sent to the PLC",
                                    expanded = binarySectionExpanded,
                                    onToggle = { binarySectionExpanded = !binarySectionExpanded },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                    ) {
                                        CmdBtn("L") { onCmd('L') }
                                        CmdBtn("B") { onCmd('B') }
                                        CmdBtn("A") { onCmd('A') }
                                        CmdBtn("P") { onCmd('P') }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                    ) {
                                        CmdBtn("D") { onCmd('D') }
                                        CmdBtn("I") { onCmd('I') }
                                        CmdBtn("C") { onCmd('C') }
                                        CmdBtn("O") { onCmd('O') }
                                    }
                                }

                                ReadingResultsSection(readings)

                                CollapsibleSettingsSection(
                                    title = "PIN / ACCESS",
                                    summary = maintenanceStatus,
                                    summaryContent = {
                                        MaintenanceAccessBadge(
                                            authorised = maintenanceAuthorised,
                                            label = maintenanceStatus,
                                        )
                                    },
                                    expanded = pinSectionExpanded,
                                    onToggle = { pinSectionExpanded = !pinSectionExpanded },
                                ) {
                                    MaintenanceActionButton(
                                        text = "Enter PIN / Authorise",
                                        onClick = onMaintenanceClick,
                                        tone = MaintenanceActionTone.SUCCESS,
                                        enabled = connected,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    MaintenanceActionButton(
                                        text = "Lock access",
                                        onClick = onLockMaintenance,
                                        tone = MaintenanceActionTone.WARNING,
                                        enabled = connected,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    MaintenanceActionButton(
                                        text = "Forget saved PIN",
                                        onClick = onForgetPin,
                                        tone = MaintenanceActionTone.DANGER,
                                        enabled = connected,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                CollapsibleSettingsSection(
                                    title = "DEVICE ID",
                                    summary = equipmentId ?: "ID not queried",
                                    expanded = equipmentSectionExpanded,
                                    onToggle = {
                                        equipmentSectionExpanded = !equipmentSectionExpanded
                                    },
                                ) {
                                    Text(
                                        "ID used for the Bluetooth name and PLC data frames.",
                                        fontSize = 12.sp,
                                    )
                                    MaintenanceActionButton(
                                        text = "Configure ID",
                                        onClick = onIdentityClick,
                                        tone = MaintenanceActionTone.INFO,
                                        enabled = connected,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                DeviceStatusSection(
                                    status = deviceStatus,
                                    receivedMs = deviceStatusReceived,
                                    ready = deviceStatusReady,
                                    busy = deviceStatusBusy,
                                    message = deviceStatusMessage,
                                    onRefresh = onStatusRefresh,
                                )

                                CollapsibleSettingsSection(
                                    title = "HEIGHT MEASUREMENT",
                                    summary =
                                        if (!altKnown) "Status not queried"
                                        else if (altState == true) "Enabled" else "Disabled",
                                    expanded = heightSectionExpanded,
                                    onToggle = { heightSectionExpanded = !heightSectionExpanded },
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {

                                        // GET STATUS
                                        Button(
                                            onClick = onAltQuery,
                                            contentPadding = PaddingValues(10.dp, 6.dp),
                                            colors =
                                                ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF1976D2),
                                                    contentColor = Color.White,
                                                ),
                                        ) {
                                            Text("Get Status", fontSize = 12.sp)
                                        }

                                        if (altKnown) {

                                            val isEnabled = altState == true

                                            Button(
                                                onClick = {
                                                    if (isEnabled) onAltDisable() else onAltEnable()
                                                },
                                                contentPadding = PaddingValues(10.dp, 6.dp),
                                                colors =
                                                    ButtonDefaults.buttonColors(
                                                        containerColor =
                                                            if (isEnabled) Color(0xFFC62828)
                                                            else Color(0xFF2E7D32),
                                                        contentColor = Color.White,
                                                    ),
                                            ) {
                                                Text(
                                                    if (isEnabled) "Disable Height Measurement"
                                                    else "Enable Height Measurement",
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        }
                                    }
                                }

                                CollapsibleSettingsSection(
                                    title = "Barcode ID / RTC",
                                    summary = "Barcode prefixes and date/time",
                                    expanded = textSectionExpanded,
                                    onToggle = { textSectionExpanded = !textSectionExpanded },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {

                                        // ===== LEFT COLUMN (READ ACTIONS)
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            // Fila Pallet
                                            Button(
                                                onClick = onCfgQuery,
                                                modifier =
                                                    Modifier.width(BTN_ACTION_W).height(ROW_H),
                                                contentPadding = PaddingValues(10.dp, 6.dp),
                                                colors =
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF1976D2),
                                                        contentColor = Color.White,
                                                    ),
                                            ) {
                                                Text("Get ID", fontSize = 12.sp)
                                            }

                                            // Fila Spot (vacía)
                                            Spacer(modifier = Modifier.height(ROW_H))

                                            // Fila RTC
                                            Button(
                                                onClick = onRefreshRtc,
                                                modifier =
                                                    Modifier.width(BTN_ACTION_W).height(ROW_H),
                                                contentPadding = PaddingValues(10.dp, 6.dp),
                                                colors =
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF1976D2),
                                                        contentColor = Color.White,
                                                    ),
                                            ) {
                                                Text("Refresh", fontSize = 12.sp)
                                            }
                                        }

                                        // ===== CENTER COLUMN (FIELDS)
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {

                                            // ====== FILA 1: PALLET
                                            Row(
                                                modifier = Modifier.fillMaxWidth().height(ROW_H),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    "Pallet",
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.width(LABEL_W),
                                                    maxLines = 1,
                                                )

                                                CompactOutlinedField(
                                                    value = pfxP,
                                                    onValueChange = onPfxPChanged,
                                                    modifier = Modifier.weight(1f),
                                                )

                                                Button(
                                                    onClick = { showPrefixDialogFor = "PFXP" },
                                                    modifier = Modifier.width(70.dp).height(ROW_H),
                                                    contentPadding = PaddingValues(6.dp),
                                                    colors =
                                                        ButtonDefaults.buttonColors(
                                                            containerColor = Color(0xFF1976D2),
                                                            contentColor = Color.White,
                                                        ),
                                                ) {
                                                    Text("Select", fontSize = 12.sp)
                                                }
                                            }

                                            // ====== FILA 2: SPOT
                                            Row(
                                                modifier = Modifier.fillMaxWidth().height(ROW_H),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    "Spot",
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.width(LABEL_W),
                                                    maxLines = 1,
                                                )

                                                CompactOutlinedField(
                                                    value = pfxU,
                                                    onValueChange = onPfxUChanged,
                                                    modifier = Modifier.weight(1f),
                                                )

                                                Button(
                                                    onClick = { showPrefixDialogFor = "PFXU" },
                                                    modifier = Modifier.width(70.dp).height(ROW_H),
                                                    contentPadding = PaddingValues(6.dp),
                                                    colors =
                                                        ButtonDefaults.buttonColors(
                                                            containerColor = Color(0xFF1976D2),
                                                            contentColor = Color.White,
                                                        ),
                                                ) {
                                                    Text("Select", fontSize = 12.sp)
                                                }
                                            }

                                            // ====== FILA 2: RTC (altura fija)
                                            Row(
                                                modifier = Modifier.fillMaxWidth().height(ROW_H),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    "  RTC",
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.width(LABEL_W),
                                                    maxLines = 1,
                                                )
                                                CompactOutlinedField(
                                                    value = rtcIso,
                                                    onValueChange = onRtcIsoChanged,
                                                    modifier = Modifier.weight(3f),
                                                )
                                            }
                                        }

                                        // ===== RIGHT COLUMN (WRITE ACTIONS)
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(
                                                onClick = onSetCfg,
                                                modifier = Modifier.width(BTN_SET_W).height(ROW_H),
                                                contentPadding = PaddingValues(10.dp, 6.dp),
                                                colors =
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF2E7D32),
                                                        contentColor = Color.White,
                                                    ),
                                            ) {
                                                Text("Set", fontSize = 12.sp)
                                            }

                                            // 🔹 Placeholder para que Spot no tenga Set
                                            Spacer(modifier = Modifier.height(ROW_H))

                                            Button(
                                                onClick = onSetRtc,
                                                modifier = Modifier.width(BTN_SET_W).height(ROW_H),
                                                contentPadding = PaddingValues(10.dp, 6.dp),
                                                colors =
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF2E7D32),
                                                        contentColor = Color.White,
                                                    ),
                                            ) {
                                                Text("Set", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }

                                CollapsibleSettingsSection(
                                    title = "MANUAL TERMINAL",
                                    summary = "Send TXT / HEX commands",
                                    expanded = manualSectionExpanded,
                                    onToggle = { manualSectionExpanded = !manualSectionExpanded },
                                ) {

                                    // --- MODE SELECTOR ---
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        ModeBtn(label = "TXT", selected = manualMode == "TXT") {
                                            manualMode = "TXT"
                                        }

                                        Spacer(Modifier.width(10.dp))

                                        ModeBtn(label = "HEX", selected = manualMode == "HEX") {
                                            manualMode = "HEX"
                                        }
                                    }

                                    // --- INPUT FIELD ---
                                    if (manualMode == "TXT") {

                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(
                                                        6.dp,
                                                        Alignment.CenterHorizontally,
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                SmallTokenBtn("<ESC>") { insertToken("<ESC>") }
                                                SmallTokenBtn("<CR>") { insertToken("<CR>") }
                                                SmallTokenBtn("<LF>") { insertToken("<LF>") }
                                                SmallTokenBtn("<STX>") { insertToken("<STX>") }
                                                SmallTokenBtn("<ETX>") { insertToken("<ETX>") }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(
                                                        6.dp,
                                                        Alignment.CenterHorizontally,
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                SmallTokenBtn("<ACK>") { insertToken("<ACK>") }
                                                SmallTokenBtn("<NAK>") { insertToken("<NAK>") }
                                            }
                                        }

                                        CompactOutlinedFieldTFV(
                                            value = manualText,
                                            onValueChange = onManualTextChanged,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(
                                                        6.dp,
                                                        Alignment.CenterHorizontally,
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                SmallHexBtn("1B - ESC") { appendHex("1B") }
                                                SmallHexBtn("0D - CR") { appendHex("0D") }
                                                SmallHexBtn("0A - LF") { appendHex("0A") }
                                                SmallHexBtn("02 - STX") { appendHex("02") }
                                                SmallHexBtn("03 - ETX") { appendHex("03") }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(
                                                        6.dp,
                                                        Alignment.CenterHorizontally,
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                SmallHexBtn("06 - ACK") { appendHex("06") }
                                                SmallHexBtn("15 - NAK") { appendHex("15") }
                                            }
                                        }

                                        CompactOutlinedField(
                                            value = manualHex,
                                            onValueChange = onManualHexChanged,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }

                                    // --- SEND BUTTON ---
                                    Button(
                                        onClick = {
                                            if (manualMode == "TXT") {
                                                onSendText(manualText.text)
                                            } else {
                                                onSendHex()
                                            }
                                        },
                                        modifier =
                                            Modifier.align(Alignment.CenterHorizontally)
                                                .width(160.dp),
                                        contentPadding = PaddingValues(10.dp, 8.dp),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF2E7D32),
                                                contentColor = Color.White,
                                            ),
                                    ) {
                                        Text("SEND", fontSize = 12.sp)
                                    }
                                }

                                Spacer(Modifier.height(60.dp))
                            }

                            // RIGHT fixed log
                            Column(modifier = Modifier.weight(0.60f).fillMaxHeight()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Card(Modifier.weight(1f)) {
                                        Text(
                                            text = "Status: $statusLine",
                                            modifier = Modifier.padding(6.dp),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = LogColors.neutralText,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = onClearLog,
                                        contentPadding = PaddingValues(10.dp, 6.dp),
                                        modifier = Modifier.widthIn(min = 90.dp),
                                    ) {
                                        Text(
                                            "Clear Log",
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Card(Modifier.fillMaxSize()) {
                                    LazyColumn(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                                        itemsIndexed(logs) { _, entry ->
                                            Spacer(Modifier.height(6.dp)) // espacio entre bloques

                                            when (entry) {
                                                is LogEntry.Simple -> {

                                                    val t = timeFmt(entry.t)

                                                    val color =
                                                        when (entry.lvl) {
                                                            LogLevel.WARN -> LogColors.warnAmber
                                                            LogLevel.ERR -> LogColors.errRed
                                                            else -> LogColors.neutralText
                                                        }

                                                    Text(
                                                        text =
                                                            "$t  ${entry.lvl.name}  ${entry.msg}",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        color = color,
                                                    )
                                                }

                                                is LogEntry.Traffic -> {

                                                    val isTx = entry.direction == LogLevel.TX
                                                    val arrow = if (isTx) "↑ TX" else "↓ RX"
                                                    val t = timeFmt(entry.t)

                                                    val bgColor =
                                                        if (isTx)
                                                            LogColors.txBlue.copy(alpha = 0.06f)
                                                        else LogColors.rxGreen.copy(alpha = 0.06f)

                                                    Card(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors =
                                                            CardDefaults.cardColors(
                                                                containerColor = bgColor
                                                            ),
                                                    ) {
                                                        Column(
                                                            modifier =
                                                                Modifier.padding(
                                                                    horizontal = 6.dp,
                                                                    vertical = 1.dp,
                                                                )
                                                        ) {

                                                            // Header
                                                            Text(
                                                                text =
                                                                    "$t  $arrow  (${entry.size}B)",
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 12.sp,
                                                                color =
                                                                    if (isTx) LogColors.txBlue
                                                                    else LogColors.rxGreen,
                                                            )

                                                            Spacer(Modifier.height(1.dp))

                                                            // ASCII
                                                            Text(
                                                                text =
                                                                    buildAnnotatedString {
                                                                        withStyle(
                                                                            SpanStyle(
                                                                                color =
                                                                                    LogColors
                                                                                        .rxLabelRed
                                                                            )
                                                                        ) {
                                                                            append("ASCII: ")
                                                                        }
                                                                        withStyle(
                                                                            SpanStyle(
                                                                                color =
                                                                                    LogColors
                                                                                        .asciiPurple
                                                                            )
                                                                        ) {
                                                                            append(entry.ascii)
                                                                        }
                                                                    },
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 12.sp,
                                                            )

                                                            // HEX
                                                            Text(
                                                                text =
                                                                    buildAnnotatedString {
                                                                        withStyle(
                                                                            SpanStyle(
                                                                                color =
                                                                                    LogColors
                                                                                        .rxLabelRed
                                                                            )
                                                                        ) {
                                                                            append("HEX:   ")
                                                                        }
                                                                        withStyle(
                                                                            SpanStyle(
                                                                                color =
                                                                                    LogColors
                                                                                        .rxHexOrange
                                                                            )
                                                                        ) {
                                                                            append(entry.hex)
                                                                        }
                                                                    },
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 12.sp,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (selectedTab == 1) {

                key("NETWORK") {
                    NetworkScreen(
                        networkSnapshot = networkSnapshot,
                        lastRxLine = lastRxLine,
                        onSendText = onSendText,
                        mode = netMode,
                        onModeChange = { netMode = it },
                        ssid = netSsid,
                        onSsidChange = { netSsid = it },
                        pwd = netPwd,
                        onPwdChange = { netPwd = it },
                        showPwd = netShowPwd,
                        onShowPwdChange = { netShowPwd = it },
                        ip = netIp,
                        onIpChange = { netIp = it },
                        gw = netGw,
                        onGwChange = { netGw = it },
                        mask = netMask,
                        onMaskChange = { netMask = it },
                        host = netHost,
                        onHostChange = { netHost = it },
                        port = netPort,
                        onPortChange = { netPort = it },
                    )
                }
            } else {
                OtaScreen(
                    onSendOta = onSendOta,
                    otaApSsid = otaApSsid,
                    otaPct = otaPct,
                    otaStatus = otaStatus,
                    otaErr = otaErr,
                    otaDone = otaDone,
                    bleLinkState = bleLinkState,
                    onResetOtaUi = onResetOtaUi,
                )
            }
            if (showPrefixDialogFor != null) {
                AimPrefixDialog(
                    onDismiss = { showPrefixDialogFor = null },
                    onPrefixSelected = { selectedPrefix ->
                        when (showPrefixDialogFor) {
                            "PFXP" -> onPfxPChanged(selectedPrefix)
                            "PFXU" -> onPfxUChanged(selectedPrefix)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CmdBtn(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.widthIn(min = 78.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE65100), // Naranja
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFF57C00).copy(alpha = 0.4f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
    ) {
        Text("CMD $label", fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SmallTokenBtn(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6A1B9A)),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun SmallHexBtn(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF6C00)),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun ModeBtn(label: String, selected: Boolean, onClick: () -> Unit) {
    val disabledContainer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    val disabledText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        elevation =
            ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
            ),
        colors =
            ButtonDefaults.buttonColors(
                containerColor =
                    if (selected) Color(0xFF1976D2)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                contentColor =
                    if (selected) Color.White
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
    ) {
        Text(text = label, fontSize = 13.sp, letterSpacing = 1.sp)
    }
}

/**
 * Campo compacto con:
 * - alto fijo 40dp
 * - padding interno pequeño
 */
@Composable
private fun CompactOutlinedFieldTFV(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val outline = MaterialTheme.colorScheme.outline
    val textColor = LogColors.neutralText
    val cursor = MaterialTheme.colorScheme.primary

    Box(
        modifier =
            modifier
                .then(if (width != null) Modifier.width(width) else Modifier)
                .height(40.dp)
                .border(1.dp, outline, shape)
                .padding(horizontal = 8.dp, vertical = 0.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = textColor),
            cursorBrush = SolidColor(cursor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompactOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val outline = MaterialTheme.colorScheme.outline
    val textColor = LogColors.neutralText
    val cursor = MaterialTheme.colorScheme.primary

    Box(
        modifier =
            modifier
                .then(if (width != null) Modifier.width(width) else Modifier)
                .height(40.dp)
                .border(1.dp, outline, shape)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = textColor),
            cursorBrush = SolidColor(cursor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
