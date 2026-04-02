package com.aerohand.websocket

/**
 * Aero Hand Open 控制协议
 * 对应 HTML 中的 COMPACT_CONTROLS
 */
data class CompactControl(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val defaultValue: Float,
    val unit: String = "°"
)

/**
 * 7 通道控制定义
 */
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

/**
 * 关节控制数据
 */
data class JointControl(
    val joint_id: String,
    val angle: Float
)

/**
 * WebSocket 请求消息
 */
data class ControlRequest(
    val type: String = "multi_joint_control",
    val timestamp: Long = System.currentTimeMillis(),
    val data: ControlData
)

data class ControlData(
    val joints: List<JointControl>,
    val duration_ms: Int = ControlDefinitions.DEFAULT_DURATION_MS
)

/**
 * WebSocket 响应消息
 */
data class StatesResponse(
    val type: String,
    val success: Boolean,
    val timestamp: Long,
    val data: List<JointState>?
)

data class JointState(
    val joint_id: String,
    val angle: Float,
    val load: Float? = null
)

/**
 * 简化的命令请求
 */
object Commands {
    fun homing() = """{"type":"homing","timestamp":${System.currentTimeMillis()}}"""

    fun getStates() = """{"type":"get_states","timestamp":${System.currentTimeMillis()}}"""
}
