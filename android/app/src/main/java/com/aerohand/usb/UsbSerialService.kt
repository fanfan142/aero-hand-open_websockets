package com.aerohand.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.aerohand.websocket.LogEntry
import com.aerohand.websocket.buildSerialGetPositionsFrame
import com.aerohand.websocket.buildSerialHomingFrame
import com.aerohand.websocket.buildSerialPositionControlFrame
import com.aerohand.websocket.parseSerialActuationResponse
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class UsbConnectionState {
    object Disconnected : UsbConnectionState()
    data class Connected(val deviceName: String) : UsbConnectionState()
    data class Error(val message: String) : UsbConnectionState()
}

class UsbSerialService(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var port: UsbSerialPort? = null
    private var currentDevice: UsbDevice? = null
    private var receiverRegistered = false
    private var pendingDevice: UsbDevice? = null

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _compactState = MutableStateFlow<Map<String, Float>>(emptyMap())
    val compactState: StateFlow<Map<String, Float>> = _compactState

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = readUsbDevice(intent) ?: pendingDevice
                    if (granted && device != null) {
                        ioScope.launch { openDevice(device) }
                    } else {
                        _connectionState.value = UsbConnectionState.Error("USB 权限被拒绝")
                        addLog(LogEntry.Error("USB permission denied", timestamp()))
                    }
                    pendingDevice = null
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = readUsbDevice(intent)
                    if (device != null && currentDevice?.deviceId == device.deviceId) {
                        addLog(LogEntry.Info("USB 已断开 ${device.deviceName}", timestamp()))
                        ioScope.launch { disconnectInternal() }
                    }
                }
            }
        }
    }

    init {
        registerReceiver()
    }

    fun findAndConnect() {
        ioScope.launch {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val supportedDrivers = drivers.filter { driver -> driver.device.vendorId in SUPPORTED_VENDOR_IDS }
            if (supportedDrivers.isEmpty()) {
                _connectionState.value = UsbConnectionState.Error("未检测到受支持的 USB 串口设备")
                addLog(LogEntry.Error("No supported USB serial device found", timestamp()))
                return@launch
            }

            val driver = supportedDrivers.first()
            val device = driver.device
            addLog(LogEntry.Info("发现 USB 设备 VID=${device.vendorId} PID=${device.productId}", timestamp()))

            if (usbManager.hasPermission(device)) {
                openDevice(device)
                return@launch
            }

            pendingDevice = device
            usbManager.requestPermission(device, buildPermissionIntent())
            _connectionState.value = UsbConnectionState.Error("正在申请 USB 权限，请确认系统弹窗")
            addLog(LogEntry.Info("Requesting USB permission", timestamp()))
        }
    }

    fun disconnect() {
        ioScope.launch { disconnectInternal() }
    }

    fun sendHoming() {
        ioScope.launch {
            sendFrame(buildSerialHomingFrame(), "homing")
        }
    }

    fun requestStates() {
        ioScope.launch {
            val frame = buildSerialGetPositionsFrame()
            sendFrame(frame, frame.toHexString())
            readActuationState()
        }
    }

    fun sendCompactState(compactState: Map<String, Float>) {
        ioScope.launch {
            val frame = buildSerialPositionControlFrame(compactState)
            sendFrame(frame, frame.toHexString())
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun release() {
        disconnectInternal()
        unregisterReceiver()
        ioScope.cancel()
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                _connectionState.value = UsbConnectionState.Error("设备不是受支持的 USB 串口")
                addLog(LogEntry.Error("Unsupported USB serial device", timestamp()))
                return
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                _connectionState.value = UsbConnectionState.Error("打开 USB 设备失败")
                addLog(LogEntry.Error("Open USB device failed", timestamp()))
                return
            }

            val nextPort = driver.ports.firstOrNull()
            if (nextPort == null) {
                connection.close()
                _connectionState.value = UsbConnectionState.Error("USB 串口不可用")
                addLog(LogEntry.Error("No USB serial port available", timestamp()))
                return
            }

            disconnectInternal()
            nextPort.open(connection)
            nextPort.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            nextPort.dtr = true
            nextPort.rts = true
            port = nextPort
            currentDevice = device
            _connectionState.value = UsbConnectionState.Connected(device.deviceName ?: "USB Serial")
            addLog(LogEntry.Info("USB connected @ 921600", timestamp()))
            readActuationState()
        } catch (e: Exception) {
            port = null
            currentDevice = null
            _connectionState.value = UsbConnectionState.Error(e.message ?: "USB 连接失败")
            addLog(LogEntry.Error("USB open failed: ${e.message}", timestamp()))
        }
    }

    private fun disconnectInternal() {
        try {
            port?.close()
        } catch (_: IOException) {
        }
        port = null
        currentDevice = null
        _connectionState.value = UsbConnectionState.Disconnected
    }

    private fun readActuationState() {
        val currentPort = port ?: return
        val buffer = ByteArray(16)
        try {
            val length = currentPort.read(buffer, 80)
            if (length == 16) {
                val state = parseSerialActuationResponse(buffer)
                if (state != null) {
                    _compactState.value = state
                    addLog(LogEntry.Receive(buffer.toHexString(), timestamp()))
                }
            }
        } catch (e: Exception) {
            addLog(LogEntry.Error("USB read failed: ${e.message}", timestamp()))
            _connectionState.value = UsbConnectionState.Error(e.message ?: "USB 读取失败")
            disconnectInternal()
        }
    }

    private fun sendFrame(frame: ByteArray, logMessage: String) {
        val currentPort = port
        if (currentPort == null) {
            addLog(LogEntry.Error("Send failed: USB socket not ready", timestamp()))
            return
        }

        try {
            currentPort.write(frame, 200)
            addLog(LogEntry.Send(logMessage, timestamp()))
        } catch (e: Exception) {
            _connectionState.value = UsbConnectionState.Error(e.message ?: "USB 发送失败")
            addLog(LogEntry.Error("USB send failed: ${e.message}", timestamp()))
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(usbPermissionReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching { appContext.unregisterReceiver(usbPermissionReceiver) }
        receiverRegistered = false
    }

    private fun buildPermissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            appContext,
            1001,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags
        )
    }

    private fun readUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun addLog(entry: LogEntry) {
        _logs.value = (_logs.value + entry).takeLast(120)
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.aerohand.USB_PERMISSION"
        private val SUPPORTED_VENDOR_IDS = setOf(1027, 11914, 6790, 4292)
    }
}
