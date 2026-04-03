package com.aerohand.websocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
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

    fun connect(host: String, port: Int) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting
        ) {
            disconnect()
        }

        val url = "ws://$host:$port"
        _connectionState.value = ConnectionState.Connecting
        addLog(LogEntry.Info("Connecting to $url...", timestamp()))

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected("$host:$port")
                addLog(LogEntry.Info("Connected", timestamp()))
                sendInternal(Commands.getStates())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                addLog(LogEntry.Receive(text, timestamp()))
                parseMessage(text)
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

    fun sendHoming() {
        sendInternal(Commands.homing())
    }

    fun requestStates() {
        sendInternal(Commands.getStates())
    }

    fun sendCompactState(compactState: Map<String, Float>) {
        sendInternal(buildPayload(compactState))
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun sendInternal(json: String) {
        val sent = webSocket?.send(json) ?: false
        if (sent) {
            addLog(LogEntry.Send(json, timestamp()))
        } else {
            addLog(LogEntry.Error("Send failed: socket not ready", timestamp()))
        }
    }

    private fun parseMessage(text: String) {
        try {
            val json = JSONObject(text)
            if (json.optString("type") != "states_response") {
                return
            }

            val statesArray = when (val data = json.opt("data")) {
                is JSONArray -> data
                is JSONObject -> data.optJSONArray("joints") ?: JSONArray()
                else -> JSONArray()
            }

            val states = mutableMapOf<String, Float>()
            for (i in 0 until statesArray.length()) {
                val joint = statesArray.optJSONObject(i) ?: continue
                val jointId = joint.optString("joint_id")
                val angle = joint.optDouble("angle", 0.0).toFloat()
                if (jointId.isNotBlank()) {
                    states[jointId] = angle
                }
            }
            _jointStates.value = states
        } catch (e: Exception) {
            addLog(LogEntry.Error("Parse error: ${e.message}", timestamp()))
        }
    }

    private fun buildPayload(compactState: Map<String, Float>): String {
        val joints = JSONArray().apply {
            put(joint("thumb_proximal", compactState["thumb_cmc_flex"] ?: 0f))
            put(joint("thumb_distal", compactState["thumb_mcp_ip"] ?: 0f))

            listOf("index", "middle", "ring", "pinky").forEach { finger ->
                val value = compactState["${finger}_flexion"] ?: 0f
                put(joint("${finger}_proximal", value))
                put(joint("${finger}_middle", value))
                put(joint("${finger}_distal", value))
            }

            put(
                joint(
                    "thumb_rotation",
                    mapRange(
                        compactState["thumb_cmc_abd"] ?: 0f,
                        0f,
                        100f,
                        ControlDefinitions.THUMB_ROTATION_MIN,
                        ControlDefinitions.THUMB_ROTATION_MAX
                    )
                )
            )
        }

        return JSONObject().apply {
            put("type", "multi_joint_control")
            put("timestamp", System.currentTimeMillis())
            put(
                "data",
                JSONObject().apply {
                    put("joints", joints)
                    put("duration_ms", ControlDefinitions.DEFAULT_DURATION_MS)
                }
            )
        }.toString()
    }

    private fun joint(jointId: String, angle: Float): JSONObject {
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

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun addLog(entry: LogEntry) {
        _logs.value = (_logs.value + entry).takeLast(100)
    }
}
