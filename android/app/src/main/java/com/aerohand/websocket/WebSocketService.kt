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
import java.util.concurrent.TimeUnit

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val server: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class LogEntry {
    abstract val message: String
    abstract val timestamp: String

    data class Send(override val message: String, override val timestamp: String) : LogEntry()
    data class Receive(override val message: String, override val timestamp: String) : LogEntry()
    data class Error(override val message: String, override val timestamp: String) : LogEntry()
    data class Info(override val message: String, override val timestamp: String) : LogEntry()
}

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

    private fun timestamp(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    fun connect(host: String, port: Int) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            disconnect()
        }

        val url = "ws://$host:$port"
        addLog(LogEntry.Info("Connecting to $url...", timestamp()))

        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected("$host:$port")
                addLog(LogEntry.Info("Connected!", timestamp()))
                // Request initial states
                send(Commands.getStates())
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

    fun sendCommand(command: String) {
        webSocket?.send(command)
        addLog(LogEntry.Send(command, timestamp()))
    }

    fun sendHoming() {
        sendCommand(Commands.homing())
    }

    fun sendAllZeros(compactState: Map<String, Float>) {
        val payload = buildPayload(compactState)
        sendCommand(payload)
    }

    fun requestStates() {
        sendCommand(Commands.getStates())
    }

    private fun send(json: String) {
        webSocket?.send(json)
        addLog(LogEntry.Send(json, timestamp()))
    }

    private fun parseMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "states_response" -> {
                    val data = json.optJSONArray("data") ?: return
                    val states = mutableMapOf<String, Float>()
                    for (i in 0 until data.length()) {
                        val joint = data.getJSONObject(i)
                        val jointId = joint.optString("joint_id")
                        val angle = joint.optDouble("angle", 0.0).toFloat()
                        if (jointId.isNotEmpty()) {
                            states[jointId] = angle
                        }
                    }
                    _jointStates.value = states
                }
            }
        } catch (e: Exception) {
            addLog(LogEntry.Error("Parse error: ${e.message}", timestamp()))
        }
    }

    private fun buildPayload(compactState: Map<String, Float>): String {
        val joints = mutableListOf<JSONObject>()

        // thumb_proximal = thumb_cmc_flex
        joints.add(JSONObject().apply {
            put("joint_id", "thumb_proximal")
            put("angle", compactState["thumb_cmc_flex"] ?: 0f)
        })

        // thumb_distal = thumb_mcp_ip
        joints.add(JSONObject().apply {
            put("joint_id", "thumb_distal")
            put("angle", compactState["thumb_mcp_ip"] ?: 0f)
        })

        // finger triples
        listOf("index", "middle", "ring", "pinky").forEach { finger ->
            val value = compactState["${finger}_flexion"] ?: 0f
            listOf("proximal", "middle", "distal").forEach { joint ->
                joints.add(JSONObject().apply {
                    put("joint_id", "${finger}_$joint")
                    put("angle", value)
                })
            }
        }

        // thumb_rotation from thumb_cmc_abd
        val thumbRotation = mapRange(
            compactState["thumb_cmc_abd"] ?: 0f,
            0f, 100f,
            ControlDefinitions.THUMB_ROTATION_MIN,
            ControlDefinitions.THUMB_ROTATION_MAX
        )
        joints.add(JSONObject().apply {
            put("joint_id", "thumb_rotation")
            put("angle", thumbRotation)
        })

        val payload = JSONObject().apply {
            put("type", "multi_joint_control")
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("joints", JSONArray(joints))
                put("duration_ms", ControlDefinitions.DEFAULT_DURATION_MS)
            })
        }

        return payload.toString()
    }

    private fun mapRange(value: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
        if (inMax == inMin) return outMin
        val normalized = (value - inMin) / (inMax - inMin)
        return outMin + normalized * (outMax - outMin)
    }

    private fun addLog(entry: LogEntry) {
        _logs.value = (_logs.value + entry).takeLast(100)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected
}
