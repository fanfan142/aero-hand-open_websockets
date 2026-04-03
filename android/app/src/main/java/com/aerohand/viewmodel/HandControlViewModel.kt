package com.aerohand.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerohand.usb.UsbConnectionState
import com.aerohand.usb.UsbSerialService
import com.aerohand.websocket.ConnectionState
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.LogEntry
import com.aerohand.websocket.WebSocketService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class HandControlUiState(
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    val wifiConnected: Boolean = false,
    val usbConnected: Boolean = false,
    val host: String = "192.168.4.1",
    val port: String = "8765",
    val controlValues: Map<String, Float> = ControlDefinitions.COMPACT_CONTROLS.associate { it.id to it.defaultValue },
    val logs: List<LogEntry> = emptyList(),
    val protocolPreview: String = "[]",
    val statusMessage: String = "准备就绪"
)

enum class ConnectionMode {
    WIFI,
    USB
}

class HandControlViewModel(application: Application) : AndroidViewModel(application) {
    private val webSocketService = WebSocketService()
    private val usbSerialService = UsbSerialService(application)

    private val _uiState = MutableStateFlow(
        HandControlUiState(
            protocolPreview = buildProtocolPreview(
                ControlDefinitions.COMPACT_CONTROLS.associate { it.id to it.defaultValue }
            )
        )
    )
    val uiState: StateFlow<HandControlUiState> = _uiState

    private var sendDebounceJob: Job? = null

    init {
        viewModelScope.launch {
            webSocketService.connectionState.collectLatest { state ->
                mutateState {
                    copy(
                        wifiConnected = state is ConnectionState.Connected,
                        statusMessage = when (state) {
                            is ConnectionState.Connected -> "已连接 ${state.server}"
                            is ConnectionState.Connecting -> "连接中..."
                            is ConnectionState.Error -> state.message
                            ConnectionState.Disconnected -> if (connectionMode == ConnectionMode.WIFI) "未连接" else statusMessage
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            webSocketService.logs.collectLatest { logs ->
                mutateState { copy(logs = logs) }
            }
        }

        viewModelScope.launch {
            webSocketService.jointStates.collectLatest { states ->
                if (states.isNotEmpty()) {
                    updateControlValuesFromStates(states)
                }
            }
        }

        viewModelScope.launch {
            usbSerialService.connectionState.collectLatest { state ->
                mutateState {
                    copy(
                        usbConnected = state is UsbConnectionState.Connected,
                        statusMessage = when (state) {
                            is UsbConnectionState.Connected -> "USB 已连接 ${state.deviceName}"
                            is UsbConnectionState.Error -> state.message
                            UsbConnectionState.Disconnected -> if (connectionMode == ConnectionMode.USB) "USB 未连接" else statusMessage
                        }
                    )
                }
            }
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        mutateState {
            copy(
                connectionMode = mode,
                statusMessage = if (mode == ConnectionMode.WIFI) {
                    if (wifiConnected) statusMessage else "未连接"
                } else {
                    if (usbConnected) statusMessage else "USB 未连接"
                }
            )
        }
    }

    fun setHost(host: String) {
        mutateState { copy(host = host) }
    }

    fun setPort(port: String) {
        mutateState { copy(port = port) }
    }

    fun connect() {
        val state = _uiState.value
        if (state.connectionMode == ConnectionMode.WIFI) {
            val host = state.host.trim().ifBlank { "192.168.4.1" }
            val port = state.port.toIntOrNull() ?: 8765
            webSocketService.connect(host, port)
        } else {
            usbSerialService.findAndConnect()
        }
    }

    fun disconnect() {
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.disconnect()
        } else {
            usbSerialService.disconnect()
        }
    }

    fun updateControlValue(controlId: String, value: Float) {
        val nextValues = _uiState.value.controlValues.toMutableMap().apply {
            this[controlId] = value
        }
        mutateState { copy(controlValues = nextValues) }

        sendDebounceJob?.cancel()
        sendDebounceJob = viewModelScope.launch {
            delay(60)
            sendCurrentState()
        }
    }

    fun sendHoming() {
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.sendHoming()
        }
    }

    fun sendAllZeros() {
        val zeros = ControlDefinitions.COMPACT_CONTROLS.associate { it.id to 0f }
        mutateState { copy(controlValues = zeros) }
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.sendCompactState(zeros)
        }
    }

    fun requestStates() {
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.requestStates()
        }
    }

    fun clearLogs() {
        webSocketService.clearLogs()
        usbSerialService.clearLogs()
        mutateState { copy(logs = emptyList()) }
    }

    private fun sendCurrentState() {
        val state = _uiState.value
        if (state.connectionMode == ConnectionMode.WIFI && state.wifiConnected) {
            webSocketService.sendCompactState(state.controlValues)
        }
    }

    private fun updateControlValuesFromStates(states: Map<String, Float>) {
        val values = _uiState.value.controlValues.toMutableMap()
        values["thumb_cmc_flex"] = (states["thumb_proximal"] ?: 0f).coerceIn(0f, 55f)
        values["thumb_mcp_ip"] = (states["thumb_distal"] ?: 0f).coerceIn(0f, 90f)
        values["index_flexion"] = (states["index_proximal"] ?: 0f).coerceIn(0f, 90f)
        values["middle_flexion"] = (states["middle_proximal"] ?: 0f).coerceIn(0f, 90f)
        values["ring_flexion"] = (states["ring_proximal"] ?: 0f).coerceIn(0f, 90f)
        values["pinky_flexion"] = (states["pinky_proximal"] ?: 0f).coerceIn(0f, 90f)
        values["thumb_cmc_abd"] = mapRange(
            states["thumb_rotation"] ?: 0f,
            ControlDefinitions.THUMB_ROTATION_MIN,
            ControlDefinitions.THUMB_ROTATION_MAX,
            0f,
            100f
        ).coerceIn(0f, 100f)
        mutateState { copy(controlValues = values) }
    }

    private fun mutateState(transform: HandControlUiState.() -> HandControlUiState) {
        val next = _uiState.value.transform()
        _uiState.value = next.copy(protocolPreview = buildProtocolPreview(next.controlValues))
    }

    private fun buildProtocolPreview(compactState: Map<String, Float>): String {
        val joints = JSONArray().apply {
            put(jointJson("thumb_proximal", compactState["thumb_cmc_flex"] ?: 0f))
            put(jointJson("thumb_distal", compactState["thumb_mcp_ip"] ?: 0f))

            listOf("index", "middle", "ring", "pinky").forEach { finger ->
                val value = compactState["${finger}_flexion"] ?: 0f
                put(jointJson("${finger}_proximal", value))
                put(jointJson("${finger}_middle", value))
                put(jointJson("${finger}_distal", value))
            }

            val thumbRotation = mapRange(
                compactState["thumb_cmc_abd"] ?: 0f,
                0f,
                100f,
                ControlDefinitions.THUMB_ROTATION_MIN,
                ControlDefinitions.THUMB_ROTATION_MAX
            )
            put(jointJson("thumb_rotation", thumbRotation))
        }
        return joints.toString(2)
    }

    private fun jointJson(jointId: String, angle: Float): JSONObject {
        return JSONObject().apply {
            put("joint_id", jointId)
            put("angle", angle)
        }
    }

    private fun mapRange(value: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
        if (inMax == inMin) {
            return outMin
        }
        val normalized = (value - inMin) / (inMax - inMin)
        return outMin + normalized * (outMax - outMin)
    }

    override fun onCleared() {
        super.onCleared()
        webSocketService.disconnect()
        usbSerialService.release()
    }
}
