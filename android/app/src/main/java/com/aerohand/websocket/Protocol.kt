package com.aerohand.websocket

/**
 * Compact 7DoF control definition for Aero Hand Open.
 */
data class CompactControl(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val defaultValue: Float,
    val unit: String = "°"
)

object ControlDefinitions {
    val COMPACT_CONTROLS = listOf(
        CompactControl("thumb_cmc_abd", "拇指外展", 0f, 100f, 0f),
        CompactControl("thumb_cmc_flex", "拇指屈曲", 0f, 55f, 0f),
        CompactControl("thumb_mcp_ip", "拇指肌腱", 0f, 90f, 0f),
        CompactControl("index_flexion", "食指", 0f, 90f, 0f),
        CompactControl("middle_flexion", "中指", 0f, 90f, 0f),
        CompactControl("ring_flexion", "无名指", 0f, 90f, 0f),
        CompactControl("pinky_flexion", "小指", 0f, 90f, 0f)
    )

    const val DEFAULT_DURATION_MS = 500
    const val THUMB_ROTATION_MIN = -30f
    const val THUMB_ROTATION_MAX = 30f
}

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

object Commands {
    fun homing() = """{"type":"homing","timestamp":${System.currentTimeMillis()}}"""

    fun getStates() = """{"type":"get_states","timestamp":${System.currentTimeMillis()}}"""
}
