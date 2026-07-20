package com.aerohand.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerohand.gesture.FingerAngles
import com.aerohand.gesture.GestureCommandScheduler
import com.aerohand.gesture.GestureTargetHand
import com.aerohand.usb.UsbConnectionState
import com.aerohand.usb.UsbSerialService
import com.aerohand.wifi.WifiNetworkItem
import com.aerohand.wifi.WifiScanService
import com.aerohand.websocket.ConnectionState
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.ControlTransport
import com.aerohand.websocket.DeviceCapabilities
import com.aerohand.websocket.DeviceCommandResult
import com.aerohand.websocket.LogEntry
import com.aerohand.websocket.PresetAction
import com.aerohand.websocket.PresetActions
import com.aerohand.websocket.WebSocketService
import com.aerohand.websocket.WifiProvisioningRequest
import com.aerohand.websocket.WifiProvisioningResult
import com.aerohand.websocket.compactStateFromJointStates
import com.aerohand.websocket.validateWifiProvisioning
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    val isScanning: Boolean = false,
    val isProvisioning: Boolean = false
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
    val deviceCapabilities: DeviceCapabilities = DeviceCapabilities(),
    val firmwareVersion: String? = null,
    val controlTransport: ControlTransport = ControlTransport.MULTI_JOINT,
    val gestureTargetHand: GestureTargetHand = GestureTargetHand.AUTO,
    val controlValues: Map<String, Float> = ControlDefinitions.DEFAULT_CONTROL_STATE,
    val logs: List<LogEntry> = emptyList(),
    val statusMessage: String = "准备就绪",
    val presetActions: List<PresetAction> = PresetActions.all,
    val activePresetId: String? = null,
    val isPresetRunning: Boolean = false,
    val isMacroRunning: Boolean = false,
    val presetRepeatCounts: Map<String, Int> = emptyMap(),
    val macroPresetIds: List<String> = emptyList()
)

enum class ConnectionMode {
    WIFI,
    USB
}

class HandControlViewModel(application: Application) : AndroidViewModel(application) {
    private val webSocketService = WebSocketService()
    private val usbSerialService = UsbSerialService(application)
    private val wifiScanService = WifiScanService(application)

    private val _uiState = MutableStateFlow(HandControlUiState())
    val uiState: StateFlow<HandControlUiState> = _uiState

    private var sendDebounceJob: Job? = null
    private var presetJob: Job? = null
    private var wifiProvisionJob: Job? = null
    private var latestWifiLogs: List<LogEntry> = emptyList()
    private var latestUsbLogs: List<LogEntry> = emptyList()
    private val gestureCommandScheduler = GestureCommandScheduler()
    private var lastGestureUiUpdateTimeMs: Long = 0L
    private var pendingWifiTransitionMessage: String? = null

    companion object {
        private const val GESTURE_UI_UPDATE_INTERVAL_MS = 100L
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
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            webSocketService.capabilities.collectLatest { capabilities ->
                mutateState {
                    copy(
                        deviceCapabilities = capabilities,
                        controlTransport = if (capabilities.actuatorControl) {
                            ControlTransport.ACTUATOR
                        } else {
                            ControlTransport.MULTI_JOINT
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            webSocketService.deviceInfo.collectLatest { info ->
                mutateState { copy(firmwareVersion = info?.firmwareVersion?.takeIf { it.isNotBlank() }) }
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

    fun setGestureTargetHand(targetHand: GestureTargetHand) {
        mutateState { copy(gestureTargetHand = targetHand) }
        resetGestureSendState()
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
        wifiProvisionJob?.cancel()
        wifiProvisionJob = null
        resetGestureSendState()
        mutateState {
            copy(
                isPresetRunning = false,
                isMacroRunning = false,
                activePresetId = null,
                wifiConfig = wifiConfig.copy(isProvisioning = false)
            )
        }
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
        if (!state.deviceCapabilities.identified) {
            mutateState { copy(statusMessage = "正在检测固件能力，请稍后重试") }
            return
        }
        if (!state.deviceCapabilities.wifiProvisioning) {
            mutateState { copy(statusMessage = "当前固件不支持 App 配网，请先刷入新版 WebSocket 固件") }
            return
        }
        if (wifiConfig.isProvisioning) return

        val request = WifiProvisioningRequest(
            ssid = wifiConfig.staSsid,
            password = wifiConfig.staPassword,
            staticIp = wifiConfig.staticIp.ifBlank { "192.168.1.210" },
            gateway = wifiConfig.gateway.ifBlank { "192.168.1.1" },
            subnet = wifiConfig.subnet.ifBlank { "255.255.255.0" },
            dns1 = wifiConfig.dns1.ifBlank { wifiConfig.gateway.ifBlank { "192.168.1.1" } },
            dns2 = wifiConfig.dns2.ifBlank { wifiConfig.dns1.ifBlank { wifiConfig.gateway.ifBlank { "192.168.1.1" } } }
        )
        val validationError = validateWifiProvisioning(request)
        if (validationError != null) {
            mutateState { copy(statusMessage = validationError) }
            return
        }
        resetGestureSendState()
        mutateState {
            copy(
                wifiConfig = wifiConfig.copy(isProvisioning = true),
                statusMessage = "正在下发 WiFi 配置并等待设备确认..."
            )
        }
        wifiProvisionJob?.cancel()
        wifiProvisionJob = viewModelScope.launch {
            when (val result = webSocketService.provisionWifi(request)) {
                is WifiProvisioningResult.SwitchScheduled -> {
                    pendingWifiTransitionMessage =
                        "设备已确认配置并开始切 STA；请将手机切到 ${request.ssid}，然后连接 ${result.targetIp}:8765"
                    mutateState {
                        copy(
                            host = result.targetIp,
                            port = "8765",
                            statusMessage = pendingWifiTransitionMessage ?: statusMessage,
                            wifiConfig = wifiConfig.copy(
                                staPassword = "",
                                configuredStaSsid = request.ssid,
                                staticIp = request.staticIp,
                                gateway = request.gateway,
                                subnet = request.subnet,
                                dns1 = request.dns1,
                                dns2 = request.dns2,
                                isProvisioning = false
                            )
                        )
                    }
                }
                is WifiProvisioningResult.Failure -> {
                    pendingWifiTransitionMessage = null
                    mutateState {
                        copy(
                            wifiConfig = wifiConfig.copy(isProvisioning = false),
                            statusMessage = result.message
                        )
                    }
                }
            }
            wifiProvisionJob = null
        }
    }

    fun switchDeviceToAp() {
        val state = _uiState.value
        if (state.connectionMode != ConnectionMode.WIFI || !state.wifiConnected) {
            mutateState { copy(statusMessage = "WiFi 未连接，无法切回 AP") }
            return
        }
        if (!state.deviceCapabilities.identified) {
            mutateState { copy(statusMessage = "正在检测固件能力，请稍后重试") }
            return
        }
        if (!state.deviceCapabilities.wifiProvisioning) {
            mutateState { copy(statusMessage = "当前固件不支持 App 切换 WiFi 模式") }
            return
        }
        if (state.wifiConfig.isProvisioning) return
        mutateState {
            copy(
                wifiConfig = wifiConfig.copy(isProvisioning = true),
                statusMessage = "正在等待设备确认切回 AP..."
            )
        }
        wifiProvisionJob = viewModelScope.launch {
            when (val result = webSocketService.switchToApConfirmed()) {
                DeviceCommandResult.Success -> {
                    pendingWifiTransitionMessage =
                        "设备已确认切回 AP；请将手机 WiFi 重连到 AeroHand_Right/Left，然后连接 192.168.4.1:8765"
                    mutateState {
                        copy(
                            host = "192.168.4.1",
                            port = "8765",
                            statusMessage = pendingWifiTransitionMessage ?: statusMessage,
                            wifiConfig = wifiConfig.copy(isProvisioning = false)
                        )
                    }
                }
                is DeviceCommandResult.Failure -> mutateState {
                    copy(
                        statusMessage = "切回 AP 失败：${result.message}",
                        wifiConfig = wifiConfig.copy(isProvisioning = false)
                    )
                }
            }
            wifiProvisionJob = null
        }
    }

    fun clearStaConfig() {
        val state = _uiState.value
        if (state.connectionMode != ConnectionMode.WIFI || !state.wifiConnected) {
            mutateState { copy(statusMessage = "WiFi 未连接，无法清除 STA 配置") }
            return
        }
        if (!state.deviceCapabilities.identified) {
            mutateState { copy(statusMessage = "正在检测固件能力，请稍后重试") }
            return
        }
        if (!state.deviceCapabilities.wifiProvisioning) {
            mutateState { copy(statusMessage = "当前固件不支持 App 清除 WiFi 配置") }
            return
        }
        if (state.wifiConfig.isProvisioning) return
        mutateState {
            copy(
                wifiConfig = wifiConfig.copy(isProvisioning = true),
                statusMessage = "正在等待设备确认清除 STA 配置..."
            )
        }
        wifiProvisionJob = viewModelScope.launch {
            when (val result = webSocketService.clearWifiConfigConfirmed()) {
                DeviceCommandResult.Success -> {
                    pendingWifiTransitionMessage = "设备已确认清除 STA 配置并切回 AP"
                    mutateState {
                        copy(
                            host = "192.168.4.1",
                            port = "8765",
                            statusMessage = pendingWifiTransitionMessage ?: statusMessage,
                            wifiConfig = wifiConfig.copy(
                                staSsid = "",
                                staPassword = "",
                                configuredStaSsid = null,
                                isProvisioning = false
                            )
                        )
                    }
                }
                is DeviceCommandResult.Failure -> mutateState {
                    copy(
                        statusMessage = "清除 STA 配置失败：${result.message}",
                        wifiConfig = wifiConfig.copy(isProvisioning = false)
                    )
                }
            }
            wifiProvisionJob = null
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
                if (state.wifiConnected && !state.wifiConfig.isProvisioning) {
                    webSocketService.sendCompactState(
                        values,
                        durationMs,
                        transport = state.controlTransport,
                        logControl = logControl
                    )
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
        _uiState.value = _uiState.value.transform()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketService.disconnect()
        usbSerialService.release()
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
        gestureCommandScheduler.reset()
        lastGestureUiUpdateTimeMs = 0L
    }

    fun markGestureControlReady() {
        gestureCommandScheduler.markReady()
    }

    fun updateControlValuesFromGesture(angles: FingerAngles, capturedAtMs: Long = 0L) {
        val now = capturedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val command = gestureCommandScheduler.plan(fingerAnglesToCompactState(angles), now) ?: return
        if (sendState(command.values, command.durationMs, logControl = false)) {
            gestureCommandScheduler.markSent(command, now)
            if (now - lastGestureUiUpdateTimeMs >= GESTURE_UI_UPDATE_INTERVAL_MS) {
                lastGestureUiUpdateTimeMs = now
                updateControlValues(command.values)
            }
        }
    }
}
