package com.aerohand.websocket

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

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

    const val DEFAULT_DURATION_MS = 500
    const val THUMB_ROTATION_MIN = -30f
    const val THUMB_ROTATION_MAX = 30f

    val ACTUATION_LOWER_LIMITS = listOf(THUMB_ROTATION_MIN, 0f, -15.2789f, 0f, 0f, 0f, 0f)
    val ACTUATION_UPPER_LIMITS = listOf(THUMB_ROTATION_MAX, 104.1250f, 247.1500f, 288.1603f, 288.1603f, 288.1603f, 288.1603f)
}

object SerialCommands {
    const val HOMING_MODE = 0x01
    const val CTRL_POS = 0x11
    const val GET_POS = 0x22
}

object PresetActions {
    private val relaxedOpen = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 10f)
    private val naturalOpen = floatArrayOf(15f, 10f, 10f, 10f, 10f, 10f, 10f)
    private val powerFist = floatArrayOf(85f, 50f, 85f, 85f, 85f, 85f, 85f)
    private val rpsScissors = floatArrayOf(20f, 10f, 85f, 10f, 10f, 10f, 10f)
    private val victoryPose = floatArrayOf(30f, 15f, 10f, 10f, 10f, 80f, 80f)
    private val thumbUpPose = floatArrayOf(80f, 55f, 20f, 10f, 10f, 10f, 10f)
    private val okPose = floatArrayOf(40f, 25f, 60f, 60f, 10f, 10f, 10f)
    private val graspPose = floatArrayOf(100f, 55f, 30f, 60f, 60f, 60f, 60f)

    private fun pose(values: FloatArray): Map<String, Float> {
        return ControlDefinitions.COMPACT_CONTROLS.mapIndexed { index, control ->
            val value = values.getOrElse(index) { control.defaultValue }
            control.id to value.coerceIn(control.min, control.max)
        }.toMap()
    }

    private fun step(durationMs: Int, values: FloatArray): PresetStep = PresetStep(pose(values), durationMs)

    private fun fingerPose(
        thumbAbd: Float = 15f,
        thumbFlex: Float = 15f,
        thumb: Float = 15f,
        index: Float = 15f,
        middle: Float = 15f,
        ring: Float = 15f,
        pinky: Float = 15f
    ): FloatArray = floatArrayOf(thumbAbd, thumbFlex, thumb, index, middle, ring, pinky)

    private fun pinchPose(fingerIndex: Int): FloatArray {
        val positions = FloatArray(7) { 15f }
        positions[0] = 70f
        positions[1] = 35f
        positions[2] = 80f
        positions[fingerIndex] = 75f
        return positions
    }

    private fun buildRpsSteps(): List<PresetStep> = listOf(
        step(1500, powerFist),
        step(800, rpsScissors),
        step(1500, naturalOpen),
        step(500, naturalOpen),
        step(350, relaxedOpen)
    )

    private fun buildWaveMeetSteps(): List<PresetStep> = buildList {
        val steps = 60
        val delayMs = 50
        val baseAngle = 30.0
        val amplitude = 55.0
        val phaseOffset = 0.7
        for (index in 0 until steps) {
            val t = (2.0 * PI * index) / (steps - 1).coerceAtLeast(1)
            val positions = DoubleArray(7) { baseAngle }

            for (joint in 3..6) {
                val phase = (joint - 3) * phaseOffset
                positions[joint] = baseAngle + amplitude * sin(t + phase)
            }
            for (joint in 3 downTo 0) {
                val phase = (3 - joint) * phaseOffset
                positions[joint] = baseAngle + amplitude * sin(t + phase)
            }

            add(step(delayMs, positions.map { it.toFloat() }.toFloatArray()))
        }
        add(step(300, relaxedOpen))
    }

    private fun buildFanOpenSteps(): List<PresetStep> = listOf(
        step(400, FloatArray(7) { 75f }),
        step(400, floatArrayOf(10f, 10f, 10f, 75f, 75f, 75f, 75f)),
        step(400, floatArrayOf(10f, 10f, 10f, 10f, 75f, 75f, 75f)),
        step(400, floatArrayOf(10f, 10f, 10f, 10f, 10f, 75f, 75f)),
        step(400, floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 75f)),
        step(800, naturalOpen),
        step(400, FloatArray(7) { 75f }),
        step(350, relaxedOpen)
    )

    private fun buildCountingSteps(): List<PresetStep> = listOf(
        step(500, FloatArray(7) { 80f }),
        step(500, floatArrayOf(80f, 80f, 80f, 10f, 80f, 80f, 80f)),
        step(500, floatArrayOf(80f, 80f, 80f, 10f, 10f, 80f, 80f)),
        step(500, floatArrayOf(80f, 80f, 80f, 10f, 10f, 10f, 80f)),
        step(500, floatArrayOf(80f, 80f, 80f, 10f, 10f, 10f, 10f)),
        step(500, naturalOpen),
        step(300, naturalOpen),
        step(300, relaxedOpen)
    )

    private fun buildPrecisionPinchSteps(): List<PresetStep> = listOf(
        step(1000, floatArrayOf(40f, 25f, 35f, 40f, 10f, 10f, 10f)),
        step(400, naturalOpen),
        step(1000, floatArrayOf(60f, 40f, 50f, 60f, 10f, 10f, 10f)),
        step(400, naturalOpen),
        step(1000, floatArrayOf(80f, 55f, 75f, 80f, 10f, 10f, 10f)),
        step(400, naturalOpen),
        step(300, relaxedOpen)
    )

    private fun buildPinchPracticeSteps(): List<PresetStep> = buildList {
        listOf(3, 4, 5, 6).forEach { fingerIndex ->
            add(step(250, fingerPose()))
            add(step(600, pinchPose(fingerIndex)))
        }
        add(step(250, fingerPose()))
        listOf(5, 4, 3).forEach { fingerIndex ->
            add(step(250, fingerPose()))
            add(step(600, pinchPose(fingerIndex)))
        }
        add(step(350, fingerPose()))
    }

    private fun buildFistReleaseSteps(): List<PresetStep> = buildList {
        add(step(600, FloatArray(7) { 80f }))
        listOf(6, 5, 4, 3, 2, 1).forEach { fingerIndex ->
            val positions = FloatArray(7) { 80f }
            positions[fingerIndex] = 5f
            add(step(300, positions))
        }
        add(step(300, FloatArray(7) { 5f }))
        add(step(500, naturalOpen))
    }

    private fun buildTypingSteps(): List<PresetStep> = buildList {
        val patterns = listOf(
            listOf(3, 4, 3, 4, 5, 4, 3),
            listOf(4, 5, 6, 5, 4, 3, 4),
            listOf(3, 4, 5, 3, 4, 5, 6),
            listOf(6, 5, 4, 3, 4, 5, 4)
        )
        patterns.forEach { pattern ->
            pattern.forEach { fingerIndex ->
                val positions = FloatArray(7) { 15f }
                positions[fingerIndex] = 70f
                add(step(120, positions))
                add(step(80, FloatArray(7) { 15f }))
            }
            add(step(400, FloatArray(7) { 15f }))
        }
        add(step(300, relaxedOpen))
    }

    val all = listOf(
        PresetAction("open_palm", "张开", "自然张手", listOf(step(450, naturalOpen))),
        PresetAction("power_grasp", "抓握", "力量抓取", listOf(step(500, graspPose), step(350, relaxedOpen))),
        PresetAction("victory", "剪刀手", "标准 V 字手型", listOf(step(500, victoryPose), step(300, relaxedOpen))),
        PresetAction("thumb_up", "点赞", "拇指上举", listOf(step(500, thumbUpPose), step(300, relaxedOpen))),
        PresetAction("ok_gesture", "OK", "拇指食指成环", listOf(step(500, okPose), step(300, relaxedOpen))),
        PresetAction("precision_pinch", "三段捏取", "轻捏到紧捏", buildPrecisionPinchSteps()),
        PresetAction("pinch_practice", "轮指捏合", "拇指依次捏四指", buildPinchPracticeSteps()),
        PresetAction("rps", "猜拳", "石头剪刀布一轮", buildRpsSteps()),
        PresetAction("wave_meet", "波浪汇聚", "参考 SDK 60 帧波浪", buildWaveMeetSteps()),
        PresetAction("fan_open", "扇形开合", "从拇指扫到小指", buildFanOpenSteps()),
        PresetAction("counting", "手指数数", "从 1 到 5 展开", buildCountingSteps()),
        PresetAction("fist_release", "逐指松拳", "握拳后逐个松开", buildFistReleaseSteps()),
        PresetAction("typing", "打字节奏", "四组键击模式", buildTypingSteps())
    )

    fun find(id: String): PresetAction? = all.firstOrNull { it.id == id }
}

data class WifiStatus(
    val mode: String = "AP",
    val ip: String = "192.168.4.1",
    val staSsid: String? = null,
    val staStaticIp: String = "192.168.1.210",
    val staGateway: String = "192.168.1.1",
    val staSubnet: String = "255.255.255.0",
    val staDns1: String = "192.168.1.1",
    val staDns2: String = "114.114.114.114"
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val server: String, val handType: String? = null) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

enum class ControlTransport {
    ACTUATOR,
    MULTI_JOINT,
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

    fun getWifiStatus() = """{"type":"wifi_status","timestamp":${System.currentTimeMillis()}}"""

    fun setWifiConfig(
        ssid: String,
        password: String,
        staticIp: String,
        gateway: String,
        subnet: String,
        dns1: String,
        dns2: String
    ) = JSONObject().apply {
        put("type", "wifi_config_set")
        put("timestamp", System.currentTimeMillis())
        put("data", JSONObject().apply {
            put("sta_ssid", ssid)
            put("sta_password", password)
            put("sta_static_ip", staticIp)
            put("sta_gateway", gateway)
            put("sta_subnet", subnet)
            put("sta_dns1", dns1)
            put("sta_dns2", dns2)
        })
    }.toString()

    fun connectSta() = """{"type":"wifi_connect_sta","timestamp":${System.currentTimeMillis()}}"""

    fun startAp() = """{"type":"wifi_start_ap","timestamp":${System.currentTimeMillis()}}"""

    fun clearWifiConfig() = """{"type":"wifi_clear_sta","timestamp":${System.currentTimeMillis()}}"""
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

fun buildActuatorControlPayload(
    compactState: Map<String, Float>,
    durationMs: Int = ControlDefinitions.DEFAULT_DURATION_MS
): String {
    return JSONObject().apply {
        put("type", "actuator_control")
        put("timestamp", System.currentTimeMillis())
        put(
            "data",
            JSONObject().apply {
                put("actuators", buildActuatorTargets(compactState))
                put("duration_ms", durationMs)
            }
        )
    }.toString()
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

fun buildActuatorTargets(compactState: Map<String, Float>): JSONArray {
    return JSONArray().apply {
        compactStateToActuations(compactState).forEachIndexed { index, actuation ->
            put(JSONObject().apply {
                put("id", index)
                put("angle", actuation.toDouble())
            })
        }
    }
}

fun buildProtocolPreview(
    compactState: Map<String, Float>,
    transport: ControlTransport = ControlTransport.ACTUATOR
): String {
    return JSONObject().apply {
        when (transport) {
            ControlTransport.ACTUATOR -> {
                put("type", "actuator_control")
                put("data", JSONObject().apply {
                    put("actuators", buildActuatorTargets(compactState))
                    put("duration_ms", ControlDefinitions.DEFAULT_DURATION_MS)
                })
            }
            ControlTransport.MULTI_JOINT -> {
                put("type", "multi_joint_control")
                put("data", JSONObject().apply {
                    put("joints", buildProtocolJoints(compactState))
                    put("duration_ms", ControlDefinitions.DEFAULT_DURATION_MS)
                })
            }
        }
    }.toString(2)
}

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
    val thumbCmcAbd = mapRange(
        compactState["thumb_cmc_abd"] ?: 0f,
        0f,
        100f,
        ControlDefinitions.THUMB_ROTATION_MIN,
        ControlDefinitions.THUMB_ROTATION_MAX
    )
    val thumbCmcFlex = compactState["thumb_cmc_flex"] ?: 0f
    val thumbMcpIp = compactState["thumb_mcp_ip"] ?: 0f

    val thumbCmcAbdActuation = thumbCmcAbd
    val thumbCmcFlexActuation = (
        THUMB_FLEX_CMC_ABD_COEFF * thumbCmcAbd +
            THUMB_FLEX_CMC_FLEX_COEFF * thumbCmcFlex
        ) / MOTOR_PULLEY_RADIUS
    val thumbTendonActuation = (
        THUMB_IP_CMC_ABD_COEFF * thumbCmcAbd -
            THUMB_IP_CMC_FLEX_COEFF * thumbCmcFlex +
            THUMB_IP_MCP_COEFF * thumbMcpIp +
            THUMB_IP_IP_COEFF * thumbMcpIp
        ) / MOTOR_PULLEY_RADIUS

    val fingerActuations = listOf("index", "middle", "ring", "pinky").map { finger ->
        val flexion = compactState["${finger}_flexion"] ?: 0f
        (
            FINGER_MCP_FLEX_COEFF * flexion +
                FINGER_PIP_COEFF * flexion +
                FINGER_DIP_COEFF * flexion
            ) / MOTOR_PULLEY_RADIUS
    }

    return listOf(thumbCmcAbdActuation, thumbCmcFlexActuation, thumbTendonActuation) + fingerActuations
}

fun compactStateFromActuations(actuationsDegrees: List<Float>): Map<String, Float> {
    require(actuationsDegrees.size == 7) { "Expected 7 actuation values, got ${actuationsDegrees.size}" }

    val actuations = actuationsDegrees.map { it * DEG_TO_RAD }

    val cmcAbdDeg = actuationsDegrees[0].coerceIn(
        ControlDefinitions.THUMB_ROTATION_MIN,
        ControlDefinitions.THUMB_ROTATION_MAX
    )
    val cmcAbdJoint = cmcAbdDeg * DEG_TO_RAD
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
        "thumb_cmc_abd" to mapRange(
            cmcAbdDeg,
            ControlDefinitions.THUMB_ROTATION_MIN,
            ControlDefinitions.THUMB_ROTATION_MAX,
            0f,
            100f
        ).coerceIn(0f, 100f),
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
    val thumbCmcFlex = compactState["thumb_cmc_flex"] ?: 0f
    val thumbMcpIp = compactState["thumb_mcp_ip"] ?: 0f
    val thumbAbd = compactState["thumb_cmc_abd"] ?: 0f
    val index = compactState["index_flexion"] ?: 0f
    val middle = compactState["middle_flexion"] ?: 0f
    val ring = compactState["ring_flexion"] ?: 0f
    val pinky = compactState["pinky_flexion"] ?: 0f

    // Joint angles for firmware (14 flex joints + thumb_rotation = 15 positions)
    // Firmware JOINT_NAMES order:
    //   0=thumb_proximal  1=thumb_distal  2-4=index  5-7=middle  8-10=ring  11-13=pinky  14=thumb_rotation
    // Compact: thumb_cmc_flex -> thumb_proximal (CMC flexion)
    //          thumb_mcp_ip -> thumb_distal (IP flexion)
    //          thumb_cmc_abd -> thumb_rotation (abduction maps to rotation)
    // Each finger: single flexion value -> all 3 joints (tendon-coupled)
    return listOf(
        thumbCmcFlex,   // 0: thumb_proximal (CMC flexion)
        thumbMcpIp,     // 1: thumb_distal (IP flexion)
        index,          // 2: index_proximal
        index,          // 3: index_middle
        index,          // 4: index_distal
        middle,         // 5: middle_proximal
        middle,         // 6: middle_middle
        middle,         // 7: middle_distal
        ring,           // 8: ring_proximal
        ring,           // 9: ring_middle
        ring,           // 10: ring_distal
        pinky,          // 11: pinky_proximal
        pinky,          // 12: pinky_middle
        pinky,          // 13: pinky_distal
        thumbAbd        // 14: thumb_rotation (abduction angle)
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
                        val angle = joint.optDouble("angle", Double.NaN)
                        if (angle.isFinite()) {
                            put(jointId, angle.toFloat())
                        }
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

fun parseWifiStatus(text: String): WifiStatus? {
    return try {
        val json = JSONObject(text)
        if (json.optString("type") != "wifi_status") {
            null
        } else {
            val data = json.optJSONObject("data") ?: json
            WifiStatus(
                mode = data.optString("mode", "AP"),
                ip = data.optString("ip", "192.168.4.1"),
                staSsid = data.optString("sta_ssid").takeIf { it.isNotBlank() },
                staStaticIp = data.optString("sta_static_ip", "192.168.1.210"),
                staGateway = data.optString("sta_gateway", "192.168.1.1"),
                staSubnet = data.optString("sta_subnet", "255.255.255.0"),
                staDns1 = data.optString("sta_dns1", "192.168.1.1"),
                staDns2 = data.optString("sta_dns2", "114.114.114.114")
            )
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
        put("angle", angle.toDouble())
    }
}

fun mapRange(value: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    if (inMax == inMin) {
        return outMin
    }
    val normalized = (value - inMin) / (inMax - inMin)
    return outMin + normalized * (outMax - outMin)
}
