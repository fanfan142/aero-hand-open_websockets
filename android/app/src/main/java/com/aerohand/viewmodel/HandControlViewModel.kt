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
    val protocolPreview: String = "{}"
)

enum class ConnectionMode {
    WIFI, USB
}

class HandControlViewModel(application: Application) : AndroidViewModel(application) {
    private val webSocketService = WebSocketService()
    private val usbSerialService = UsbSerialService(application)

    private val _uiState = MutableStateFlow(HandControlUiState())
    val uiState: StateFlow<HandControlUiState> = _uiState

    private var sendDebounceJob: Job? = null

    init {
        // Observe WebSocket state
        viewModelScope.launch {
            webSocketService.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    wifiConnected = state is ConnectionState.Connected,
                    logs = webSocketService.logs.value
                )
            }
        }

        // Observe WebSocket logs
        viewModelScope.launch {
            webSocketService.logs.collect { logs ->
                _uiState.value = _uiState.value.copy(logs = logs)
            }
        }

        // Observe USB state
        viewModelScope.launch {
            usbSerialService.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    usbConnected = state is UsbConnectionState.Connected
                )
            }
        }

        // Observe joint states from server
        viewModelScope.launch {
            webSocketService.jointStates.collect { states ->
                if (states.isNotEmpty()) {
                    updateControlValuesFromStates(states)
                }
            }
        }

        // Update protocol preview
        viewModelScope.launch {
            _uiState.collect {
                updateProtocolPreview()
            }
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        _uiState.value = _uiState.value.copy(connectionMode = mode)
    }

    fun setHost(host: String) {
        _uiState.value = _uiState.value.copy(host = host)
    }

    fun setPort(port: String) {
        _uiState.value = _uiState.value.copy(port = port)
    }

    fun connect() {
        val state = _uiState.value
        if (state.connectionMode == ConnectionMode.WIFI) {
            val host = state.host.trim()
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
        val newValues = _uiState.value.controlValues.toMutableMap()
        newValues[controlId] = value
        _uiState.value = _uiState.value.copy(controlValues = newValues)

        // Debounced send
        sendDebounceJob?.cancel()
        sendDebounceJob = viewModelScope.launch {
            delay(60)
            sendCurrentState()
        }
    }

    fun sendHoming() {
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.sendHoming()
        } else {
            // Convert to serial command
            usbSerialService.send("{\"type\":\"homing\",\"timestamp\":${System.currentTimeMillis()}}")
        }
    }

    fun sendAllZeros() {
        val zeros = ControlDefinitions.COMPACT_CONTROLS.associate { it.id to it.defaultValue }
        _uiState.value = _uiState.value.copy(controlValues = zeros)

        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.sendAllZeros(zeros)
        } else {
            sendCurrentStateViaUsb()
        }
    }

    fun requestStates() {
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.requestStates()
        }
    }

    fun clearLogs() {
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.clearLogs()
        } else {
            usbSerialService.clearLogs()
        }
    }

    private fun sendCurrentState() {
        val state = _uiState.value
        if (state.connectionMode == ConnectionMode.WIFI && state.wifiConnected) {
            webSocketService.sendAllZeros(state.controlValues)
        } else if (state.connectionMode == ConnectionMode.USB && state.usbConnected) {
            sendCurrentStateViaUsb()
        }
    }

    private fun sendCurrentStateViaUsb() {
        val state = _uiState.value
        val payload = buildPayload(state.controlValues)
        usbSerialService.send(payload)
    }

    private fun buildPayload(compactState: Map<String, Float>): String {
        val joints = mutableListOf<Map<String, Any>>()

        // thumb_proximal = thumb_cmc_flex
        joints.add(mapOf("joint_id" to "thumb_proximal", "angle" to (compactState["thumb_cmc_flex"] ?: 0f)))

        // thumb_distal = thumb_mcp_ip
        joints.add(mapOf("joint_id" to "thumb_distal", "angle" to (compactState["thumb_mcp_ip"] ?: 0f)))

        // finger triples
        listOf("index", "middle", "ring", "pinky").forEach { finger ->
            val value = compactState["${finger}_flexion"] ?: 0f
            listOf("proximal", "middle", "distal").forEach { joint ->
                joints.add(mapOf("joint_id" to "${finger}_$joint", "angle" to value))
            }
        }

        // thumb_rotation
        val thumbRotation = mapRange(
            compactState["thumb_cmc_abd"] ?: 0f,
            0f, 100f,
            ControlDefinitions.THUMB_ROTATION_MIN,
            ControlDefinitions.THUMB_ROTATION_MAX
        )
        joints.add(mapOf("joint_id" to "thumb_rotation", "angle" to thumbRotation))

        val payload = mapOf(
            "type" to "multi_joint_control",
            "timestamp" to System.currentTimeMillis(),
            "data" to mapOf(
                "joints" to joints,
                "duration_ms" to ControlDefinitions.DEFAULT_DURATION_MS
            )
        )

        return JSONObject(payload).toString()
    }

    private fun mapRange(value: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
        if (inMax == inMin) return outMin
        val normalized = (value - inMin) / (inMax - inMin)
        return outMin + normalized * (outMax - outMin)
    }

    private fun updateProtocolPreview() {
        val state = _uiState.value
        val joints = mutableListOf<Map<String, Any>>()

        joints.add(mapOf("joint_id" to "thumb_proximal", "angle" to (state.controlValues["thumb_cmc_flex"] ?: 0f)))
        joints.add(mapOf("joint_id" to "thumb_distal", "angle" to (state.controlValues["thumb_mcp_ip"] ?: 0f)))

        listOf("index", "middle", "ring", "pinky").forEach { finger ->
            val value = state.controlValues["${finger}_flexion"] ?: 0f
            listOf("proximal", "middle", "distal").forEach { joint ->
                joints.add(mapOf("joint_id" to "${finger}_$joint", "angle" to value))
            }
        }

        val thumbRotation = mapRange(
            state.controlValues["thumb_cmc_abd"] ?: 0f,
            0f, 100f,
            ControlDefinitions.THUMB_ROTATION_MIN,
            ControlDefinitions.THUMB_ROTATION_MAX
        )
        joints.add(mapOf("joint_id" to "thumb_rotation", "angle" to thumbRotation))

        val preview = JSONArray(joints).toString(2)
        _uiState.value = _uiState.value.copy(protocolPreview = preview)
    }

    private fun updateControlValuesFromStates(states: Map<String, Float>) {
        val values = _uiState.value.controlValues.toMutableMap()

        // Reverse mapping from joint states to compact controls
        values["thumb_cmc_flex"] = clamp(states["thumb_proximal"] ?: 0f, 0f, 55f)
        values["thumb_mcp_ip"] = clamp(states["thumb_distal"] ?: 0f, 0f, 90f)
        values["index_flexion"] = clamp(states["index_proximal"] ?: 0f, 0f, 90f)
        values["middle_flexion"] = clamp(states["middle_proximal"] ?: 0f, 0f, 90f)
        values["ring_flexion"] = clamp(states["ring_proximal"] ?: 0f, 0f, 90f)
        values["pinky_flexion"] = clamp(states["pinky_proximal"] ?: 0f, 0f, 90f)

        // thumb_rotation -> thumb_cmc_abd
        val thumbRotation = states["thumb_rotation"] ?: 0f
        values["thumb_cmc_abd"] = clamp(
            mapRange(thumbRotation, ControlDefinitions.THUMB_ROTATION_MIN, ControlDefinitions.THUMB_ROTATION_MAX, 0f, 100f),
            0f, 100f
        )

        _uiState.value = _uiState.value.copy(controlValues = values)
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }

    override fun onCleared() {
        super.onCleared()
        webSocketService.disconnect()
        usbSerialService.release()
    }
}
