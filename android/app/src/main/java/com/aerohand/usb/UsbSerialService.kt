package com.aerohand.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

sealed class UsbConnectionState {
    object Disconnected : UsbConnectionState()
    object PermissionRequired : UsbConnectionState()
    data class Connected(val deviceName: String) : UsbConnectionState()
    data class Error(val message: String) : UsbConnectionState()
}

sealed class UsbLogEntry {
    abstract val message: String
    data class Send(override val message: String) : UsbLogEntry()
    data class Receive(override val message: String) : UsbLogEntry()
    data class Error(override val message: String) : UsbLogEntry()
    data class Info(override val message: String) : UsbLogEntry()
}

class UsbSerialService(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var inputOutputManager: SerialInputOutputManager? = null

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState

    private val _logs = MutableStateFlow<List<UsbLogEntry>>(emptyList())
    val logs: StateFlow<List<UsbLogEntry>> = _logs

    private var pendingDevice: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbDevice.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { connectToDevice(it) }
                        } else {
                            _connectionState.value = UsbConnectionState.Error("Permission denied")
                            addLog(UsbLogEntry.Error("USB permission denied"))
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbDevice.EXTRA_DEVICE)
                    device?.let { findAndConnect(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)
    }

    fun findAndConnect(device: UsbDevice? = null) {
        val foundDevice = device ?: usbManager.deviceList.values.firstOrNull { d ->
            // Filter for serial devices
            d.vendorId in listOf(1027, 11913, 6790, 10655, 0x1a86) // FTDI, Silabs, CH340, CP210x, CH340
        }

        if (foundDevice == null) {
            _connectionState.value = UsbConnectionState.Error("No USB serial device found")
            addLog(UsbLogEntry.Error("No USB serial device found"))
            return
        }

        if (!usbManager.hasPermission(foundDevice)) {
            pendingDevice = foundDevice
            _connectionState.value = UsbConnectionState.PermissionRequired
            addLog(UsbLogEntry.Info("Requesting USB permission..."))
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(foundDevice, permissionIntent)
            return
        }

        connectToDevice(foundDevice)
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val driver: UsbSerialDriver? = UsbSerialProber.getDefaultProber()
                .probeDevice(device)

            if (driver == null) {
                _connectionState.value = UsbConnectionState.Error("No driver for device")
                addLog(UsbLogEntry.Error("No driver for this device"))
                return
            }

            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                _connectionState.value = UsbConnectionState.Error("Cannot open USB connection")
                addLog(UsbLogEntry.Error("Cannot open USB connection"))
                return
            }

            serialPort = driver.ports[0]
            serialPort?.open(connection)
            serialPort?.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            _connectionState.value = UsbConnectionState.Connected(device.deviceName)
            addLog(UsbLogEntry.Info("USB connected: ${device.deviceName}"))

            startListening()
        } catch (e: IOException) {
            _connectionState.value = UsbConnectionState.Error(e.message ?: "Connection failed")
            addLog(UsbLogEntry.Error("Connection failed: ${e.message}"))
        }
    }

    private fun startListening() {
        val port = serialPort ?: return
        inputOutputManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val text = String(data, Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    addLog(UsbLogEntry.Receive(text))
                }
            }

            override fun onRunError(e: Exception?) {
                e?.let {
                    addLog(UsbLogEntry.Error("Read error: ${it.message}"))
                }
            }
        }).apply {
            start()
        }
    }

    fun send(text: String) {
        val port = serialPort
        if (port == null) {
            addLog(UsbLogEntry.Error("Not connected"))
            return
        }

        try {
            port.write((text + "\n").toByteArray(Charsets.UTF_8), 1000)
            addLog(UsbLogEntry.Send(text))
        } catch (e: IOException) {
            addLog(UsbLogEntry.Error("Write error: ${e.message}"))
        }
    }

    fun disconnect() {
        try {
            inputOutputManager?.stop()
            serialPort?.close()
        } catch (e: IOException) {
            // Ignore
        }
        serialPort = null
        inputOutputManager = null
        _connectionState.value = UsbConnectionState.Disconnected
        addLog(UsbLogEntry.Info("USB disconnected"))
    }

    fun isConnected(): Boolean = serialPort != null

    private fun addLog(entry: UsbLogEntry) {
        _logs.value = (_logs.value + entry).takeLast(100)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun release() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.aerohand.USB_PERMISSION"
    }
}
