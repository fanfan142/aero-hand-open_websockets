package com.aerohand.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

// USB Serial functionality
// Note: Full USB support requires usb-serial-for-android library
// This is a stub implementation for build compatibility

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
                            device?.let { findAndConnect(it) }
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
        try {
            context.registerReceiver(usbReceiver, filter)
        } catch (e: Exception) {
            // Ignore if already registered
        }
    }

    fun findAndConnect(device: UsbDevice? = null) {
        addLog(UsbLogEntry.Info("USB not available in this build"))
        _connectionState.value = UsbConnectionState.Error("USB not available - library not linked")
    }

    fun send(text: String) {
        addLog(UsbLogEntry.Error("USB not available"))
    }

    fun disconnect() {
        _connectionState.value = UsbConnectionState.Disconnected
        addLog(UsbLogEntry.Info("USB disconnected"))
    }

    fun isConnected(): Boolean = false

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
