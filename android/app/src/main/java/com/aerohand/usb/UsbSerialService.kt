package com.aerohand.usb

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class UsbConnectionState {
    object Disconnected : UsbConnectionState()
    data class Connected(val deviceName: String) : UsbConnectionState()
    data class Error(val message: String) : UsbConnectionState()
}

class UsbSerialService(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState

    fun findAndConnect() {
        _connectionState.value = UsbConnectionState.Error("当前构建提供 WiFi 控制；USB 串口接口保留为后续扩展")
    }

    fun disconnect() {
        _connectionState.value = UsbConnectionState.Disconnected
    }

    fun send(@Suppress("UNUSED_PARAMETER") text: String) = Unit

    fun clearLogs() = Unit

    fun release() = Unit
}
