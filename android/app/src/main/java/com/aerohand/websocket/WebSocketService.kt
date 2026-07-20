package com.aerohand.websocket

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WebSocketService {
    companion object {
        private const val REALTIME_MAX_QUEUE_BYTES = 4096L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val connectionToken = AtomicInteger(0)
    private val commandSequence = AtomicLong(0L)
    private var serialBusyHintLogged = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _jointStates = MutableStateFlow<Map<String, Float>>(emptyMap())
    val jointStates: StateFlow<Map<String, Float>> = _jointStates

    private val _wifiStatus = MutableStateFlow(WifiStatus())
    val wifiStatus: StateFlow<WifiStatus> = _wifiStatus

    private val _wifiStatusEvents = MutableSharedFlow<WifiStatus>(extraBufferCapacity = 8)
    val wifiStatusEvents: SharedFlow<WifiStatus> = _wifiStatusEvents

    private val commandResponses = MutableSharedFlow<CommandResponse>(extraBufferCapacity = 16)

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    private val _capabilities = MutableStateFlow(DeviceCapabilities())
    val capabilities: StateFlow<DeviceCapabilities> = _capabilities

    fun connect(host: String, port: Int) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting
        ) {
            disconnect()
        }

        val url = "ws://$host:$port/"
        val token = connectionToken.incrementAndGet()
        serialBusyHintLogged = false
        _deviceInfo.value = null
        _capabilities.value = DeviceCapabilities()
        _wifiStatus.value = WifiStatus()
        _connectionState.value = ConnectionState.Connecting
        addLog(LogEntry.Info("Connecting to $url...", timestamp()))

        val request = Request.Builder().url(url).build()
        val nextWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun isCurrent(): Boolean = connectionToken.get() == token

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) return
                _connectionState.value = ConnectionState.Connected("$host:$port")
                addLog(LogEntry.Info("Connected", timestamp()))
                requestStates()
                requestWifiStatus()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent()) return
                val response = parseCommandResponse(text)
                response?.let { commandResponses.tryEmit(it) }
                if (response?.isGenericExecutionAck != true) {
                    addLog(LogEntry.Receive(sanitizeLogMessage(text), timestamp()))
                }
                maybeLogSerialBusyHint(text)
                parseDeviceInfo(text)?.let { info ->
                    _deviceInfo.value = info
                    _capabilities.value = info.capabilities
                    val current = _connectionState.value
                    if (current is ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Connected(current.server, info.handType)
                    }
                }
                parseWifiStatus(text)?.let { status ->
                    _wifiStatus.value = status
                    _wifiStatusEvents.tryEmit(status)
                    val currentCapabilities = _capabilities.value
                    _capabilities.value = currentCapabilities.copy(
                        identified = true,
                        wifiProvisioning = currentCapabilities.wifiProvisioning || status.supportsStaticConfiguration
                    )
                }
                if (response?.success == false && response.errorMessage?.contains("Unknown command type") == true) {
                    _capabilities.value = DeviceCapabilities(identified = true)
                }
                parseStatesResponse(text)?.let { _jointStates.value = it }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent()) return
                _connectionState.value = ConnectionState.Disconnected
                addLog(LogEntry.Info("Disconnected", timestamp()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent()) return
                _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                addLog(LogEntry.Error("Error: ${t.message}", timestamp()))
            }
        })
        webSocket = nextWebSocket
    }

    fun disconnect() {
        connectionToken.incrementAndGet()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun sendHoming(): Boolean {
        return sendInternal(Commands.homing())
    }

    fun requestStates(): Boolean {
        return sendInternal(Commands.getStates())
    }

    fun requestWifiStatus(): Boolean {
        return sendInternal(Commands.getWifiStatus())
    }

    suspend fun provisionWifi(
        request: WifiProvisioningRequest,
        confirmationTimeoutMs: Long = 2_500L
    ): WifiProvisioningResult = coroutineScope {
        val configRequestId = nextRequestId()
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(confirmationTimeoutMs) {
                wifiStatusEvents.first { status ->
                    isWifiProvisioningConfirmation(request, configRequestId, status)
                }
            }
        }
        val configAck = async(start = CoroutineStart.UNDISPATCHED) {
            awaitCommandResponse("wifi_config_set", configRequestId, confirmationTimeoutMs)
        }
        if (!sendInternal(Commands.setWifiConfig(request, configRequestId))) {
            confirmation.cancel()
            configAck.cancel()
            return@coroutineScope WifiProvisioningResult.Failure("WiFi 配置发送失败，请检查连接")
        }
        val configResponse = configAck.await()
        if (configResponse == null) {
            confirmation.cancel()
            return@coroutineScope WifiProvisioningResult.Failure("设备未确认 WiFi 配置指令；请检查固件版本与连接")
        }
        if (!configResponse.success) {
            confirmation.cancel()
            return@coroutineScope WifiProvisioningResult.Failure(
                configResponse.errorMessage ?: "设备拒绝 WiFi 配置"
            )
        }
        if (confirmation.await() == null) {
            return@coroutineScope WifiProvisioningResult.Failure(
                "设备未确认 WiFi 配置；当前固件可能过旧或已拒绝参数"
            )
        }
        when (
            val switchResult = sendAndAwaitCommand(
                commandType = "wifi_connect_sta",
                payload = Commands::connectSta,
                timeoutMs = confirmationTimeoutMs
            )
        ) {
            DeviceCommandResult.Success -> WifiProvisioningResult.SwitchScheduled(request.staticIp)
            is DeviceCommandResult.Failure -> WifiProvisioningResult.Failure(
                "配置已保存，但${switchResult.message}"
            )
        }
    }

    suspend fun switchToApConfirmed(timeoutMs: Long = 2_500L): DeviceCommandResult {
        return sendAndAwaitCommand("wifi_start_ap", Commands::startAp, timeoutMs)
    }

    suspend fun clearWifiConfigConfirmed(timeoutMs: Long = 2_500L): DeviceCommandResult {
        return sendAndAwaitCommand("wifi_clear_sta", Commands::clearWifiConfig, timeoutMs)
    }

    fun sendCompactState(
        compactState: Map<String, Float>,
        durationMs: Int = ControlDefinitions.DEFAULT_DURATION_MS,
        transport: ControlTransport = ControlTransport.ACTUATOR,
        logControl: Boolean = true
    ): Boolean {
        if (!logControl && (webSocket?.queueSize() ?: 0L) > REALTIME_MAX_QUEUE_BYTES) {
            return false
        }
        val payload = when (transport) {
            ControlTransport.ACTUATOR -> buildActuatorControlPayload(compactState, durationMs)
            ControlTransport.MULTI_JOINT -> buildMultiJointControlPayload(compactState, durationMs)
        }
        val sent = sendInternal(payload, logPayload = false)
        if (sent && logControl) {
            addLog(LogEntry.Send(controlLogMessage(transport, compactState, durationMs), timestamp()))
        }
        return sent
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun sendInternal(json: String, logPayload: Boolean = true): Boolean {
        val sent = webSocket?.send(json) ?: false
        if (sent) {
            if (logPayload) {
                addLog(LogEntry.Send(sanitizeLogMessage(json), timestamp()))
            }
        } else {
            addLog(LogEntry.Error("Send failed: socket not ready", timestamp()))
        }
        return sent
    }

    private suspend fun sendAndAwaitCommand(
        commandType: String,
        payload: (String?) -> String,
        timeoutMs: Long
    ): DeviceCommandResult = coroutineScope {
        val requestId = nextRequestId()
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            awaitCommandResponse(commandType, requestId, timeoutMs)
        }
        if (!sendInternal(payload(requestId))) {
            response.cancel()
            return@coroutineScope DeviceCommandResult.Failure("指令发送失败，请检查连接")
        }
        val commandResponse = response.await()
            ?: return@coroutineScope DeviceCommandResult.Failure("设备确认超时，请检查固件版本与连接")
        if (commandResponse.success) {
            DeviceCommandResult.Success
        } else {
            DeviceCommandResult.Failure(commandResponse.errorMessage ?: "设备拒绝指令")
        }
    }

    private suspend fun awaitCommandResponse(
        commandType: String,
        requestId: String,
        timeoutMs: Long
    ): CommandResponse? {
        return withTimeoutOrNull(timeoutMs) {
            commandResponses.first { response ->
                response.commandType == commandType && response.requestId == requestId
            }
        }
    }

    private fun nextRequestId(): String {
        return "${connectionToken.get()}-${commandSequence.incrementAndGet()}"
    }

    private fun sanitizeLogMessage(message: String): String {
        return try {
            val json = JSONObject(message)
            if (json.optString("type") == "wifi_config_set") {
                val data = json.optJSONObject("data")
                data?.put("sta_password", "***")
                json.toString()
            } else {
                message
            }
        } catch (_: Exception) {
            message
        }
    }

    private fun maybeLogSerialBusyHint(message: String) {
        if (serialBusyHintLogged || !message.contains("Control busy: serial source active")) {
            return
        }
        serialBusyHintLogged = true
        addLog(
            LogEntry.Info(
                "提示：固件检测到电脑 USB 串口仍在控制，请关闭电脑端 UI/串口监视器后重启设备，再用 WiFi 控制",
                timestamp()
            )
        )
    }

    private fun controlLogMessage(
        transport: ControlTransport,
        compactState: Map<String, Float>,
        durationMs: Int
    ): String {
        val commandType = when (transport) {
            ControlTransport.ACTUATOR -> "actuator_control"
            ControlTransport.MULTI_JOINT -> "multi_joint_control"
        }
        val values = ControlDefinitions.COMPACT_CONTROLS.joinToString(",") { control ->
            val value = compactState[control.id] ?: control.defaultValue
            "${control.id}=${value.toInt()}"
        }
        return "Sent $commandType duration=${durationMs}ms values=$values"
    }

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun addLog(entry: LogEntry) {
        _logs.value = (_logs.value + entry).takeLast(120)
    }
}
