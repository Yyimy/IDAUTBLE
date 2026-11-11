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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.idautble.ui.theme.IDAUTBLETheme
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

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

private class LogColors(dark: Boolean, val neutralText: Color) {
    val txBlue = if (dark) Color(0xFF90CAF9) else Color(0xFF1E88E5)
    val rxGreen = if (dark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    val asciiPurple = if (dark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)
    val rxLabelRed = if (dark) Color(0xFFFFABAB) else Color(0xFFD32F2F)
    val rxHexOrange = if (dark) Color(0xFFFFCC80) else Color(0xFFF57C00)
    val warnAmber = if (dark) Color(0xFFFFD166) else Color(0xFFF9A825)
    val errRed = rxLabelRed
}

data class BleDevice(val name: String, val address: String, val rssi: Int?)

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

    private var otaReconnectAttempts = 0
    private val maxOtaReconnectAttempts = 15 // ~30 segundos si cada 2s
    private var otaReconnectActive = false
    private var otaRebootExpected = false
    private var otaReconnectDeadline = 0L

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
                        otaSession.confirmationTimedOut()
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

        initBluetooth()
        val tempScanner = scanner ?: return

        log(LogLevel.INFO, "OTA scan before reconnect...")

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
    private val recentPlcPreferences by lazy { getSharedPreferences("recent_plc", MODE_PRIVATE) }
    private var recentPlc: BleDevice? = null

    private fun publishScanDevices() {
        val scanned = foundMap.values.sortedByDescending { it.rssi }
        val saved = recentPlc
        uiDevices.value = if (saved != null && saved.address !in foundMap) scanned + saved else scanned
    }

    private fun rememberPlc(device: BleDevice) {
        val saved = device.copy(rssi = null)
        if (saved == recentPlc) return
        recentPlc = saved
        recentPlcPreferences.edit().putString("address", saved.address).putString("name", saved.name).apply()
        publishScanDevices()
    }

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
    private var countersRequest = 0L
    private val uiCounters = mutableStateOf(CountersSnapshot())
    private val statusRefresh = StatusRefreshPolicy()
    private val uiRefreshAfterReads = mutableStateOf(true)
    private val uiAutoRefreshEnabled = mutableStateOf(true)
    private val statusPreferences by lazy { getSharedPreferences("device_status", MODE_PRIVATE) }

    private fun setRefreshAfterReads(enabled: Boolean) {
        uiRefreshAfterReads.value = enabled
        statusRefresh.refreshAfterReads = enabled
        statusPreferences.edit().putBoolean("refresh_after_reads", enabled).apply()
    }

    private fun setAutoRefresh(enabled: Boolean) {
        uiAutoRefreshEnabled.value = enabled
        statusRefresh.autoRefreshEnabled = enabled
        statusPreferences.edit { putBoolean("auto_refresh", enabled) }
    }

    private var appInForeground = false
    private var maintenanceLeaseSupported = false
    private var maintenanceReleaseSupported = false
    private var maintenanceReleasePending = false
    private var maintenanceReleaseRequest = 0L
    private var nextMaintenancePing = 0L
    private val statusRefreshTick =
        object : Runnable {
            override fun run() {
                if (!appInForeground || maintenanceReleasePending) {
                    if (appInForeground) mainHandler.postDelayed(this, 1000)
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (maintenanceLeaseSupported && bleChannelReady && now >= nextMaintenancePing) {
                    nextMaintenancePing = now + 15000L
                    enqueueWrite(PendingWrite("MAINTPING\r\n".toByteArray(Charsets.US_ASCII), "MAINTPING"))
                }
                networkSession.tick()
                requestDeviceStatus(automatic = true)
                mainHandler.postDelayed(this, 1000)
            }
        }

    private val uiIdentityDialog = mutableStateOf(false)
    private val uiIdentity = mutableStateOf<EquipmentIdentity?>(null)
    private val uiEquipmentId = mutableStateOf<String?>(null)
    private val uiReadings = mutableStateOf(PlcReadingsState())
    private val uiHidReceiver = mutableStateOf(HidReceiverState())
    private var hidReceiverRequest = 0L
    private val uiHidOutput = mutableStateOf(HidOutputState())
    private var hidOutputRequest = 0L
    private var hidOutputExpected: HidOutputConfig? = null
    private val uiIdentityDraft = mutableStateOf("")
    private val uiIdentityBusy = mutableStateOf(false)
    private val uiIdentityStatus = mutableStateOf("")
    private var identityExpected: String? = null
    private var identityRequest = 0L

    private data class PendingWrite(
        val data: ByteArray,
        val tag: String,
        val authAttempt: Long = 0L,
        val networkId: Long? = null,
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
    private val networkSession by lazy {
        NetworkSession({ SystemClock.elapsedRealtime() }) { command, id ->
            // Network fields are literal UTF-8, not terminal escape/token notation.
            sendBytes((command + "\r\n").toByteArray(Charsets.UTF_8), "NETWORK", id)
        }
    }
    private val uiAltState = mutableStateOf<Boolean?>(null)
    private val uiAltKnown = mutableStateOf(false)
    private val uiScanning = mutableStateOf(false)
    // Progreso Scan 5s
    private val uiScanProgress = mutableStateOf(0f) // 0..1
    private val uiScanRemainSec = mutableStateOf(0) // 5..0

    // ============================
    // OTA UI State
    // ============================
    private val otaSession by lazy {
        val appContext = applicationContext
        OtaSession { file ->
            HttpFileServer(port = 8080, fileSize = file.size) {
                appContext.contentResolver.openInputStream(Uri.parse(file.uri))
            }
        }
    }

    private fun selectOtaFirmware(uri: Uri) {
        val ticket = otaSession.beginSelection(uri.lastPathSegment ?: "Firmware") ?: return
        lifecycleScope.launch {
            otaSession.finishSelection(ticket, loadOtaFirmware(applicationContext, uri))
        }
    }

    private fun abortOta() {
        sendText("OTA!", addCrlf = false)
        otaSession.aborted()
        resetOtaUi()
    }

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
    private val uiManualLines = mutableStateListOf(ManualLine(), ManualLine(), ManualLine())
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
    private fun enabledBluetoothAdapter(): BluetoothAdapter? {
        if (!hasConnectPermission()) return null
        return try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                log(LogLevel.WARN, "Enable Bluetooth in Android settings")
                null
            } else adapter
        } catch (_: SecurityException) {
            log(LogLevel.WARN, "Bluetooth permission is pending")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth() {
        scanner = null
        if (!hasScanPermission()) return
        val adapter = enabledBluetoothAdapter() ?: return
        try {
            scanner = adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
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

                val name =
                    result.scanRecord?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
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
                            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: recentPlc?.takeIf { it.address == addr }?.let { foundMap[addr]?.name ?: it.name }
                        ?: return

                val rssi = result.rssi
                val device = BleDevice(name, addr, rssi)
                foundMap[addr] = device
                if (uiSelected.value?.let { it.address == addr && it.rssi == null } == true) {
                    uiSelected.value = device
                }
                publishScanDevices()
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
        if (!ensureConnectPermissionOrRequest()) return
        initBluetooth()
        if (scanner == null) return
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
        uiSelected.value = uiSelected.value?.copy(rssi = null)
        publishScanDevices()

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
        log(LogLevel.INFO, "Found: ${foundMap.size}")
    }

    // ============================
    // CONNECT / DISCONNECT
    // ============================
    @SuppressLint("MissingPermission")
    private fun connectSelected() {
        if (!ensureConnectPermissionOrRequest()) return
        val adapter = enabledBluetoothAdapter() ?: return

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

        val remote =
            try {
                adapter.getRemoteDevice(d.address)
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
        val adapter = enabledBluetoothAdapter() ?: return

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

        val remote =
            try {
                adapter.getRemoteDevice(addr)
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

    private fun requestMaintenanceDisconnect() {
        if (maintenanceReleasePending) return
        if (uiOtaActive.value || statusRefresh.otaActive) {
            log(LogLevel.WARN, "Finish or cancel the OTA update before disconnecting")
            return
        }
        val connection = gatt
        if (connection == null || !bleChannelReady || !maintenanceReleaseSupported) {
            disconnectGatt()
            uiSelected.value = null
            return
        }

        maintenanceReleasePending = true
        val request = ++maintenanceReleaseRequest
        writeQueue.clear()
        log(LogLevel.INFO, "Releasing maintenance connection…")
        if (!enqueueWrite(PendingWrite("MAINTBYE\r\n".toByteArray(Charsets.US_ASCII), "MAINTBYE"))) {
            maintenanceReleasePending = false
            return
        }
        mainHandler.postDelayed({
            if (gatt === connection && maintenanceReleasePending && request == maintenanceReleaseRequest) {
                log(LogLevel.WARN, "PLC release timed out. Closing the app connection.")
                disconnectGatt()
                uiSelected.value = null
            }
        }, 3000L)
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
        val bytes = expandTokens(text)
        val endsInCrlf = bytes.size >= 2 && bytes[bytes.lastIndex - 1] == CR && bytes.last() == LF
        val payload = if (addCrlf && !endsInCrlf) bytes + byteArrayOf(CR, LF) else bytes
        sendBytes(payload, "TXT")
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

    private fun requestHidReceiverAction(command: String) {
        if (!bleChannelReady || !uiHidReceiver.value.supported || uiHidReceiver.value.busy) return
        if (command != "HIDRECEIVER?" && !maintenanceSession.isAuthorized()) {
            showIdentityAuthorizationMessage()
            return
        }
        if (command !in setOf("HIDRECEIVER?", "HIDPAIR=1", "HIDPAIR=ANDROID", "HIDPAIR=0", "HIDFORGET=1", "HIDTYPE=TEST") &&
            !Regex("HIDPIN=[0-9]{6}").matches(command)) return
        val request = ++hidReceiverRequest
        uiHidReceiver.value = uiHidReceiver.value.copy(busy = true, message = "Waiting for the PLC...")
        val wireCommand = if (command == "HIDRECEIVER?" && uiHidReceiver.value.androidPairingSupported)
            "HIDRECEIVER2?" else command
        sendText(wireCommand, true)
        mainHandler.postDelayed({
            if (request == hidReceiverRequest && uiHidReceiver.value.busy) {
                uiHidReceiver.value = uiHidReceiver.value.copy(busy = false,
                    message = "No confirmation. Refresh receiver status before retrying.")
            }
        }, 8000)
    }

    private fun requestHidOutput(config: HidOutputConfig?) {
        if (!bleChannelReady || !uiHidOutput.value.supported || uiHidOutput.value.busy) return
        if (config != null && !maintenanceSession.isAuthorized()) { showIdentityAuthorizationMessage(); return }
        val prefixKeys = uiHidOutput.value.prefixKeys
        val readHotkey = uiHidOutput.value.readHotkey
        val endingSequence = uiHidOutput.value.endingSequence
        val command = if (config == null) {
            when {
                endingSequence -> "HIDCFG4?"
                readHotkey -> "HIDCFG3?"
                prefixKeys -> "HIDCFG2?"
                else -> "HIDCFG?"
            }
        } else runCatching { config.wire(includePrefix = prefixKeys, includeHotkey = readHotkey, includeEndingSequence = endingSequence) }.getOrNull() ?: return
        hidOutputExpected = config
        val request = ++hidOutputRequest
        uiHidOutput.value = uiHidOutput.value.copy(busy = true, message = "Waiting for the PLC...")
        sendText(command, true)
        mainHandler.postDelayed({
            if (request == hidOutputRequest && uiHidOutput.value.busy) {
                hidOutputExpected = null
                uiHidOutput.value = uiHidOutput.value.copy(busy = false, message = "No confirmation. Refresh to check the saved format.")
            }
        }, 8000)
    }

    private fun handleHidOutputMessage(message: PlcMessage) {
        val text = message.text
        if (message.acknowledged && text.startsWith("FW=")) {
            val supported = text.split(';').count { it == "HIDFORMAT=1" } == 1
            uiHidOutput.value = HidOutputState(
                supported = supported,
                arrowSeparators = "HIDARROWS=1" in text.split(';'),
                extendedKeys = "HIDKEYS=1" in text.split(';'),
                prefixKeys = "HIDPREFIX=1" in text.split(';'),
                readHotkey = "HIDHOTKEY=1" in text.split(';'),
                endingSequence = "HIDENDSEQ=1" in text.split(';'),
            )
            hidOutputRequest++; hidOutputExpected = null
            if (supported) requestHidOutput(null)
        } else if (message.acknowledged && text.startsWith("HIDCFG=")) {
            if (!uiHidOutput.value.supported) return
            val config = parseHidOutputConfig(text)
            val expected = hidOutputExpected
            hidOutputRequest++; hidOutputExpected = null
            uiHidOutput.value = uiHidOutput.value.copy(config = config, busy = false, revision = uiHidOutput.value.revision + 1, message = when {
                config == null -> "Invalid format response. Refresh before saving."
                expected != null && config == expected -> "Format saved on the PLC."
                expected != null -> "Saved format differs from the request. Check the values."
                else -> "Saved format loaded from PLC."
            })
        } else if (message.rejected && uiHidOutput.value.busy &&
            (text.startsWith("ERR=HIDCFG") || text.startsWith("ERR=AUTH") || text == "BUSY")) {
            hidOutputRequest++; hidOutputExpected = null
            uiHidOutput.value = uiHidOutput.value.copy(busy = false, message = when (text) {
                "ERR=HIDCFG_STORAGE" -> "PLC storage failed. The active format was not changed."
                "ERR=HIDCFG_BUSY", "BUSY" -> "Wait for the keyboard test to finish."
                "ERR=HIDCFG_INVALID" -> "PLC rejected the format. Check the key sequences."
                else -> "Enter the PIN in PIN / ACCESS before saving."
            })
        }
    }

    private fun handleHidReceiverMessage(message: PlcMessage) {
        val text = message.text
        if (message.acknowledged && text.startsWith("FW=")) {
            val supported = supportsPersistentHidReceiver(text)
            uiHidReceiver.value = HidReceiverState(
                supported = supported,
                production = "HIDRECEIVER=1" in text.split(';'),
                androidPairingSupported = supportsAndroidHidPairing(text),
            )
            if (supported) requestHidReceiverAction("HIDRECEIVER?")
            return
        }
        if (!uiHidReceiver.value.supported) return
        if (message.acknowledged && text.startsWith("HIDRECEIVER=")) {
            val info = parseHidReceiver(text)
            hidReceiverRequest++
            uiHidReceiver.value = uiHidReceiver.value.copy(info = info, busy = false,
                message = if (info == null) "Invalid receiver response. Refresh before making changes." else if (uiHidReceiver.value.message == "Waiting for the PLC...") "" else uiHidReceiver.value.message)
        } else if (message.acknowledged && text.startsWith("HIDTEST=")) {
            val summary = when {
                text == "HIDTEST=BOUND" -> "Receiver saved and ready."
                text == "HIDTEST=FORGOTTEN" -> "Receiver assignment forgotten."
                text == "HIDTEST=PAIR_WINDOW_60S" -> "Assignment open for 60 seconds. Connect the intended receiver."
                text == "HIDTEST=PIN_REQUIRED" -> "Android is requesting its pairing code. Enter the six digits shown on the new terminal."
                text == "HIDTEST=PIN_SUBMITTED" -> "Pairing code submitted. Waiting for secure HID connection."
                text == "HIDTEST=PAIR_CLOSED" -> "Assignment cancelled."
                text == "HIDTEST=PAIR_TIMEOUT" -> "No bonded HID receiver found within 60 seconds."
                text == "HIDTEST=QUEUED" -> "Sending test text..."
                text.startsWith("HIDTEST=SUBMITTED") -> "Test submitted. Check the text on the receiver."
                text.startsWith("HIDTEST=CANCELLED") -> "Test cancelled; it will not be replayed."
                else -> return
            }
            hidReceiverRequest++
            uiHidReceiver.value = uiHidReceiver.value.copy(busy = text == "HIDTEST=QUEUED", message = summary)
            if (text == "HIDTEST=QUEUED") {
                val request = hidReceiverRequest
                mainHandler.postDelayed({
                    if (request == hidReceiverRequest && uiHidReceiver.value.busy)
                        uiHidReceiver.value = uiHidReceiver.value.copy(busy = false, message = "Test result not confirmed. Check the receiver before retrying.")
                }, 8000)
            }
        } else if (message.rejected && text.startsWith("ERR=HID") && !text.startsWith("ERR=HIDCFG")) {
            hidReceiverRequest++
            uiHidReceiver.value = uiHidReceiver.value.copy(busy = false, message = when (text) {
                "ERR=HID_ALREADY_BOUND" -> "Forget the current receiver assignment before replacing it."
                "ERR=HID_STORAGE" -> "The PLC could not save the assignment. Refresh before retrying."
                "ERR=HID_AMBIGUOUS" -> "More than one receiver is available. Connect only the intended receiver."
                "ERR=HID_PIN_INVALID" -> "Enter exactly six digits from the new Android terminal."
                "ERR=HID_PIN_NOT_REQUESTED" -> "No current Android pairing code request. Retry pairing within the 60-second window."
                "ERR=HID_PIN_REPLY_FAILED" -> "The Bluetooth stack rejected the pairing response. Retry from the receiver."
                "ERR=HID_SECURITY_CONFIG" -> "The PLC could not enable Android pairing mode. Windows pairing remains unchanged."
                "ERR=HID_PAIR_ALREADY_OPEN" -> "Cancel the current assignment before opening another."
                "ERR=HID_NOT_READY_OR_BUSY" -> "The receiver is not ready or a test is already running."
                else -> text
            })
        }
    }

    private fun resetSecureTransport() {
        maintenanceLeaseSupported = false
        maintenanceReleaseSupported = false
        maintenanceReleasePending = false
        nextMaintenancePing = 0L
        hidOutputRequest++; hidOutputExpected = null
        uiHidOutput.value = HidOutputState()
        hidReceiverRequest++
        uiHidReceiver.value = HidReceiverState()
        txTimeout?.let { mainHandler.removeCallbacks(it) }
        txTimeout = null
        maintenanceSession.reset()
        connectionHandshake.reset()
        deviceStatusRequest++
        countersRequest++
        uiCounters.value = CountersSnapshot()
        statusRefresh.reset()
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
        networkSession.reset()
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
        networkSession.authorisationCancelled(
            "Maintenance access locked. Pending change cancelled."
        )
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
        networkSession.authorisationCancelled(reason)
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
    private fun sendBytes(data: ByteArray, tag: String, networkId: Long? = null) {
        if (maintenanceReleasePending) {
            networkId?.let { networkSession.sendFailed(it, "Bluetooth is disconnecting.") }
            return
        }
        if (!ensureConnectPermissionOrRequest()) {
            networkId?.let { networkSession.sendFailed(it, "Bluetooth permission is required.") }
            return
        }
        if (!deviceConnected || gatt == null || txChar == null) {
            log(LogLevel.WARN, "Not connected")
            networkId?.let { networkSession.sendFailed(it, "Bluetooth is disconnected.") }
            return
        }
        val ready = data.contentEquals(byteArrayOf(ESC, 'C'.code.toByte(), CR, LF))
        if (!bleChannelReady && !ready) {
            log(LogLevel.WARN, "Wait for pairing and the BLE channel to be ready")
            networkId?.let { networkSession.sendFailed(it, "Wait for Bluetooth to be ready.") }
            return
        }
        if (data.isEmpty() || data.size >= 384) {
            log(LogLevel.WARN, "The firmware accepts commands of up to 383 bytes")
            networkId?.let {
                networkSession.sendFailed(it, "Network command exceeds the PLC limit.")
            }
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
        val packet = PendingWrite(data.copyOf(), tag, networkId = networkId)
        if (requiresMaintenanceAuthorization(data) && !maintenanceSession.isAuthorized()) {
            pendingProtected.removeAll {
                it.networkId?.let { id -> !networkSession.isCurrent(id) } == true
            }
            if (pendingProtected.size >= 8) {
                log(LogLevel.WARN, "Too many changes are waiting for authorisation")
                networkId?.let {
                    networkSession.sendFailed(it, "Too many changes are waiting for authorisation.")
                }
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
        if (packet.networkId?.let { !networkSession.isCurrent(it) } == true) return false
        if (writeQueue.size >= 24) {
            log(LogLevel.ERR, "Send queue is full: command not sent")
            packet.networkId?.let {
                networkSession.sendFailed(it, "Send queue is full. Network command was not sent.")
            }
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
        while (writeQueue.firstOrNull()?.networkId?.let { !networkSession.isCurrent(it) } == true) {
            writeQueue.removeFirst()
        }
        if (writeQueue.isEmpty()) return
        val packet = writeQueue.removeFirst()
        activeWrite = packet
        activeReliableWrite = packet.data.size > negotiatedMtu - 3
        if (packet.tag != "MAINTPING") {
            // The Android HID one-time SMP code must not leak into the
            // maintenance traffic history (neither ASCII nor hexadecimal).
            val hidPairCode = packet.data.size >= 7 &&
                String(packet.data, 0, 7, Charsets.US_ASCII) == "HIDPIN="
            val secret = hasProtocolSecret(packet.data) || hidPairCode
            logTraffic(
                LogLevel.TX,
                if (hidPairCode) "HIDPIN=[redacted]" else redactProtocolText(asciiPretty(packet.data)),
                if (secret) "[sensitive content hidden]" else bytesToHex(packet.data),
                packet.data.size,
            )
        }
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
            packet.networkId?.let { networkSession.commandSent(it) }
            if (isReadingCommand(packet.data)) {
                statusRefresh.readSent(SystemClock.elapsedRealtime())
            }
            val text = packet.data.toString(Charsets.UTF_8).trim()
            if (text == "OTA=START" || text.startsWith("OTA=URL=")) {
                if (text == "OTA=START") {
                    otaSession.startSent(lastConnectedAddress, uiDeviceStatus.value?.firmware)
                    otaReconnectDeadline = 0L
                }
                statusRefresh.otaStarted()
                networkSession.setPaused(true)
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
        networkSession.transportError(reason)
        statusRefresh.cancelReading()
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

    private fun requestDeviceStatus(automatic: Boolean = false) {
        if (
            !appInForeground ||
                !bleChannelReady ||
                !deviceConnected ||
                !hasConnectPermission() ||
                statusRefresh.otaActive ||
                uiDeviceStatusBusy.value ||
                uiCounters.value.busy
        )
            return
        val now = SystemClock.elapsedRealtime()
        if (
            automatic &&
                (activeWrite != null ||
                    writeQueue.isNotEmpty() ||
                    !statusRefresh.shouldRefresh(now))
        )
            return
        // A manual refresh also waits for an outstanding reading to finish.
        if (statusRefresh.readPending(now)) return
        statusRefresh.requested(now)
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

    private fun requestDeviceCounters() {
        if (
            !appInForeground || !bleChannelReady || statusRefresh.otaActive || uiCounters.value.busy
        )
            return
        val request = ++countersRequest
        uiCounters.value = uiCounters.value.querying()
        sendText("COUNTERS?", true)
        mainHandler.postDelayed(
            {
                if (request == countersRequest && uiCounters.value.busy)
                    uiCounters.value = uiCounters.value.timedOut()
            },
            6000,
        )
    }

    private fun handleProtocolControl(message: PlcMessage) {
        val text = message.text
        if (message.acknowledged && text.startsWith("FW=")) {
            val capabilities = text.split(';')
            maintenanceLeaseSupported = "MAINTLEASE=1" in capabilities
            maintenanceReleaseSupported = "MAINTRELEASE=1" in capabilities
        }
        if (maintenanceReleasePending && message.rejected && text.startsWith("ERR=MAINT_RELEASE")) {
            maintenanceReleasePending = false
            log(LogLevel.WARN, if (text == "ERR=MAINT_RELEASE_OTA") "Finish or cancel the OTA update before disconnecting"
                else "The PLC could not release the connection. Try Disconnect again.")
            return
        }
        handleHidReceiverMessage(message)
        handleHidOutputMessage(message)
        statusRefresh.otaMessage(text)
        networkSession.setPaused(statusRefresh.otaActive)
        if (message.acknowledged && text.startsWith("COUNTERS=")) {
            countersRequest++
            uiCounters.value = uiCounters.value.receive(text, SystemClock.elapsedRealtime())
            return
        }
        if (message.acknowledged && text.startsWith("STATUS=")) {
            val status = parseDeviceStatus(text)
            uiDeviceStatusBusy.value = false
            deviceStatusRequest++
            if (status != null) {
                if (otaSession.reportedVersion(lastConnectedAddress, status.firmware)) {
                    log(LogLevel.INFO, otaSession.confirmationText ?: "PLC firmware confirmed")
                    resetOtaUi()
                }
                uiDeviceStatus.value = status
                uiEquipmentId.value = status.id
                if (uiIdentity.value?.id != status.id) uiIdentity.value = null
                uiDeviceStatusReceived.value = SystemClock.elapsedRealtime()
                statusRefresh.received(SystemClock.elapsedRealtime())
                uiDeviceStatusMessage.value = ""
                if (status.countersSupported) requestDeviceCounters()
                else {
                    countersRequest++
                    uiCounters.value =
                        CountersSnapshot(
                            message =
                                "Counters require firmware with COUNTERS? support (3.0.11 or later)."
                        )
                }
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
                recentPlc?.takeIf { it.address == address }?.let {
                    rememberPlc(it.copy(name = identity.bluetoothName))
                }
                publishScanDevices()
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
            uiSelected.value?.takeIf { it.address == lastConnectedAddress }?.let(::rememberPlc)
            bleChannelReady = true
            uiDeviceStatusReady.value = true
            networkSession.setReady(true)
            log(LogLevel.INFO, "BLE ready. Queries are available.")
            if (otaSession.ready(lastConnectedAddress)) {
                val attempt = otaSession.attempt
                mainHandler.postDelayed({ otaSession.confirmationTimedOut(attempt) }, 10000)
            }
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
                    otaSession.failed("PLC is busy. Unload the forks and wait for pending scans.")
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
                val now = SystemClock.elapsedRealtime()
                uiReadings.value = uiReadings.value.receive(message, now)
                // Sensor events and manual reads both update status/counters when enabled.
                if (!uiReadings.value.invalidFrame && statusRefresh.readReceived()) {
                    mainHandler.postDelayed({ requestDeviceStatus(automatic = true) }, 150)
                }
                continue
            }
            val first = message.bytes.firstOrNull()
            val payloadText = message.text
            networkSession.receive(message)
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
                    otaSession.failed(uiOtaErr.value!!)
                    uiOtaStatus.value = "ERROR: ${uiOtaErr.value}"
                } else {
                    val body = payloadText.substringAfter("OTA=").trim()
                    parseOtaApSsid(body)?.let { uiOtaApSsid.value = it }
                    uiOtaStatus.value = body

                    // Progreso
                    if (body.startsWith("PCT=", ignoreCase = true)) {
                        val pctVal = body.substringAfter("PCT=").trim().toIntOrNull()
                        if (pctVal != null) uiOtaPct.value = pctVal.coerceIn(0, 100)
                    }

                    // Done
                    if (body == "DONE REBOOT") {
                        uiOtaDone.value = true
                        uiOtaPct.value = 100
                        otaRebootExpected = true
                        otaSession.doneReceived()
                        val attempt = otaSession.attempt
                        mainHandler.postDelayed({ otaSession.confirmationTimedOut(attempt) }, 45000)
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
                        if (maintenanceReleasePending) uiSelected.value = null
                        val shouldReconnect = otaRebootExpected
                        otaSession.disconnected(lastConnectedAddress)
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
        val refreshAfterReads = statusPreferences.getBoolean("refresh_after_reads", true)
        uiRefreshAfterReads.value = refreshAfterReads
        statusRefresh.refreshAfterReads = refreshAfterReads
        val autoRefresh = statusPreferences.getBoolean("auto_refresh", true)
        uiAutoRefreshEnabled.value = autoRefresh
        statusRefresh.autoRefreshEnabled = autoRefresh
        ContextCompat.registerReceiver(
            this,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        recentPlcPreferences.getString("address", null)?.takeIf(BluetoothAdapter::checkBluetoothAddress)?.let { address ->
            val name = recentPlcPreferences.getString("name", null) ?: "PLC"
            recentPlc = BleDevice(name, address, null)
            uiSelected.value = recentPlc
            publishScanDevices()
        }
        requestBlePermissions()
        initBluetooth()

        enableEdgeToEdge()
        hideNavigationBar()
        val appSettings = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        setContent {
            // The previous Graphite preference selected the full dark theme.
            var darkMode by remember { mutableStateOf(appSettings.getBoolean("graphite_background", false)) }
            var background by remember {
                val saved = appSettings.getString("background_color", null)
                mutableStateOf(AppBackground.entries.firstOrNull { it.name == saved } ?: AppBackground.DEFAULT)
            }
            var terminalTextSize by remember {
                val saved = appSettings.getString("terminal_text_size", null)
                mutableStateOf(TerminalTextSize.entries.firstOrNull { it.name == saved } ?: TerminalTextSize.NORMAL)
            }
            var logView by remember {
                val saved = appSettings.getString("terminal_log_view", null)
                mutableStateOf(TerminalLogView.entries.firstOrNull { it.name == saved } ?: TerminalLogView.BOTH)
            }
            var terminalOrder by remember {
                mutableStateOf(TerminalSection.readOrder(appSettings.getString("terminal_section_order", null)))
            }
            var defaultExpanded by remember {
                mutableStateOf(TerminalSection.readExpanded(appSettings.getString("terminal_expanded_sections", null)))
            }
            var expandedSectionIds by rememberSaveable {
                mutableStateOf(defaultExpanded.joinToString(",") { it.name })
            }
            val expandedSections = remember(expandedSectionIds) { TerminalSection.readExpanded(expandedSectionIds) }
            var keepScreenOn by remember { mutableStateOf(appSettings.getBoolean("keep_screen_on", false)) }
            var showAppSettings by rememberSaveable { mutableStateOf(false) }
            val lightSystemBars = background.color?.let { it.luminance() > 0.179f } ?: !darkMode
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = lightSystemBars
                controller.isAppearanceLightNavigationBars = lightSystemBars
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            IDAUTBLETheme(darkTheme = darkMode) {
                if (showAppSettings) {
                    AppSettingsDialog(
                        darkMode = darkMode,
                        background = background,
                        terminalTextSize = terminalTextSize,
                        logView = logView,
                        keepScreenOn = keepScreenOn,
                        terminalOrder = terminalOrder,
                        defaultExpanded = defaultExpanded,
                        onDefaultExpandedChange = { sections ->
                            defaultExpanded = sections
                            expandedSectionIds = sections.joinToString(",") { it.name }
                            appSettings.edit { putString("terminal_expanded_sections", expandedSectionIds) }
                        },
                        onTerminalOrderChange = { order ->
                            terminalOrder = order
                            appSettings.edit { putString("terminal_section_order", order.joinToString(",") { it.name }) }
                        },
                        onTerminalTextSizeChange = { option ->
                            terminalTextSize = option
                            appSettings.edit { putString("terminal_text_size", option.name) }
                        },
                        onLogViewChange = { option ->
                            logView = option
                            appSettings.edit { putString("terminal_log_view", option.name) }
                        },
                        onKeepScreenOnChange = { enabled ->
                            keepScreenOn = enabled
                            appSettings.edit { putBoolean("keep_screen_on", enabled) }
                        },
                        onDarkModeChange = { enabled ->
                            darkMode = enabled
                            appSettings.edit { putBoolean("graphite_background", enabled) }
                        },
                        onBackgroundChange = { option ->
                            background = option
                            appSettings.edit { putString("background_color", option.name) }
                        },
                        onDismiss = { showAppSettings = false },
                    )
                }
                PlcBleTesterLandscape(
                    onOpenAppSettings = { showAppSettings = true },
                    backgroundColor = background.color,
                    terminalTextSize = terminalTextSize,
                    logView = logView,
                    terminalOrder = terminalOrder,
                    expandedSections = expandedSections,
                    onToggleSection = { section ->
                        val updated = if (section in expandedSections) expandedSections - section else expandedSections + section
                        expandedSectionIds = updated.joinToString(",") { it.name }
                    },
                    networkSession = networkSession,
                    maintenanceStatus = uiMaintenanceStatus.value,
                    maintenanceAuthorised = maintenanceSession.isAuthorized(),
                    deviceStatus = uiDeviceStatus.value,
                    deviceStatusReceived = uiDeviceStatusReceived.value,
                    deviceStatusReady = uiDeviceStatusReady.value,
                    deviceStatusBusy = uiDeviceStatusBusy.value || uiCounters.value.busy,
                    deviceCounters = uiCounters.value,
                    deviceStatusMessage = uiDeviceStatusMessage.value,
                    onStatusRefresh = { requestDeviceStatus() },
                    refreshAfterReads = uiRefreshAfterReads.value,
                    onRefreshAfterReadsChange = { setRefreshAfterReads(it) },
                    autoRefreshEnabled = uiAutoRefreshEnabled.value,
                    onAutoRefreshChange = { setAutoRefresh(it) },
                    readings = uiReadings.value,
                    hidOutput = uiHidOutput.value,
                    onHidOutputAction = { requestHidOutput(it) },
                    hidReceiver = uiHidReceiver.value,
                    onHidReceiverAction = { requestHidReceiverAction(it) },
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
                    manualLines = uiManualLines,
                    txtAddCrlf = uiTxtAddCrlf.value,
                    onTxtAddCrlfChanged = { uiTxtAddCrlf.value = it },
                    onSelectDevice = { uiSelected.value = it },
                    onScan5s = { startScan(5000) },
                    onConnect = { connectSelected() },
                    onDisconnect = ::requestMaintenanceDisconnect,
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
                    onManualEdit = { index, line -> uiManualLines[index] = line },
                    onManualAdd = { if (uiManualLines.size < MANUAL_LINE_LIMIT) uiManualLines.add(ManualLine()) },
                    onManualRemoveLast = { if (uiManualLines.size > 1) uiManualLines.removeAt(uiManualLines.lastIndex) },
                    onSendText = { text -> sendText(text, addCrlf = true) },
                    onSendHex = ::sendHexString,
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
                    otaSession = otaSession,
                    onSelectOtaFirmware = ::selectOtaFirmware,
                    onAbortOta = ::abortOta,
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
                            networkSession.authorisationCancelled(
                                "Authorisation cancelled. Network change was not sent."
                            )
                            uiMaintenanceStatus.value = "Authorisation cancelled"
                        },
                    )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onStart() {
        super.onStart()
        appInForeground = true
        nextMaintenancePing = 0L
        networkSession.setForeground(true)
        mainHandler.removeCallbacks(statusRefreshTick)
        mainHandler.post(statusRefreshTick)
    }

    override fun onStop() {
        appInForeground = false
        writeQueue.removeAll { it.tag == "MAINTPING" }
        networkSession.setForeground(false)
        mainHandler.removeCallbacks(statusRefreshTick)
        super.onStop()
    }

    override fun onDestroy() {
        otaSession.close()
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
    onOpenAppSettings: () -> Unit,
    backgroundColor: Color?,
    terminalTextSize: TerminalTextSize,
    logView: TerminalLogView,
    terminalOrder: List<TerminalSection>,
    expandedSections: Set<TerminalSection>,
    onToggleSection: (TerminalSection) -> Unit,
    networkSession: NetworkSession,
    maintenanceStatus: String,
    maintenanceAuthorised: Boolean,
    deviceStatus: DeviceStatus?,
    deviceStatusReceived: Long?,
    deviceStatusReady: Boolean,
    deviceStatusBusy: Boolean,
    deviceCounters: CountersSnapshot,
    deviceStatusMessage: String,
    onStatusRefresh: () -> Unit,
    refreshAfterReads: Boolean,
    onRefreshAfterReadsChange: (Boolean) -> Unit,
    autoRefreshEnabled: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    readings: PlcReadingsState,
    hidOutput: HidOutputState,
    onHidOutputAction: (HidOutputConfig?) -> Unit,
    hidReceiver: HidReceiverState,
    onHidReceiverAction: (String) -> Unit,
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
    manualLines: List<ManualLine>,
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
    onManualEdit: (Int, ManualLine) -> Unit,
    onManualAdd: () -> Unit,
    onManualRemoveLast: () -> Unit,
    onSendText: (String) -> Unit,
    onSendHex: (String) -> Unit,
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
    otaSession: OtaSession,
    onSelectOtaFirmware: (Uri) -> Unit,
    onAbortOta: () -> Unit,
) {
    var showDevices by remember { mutableStateOf(false) } // oculto por defecto

    LaunchedEffect(connected) {
        if (connected) {
            showDevices = false
        }
    }

    var selectedTab by remember { mutableStateOf(0) }
    var showPrefixDialogFor by remember { mutableStateOf<String?>(null) }

    val leftScroll = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    val logColors = remember(scheme) {
        LogColors(scheme.background.luminance() < 0.5f, scheme.onSurface)
    }

    // TopBar eliminado + sin insets reservados
    Scaffold(
        topBar = {},
        containerColor = backgroundColor ?: scheme.background,
        contentColor = when {
            backgroundColor == null -> scheme.onBackground
            backgroundColor.luminance() > 0.179f -> Color.Black
            else -> Color.White
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        Column(modifier = Modifier.padding(inner).padding(6.dp).fillMaxSize()) {

            // =========================
            // TABS
            // =========================
            MaintenanceTabs(
                selectedIndex = selectedTab,
                otaLabel = if (otaSession.busy && otaPct != null) "OTA · $otaPct%" else "OTA",
                onSelect = { selectedTab = it },
                onOpenSettings = onOpenAppSettings,
            )

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
                                terminalOrder.forEach { section ->
                                    key(section) {
                                        val expanded = section in expandedSections
                                        val onToggle = { onToggleSection(section) }
                                        when (section) {
                                            TerminalSection.BLUETOOTH -> {
                                                CollapsibleSettingsSection(
                                                    title = "BLUETOOTH CONNECTION",
                                                    summary =
                                                        if (scanning) "Scanning... ${scanRemainSec}s"
                                                        else if (connected) "Connected: ${selected?.name ?: "PLC"}"
                                                        else "Disconnected",
                                                    expanded = expanded,
                                                    onToggle = onToggle,
                                                ) {
                                                    Row(
                                                        Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement =
                                                            Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                                    ) {
                                                        Button(
                                                            shape = RoundedCornerShape(6.dp),
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
                                                                fontSize = 11.sp,
                                                                maxLines = 1,
                                                                softWrap = false,
                                                            )
                                                        }

                                                        OutlinedButton(
                                                            shape = RoundedCornerShape(6.dp),
                                                            onClick = { showDevices = !showDevices },
                                                            contentPadding = PaddingValues(10.dp, 6.dp),
                                                        ) {
                                                            Text(
                                                                if (showDevices) "Hide Devices" else "Show Devices",
                                                                fontSize = 11.sp,
                                                                maxLines = 1,
                                                                softWrap = false,
                                                            )
                                                        }
                                                        Button(
                                                            shape = RoundedCornerShape(6.dp),
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
                                                                fontSize = 11.sp,
                                                                maxLines = 1,
                                                                softWrap = false,
                                                            )
                                                        }

                                                        Button(
                                                            shape = RoundedCornerShape(6.dp),
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
                                                                fontSize = 11.sp,
                                                                maxLines = 1,
                                                                softWrap = false,
                                                            )
                                                        }
                                                    }

                                                    if (selected != null) {
                                                        val observation = selected.rssi?.let { " | RSSI:$it" }
                                                            ?: if (devices.any { it.address == selected.address && it.rssi == null }) {
                                                                " | Last connected PLC"
                                                            } else ""
                                                        Text(
                                                            text = "${selected.name} | ${selected.address}$observation",
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }

                                                    if (showDevices) {
                                                        MaintenanceCard(Modifier.fillMaxWidth()) {
                                                            Column(
                                                                Modifier.padding(6.dp),
                                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                            ) {
                                                                if (scanning) {
                                                                    Text(
                                                                        text =
                                                                            "Scanning, Please Wait... ${scanRemainSec}s",
                                                                        fontSize = 11.sp,
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
                                                                    "Visible devices: ${devices.count { it.rssi != null }}",
                                                                    fontSize = 11.sp,
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
                                                                                fontSize = 11.sp,
                                                                                maxLines = 1,
                                                                                overflow = TextOverflow.Ellipsis,
                                                                            )
                                                                            Text(d.address, fontSize = 11.sp)
                                                                            Text(d.rssi?.let { "RSSI:$it" } ?: "Last PLC", fontSize = 11.sp)
                                                                        }
                                                                        Divider()
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            TerminalSection.BINARY_COMMANDS -> {
                                                CollapsibleSettingsSection(
                                                    title = "BINARY COMMANDS",
                                                    summary = "Commands sent to the PLC",
                                                    expanded = expanded,
                                                    onToggle = onToggle,
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
                                            }
                                            TerminalSection.READINGS -> {
                                                ReadingResultsSection(readings, expanded, onToggle)
                                            }
                                            TerminalSection.DEVICE_STATUS -> {
                                                DeviceStatusSection(
                                                    expanded = expanded,
                                                    onToggle = onToggle,
                                                    status = deviceStatus,
                                                    counters = deviceCounters,
                                                    receivedMs = deviceStatusReceived,
                                                    ready = deviceStatusReady,
                                                    busy = deviceStatusBusy,
                                                    message = deviceStatusMessage,
                                                    onRefresh = onStatusRefresh,
                                                    refreshAfterReads = refreshAfterReads,
                                                    onRefreshAfterReadsChange = onRefreshAfterReadsChange,
                                                    autoRefreshEnabled = autoRefreshEnabled,
                                                    onAutoRefreshChange = onAutoRefreshChange,
                                                )
                                            }
                                            TerminalSection.PIN_ACCESS -> {
                                                CollapsibleSettingsSection(
                                                    title = "PIN / ACCESS",
                                                    summary = maintenanceStatus,
                                                    summaryContent = {
                                                        MaintenanceAccessBadge(
                                                            authorised = maintenanceAuthorised,
                                                            label = maintenanceStatus,
                                                        )
                                                    },
                                                    expanded = expanded,
                                                    onToggle = onToggle,
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
                                            }
                                            TerminalSection.HID_RECEIVER -> {
                                                HidReceiverSection(hidReceiver, connected, maintenanceAuthorised, onHidReceiverAction, hidOutput.supported, expanded, onToggle)
                                            }
                                            TerminalSection.HID_OUTPUT -> {
                                                HidOutputSection(hidOutput, connected, hidReceiver.production, onHidOutputAction, expanded, onToggle)
                                            }
                                            TerminalSection.DEVICE_ID -> {
                                                CollapsibleSettingsSection(
                                                    title = "DEVICE ID",
                                                    summary = equipmentId ?: "ID not queried",
                                                    expanded = expanded,
                                                    onToggle = onToggle,
                                                ) {
                                                    Text(
                                                        "ID used for the Bluetooth name and PLC data frames.",
                                                        fontSize = 11.sp,
                                                    )
                                                    MaintenanceActionButton(
                                                        text = "Configure ID",
                                                        onClick = onIdentityClick,
                                                        tone = MaintenanceActionTone.INFO,
                                                        enabled = connected,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                            }
                                            TerminalSection.HEIGHT_MEASUREMENT -> {
                                                CollapsibleSettingsSection(
                                                    title = "HEIGHT MEASUREMENT",
                                                    summary =
                                                        if (!altKnown) "Status not queried"
                                                        else if (altState == true) "Enabled" else "Disabled",
                                                    expanded = expanded,
                                                    onToggle = onToggle,
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        MaintenanceActionButton(
                                                            text = "Get Status",
                                                            onClick = onAltQuery,
                                                            tone = MaintenanceActionTone.INFO,
                                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                                        )
                                                        if (altKnown) {
                                                            val isEnabled = altState == true
                                                            MaintenanceActionButton(
                                                                text = if (isEnabled) "Disable Height Measurement"
                                                                    else "Enable Height Measurement",
                                                                onClick = { if (isEnabled) onAltDisable() else onAltEnable() },
                                                                tone = if (isEnabled) MaintenanceActionTone.DANGER
                                                                    else MaintenanceActionTone.SUCCESS,
                                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            TerminalSection.BARCODE_ID -> {
                                                CollapsibleSettingsSection(
                                                    title = "BARCODE ID",
                                                    summary = "Barcode prefixes",
                                                    expanded = expanded,
                                                    onToggle = onToggle,
                                                ) {
                                                    BarcodePrefixField("Pallet", pfxP, onPfxPChanged) {
                                                        showPrefixDialogFor = "PFXP"
                                                    }
                                                    BarcodePrefixField("Spot", pfxU, onPfxUChanged) {
                                                        showPrefixDialogFor = "PFXU"
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        MaintenanceActionButton(
                                                            "Get ID", onCfgQuery, MaintenanceActionTone.INFO, Modifier.weight(1f),
                                                        )
                                                        MaintenanceActionButton(
                                                            "Set", onSetCfg, MaintenanceActionTone.SUCCESS, Modifier.weight(1f),
                                                        )
                                                    }
                                                }
                                            }
                                            TerminalSection.RTC -> {
                                                CollapsibleSettingsSection(
                                                    title = "RTC",
                                                    summary = "Date and time",
                                                    expanded = expanded,
                                                    onToggle = onToggle,
                                                ) {
                                                    CompactOutlinedField(rtcIso, onRtcIsoChanged, Modifier.fillMaxWidth())
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        MaintenanceActionButton(
                                                            "Refresh", onRefreshRtc, MaintenanceActionTone.INFO, Modifier.weight(1f),
                                                        )
                                                        MaintenanceActionButton(
                                                            "Set", onSetRtc, MaintenanceActionTone.SUCCESS, Modifier.weight(1f),
                                                        )
                                                    }
                                                }
                                            }
                                            TerminalSection.MANUAL_TERMINAL -> {
                                                ManualTerminalSection(
                                                    expanded = expanded,
                                                    onToggle = onToggle,
                                                    lines = manualLines,
                                                    onEdit = onManualEdit,
                                                    onAdd = onManualAdd,
                                                    onRemoveLast = onManualRemoveLast,
                                                    connected = connected,
                                                    onSendText = onSendText,
                                                    onSendHex = onSendHex,
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(60.dp))
                            }

                            // RIGHT fixed log
                            Column(modifier = Modifier.weight(0.60f).fillMaxHeight()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MaintenanceCard(Modifier.weight(1f).fillMaxHeight()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Text(
                                                text = "Status: $statusLine",
                                                fontSize = terminalTextSize.fontSizeSp.sp,
                                                lineHeight = terminalTextSize.lineHeightSp.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = logColors.neutralText,
                                            )
                                        }
                                    }
                                    OutlinedButton(
                                        shape = RoundedCornerShape(8.dp),
                                        onClick = onClearLog,
                                        contentPadding = PaddingValues(10.dp, 6.dp),
                                        modifier = Modifier.widthIn(min = 90.dp).fillMaxHeight(),
                                        border = BorderStroke(1.dp, scheme.outlineVariant),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = scheme.surfaceContainerLow),
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

                                MaintenanceCard(Modifier.fillMaxSize()) {
                                    LazyColumn(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                                        itemsIndexed(logs) { _, entry ->
                                            Spacer(Modifier.height(6.dp)) // espacio entre bloques

                                            when (entry) {
                                                is LogEntry.Simple -> {

                                                    val t = timeFmt(entry.t)

                                                    val color =
                                                        when (entry.lvl) {
                                                            LogLevel.WARN -> logColors.warnAmber
                                                            LogLevel.ERR -> logColors.errRed
                                                            else -> logColors.neutralText
                                                        }

                                                    Text(
                                                        text =
                                                            "$t  ${entry.lvl.name}  ${entry.msg}",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = terminalTextSize.fontSizeSp.sp,
                                                        lineHeight = terminalTextSize.lineHeightSp.sp,
                                                        color = color,
                                                    )
                                                }

                                                is LogEntry.Traffic -> {

                                                    val isTx = entry.direction == LogLevel.TX
                                                    val arrow = if (isTx) "↑ TX" else "↓ RX"
                                                    val t = timeFmt(entry.t)

                                                    val bgColor =
                                                        if (isTx)
                                                            logColors.txBlue.copy(alpha = 0.06f)
                                                        else logColors.rxGreen.copy(alpha = 0.06f)

                                                    MaintenanceCard(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        containerColor = bgColor,
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
                                                                fontSize = terminalTextSize.fontSizeSp.sp,
                                                                lineHeight = terminalTextSize.lineHeightSp.sp,
                                                                color =
                                                                    if (isTx) logColors.txBlue
                                                                    else logColors.rxGreen,
                                                            )

                                                            Spacer(Modifier.height(1.dp))

                                                            if (logView.showAscii) {
                                                                TerminalLogPayload("ASCII: ", entry.ascii, logColors.rxLabelRed,
                                                                    logColors.asciiPurple, terminalTextSize)
                                                            }
                                                            if (logView.showHex) {
                                                                TerminalLogPayload("HEX:   ", entry.hex, logColors.rxLabelRed,
                                                                    logColors.rxHexOrange, terminalTextSize)
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
                }
            } else if (selectedTab == 1) {

                key("NETWORK") { NetworkScreen(session = networkSession) }
            } else {
                OtaScreen(
                    onSendOta = onSendOta,
                    otaApSsid = otaApSsid,
                    otaPct = otaPct,
                    otaStatus = otaStatus,
                    otaErr = otaErr,
                    otaDone = otaDone,
                    bleLinkState = bleLinkState,
                    session = otaSession,
                    onSelectFirmware = onSelectOtaFirmware,
                    onAbortOta = onAbortOta,
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
private fun TerminalLogPayload(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    textSize: TerminalTextSize,
) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = labelColor)) { append(label) }
            withStyle(SpanStyle(color = valueColor)) { append(value) }
        },
        fontFamily = FontFamily.Monospace,
        fontSize = textSize.fontSizeSp.sp,
        lineHeight = textSize.lineHeightSp.sp,
    )
}

@Composable
private fun CmdBtn(label: String, onClick: () -> Unit) {
    Button(
        shape = RoundedCornerShape(6.dp),
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
        Text(
            "CMD $label",
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Campo compacto con:
 * - alto fijo 40dp
 * - padding interno pequeño
 */
@Composable
internal fun CompactOutlinedFieldTFV(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    val outline = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface
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
            textStyle =
                MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                ),
            cursorBrush = SolidColor(cursor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BarcodePrefixField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, modifier = Modifier.width(52.dp), fontSize = 11.sp)
        CompactOutlinedField(value, onValueChange, Modifier.weight(1f))
        MaintenanceActionButton("Select", onSelect, MaintenanceActionTone.INFO)
    }
}

@Composable
private fun CompactOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    val outline = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface
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
            textStyle =
                MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                ),
            cursorBrush = SolidColor(cursor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
