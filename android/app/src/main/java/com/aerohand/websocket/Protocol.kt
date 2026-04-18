package com.aerohand.websocket

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

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

data class PresetStep(
    val values: Map<String, Float>,
    val durationMs: Int = ControlDefinitions.DEFAULT_DURATION_MS
)

data class PresetAction(
    val id: String,
    val label: String,
    val subtitle: String,
    val steps: List<PresetStep>
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

    val DEFAULT_CONTROL_STATE: Map<String, Float> = COMPACT_CONTROLS.associate { it.id to it.defaultValue }

    val ACTUATION_LOWER_LIMITS = listOf(0f, 0f, -15.2789f, 0f, 0f, 0f, 0f)
    val ACTUATION_UPPER_LIMITS = listOf(100f, 104.1250f, 247.1500f, 288.1603f, 288.1603f, 288.1603f, 288.1603f)

    const val DEFAULT_DURATION_MS = 500
    const val THUMB_ROTATION_MIN = -30f
    const val THUMB_ROTATION_MAX = 30f
}

object SerialCommands {
    const val HOMING_MODE = 0x01
    const val CTRL_POS = 0x11
    const val GET_POS = 0x22
}

object PresetActions {
    private fun pose(vararg values: Float): Map<String, Float> = compactStateOf(values.toList())

    val all = listOf(
        PresetAction("open_palm", "张开", "自然张手", listOf(PresetStep(pose(10f, 10f, 10f, 10f, 10f, 10f, 10f), 450))),
        PresetAction("power_grasp", "抓握", "力量抓取", listOf(PresetStep(pose(100f, 55f, 30f, 60f, 60f, 60f, 60f), 500))),
        PresetAction("precision_pinch", "捏取", "精细夹捏", listOf(PresetStep(pose(60f, 40f, 50f, 60f, 10f, 10f, 10f), 450))),
        PresetAction("ok_gesture", "OK", "手势识别", listOf(PresetStep(pose(40f, 25f, 60f, 60f, 10f, 10f, 10f), 450))),
        PresetAction("victory", "剪刀手", "Victory", listOf(PresetStep(pose(30f, 15f, 10f, 10f, 10f, 80f, 80f), 450))),
        PresetAction("thumb_up", "点赞", "Thumbs up", listOf(PresetStep(pose(80f, 55f, 20f, 10f, 10f, 10f, 10f), 450))),
        PresetAction("rock", "石头", "Rock", listOf(PresetStep(pose(85f, 50f, 85f, 85f, 85f, 85f, 85f), 420))),
        PresetAction("paper", "布", "Paper", listOf(PresetStep(pose(15f, 10f, 10f, 10f, 10f, 10f, 10f), 420))),
        PresetAction("scissors", "剪刀", "Scissors", listOf(PresetStep(pose(20f, 10f, 85f, 10f, 10f, 10f, 10f), 420))),
        PresetAction(
            "counting",
            "数数",
            "逐指展开",
            listOf(
                PresetStep(pose(80f, 80f, 80f, 80f, 80f, 80f, 80f), 260),
                PresetStep(pose(80f, 80f, 80f, 10f, 80f, 80f, 80f), 260),
                PresetStep(pose(80f, 80f, 80f, 10f, 10f, 80f, 80f), 260),
                PresetStep(pose(80f, 80f, 80f, 10f, 10f, 10f, 80f), 260),
                PresetStep(pose(80f, 80f, 80f, 10f, 10f, 10f, 10f), 260),
                PresetStep(pose(10f, 10f, 10f, 10f, 10f, 10f, 10f), 260)
            )
        ),
        PresetAction(
            "fan_open",
            "扇形展开",
            "从拇指到小指",
            listOf(
                PresetStep(pose(75f, 75f, 75f, 75f, 75f, 75f, 75f), 220),
                PresetStep(pose(10f, 10f, 75f, 75f, 75f, 75f, 75f), 220),
                PresetStep(pose(10f, 10f, 10f, 75f, 75f, 75f, 75f), 220),
                PresetStep(pose(10f, 10f, 10f, 10f, 75f, 75f, 75f), 220),
                PresetStep(pose(10f, 10f, 10f, 10f, 10f, 75f, 75f), 220),
                PresetStep(pose(10f, 10f, 10f, 10f, 10f, 10f, 10f), 220)
            )
        ),
        PresetAction(
            "piano",
            "钢琴",
            "逐指弹奏",
            listOf(
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 150),
                PresetStep(pose(15f, 15f, 70f, 15f, 15f, 15f, 15f), 120),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 80),
                PresetStep(pose(15f, 15f, 15f, 70f, 15f, 15f, 15f), 120),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 80),
                PresetStep(pose(15f, 15f, 15f, 15f, 70f, 15f, 15f), 120),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 80),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 70f, 15f), 120),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 80),
                PresetStep(pose(15f, 15f, 15f, 15f, 70f, 15f, 15f), 120),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 80),
                PresetStep(pose(15f, 15f, 15f, 70f, 15f, 15f, 15f), 120),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 80),
                PresetStep(pose(15f, 15f, 70f, 15f, 15f, 15f, 15f), 120),
                PresetStep(pose(15f, 15f, 15f, 15f, 15f, 15f, 15f), 150)
            )
        ),
        PresetAction(
            "wave_meet",
            "波浪汇聚",
            "双向波浪交汇",
            listOf(
                // t=0: all flat
                PresetStep(pose(30f, 30f, 30f, 30f, 30f, 30f, 30f), 100),
                // t=π/4: index/middle up, ring/pinky down
                PresetStep(pose(30f, 30f, 85f, 85f, 10f, 10f, 10f), 100),
                // t=π/2: ring/pinky up, index/middle down
                PresetStep(pose(30f, 30f, 10f, 10f, 85f, 85f, 85f), 100),
                // t=3π/4: index/middle up again
                PresetStep(pose(30f, 30f, 85f, 85f, 10f, 10f, 10f), 100),
                // t=π: all flat
                PresetStep(pose(30f, 30f, 30f, 30f, 30f, 30f, 30f), 100),
                // t=5π/4: ring/pinky up, index/middle down
                PresetStep(pose(30f, 30f, 10f, 10f, 85f, 85f, 85f), 100),
                // t=3π/2: index/middle up, ring/pinky down
                PresetStep(pose(30f, 30f, 85f, 85f, 10f, 10f, 10f), 100),
                // t=7π/4: all flat
                PresetStep(pose(30f, 30f, 30f, 30f, 30f, 30f, 30f), 100),
                // t=2π: back to flat
                PresetStep(pose(10f, 10f, 10f, 10f, 10f, 10f, 10f), 200)
            )
        ),
        PresetAction(
            "finger_count_10",
            "数到10",
            "完整伸展计数",
            listOf(
                PresetStep(pose(80f, 80f, 80f, 80f, 80f, 80f, 80f), 300),
                PresetStep(pose(80f, 80f, 10f, 80f, 80f, 80f, 80f), 300),
                PresetStep(pose(80f, 80f, 10f, 10f, 80f, 80f, 80f), 300),
                PresetStep(pose(80f, 80f, 10f, 10f, 10f, 80f, 80f), 300),
                PresetStep(pose(80f, 80f, 10f, 10f, 10f, 10f, 80f), 300),
                PresetStep(pose(80f, 80f, 10f, 10f, 10f, 10f, 10f), 300),
                PresetStep(pose(80f, 80f, 80f, 10f, 10f, 10f, 10f), 300),
                PresetStep(pose(80f, 80f, 80f, 80f, 10f, 10f, 10f), 300),
                PresetStep(pose(80f, 80f, 80f, 80f, 80f, 10f, 10f), 300),
                PresetStep(pose(80f, 80f, 80f, 80f, 80f, 80f, 10f), 300),
                PresetStep(pose(80f, 80f, 80f, 80f, 80f, 80f, 80f), 300),
                PresetStep(pose(10f, 10f, 10f, 10f, 10f, 10f, 10f), 300)
            )
        )
    )

    fun find(id: String): PresetAction? = all.firstOrNull { it.id == id }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val server: String, val handType: String? = null) : ConnectionState()
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

private const val UINT16_MAX = 65535f
private const val MOTOR_PULLEY_RADIUS = 9f
private const val FINGER_MCP_FLEX_COEFF = 12.4912f
private const val FINGER_PIP_COEFF = 7.3211f
private const val FINGER_DIP_COEFF = 9.0f
private const val THUMB_FLEX_CMC_ABD_COEFF = 2.5f
private const val THUMB_FLEX_CMC_FLEX_COEFF = 12.4931f
private const val THUMB_IP_CMC_ABD_COEFF = 2.5f
private const val THUMB_IP_CMC_FLEX_COEFF = 2.5f
private const val THUMB_IP_MCP_COEFF = 9.4372f
private const val THUMB_IP_IP_COEFF = 12.5f
private val DEG_TO_RAD = (PI / 180.0).toFloat()
private val RAD_TO_DEG = (180.0 / PI).toFloat()

fun compactStateOf(values: List<Float>): Map<String, Float> {
    val ids = ControlDefinitions.COMPACT_CONTROLS.map { it.id }
    require(values.size == ids.size) { "Expected ${ids.size} compact control values, got ${values.size}" }
    return ids.zip(values).toMap()
}

fun buildMultiJointControlPayload(
    compactState: Map<String, Float>,
    durationMs: Int = ControlDefinitions.DEFAULT_DURATION_MS
): String {
    return JSONObject().apply {
        put("type", "multi_joint_control")
        put("timestamp", System.currentTimeMillis())
        put(
            "data",
            JSONObject().apply {
                put("joints", buildProtocolJoints(compactState))
                put("duration_ms", durationMs)
            }
        )
    }.toString()
}

fun buildProtocolPreview(compactState: Map<String, Float>): String = buildProtocolJoints(compactState).toString(2)

fun buildProtocolJoints(compactState: Map<String, Float>): JSONArray {
    return JSONArray().apply {
        put(jointJson("thumb_proximal", compactState["thumb_cmc_flex"] ?: 0f))
        put(jointJson("thumb_distal", compactState["thumb_mcp_ip"] ?: 0f))

        listOf("index", "middle", "ring", "pinky").forEach { finger ->
            val value = compactState["${finger}_flexion"] ?: 0f
            put(jointJson("${finger}_proximal", value))
            put(jointJson("${finger}_middle", value))
            put(jointJson("${finger}_distal", value))
        }

        put(
            jointJson(
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
}

fun buildSerialPositionControlFrame(compactState: Map<String, Float>): ByteArray {
    val payload = compactStateToActuations(compactState).mapIndexed { index, actuation ->
        val lower = ControlDefinitions.ACTUATION_LOWER_LIMITS[index]
        val upper = ControlDefinitions.ACTUATION_UPPER_LIMITS[index]
        val normalized = ((actuation.coerceIn(lower, upper) - lower) / (upper - lower)) * UINT16_MAX
        normalized.toInt().coerceIn(0, UINT16_MAX.toInt())
    }
    return buildSerialFrame(SerialCommands.CTRL_POS, payload)
}

fun buildSerialHomingFrame(): ByteArray = buildSerialFrame(SerialCommands.HOMING_MODE)

fun buildSerialGetPositionsFrame(): ByteArray = buildSerialFrame(SerialCommands.GET_POS)

fun compactStateToActuations(compactState: Map<String, Float>): List<Float> {
    val positions = compactStateToJointPositions(compactState)

    val thumbCmcAbd = positions[0]
    val thumbCmcFlex = positions[1]
    val thumbMcp = positions[2]
    val thumbIp = positions[3]

    val thumbCmcAbdActuation = thumbCmcAbd
    val thumbCmcFlexActuation = (
        THUMB_FLEX_CMC_ABD_COEFF * thumbCmcAbd +
            THUMB_FLEX_CMC_FLEX_COEFF * thumbCmcFlex
        ) / MOTOR_PULLEY_RADIUS
    val thumbTendonActuation = (
        THUMB_IP_CMC_ABD_COEFF * thumbCmcAbd -
            THUMB_IP_CMC_FLEX_COEFF * thumbCmcFlex +
            THUMB_IP_MCP_COEFF * thumbMcp +
            THUMB_IP_IP_COEFF * thumbIp
        ) / MOTOR_PULLEY_RADIUS

    val fingerActuations = listOf(4, 7, 10, 13).map { offset ->
        val mcp = positions[offset]
        val pip = positions[offset + 1]
        val dip = positions[offset + 2]
        (
            FINGER_MCP_FLEX_COEFF * mcp +
                FINGER_PIP_COEFF * pip +
                FINGER_DIP_COEFF * dip
            ) / MOTOR_PULLEY_RADIUS
    }

    return listOf(thumbCmcAbdActuation, thumbCmcFlexActuation, thumbTendonActuation) + fingerActuations
}

fun compactStateFromActuations(actuationsDegrees: List<Float>): Map<String, Float> {
    require(actuationsDegrees.size == 7) { "Expected 7 actuation values, got ${actuationsDegrees.size}" }

    val actuations = actuationsDegrees.map { it * DEG_TO_RAD }

    val cmcAbdJoint = actuations[0]
    val flexTendonMovement = actuations[1] * MOTOR_PULLEY_RADIUS
    val cmcFlexJoint = (
        flexTendonMovement - THUMB_FLEX_CMC_ABD_COEFF * cmcAbdJoint
        ) / THUMB_FLEX_CMC_FLEX_COEFF

    val thumbTendonMovement = actuations[2] * MOTOR_PULLEY_RADIUS
    val mcpIpJoint = (
        thumbTendonMovement - THUMB_IP_CMC_ABD_COEFF * cmcAbdJoint + THUMB_IP_CMC_FLEX_COEFF * cmcFlexJoint
        ) / (THUMB_IP_MCP_COEFF + THUMB_IP_IP_COEFF)

    fun fingerJoint(index: Int): Float {
        val tendonMovement = actuations[index] * MOTOR_PULLEY_RADIUS
        return tendonMovement / (FINGER_MCP_FLEX_COEFF + FINGER_PIP_COEFF + FINGER_DIP_COEFF)
    }

    return mapOf(
        "thumb_cmc_abd" to (cmcAbdJoint * RAD_TO_DEG).coerceIn(0f, 100f),
        "thumb_cmc_flex" to (cmcFlexJoint * RAD_TO_DEG).coerceIn(0f, 55f),
        "thumb_mcp_ip" to (mcpIpJoint * RAD_TO_DEG).coerceIn(0f, 90f),
        "index_flexion" to (fingerJoint(3) * RAD_TO_DEG).coerceIn(0f, 90f),
        "middle_flexion" to (fingerJoint(4) * RAD_TO_DEG).coerceIn(0f, 90f),
        "ring_flexion" to (fingerJoint(5) * RAD_TO_DEG).coerceIn(0f, 90f),
        "pinky_flexion" to (fingerJoint(6) * RAD_TO_DEG).coerceIn(0f, 90f)
    )
}

fun parseSerialActuationResponse(frame: ByteArray): Map<String, Float>? {
    if (frame.size != 16) {
        return null
    }

    val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
    val opcode = buffer.get().toInt() and 0xFF
    buffer.get()
    if (opcode != SerialCommands.GET_POS) {
        return null
    }

    val actuations = List(7) { index ->
        val raw = buffer.short.toInt() and 0xFFFF
        val lower = ControlDefinitions.ACTUATION_LOWER_LIMITS[index]
        val upper = ControlDefinitions.ACTUATION_UPPER_LIMITS[index]
        lower + (raw / UINT16_MAX) * (upper - lower)
    }
    return compactStateFromActuations(actuations)
}

fun compactStateToJointPositions(compactState: Map<String, Float>): List<Float> {
    val thumbAbd = compactState["thumb_cmc_abd"] ?: 0f
    val thumbFlex = compactState["thumb_cmc_flex"] ?: 0f
    val thumbMcpIp = compactState["thumb_mcp_ip"] ?: 0f
    val index = compactState["index_flexion"] ?: 0f
    val middle = compactState["middle_flexion"] ?: 0f
    val ring = compactState["ring_flexion"] ?: 0f
    val pinky = compactState["pinky_flexion"] ?: 0f

    return listOf(
        thumbAbd,
        thumbFlex,
        thumbMcpIp,
        thumbMcpIp,
        index,
        index,
        index,
        middle,
        middle,
        middle,
        ring,
        ring,
        ring,
        pinky,
        pinky,
        pinky
    )
}

fun compactStateFromJointStates(states: Map<String, Float>): Map<String, Float> {
    val values = ControlDefinitions.DEFAULT_CONTROL_STATE.toMutableMap()
    values["thumb_cmc_flex"] = (states["thumb_proximal"] ?: 0f).coerceIn(0f, 55f)
    values["thumb_mcp_ip"] = (states["thumb_distal"] ?: 0f).coerceIn(0f, 90f)
    values["index_flexion"] = (states["index_proximal"] ?: 0f).coerceIn(0f, 90f)
    values["middle_flexion"] = (states["middle_proximal"] ?: 0f).coerceIn(0f, 90f)
    values["ring_flexion"] = (states["ring_proximal"] ?: 0f).coerceIn(0f, 90f)
    values["pinky_flexion"] = (states["pinky_proximal"] ?: 0f).coerceIn(0f, 90f)
    values["thumb_cmc_abd"] = mapRange(
        states["thumb_rotation"] ?: 0f,
        ControlDefinitions.THUMB_ROTATION_MIN,
        ControlDefinitions.THUMB_ROTATION_MAX,
        0f,
        100f
    ).coerceIn(0f, 100f)
    return values
}

fun parseStatesResponse(text: String): Map<String, Float>? {
    return try {
        val json = JSONObject(text)
        if (json.optString("type") != "states_response") {
            null
        } else {
            val statesArray = when (val data = json.opt("data")) {
                is JSONArray -> data
                is JSONObject -> data.optJSONArray("joints") ?: JSONArray()
                else -> JSONArray()
            }
            buildMap {
                for (i in 0 until statesArray.length()) {
                    val joint = statesArray.optJSONObject(i) ?: continue
                    val jointId = joint.optString("joint_id")
                    if (jointId.isNotBlank()) {
                        put(jointId, joint.optDouble("angle", 0.0).toFloat())
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

fun parseHandInfo(text: String): String? {
    return try {
        val json = JSONObject(text)
        if (json.optString("type") != "hand_info") {
            null
        } else {
            json.optString("hand_type").takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }
}

private fun buildSerialFrame(opcode: Int, payload: List<Int> = List(7) { 0 }): ByteArray {
    require(payload.size == 7) { "Expected 7 payload words, got ${payload.size}" }
    val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put((opcode and 0xFF).toByte())
    buffer.put(0)
    payload.forEach { value ->
        buffer.putShort((value and 0xFFFF).toShort())
    }
    return buffer.array()
}

private fun jointJson(jointId: String, angle: Float): JSONObject {
    return JSONObject().apply {
        put("joint_id", jointId)
        put("angle", angle)
    }
}

fun mapRange(value: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    if (inMax == inMin) {
        return outMin
    }
    val normalized = (value - inMin) / (inMax - inMin)
    return outMin + normalized * (outMax - outMin)
}
