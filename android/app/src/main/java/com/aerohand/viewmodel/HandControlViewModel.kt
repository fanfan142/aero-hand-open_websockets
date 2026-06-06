package com.aerohand.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerohand.gesture.FingerAngles
import com.aerohand.gesture.GestureCameraState
import com.aerohand.gesture.GestureTargetHand
import com.aerohand.usb.UsbConnectionState
import com.aerohand.usb.UsbSerialService
import com.aerohand.wifi.WifiNetworkItem
import com.aerohand.wifi.WifiScanService
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
import kotlin.coroutines.coroutineContext

data class WifiConfigUiState(
    val staSsid: String = "",
    val staPassword: String = "",
    val staticIp: String = "192.168.1.210",
    val gateway: String = "192.168.1.1",
    val subnet: String = "255.255.255.0",
    val dns1: String = "192.168.1.1",
    val dns2: String = "114.114.114.114",
    val currentWifiMode: String = "AP",
    val currentIp: String = "192.168.4.1",
    val configuredStaSsid: String? = null,
    val scanResults: List<WifiNetworkItem> = emptyList(),
    val isScanning: Boolean = false
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
    val isMacroRunning: Boolean = false,
    val presetRepeatCounts: Map<String, Int> = emptyMap(),
    val macroPresetIds: List<String> = emptyList(),
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
    private val wifiScanService = WifiScanService(application)

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
    private var lastGestureUiUpdateTimeMs: Long = 0L
    private var gestureControlReady = false
    private var pendingWifiTransitionMessage: String? = null

    companion object {
        private const val GESTURE_SEND_INTERVAL_MS = 16L
        private const val GESTURE_UI_UPDATE_INTERVAL_MS = 33L
        private const val GESTURE_DURATION_MS = 24
        private const val GESTURE_MIN_DELTA = 0.18f
    }

    init {
        viewModelScope.launch {
            webSocketService.connectionState.collectLatest { state ->
                val transitionMessage = pendingWifiTransitionMessage
                if (state is ConnectionState.Connected) {
                    pendingWifiTransitionMessage = null
                }
                mutateState {
                    copy(
                        wifiConnected = state is ConnectionState.Connected,
                        connectedHandType = if (state is ConnectionState.Connected) state.handType else null,
                        connectedServer = if (state is ConnectionState.Connected) state.server else null,
                        statusMessage = when (state) {
                            is ConnectionState.Connected -> "WiFi 已连接 ${state.server}"
                            is ConnectionState.Connecting -> "WiFi 连接中..."
                            is ConnectionState.Error -> transitionMessage ?: state.message
                            ConnectionState.Disconnected -> if (connectionMode == ConnectionMode.WIFI) {
                                transitionMessage ?: "WiFi 未连接"
                            } else {
                                statusMessage
                            }
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
                            configuredStaSsid = status.staSsid?.takeIf { it.isNotBlank() },
                            staticIp = status.staStaticIp,
                            gateway = status.staGateway,
                            subnet = status.staSubnet,
                            dns1 = status.staDns1,
                            dns2 = status.staDns2
                        ),
                        host = if (wifiConnected && status.ip.isNotBlank() && status.ip != "0.0.0.0") status.ip else host
                    )
                }
            }
        }

        viewModelScope.launch {
            webSocketService.jointStates.collectLatest { states ->
                if (states.isNotEmpty() && _uiState.value.connectionMode == ConnectionMode.WIFI) {
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

    fun setStaStaticIp(value: String) {
        mutateState { copy(wifiConfig = wifiConfig.copy(staticIp = value)) }
    }

    fun setStaGateway(value: String) {
        mutateState { copy(wifiConfig = wifiConfig.copy(gateway = value)) }
    }

    fun setStaSubnet(value: String) {
        mutateState { copy(wifiConfig = wifiConfig.copy(subnet = value)) }
    }

    fun setStaDns1(value: String) {
        mutateState { copy(wifiConfig = wifiConfig.copy(dns1 = value)) }
    }

    fun setStaDns2(value: String) {
        mutateState { copy(wifiConfig = wifiConfig.copy(dns2 = value)) }
    }

    fun scanWifiNetworks() {
        if (!hasWifiScanPermission()) {
            mutateState { copy(statusMessage = "请先授予附近 WiFi / 定位权限") }
            return
        }
        if (!wifiScanService.isWifiEnabled()) {
            mutateState { copy(statusMessage = "请先在系统设置中打开 WiFi") }
            return
        }
        mutateState { copy(wifiConfig = wifiConfig.copy(isScanning = true), statusMessage = "正在扫描 2.4GHz WiFi") }
        viewModelScope.launch {
            val results = runCatching { wifiScanService.scan2g() }.getOrElse { emptyList() }
            mutateState {
                copy(
                    wifiConfig = wifiConfig.copy(scanResults = results, isScanning = false),
                    statusMessage = if (results.isEmpty()) "未扫描到 2.4GHz WiFi，稍后重试" else "扫描到 ${results.size} 个 2.4GHz WiFi"
                )
            }
        }
    }

    private fun hasWifiScanPermission(): Boolean {
        val context = getApplication<Application>()
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearbyWifi = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        return fineLocation && nearbyWifi
    }

    fun connect() {
        resetGestureSendState()
        val state = _uiState.value
        when (state.connectionMode) {
            ConnectionMode.WIFI -> {
                val host = state.host.trim().ifBlank { "192.168.4.1" }
                val port = state.port.toIntOrNull()
                if (host.isBlank()) {
                    mutateState { copy(statusMessage = "Host 不能为空") }
                    return
                }
                if (port == null || port !in 1..65535) {
                    mutateState { copy(statusMessage = "端口范围应为 1-65535") }
                    return
                }
                webSocketService.connect(host, port)
            }
            ConnectionMode.USB -> {
                usbSerialService.findAndConnect()
            }
        }
    }

    fun disconnect() {
        presetJob?.cancel()
        presetJob = null
        resetGestureSendState()
        mutateState { copy(isPresetRunning = false, isMacroRunning = false, activePresetId = null) }
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
        val ssid = wifiConfig.staSsid.trim()
        val password = wifiConfig.staPassword.trim()
        if (ssid.isBlank()) {
            mutateState { copy(statusMessage = "请输入 STA WiFi 名称") }
            return
        }
        if (password.isBlank()) {
            mutateState { copy(statusMessage = "请输入 STA WiFi 密码") }
            return
        }
        val targetIp = wifiConfig.staticIp.ifBlank { "192.168.1.210" }
        val targetGateway = wifiConfig.gateway.ifBlank { "192.168.1.1" }
        val targetDns1 = wifiConfig.dns1.ifBlank { targetGateway }
        if (!isValidIpv4(targetIp)) {
            mutateState { copy(statusMessage = "静态 IP 格式不合法") }
            return
        }
        pendingWifiTransitionMessage = "已请求切 STA，请将手机 WiFi 换到 ${ssid}，然后连接 $targetIp:8765"
        val sentConfig = webSocketService.sendWifiConfig(
            ssid,
            password,
            targetIp,
            targetGateway,
            wifiConfig.subnet.ifBlank { "255.255.255.0" },
            targetDns1,
            wifiConfig.dns2.ifBlank { targetDns1 }
        )
        val sentConnect = sentConfig && webSocketService.connectSta()
        if (sentConnect) {
            mutateState {
                copy(
                    host = targetIp,
                    port = "8765",
                    statusMessage = pendingWifiTransitionMessage ?: statusMessage,
                    wifiConfig = wifiConfig.copy(
                        staPassword = "",
                        configuredStaSsid = ssid,
                        staticIp = targetIp,
                        gateway = targetGateway,
                        dns1 = targetDns1,
                        dns2 = wifiConfig.dns2.ifBlank { targetDns1 }
                    )
                )
            }
        } else {
            pendingWifiTransitionMessage = null
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
            pendingWifiTransitionMessage = "设备即将切回 AP，请将手机 WiFi 重连到 AeroHand_WIFI，然后重连 192.168.4.1:8765"
            mutateState {
                copy(
                    host = "192.168.4.1",
                    port = "8765",
                    statusMessage = pendingWifiTransitionMessage ?: statusMessage
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
            pendingWifiTransitionMessage = "已清除 STA 配置，设备即将切回 AP"
            mutateState {
                copy(
                    statusMessage = pendingWifiTransitionMessage ?: statusMessage,
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
        val repeatCount = _uiState.value.presetRepeatCounts[presetId] ?: 1
        runPresetSequence(listOf(preset to repeatCount), isMacro = false)
    }

    fun cyclePresetRepeat(presetId: String) {
        mutateState {
            val nextCount = ((presetRepeatCounts[presetId] ?: 1) % 3) + 1
            copy(
                presetRepeatCounts = presetRepeatCounts.toMutableMap().apply {
                    this[presetId] = nextCount
                }
            )
        }
    }

    fun togglePresetInMacro(presetId: String) {
        mutateState {
            val nextMacroPresetIds = if (macroPresetIds.contains(presetId)) {
                macroPresetIds.filterNot { it == presetId }
            } else {
                macroPresetIds + presetId
            }
            copy(macroPresetIds = nextMacroPresetIds)
        }
    }

    fun runMacro() {
        if (!isConnected()) {
            mutateState { copy(statusMessage = "请先连接再执行宏动作") }
            return
        }

        val state = _uiState.value
        val sequence = state.macroPresetIds.mapNotNull { presetId ->
            PresetActions.find(presetId)?.let { preset ->
                preset to (state.presetRepeatCounts[presetId] ?: 1)
            }
        }
        if (sequence.isEmpty()) {
            mutateState { copy(statusMessage = "请先勾选至少一个常规动作") }
            return
        }
        runPresetSequence(sequence, isMacro = true)
    }

    fun clearMacro() {
        mutateState {
            copy(
                macroPresetIds = emptyList(),
                statusMessage = if (macroPresetIds.isEmpty()) statusMessage else "已清空宏队列"
            )
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

    private fun runPresetSequence(sequence: List<Pair<PresetAction, Int>>, isMacro: Boolean) {
        if (sequence.isEmpty()) {
            return
        }

        presetJob?.cancel()
        val initialStatus = if (isMacro) {
            "执行宏动作：${sequence.size} 项"
        } else {
            "执行预设：${sequence.first().first.label}"
        }
        val job = viewModelScope.launch {
            mutateState {
                copy(
                    activePresetId = sequence.first().first.id,
                    isPresetRunning = true,
                    isMacroRunning = isMacro,
                    statusMessage = initialStatus
                )
            }
            try {
                sequence.forEachIndexed { actionIndex, (preset, repeatCount) ->
                    repeat(repeatCount) { round ->
                        mutateState {
                            copy(
                                activePresetId = preset.id,
                                statusMessage = if (isMacro) {
                                    "宏 ${actionIndex + 1}/${sequence.size} · ${preset.label} x${round + 1}/$repeatCount"
                                } else {
                                    "执行预设：${preset.label} x${round + 1}/$repeatCount"
                                }
                            )
                        }
                        preset.steps.forEach { step ->
                            mutateState { copy(controlValues = step.values) }
                            sendState(step.values, step.durationMs)
                            delay(step.durationMs.toLong())
                        }
                    }
                }
                mutateState {
                    copy(
                        statusMessage = if (isMacro) "宏动作执行完成" else "预设完成：${sequence.first().first.label}"
                    )
                }
            } finally {
                val currentJob = coroutineContext[Job]
                if (presetJob === currentJob) {
                    presetJob = null
                    mutateState {
                        copy(
                            activePresetId = null,
                            isPresetRunning = false,
                            isMacroRunning = false
                        )
                    }
                }
            }
        }
        presetJob = job
    }

    private fun sendState(values: Map<String, Float>, durationMs: Int, logControl: Boolean = true): Boolean {
        val state = _uiState.value
        return when (state.connectionMode) {
            ConnectionMode.WIFI -> {
                if (state.wifiConnected) {
                    webSocketService.sendCompactState(values, durationMs, logControl = logControl)
                } else {
                    false
                }
            }
            ConnectionMode.USB -> {
                if (state.usbConnected) {
                    usbSerialService.sendCompactState(values)
                    true
                } else {
                    false
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

    private fun isValidIpv4(value: String): Boolean {
        return value.split(".").let { parts ->
            parts.size == 4 && parts.all { segment ->
                segment.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
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
        lastGestureUiUpdateTimeMs = 0L
        gestureControlReady = false
    }

    fun markGestureControlReady() {
        if (!gestureControlReady) {
            gestureControlReady = true
            lastGestureCompactState = null
            lastGestureSendTimeMs = 0L
            lastGestureUiUpdateTimeMs = 0L
        }
    }

    fun updateControlValuesFromGesture(angles: FingerAngles) {
        val rawCompactState = fingerAnglesToCompactState(angles)
        val previous = lastGestureCompactState
        val compactState = rawCompactState
        val now = System.currentTimeMillis()
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

        if (sendState(compactState, GESTURE_DURATION_MS, logControl = false)) {
            gestureControlReady = true
            lastGestureCompactState = compactState
            lastGestureSendTimeMs = now
            if (now - lastGestureUiUpdateTimeMs >= GESTURE_UI_UPDATE_INTERVAL_MS) {
                lastGestureUiUpdateTimeMs = now
                updateControlValues(compactState)
            }
        }
    }
}
