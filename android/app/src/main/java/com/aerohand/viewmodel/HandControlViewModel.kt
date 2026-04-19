package com.aerohand.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerohand.gesture.FingerAngles
import com.aerohand.gesture.GestureCameraState
import com.aerohand.gesture.GestureTargetHand
import com.aerohand.usb.UsbConnectionState
import com.aerohand.usb.UsbSerialService
import com.aerohand.websocket.ConnectionState
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.LogEntry
import com.aerohand.websocket.PresetAction
import com.aerohand.websocket.PresetActions
import com.aerohand.websocket.WebSocketService
import com.aerohand.websocket.buildProtocolPreview
import com.aerohand.websocket.compactStateFromJointStates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

data class WifiConfigUiState(
    val staSsid: String = "",
    val staPassword: String = "",
    val currentWifiMode: String = "AP",
    val currentIp: String = "192.168.4.1",
    val configuredStaSsid: String? = null
)

enum class ConnectionPanelVisibility {
    EXPANDED,
    COLLAPSED,
    HIDDEN
}

data class HandControlUiState(
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    val wifiConnected: Boolean = false,
    val usbConnected: Boolean = false,
    val connectedHandType: String? = null,
    val connectedServer: String? = null,
    val host: String = "192.168.4.1",
    val port: String = "8765",
    val connectionPanelVisibility: ConnectionPanelVisibility = ConnectionPanelVisibility.COLLAPSED,
    val wifiConfig: WifiConfigUiState = WifiConfigUiState(),
    val controlValues: Map<String, Float> = ControlDefinitions.DEFAULT_CONTROL_STATE,
    val logs: List<LogEntry> = emptyList(),
    val protocolPreview: String = "[]",
    val statusMessage: String = "准备就绪",
    val presetActions: List<PresetAction> = PresetActions.all,
    val activePresetId: String? = null,
    val isPresetRunning: Boolean = false,
    val gestureTargetHand: GestureTargetHand = GestureTargetHand.AUTO,
    val gestureCameraState: GestureCameraState = GestureCameraState()
)

enum class ConnectionMode {
    WIFI,
    USB
}

class HandControlViewModel(application: Application) : AndroidViewModel(application) {
    private val webSocketService = WebSocketService()
    private val usbSerialService = UsbSerialService(application)

    private val _uiState = MutableStateFlow(
        HandControlUiState(protocolPreview = buildProtocolPreview(ControlDefinitions.DEFAULT_CONTROL_STATE))
    )
    val uiState: StateFlow<HandControlUiState> = _uiState

    private var sendDebounceJob: Job? = null
    private var presetJob: Job? = null
    private var latestWifiLogs: List<LogEntry> = emptyList()
    private var latestUsbLogs: List<LogEntry> = emptyList()
    private var lastGestureCompactState: Map<String, Float>? = null
    private var lastGestureSendTimeMs: Long = 0L
    private var gestureControlReady = false

    companion object {
        private const val GESTURE_SEND_INTERVAL_MS = 80L
        private const val GESTURE_MIN_DELTA = 1.5f
    }

    init {
        viewModelScope.launch {
            webSocketService.connectionState.collectLatest { state ->
                mutateState {
                    copy(
                        wifiConnected = state is ConnectionState.Connected,
                        connectedHandType = if (state is ConnectionState.Connected) state.handType else null,
                        connectedServer = if (state is ConnectionState.Connected) state.server else null,
                        statusMessage = when (state) {
                            is ConnectionState.Connected -> "WiFi 已连接 ${state.server}"
                            is ConnectionState.Connecting -> "WiFi 连接中..."
                            is ConnectionState.Error -> state.message
                            ConnectionState.Disconnected -> if (connectionMode == ConnectionMode.WIFI) "WiFi 未连接" else statusMessage
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            webSocketService.logs.collectLatest { logs ->
                latestWifiLogs = logs
                refreshLogs()
            }
        }

        viewModelScope.launch {
            webSocketService.wifiStatus.collectLatest { status ->
                mutateState {
                    copy(
                        wifiConfig = wifiConfig.copy(
                            currentWifiMode = status.mode,
                            currentIp = status.ip,
                            configuredStaSsid = status.staSsid?.takeIf { it.isNotBlank() }
                        ),
                        host = if (wifiConnected && status.ip.isNotBlank() && status.ip != "0.0.0.0") status.ip else host
                    )
                }
            }
        }

        viewModelScope.launch {
            webSocketService.jointStates.collectLatest { states ->
                if (states.isNotEmpty()) {
                    updateControlValues(compactStateFromJointStates(states))
                }
            }
        }

        viewModelScope.launch {
            usbSerialService.connectionState.collectLatest { state ->
                mutateState {
                    copy(
                        usbConnected = state is UsbConnectionState.Connected,
                        statusMessage = when (state) {
                            is UsbConnectionState.Connected -> "USB 已连接 ${state.deviceName} @ 921600"
                            is UsbConnectionState.Error -> state.message
                            UsbConnectionState.Disconnected -> if (connectionMode == ConnectionMode.USB) "USB 未连接" else statusMessage
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            usbSerialService.logs.collectLatest { logs ->
                latestUsbLogs = logs
                refreshLogs()
            }
        }

        viewModelScope.launch {
            usbSerialService.compactState.collectLatest { state ->
                if (state.isNotEmpty()) {
                    updateControlValues(state)
                }
            }
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        mutateState {
            copy(
                connectionMode = mode,
                logs = when (mode) {
                    ConnectionMode.WIFI -> latestWifiLogs
                    ConnectionMode.USB -> latestUsbLogs
                },
                statusMessage = when (mode) {
                    ConnectionMode.WIFI -> if (wifiConnected) statusMessage else "WiFi 未连接"
                    ConnectionMode.USB -> if (usbConnected) statusMessage else "USB 未连接"
                }
            )
        }
    }

    fun setHost(host: String) {
        mutateState { copy(host = host) }
    }

    fun setPort(port: String) {
        mutateState { copy(port = port.filter { it.isDigit() }.take(5)) }
    }

    fun cycleConnectionPanelVisibility() {
        mutateState {
            copy(
                connectionPanelVisibility = when (connectionPanelVisibility) {
                    ConnectionPanelVisibility.EXPANDED -> ConnectionPanelVisibility.COLLAPSED
                    ConnectionPanelVisibility.COLLAPSED -> ConnectionPanelVisibility.HIDDEN
                    ConnectionPanelVisibility.HIDDEN -> ConnectionPanelVisibility.EXPANDED
                }
            )
        }
    }

    fun setConnectionPanelVisibility(visibility: ConnectionPanelVisibility) {
        mutateState { copy(connectionPanelVisibility = visibility) }
    }

    fun setStaSsid(value: String) {
        mutateState { copy(wifiConfig = wifiConfig.copy(staSsid = value)) }
    }

    fun setStaPassword(value: String) {
        mutateState { copy(wifiConfig = wifiConfig.copy(staPassword = value)) }
    }

    fun connect() {
        resetGestureSendState()
        val state = _uiState.value
        when (state.connectionMode) {
            ConnectionMode.WIFI -> {
                val host = state.host.trim().ifBlank { "192.168.4.1" }
                val port = state.port.toIntOrNull() ?: 8765
                webSocketService.connect(host, port)
            }
            ConnectionMode.USB -> {
                usbSerialService.findAndConnect()
            }
        }
    }

    fun disconnect() {
        presetJob?.cancel()
        resetGestureSendState()
        mutateState { copy(isPresetRunning = false, activePresetId = null) }
        when (_uiState.value.connectionMode) {
            ConnectionMode.WIFI -> webSocketService.disconnect()
            ConnectionMode.USB -> usbSerialService.disconnect()
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
        when (_uiState.value.connectionMode) {
            ConnectionMode.WIFI -> webSocketService.sendHoming()
            ConnectionMode.USB -> usbSerialService.sendHoming()
        }
    }

    fun sendAllZeros() {
        val zeros = ControlDefinitions.DEFAULT_CONTROL_STATE.mapValues { 0f }
        mutateState { copy(controlValues = zeros) }
        sendCurrentState()
    }

    fun requestStates() {
        when (_uiState.value.connectionMode) {
            ConnectionMode.WIFI -> webSocketService.requestStates()
            ConnectionMode.USB -> usbSerialService.requestStates()
        }
    }

    fun requestWifiStatus() {
        if (_uiState.value.connectionMode == ConnectionMode.WIFI) {
            webSocketService.requestWifiStatus()
        }
    }

    fun applyStaConfig() {
        val state = _uiState.value
        val wifiConfig = state.wifiConfig
        if (state.connectionMode != ConnectionMode.WIFI || !state.wifiConnected) {
            mutateState { copy(statusMessage = "WiFi 未连接，无法下发配置") }
            return
        }
        if (state.connectedServer != "192.168.4.1:8765") {
            mutateState { copy(statusMessage = "仅连接设备默认 AP 时允许下发 WiFi 配置") }
            return
        }
        val sentConfig = webSocketService.sendWifiConfig(wifiConfig.staSsid, wifiConfig.staPassword)
        val sentConnect = sentConfig && webSocketService.connectSta()
        if (sentConnect) {
            mutateState {
                copy(
                    statusMessage = "已发送 WiFi 配置，等待设备切换 STA",
                    wifiConfig = wifiConfig.copy(
                        staPassword = "",
                        configuredStaSsid = wifiConfig.staSsid
                    )
                )
            }
        } else {
            mutateState { copy(statusMessage = "WiFi 配置发送失败，请检查连接") }
        }
    }

    fun switchDeviceToAp() {
        val state = _uiState.value
        if (state.connectionMode != ConnectionMode.WIFI || !state.wifiConnected) {
            mutateState { copy(statusMessage = "WiFi 未连接，无法切回 AP") }
            return
        }
        if (webSocketService.switchToAp()) {
            mutateState {
                copy(
                    host = "192.168.4.1",
                    port = "8765",
                    statusMessage = "已请求设备切回 AP 模式"
                )
            }
        } else {
            mutateState { copy(statusMessage = "切回 AP 失败，请检查连接") }
        }
    }

    fun clearStaConfig() {
        val state = _uiState.value
        if (state.connectionMode != ConnectionMode.WIFI || !state.wifiConnected) {
            mutateState { copy(statusMessage = "WiFi 未连接，无法清除 STA 配置") }
            return
        }
        if (webSocketService.clearWifiConfig()) {
            mutateState {
                copy(
                    statusMessage = "已清除设备 STA 配置",
                    wifiConfig = wifiConfig.copy(staSsid = "", staPassword = "", configuredStaSsid = null)
                )
            }
        } else {
            mutateState { copy(statusMessage = "清除 STA 配置失败，请检查连接") }
        }
    }

    fun runPreset(presetId: String) {
        val preset = PresetActions.find(presetId) ?: return
        if (!isConnected()) {
            mutateState { copy(statusMessage = "请先连接再执行预设动作") }
            return
        }

        presetJob?.cancel()
        presetJob = viewModelScope.launch {
            mutateState { copy(activePresetId = preset.id, isPresetRunning = true, statusMessage = "执行预设：${preset.label}") }
            try {
                preset.steps.forEach { step ->
                    mutateState { copy(controlValues = step.values) }
                    sendState(step.values, step.durationMs)
                    delay(step.durationMs.toLong())
                }
                mutateState { copy(statusMessage = "预设完成：${preset.label}") }
            } finally {
                mutateState { copy(activePresetId = null, isPresetRunning = false) }
            }
        }
    }

    fun clearLogs() {
        webSocketService.clearLogs()
        usbSerialService.clearLogs()
        latestWifiLogs = emptyList()
        latestUsbLogs = emptyList()
        refreshLogs()
    }

    private fun sendCurrentState() {
        sendState(_uiState.value.controlValues, ControlDefinitions.DEFAULT_DURATION_MS)
    }

    private fun sendState(values: Map<String, Float>, durationMs: Int) {
        val state = _uiState.value
        when (state.connectionMode) {
            ConnectionMode.WIFI -> {
                if (state.wifiConnected) {
                    webSocketService.sendCompactState(values, durationMs)
                }
            }
            ConnectionMode.USB -> {
                if (state.usbConnected) {
                    usbSerialService.sendCompactState(values)
                }
            }
        }
    }

    private fun updateControlValues(values: Map<String, Float>) {
        mutateState { copy(controlValues = ControlDefinitions.DEFAULT_CONTROL_STATE + values) }
    }

    private fun refreshLogs() {
        mutateState {
            copy(logs = when (connectionMode) {
                ConnectionMode.WIFI -> latestWifiLogs
                ConnectionMode.USB -> latestUsbLogs
            })
        }
    }

    private fun isConnected(): Boolean {
        val state = _uiState.value
        return when (state.connectionMode) {
            ConnectionMode.WIFI -> state.wifiConnected
            ConnectionMode.USB -> state.usbConnected
        }
    }

    private fun mutateState(transform: HandControlUiState.() -> HandControlUiState) {
        val next = _uiState.value.transform()
        _uiState.value = next.copy(protocolPreview = buildProtocolPreview(next.controlValues))
    }

    override fun onCleared() {
        super.onCleared()
        webSocketService.disconnect()
        usbSerialService.release()
    }

    fun setGestureTargetHand(targetHand: GestureTargetHand) {
        resetGestureSendState()
        mutateState {
            copy(
                gestureTargetHand = targetHand,
                gestureCameraState = gestureCameraState.copy(
                    targetHand = targetHand,
                    targetHandMatched = targetHand.matches(gestureCameraState.handedness)
                )
            )
        }
    }

    fun updateGestureCameraState(state: GestureCameraState) {
        val targetHand = _uiState.value.gestureTargetHand
        mutateState {
            copy(
                gestureCameraState = state.copy(
                    targetHand = targetHand,
                    targetHandMatched = targetHand.matches(state.handedness)
                )
            )
        }
    }

    fun fingerAnglesToCompactState(angles: FingerAngles): Map<String, Float> {
        return mapOf(
            "thumb_cmc_abd" to angles.thumbAbd.coerceIn(0f, 100f),
            "thumb_cmc_flex" to angles.thumbCmcFlex.coerceIn(0f, 55f),
            "thumb_mcp_ip" to angles.thumbTendon.coerceIn(0f, 90f),
            "index_flexion" to angles.indexTendon.coerceIn(0f, 90f),
            "middle_flexion" to angles.middleTendon.coerceIn(0f, 90f),
            "ring_flexion" to angles.ringTendon.coerceIn(0f, 90f),
            "pinky_flexion" to angles.pinkyTendon.coerceIn(0f, 90f)
        )
    }

    fun resetGestureSendState() {
        lastGestureCompactState = null
        lastGestureSendTimeMs = 0L
        gestureControlReady = false
    }

    fun markGestureControlReady() {
        if (!gestureControlReady) {
            gestureControlReady = true
            lastGestureCompactState = null
            lastGestureSendTimeMs = 0L
        }
    }

    fun updateControlValuesFromGesture(angles: FingerAngles) {
        val compactState = fingerAnglesToCompactState(angles)
        val now = System.currentTimeMillis()
        val previous = lastGestureCompactState
        val changedEnough = previous == null || compactState.any { (key, value) ->
            abs((previous[key] ?: value) - value) >= GESTURE_MIN_DELTA
        }
        val intervalReached = now - lastGestureSendTimeMs >= GESTURE_SEND_INTERVAL_MS
        val shouldSend = if (!gestureControlReady) {
            false
        } else if (previous == null) {
            true
        } else {
            changedEnough && intervalReached
        }

        if (!shouldSend) {
            return
        }

        gestureControlReady = true
        lastGestureCompactState = compactState
        lastGestureSendTimeMs = now
        updateControlValues(compactState)
        sendCurrentState()
    }
}
