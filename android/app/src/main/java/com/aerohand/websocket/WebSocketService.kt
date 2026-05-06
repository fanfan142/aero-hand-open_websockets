package com.aerohand.websocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class WebSocketService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _jointStates = MutableStateFlow<Map<String, Float>>(emptyMap())
    val jointStates: StateFlow<Map<String, Float>> = _jointStates

    private val _wifiStatus = MutableStateFlow(WifiStatus())
    val wifiStatus: StateFlow<WifiStatus> = _wifiStatus

    fun connect(host: String, port: Int) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting
        ) {
            disconnect()
        }

        val url = "ws://$host:$port/"
        _connectionState.value = ConnectionState.Connecting
        addLog(LogEntry.Info("Connecting to $url...", timestamp()))

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected("$host:$port")
                addLog(LogEntry.Info("Connected", timestamp()))
                requestStates()
                requestWifiStatus()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                addLog(LogEntry.Receive(sanitizeLogMessage(text), timestamp()))
                parseHandInfo(text)?.let { handType ->
                    val current = _connectionState.value
                    if (current is ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Connected(current.server, handType)
                    }
                }
                parseWifiStatus(text)?.let { _wifiStatus.value = it }
                parseStatesResponse(text)?.let { _jointStates.value = it }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
                addLog(LogEntry.Info("Disconnected", timestamp()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                addLog(LogEntry.Error("Error: ${t.message}", timestamp()))
            }
        })
    }

    fun disconnect() {
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

    fun sendWifiConfig(ssid: String, password: String): Boolean {
        return sendInternal(Commands.setWifiConfig(ssid, password))
    }

    fun connectSta(): Boolean {
        return sendInternal(Commands.connectSta())
    }

    fun switchToAp(): Boolean {
        return sendInternal(Commands.startAp())
    }

    fun clearWifiConfig(): Boolean {
        return sendInternal(Commands.clearWifiConfig())
    }

    fun sendCompactState(
        compactState: Map<String, Float>,
        durationMs: Int = ControlDefinitions.DEFAULT_DURATION_MS
    ): Boolean {
        return sendInternal(buildMultiJointControlPayload(compactState, durationMs))
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun sendInternal(json: String): Boolean {
        val sent = webSocket?.send(json) ?: false
        if (sent) {
            addLog(LogEntry.Send(sanitizeLogMessage(json), timestamp()))
        } else {
            addLog(LogEntry.Error("Send failed: socket not ready", timestamp()))
        }
        return sent
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

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun addLog(entry: LogEntry) {
        _logs.value = (_logs.value + entry).takeLast(120)
    }
}
