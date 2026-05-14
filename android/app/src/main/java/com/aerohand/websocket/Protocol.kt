package com.aerohand.websocket

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
    private val flatOpen = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val sdkOpenPalm = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 10f)
    private val relaxedOpen = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 10f)
    private val naturalOpen = floatArrayOf(15f, 10f, 10f, 10f, 10f, 10f, 10f)
    private val powerFist = floatArrayOf(85f, 50f, 85f, 85f, 85f, 85f, 85f)
    private val rpsScissors = floatArrayOf(20f, 10f, 85f, 10f, 10f, 10f, 10f)
    private val victoryPose = floatArrayOf(30f, 15f, 10f, 10f, 10f, 80f, 80f)
    private val peacePose = victoryPose.copyOf()
    private val thumbUpPose = floatArrayOf(80f, 55f, 20f, 10f, 10f, 10f, 10f)
    private val thumbDownPose = floatArrayOf(80f, 55f, 80f, 10f, 10f, 10f, 10f)
    private val okPose = floatArrayOf(40f, 25f, 60f, 60f, 10f, 10f, 10f)
    private val metalPose = floatArrayOf(20f, 10f, 10f, 10f, 80f, 80f, 10f)
    private val graspPose = floatArrayOf(100f, 55f, 30f, 60f, 60f, 60f, 60f)
    private val helloPose = floatArrayOf(30f, 15f, 20f, 80f, 80f, 80f, 80f)
    private val thanksPose = floatArrayOf(20f, 10f, 10f, 10f, 80f, 80f, 80f)
    private val loveYouPose = floatArrayOf(50f, 30f, 20f, 20f, 80f, 80f, 80f)
    private val screwGripPose = floatArrayOf(50f, 35f, 60f, 70f, 70f, 10f, 10f)
    private val objectBallPose = floatArrayOf(30f, 20f, 50f, 50f, 50f, 50f, 50f)
    private val objectPenPose = floatArrayOf(40f, 25f, 60f, 70f, 70f, 10f, 10f)
    private val objectCardPose = floatArrayOf(50f, 35f, 30f, 80f, 80f, 80f, 80f)
    private val magicVanishPose = floatArrayOf(50f, 35f, 30f, 80f, 80f, 80f, 80f)
    private val magicAppearPose = floatArrayOf(20f, 10f, 10f, 20f, 20f, 20f, 20f)
    private val magicPassPose = floatArrayOf(30f, 15f, 20f, 10f, 80f, 80f, 10f)

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
        step(240, sdkOpenPalm),
        step(520, powerFist),
        step(220, sdkOpenPalm),
        step(520, rpsScissors),
        step(220, sdkOpenPalm),
        step(520, naturalOpen),
        step(350, relaxedOpen)
    )

    private fun buildFingerTourSteps(): List<PresetStep> = listOf(
        step(520, flatOpen),
        step(420, floatArrayOf(100f, 35f, 23f, 0f, 0f, 0f, 50f)),
        step(220, floatArrayOf(100f, 35f, 23f, 0f, 0f, 0f, 50f)),
        step(420, floatArrayOf(100f, 42f, 23f, 0f, 0f, 52f, 0f)),
        step(220, floatArrayOf(100f, 42f, 23f, 0f, 0f, 52f, 0f)),
        step(420, floatArrayOf(83f, 42f, 23f, 0f, 50f, 0f, 0f)),
        step(220, floatArrayOf(83f, 42f, 23f, 0f, 50f, 0f, 0f)),
        step(420, floatArrayOf(75f, 25f, 30f, 50f, 0f, 0f, 0f)),
        step(220, floatArrayOf(75f, 25f, 30f, 50f, 0f, 0f, 0f)),
        step(320, flatOpen),
        step(320, flatOpen),
        step(420, peacePose),
        step(900, peacePose),
        step(320, flatOpen),
        step(320, flatOpen),
        step(420, floatArrayOf(0f, 0f, 0f, 0f, 90f, 90f, 0f)),
        step(900, floatArrayOf(0f, 0f, 0f, 0f, 90f, 90f, 0f)),
        step(420, flatOpen),
        step(320, relaxedOpen)
    )

    private fun buildDemoRoutineSteps(): List<PresetStep> = buildList {
        addAll(buildCountingSteps())
        addAll(buildFistReleaseSteps())
        addAll(buildPianoSteps())
        addAll(buildPinchPracticeSteps())
        addAll(buildTypingSteps())
        addAll(buildRandomDanceSteps())
        add(step(420, relaxedOpen))
    }

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

    private fun buildWaveSteps(reverse: Boolean = false): List<PresetStep> = buildList {
        val steps = 72
        val delayMs = 40
        val baseAngle = 25.0
        val amplitude = 65.0
        val phaseOffset = 0.8
        for (index in 0 until steps) {
            val t = (4.0 * PI * index) / (steps - 1).coerceAtLeast(1)
            val positions = FloatArray(7) { joint ->
                val phase = if (reverse) -joint * phaseOffset else joint * phaseOffset
                val value = when (joint) {
                    0 -> baseAngle + 75.0 * sin(t + phase)
                    1 -> 20.0 + 20.0 * sin(t + phase)
                    2 -> baseAngle + 60.0 * sin(t + phase)
                    else -> baseAngle + amplitude * sin(t + phase)
                }
                value.toFloat()
            }
            add(step(delayMs, positions))
        }
        add(step(260, relaxedOpen))
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

    private fun buildPianoSteps(): List<PresetStep> = buildList {
        val sequence = listOf(3, 4, 5, 6, 5, 4, 3)
        repeat(2) {
            sequence.forEach { fingerIndex ->
                val positions = FloatArray(7) { 10f }
                positions[fingerIndex] = 70f
                add(step(150, positions))
                add(step(75, FloatArray(7) { 10f }))
            }
            add(step(220, naturalOpen))
        }
        add(step(300, relaxedOpen))
    }

    private fun buildSpiralSteps(): List<PresetStep> = buildList {
        val steps = 80
        val delayMs = 60
        for (index in 0 until steps) {
            val t = (4 * PI * index) / (steps - 1).coerceAtLeast(1)
            val phase = sin(t)
            val centerAngle = 45 + 30 * phase
            val spiralOffset = cos(t * 0.5) * 20
            val positions = floatArrayOf(
                (centerAngle + spiralOffset * 0.3).toFloat(),
                (centerAngle * 0.6 + spiralOffset * 0.2).toFloat(),
                (centerAngle + spiralOffset * 0.5).toFloat(),
                (centerAngle + spiralOffset * 0.8).toFloat(),
                (centerAngle + spiralOffset * 0.4).toFloat(),
                (centerAngle + spiralOffset).toFloat(),
                (centerAngle + spiralOffset * 1.2).toFloat()
            ).mapIndexed { jointIndex, value ->
                val control = ControlDefinitions.COMPACT_CONTROLS[jointIndex]
                value.coerceIn(control.min, control.max)
            }.toFloatArray()
            add(step(delayMs, positions))
        }
        add(step(320, relaxedOpen))
    }

    private fun buildClapSteps(): List<PresetStep> = listOf(
        step(150, naturalOpen),
        step(225, powerFist),
        step(150, naturalOpen),
        step(225, powerFist),
        step(500, naturalOpen),
        step(300, relaxedOpen)
    )

    private fun buildEmojiShowcaseSteps(): List<PresetStep> = listOf(
        step(420, thumbUpPose),
        step(180, naturalOpen),
        step(420, thumbDownPose),
        step(180, naturalOpen),
        step(420, okPose),
        step(180, naturalOpen),
        step(420, victoryPose),
        step(180, naturalOpen),
        step(420, loveYouPose),
        step(300, relaxedOpen)
    )

    private fun buildConductingSteps(): List<PresetStep> = buildList {
        val conductingPose = floatArrayOf(30f, 15f, 30f, 40f, 40f, 40f, 40f)
        val beatPoses = listOf(
            floatArrayOf(50f, 25f, 50f, 60f, 60f, 60f, 60f),
            floatArrayOf(20f, 10f, 20f, 30f, 50f, 50f, 50f),
            floatArrayOf(20f, 10f, 20f, 50f, 30f, 50f, 50f),
            floatArrayOf(20f, 10f, 20f, 20f, 20f, 20f, 20f)
        )
        repeat(2) {
            add(step(500, conductingPose))
            beatPoses.forEach { pose ->
                add(step(300, pose))
                add(step(90, conductingPose))
            }
            add(step(450, naturalOpen))
        }
        add(step(300, relaxedOpen))
    }

    private fun buildGuitarStrumSteps(): List<PresetStep> = buildList {
        val rest = FloatArray(7) { 10f }
        listOf(2, 3, 4, 5, 6).forEach { fingerIndex ->
            val positions = rest.copyOf()
            positions[fingerIndex] = 60f
            add(step(150, positions))
        }
        listOf(6, 5, 4, 3, 2).forEach { fingerIndex ->
            val positions = rest.copyOf()
            positions[fingerIndex] = 60f
            add(step(150, positions))
        }
        add(step(300, floatArrayOf(30f, 20f, 40f, 70f, 70f, 70f, 70f)))
        add(step(150, rest))
        add(step(300, relaxedOpen))
    }

    private fun buildDrumRollSteps(): List<PresetStep> = buildList {
        val rest = FloatArray(7) { 10f }
        repeat(2) {
            listOf(3, 4, 5, 6, 3, 4, 5, 6).forEach { fingerIndex ->
                val positions = rest.copyOf()
                positions[fingerIndex] = 70f
                add(step(80, positions))
            }
            repeat(4) {
                add(step(80, floatArrayOf(10f, 10f, 10f, 70f, 70f, 10f, 10f)))
                add(step(80, floatArrayOf(10f, 10f, 10f, 10f, 10f, 70f, 70f)))
            }
            add(step(160, rest))
        }
        add(step(300, relaxedOpen))
    }

    private fun buildFingerDanceSteps(): List<PresetStep> = buildList {
        val segments = listOf(
            Triple(2 * PI, 36, 0.0),
            Triple(4 * PI, 36, 0.5),
            Triple(3 * PI, 36, 1.0)
        )
        segments.forEachIndexed { segmentIndex, (maxT, count, phaseScale) ->
            for (index in 0 until count) {
                val t = maxT * index / (count - 1).coerceAtLeast(1)
                val positions = FloatArray(7) { joint ->
                    val value = when (segmentIndex) {
                        0 -> 10 + 60 * sin(t - joint * 0.4).let { it * it }
                        1 -> 45 + (30 + 20 * sin(t)) * sin(t + joint * phaseScale)
                        else -> {
                            val wave1 = sin(t + joint * 0.3)
                            val wave2 = sin(2 * t + joint * 0.5) * 0.5
                            val wave3 = sin(3 * t + joint * 0.7) * 0.3
                            45 + 40 * (wave1 + wave2 + wave3)
                        }
                    }
                    value.toFloat().coerceIn(0f, 90f)
                }
                add(step(40, positions))
            }
        }
        add(step(320, relaxedOpen))
    }

    private fun buildScrewTwistSteps(): List<PresetStep> = buildList {
        repeat(3) {
            add(step(220, naturalOpen))
            add(step(320, screwGripPose))
            repeat(4) { turn ->
                val phase = turn * PI / 2.0
                add(step(140, floatArrayOf(
                    50f,
                    35f,
                    (60 + 10 * sin(phase)).toFloat(),
                    (70 + 10 * cos(phase)).toFloat(),
                    70f,
                    10f,
                    10f
                )))
                add(step(140, floatArrayOf(
                    50f,
                    35f,
                    (60 - 10 * sin(phase)).toFloat(),
                    (70 - 10 * cos(phase)).toFloat(),
                    70f,
                    10f,
                    10f
                )))
            }
            add(step(260, naturalOpen))
        }
        add(step(320, relaxedOpen))
    }

    private fun buildObjectGripSteps(): List<PresetStep> = buildList {
        listOf(objectBallPose, objectPenPose, objectCardPose).forEach { grip ->
            add(step(260, naturalOpen))
            add(step(650, grip))
            add(step(240, naturalOpen))
        }
        add(step(320, relaxedOpen))
    }

    private fun buildRandomDanceSteps(): List<PresetStep> = buildList {
        val random = Random(42)
        val current = FloatArray(7) { 30f }
        repeat(8) {
            val target = FloatArray(7) { index ->
                when (index) {
                    0 -> 15f + random.nextFloat() * 75f
                    1 -> 5f + random.nextFloat() * 45f
                    else -> 10f + random.nextFloat() * 70f
                }
            }
            repeat(3) {
                for (joint in current.indices) {
                    current[joint] += (target[joint] - current[joint]) * 0.45f
                }
                add(step(90, current.copyOf()))
            }
        }
        add(step(320, relaxedOpen))
    }

    private fun buildMagicTrickSteps(): List<PresetStep> = listOf(
        step(520, naturalOpen),
        step(260, magicVanishPose),
        step(260, naturalOpen),
        step(520, powerFist),
        step(320, magicAppearPose),
        step(520, magicPassPose),
        step(280, naturalOpen),
        step(300, relaxedOpen)
    )

    private fun buildMorseSosSteps(): List<PresetStep> = buildList {
        val rest = FloatArray(7) { 10f }
        val tap = rest.copyOf().also { it[3] = 70f }

        fun dot() {
            add(step(150, tap))
            add(step(180, rest))
        }

        fun dash() {
            add(step(420, tap))
            add(step(220, rest))
        }

        repeat(3) { dot() }
        add(step(320, rest))
        repeat(3) { dash() }
        add(step(420, rest))
        repeat(3) { dot() }
        add(step(300, relaxedOpen))
    }

    private fun buildJointIsolationSteps(): List<PresetStep> = buildList {
        val rest = FloatArray(7) { 10f }
        ControlDefinitions.COMPACT_CONTROLS.forEachIndexed { jointIndex, control ->
            val positions = rest.copyOf()
            positions[jointIndex] = (control.max * 0.7f).coerceIn(control.min, control.max)
            add(step(180, rest))
            add(step(420, positions))
            add(step(220, rest))
        }
        add(step(260, FloatArray(7) { 60f }))
        add(step(300, relaxedOpen))
    }

    private fun buildRangeMotionSteps(): List<PresetStep> = buildList {
        ControlDefinitions.COMPACT_CONTROLS.forEachIndexed { jointIndex, control ->
            for (stepIndex in 0..5) {
                val progress = stepIndex / 5f
                val positions = FloatArray(7) { control.min }
                positions[jointIndex] = control.min + (control.max - control.min) * progress
                add(step(60, positions))
            }
            for (stepIndex in 4 downTo 0) {
                val progress = stepIndex / 5f
                val positions = FloatArray(7) { control.min }
                positions[jointIndex] = control.min + (control.max - control.min) * progress
                add(step(60, positions))
            }
        }
        add(step(250, naturalOpen))
        add(step(300, relaxedOpen))
    }

    private fun buildSpeedCalibrationSteps(): List<PresetStep> = buildList {
        val rest = FloatArray(7) { 10f }
        val allFast = FloatArray(7) { 70f }
        listOf(160, 100, 60, 35).forEach { delayMs ->
            repeat(3) {
                val positions = rest.copyOf()
                positions[3] = 70f
                add(step(delayMs, positions))
                add(step(delayMs, rest))
            }
        }
        listOf(160, 100, 60, 35).forEach { delayMs ->
            repeat(3) {
                add(step(delayMs, allFast))
                add(step(delayMs, rest))
            }
        }
        add(step(300, relaxedOpen))
    }

    private fun buildSignLanguageShowcaseSteps(): List<PresetStep> = buildList {
        repeat(2) {
            add(step(520, helloPose))
            add(step(180, naturalOpen))
            add(step(520, thanksPose))
            add(step(180, naturalOpen))
            add(step(520, loveYouPose))
            add(step(260, naturalOpen))
        }
        add(step(320, relaxedOpen))
    }

    private fun buildMemorySequenceSteps(): List<PresetStep> = buildList {
        val sequence = listOf(
            powerFist,
            victoryPose,
            naturalOpen,
            thumbUpPose,
            okPose,
            powerFist
        )
        sequence.forEachIndexed { index, pose ->
            val hold = if (index < 2) 520 else if (index < 4) 430 else 360
            add(step(hold, pose))
            add(step(180, relaxedOpen))
        }
        sequence.forEach { pose ->
            add(step(260, pose))
            add(step(120, relaxedOpen))
        }
        add(step(320, relaxedOpen))
    }

    private fun buildReactionPulseSteps(): List<PresetStep> = buildList {
        val rest = FloatArray(7) { 10f }
        val reactionGrip = floatArrayOf(80f, 55f, 80f, 60f, 60f, 60f, 60f)
        listOf(480, 360, 280, 220, 180).forEach { waitMs ->
            add(step(waitMs, rest))
            add(step(140, reactionGrip))
            add(step(120, rest))
        }
        add(step(320, relaxedOpen))
    }

    private fun buildWhackAMoleSteps(): List<PresetStep> = buildList {
        val rest = FloatArray(7) { 10f }
        val targets = listOf(3, 4, 5, 6, 4, 3, 6, 5)
        targets.forEach { fingerIndex ->
            val raise = rest.copyOf().also { it[fingerIndex] = 72f }
            add(step(220, raise))
            add(step(120, rest))
            val strike = rest.copyOf().also { it[fingerIndex] = 88f }
            add(step(130, strike))
            add(step(120, rest))
        }
        add(step(320, relaxedOpen))
    }

    private fun chainRoutines(vararg routines: List<PresetStep>): List<PresetStep> = buildList {
        val validRoutines = routines.filter { it.isNotEmpty() }
        validRoutines.forEachIndexed { index, routine ->
            addAll(routine)
            if (index != validRoutines.lastIndex) {
                add(step(220, naturalOpen))
            }
        }
        add(step(320, relaxedOpen))
    }

    private fun buildFlexPackSteps(): List<PresetStep> = chainRoutines(
        buildRpsSteps(),
        buildFingerTourSteps(),
        buildWaveSteps(),
        buildWaveSteps(reverse = true),
        buildWaveMeetSteps(),
        buildFanOpenSteps(),
        buildCountingSteps(),
        buildFistReleaseSteps(),
        buildTypingSteps(),
        buildPianoSteps(),
        buildSpiralSteps(),
        buildRandomDanceSteps()
    )

    private fun buildGesturePackSteps(): List<PresetStep> = chainRoutines(
        buildSignLanguageShowcaseSteps(),
        buildEmojiShowcaseSteps(),
        buildClapSteps()
    )

    private fun buildPrecisionPackSteps(): List<PresetStep> = chainRoutines(
        buildPrecisionPinchSteps(),
        buildPinchPracticeSteps(),
        buildObjectGripSteps(),
        buildScrewTwistSteps()
    )

    private fun buildArtPackSteps(): List<PresetStep> = chainRoutines(
        buildConductingSteps(),
        buildFingerDanceSteps(),
        buildMagicTrickSteps()
    )

    private fun buildSportsPackSteps(): List<PresetStep> = chainRoutines(
        buildDrumRollSteps(),
        buildGuitarStrumSteps(),
        buildMorseSosSteps()
    )

    private fun buildSciencePackSteps(): List<PresetStep> = chainRoutines(
        buildJointIsolationSteps(),
        buildRangeMotionSteps(),
        buildSpeedCalibrationSteps()
    )

    private fun buildGamePackSteps(): List<PresetStep> = chainRoutines(
        buildReactionPulseSteps(),
        buildWhackAMoleSteps(),
        buildMemorySequenceSteps()
    )

    private fun buildAllDemosOnceSteps(): List<PresetStep> = chainRoutines(
        buildFlexPackSteps(),
        buildGesturePackSteps(),
        buildPrecisionPackSteps(),
        buildArtPackSteps(),
        buildSportsPackSteps(),
        buildSciencePackSteps(),
        buildGamePackSteps()
    )

    val all = listOf(
        PresetAction("flat_open", "平掌", "五指完全张开", listOf(step(450, flatOpen), step(250, relaxedOpen))),
        PresetAction("sdk_open", "SDK 张手", "对齐 SDK examples 的开手姿态", listOf(step(450, sdkOpenPalm), step(250, relaxedOpen))),
        PresetAction("rest_pose", "待机", "放松待机手型", listOf(step(450, relaxedOpen))),
        PresetAction("open_palm", "张开", "自然张手", listOf(step(450, naturalOpen))),
        PresetAction("rock_pose", "石头", "SDK 猜拳单动作", listOf(step(520, powerFist), step(300, relaxedOpen))),
        PresetAction("scissors_pose", "剪刀", "SDK 猜拳单动作", listOf(step(520, rpsScissors), step(300, relaxedOpen))),
        PresetAction("paper_pose", "布", "SDK 猜拳单动作", listOf(step(520, naturalOpen), step(300, relaxedOpen))),
        PresetAction("power_fist", "握拳", "标准握拳", listOf(step(500, powerFist), step(300, relaxedOpen))),
        PresetAction("power_grasp", "抓握", "力量抓取", listOf(step(500, graspPose), step(350, relaxedOpen))),
        PresetAction("peace", "比耶", "和平手势", listOf(step(500, peacePose), step(300, relaxedOpen))),
        PresetAction("victory", "剪刀手", "标准 V 字手型", listOf(step(500, victoryPose), step(300, relaxedOpen))),
        PresetAction("thumb_up", "点赞", "拇指上举", listOf(step(500, thumbUpPose), step(300, relaxedOpen))),
        PresetAction("thumb_down", "拇指向下", "参考 emoji 示例", listOf(step(500, thumbDownPose), step(300, relaxedOpen))),
        PresetAction("ok_gesture", "OK", "拇指食指成环", listOf(step(500, okPose), step(300, relaxedOpen))),
        PresetAction("metal", "金属手势", "参考 emoji 示例", listOf(step(500, metalPose), step(300, relaxedOpen))),
        PresetAction("pinch_index", "食指捏", "拇指食指捏合", listOf(step(500, pinchPose(3)), step(300, relaxedOpen))),
        PresetAction("pinch_middle", "中指捏", "拇指中指捏合", listOf(step(500, pinchPose(4)), step(300, relaxedOpen))),
        PresetAction("pinch_ring", "无名捏", "拇指无名指捏合", listOf(step(500, pinchPose(5)), step(300, relaxedOpen))),
        PresetAction("pinch_pinky", "小指捏", "拇指小指捏合", listOf(step(500, pinchPose(6)), step(300, relaxedOpen))),
        PresetAction("precision_pinch", "三段捏取", "轻捏到紧捏", buildPrecisionPinchSteps()),
        PresetAction("pinch_practice", "轮指捏合", "拇指依次捏四指", buildPinchPracticeSteps()),
        PresetAction("flex_pack", "灵活组", "参考 flexibility_moves 全套组合", buildFlexPackSteps()),
        PresetAction("gesture_pack", "手势组", "参考 creative_moves 手势互动分类", buildGesturePackSteps()),
        PresetAction("precision_pack", "精细组", "参考 creative_moves 精细操作分类", buildPrecisionPackSteps()),
        PresetAction("art_pack", "艺术组", "参考 creative_moves 艺术创意分类", buildArtPackSteps()),
        PresetAction("sports_pack", "运动组", "参考 creative_moves 运动技能分类", buildSportsPackSteps()),
        PresetAction("science_pack", "科学组", "参考 creative_moves 科学演示分类", buildSciencePackSteps()),
        PresetAction("game_pack", "游戏组", "参考 creative_moves 游戏互动分类", buildGamePackSteps()),
        PresetAction("all_demos_once", "全套演示", "参考 run_all_demos 一次跑完", buildAllDemosOnceSteps()),
        PresetAction("rps", "猜拳", "石头剪刀布一轮", buildRpsSteps()),
        PresetAction("finger_tour", "触指巡游", "参考 SDK run_sequence", buildFingerTourSteps()),
        PresetAction("demo_routine", "综合演示", "参考 SDK demo_routine", buildDemoRoutineSteps()),
        PresetAction("wave", "波浪", "参考 SDK wave_motion", buildWaveSteps()),
        PresetAction("reverse_wave", "逆波浪", "参考 SDK reverse_wave_motion", buildWaveSteps(reverse = true)),
        PresetAction("wave_meet", "波浪汇聚", "参考 SDK 60 帧波浪", buildWaveMeetSteps()),
        PresetAction("fan_open", "扇形开合", "从拇指扫到小指", buildFanOpenSteps()),
        PresetAction("counting", "手指数数", "从 1 到 5 展开", buildCountingSteps()),
        PresetAction("fist_release", "逐指松拳", "握拳后逐个松开", buildFistReleaseSteps()),
        PresetAction("typing", "打字节奏", "四组键击模式", buildTypingSteps()),
        PresetAction("piano", "钢琴", "按 SDK 钢琴顺序敲击", buildPianoSteps()),
        PresetAction("spiral", "螺旋", "手指螺旋收放", buildSpiralSteps()),
        PresetAction("clap", "拍手", "快速开合两次", buildClapSteps()),
        PresetAction("emoji_showcase", "表情组", "参考 SDK gesture_emoji_imitation", buildEmojiShowcaseSteps()),
        PresetAction("conducting", "指挥", "四拍指挥手势", buildConductingSteps()),
        PresetAction("guitar_strum", "扫弦", "从拇指到小指往返扫弦", buildGuitarStrumSteps()),
        PresetAction("drum_roll", "滚奏", "四指连续敲击", buildDrumRollSteps()),
        PresetAction("finger_dance", "手指舞", "三段式波形组合", buildFingerDanceSteps()),
        PresetAction("random_dance", "随机舞蹈", "参考 SDK random_dance", buildRandomDanceSteps()),
        PresetAction("ball_grip", "球抓", "SDK 物体抓取：球体", listOf(step(260, naturalOpen), step(650, objectBallPose), step(240, naturalOpen), step(320, relaxedOpen))),
        PresetAction("pen_grip", "笔抓", "SDK 物体抓取：笔", listOf(step(260, naturalOpen), step(650, objectPenPose), step(240, naturalOpen), step(320, relaxedOpen))),
        PresetAction("card_grip", "卡抓", "SDK 物体抓取：卡片", listOf(step(260, naturalOpen), step(650, objectCardPose), step(240, naturalOpen), step(320, relaxedOpen))),
        PresetAction("magic_vanish", "消失", "SDK 魔术单动作", listOf(step(520, naturalOpen), step(260, magicVanishPose), step(260, naturalOpen), step(300, relaxedOpen))),
        PresetAction("magic_appear", "出现", "SDK 魔术单动作", listOf(step(520, powerFist), step(320, magicAppearPose), step(260, naturalOpen), step(300, relaxedOpen))),
        PresetAction("magic_pass", "穿越", "SDK 魔术单动作", listOf(step(520, magicPassPose), step(280, naturalOpen), step(300, relaxedOpen))),
        PresetAction("magic_trick", "魔术", "参考 SDK artistic_magic_tricks", buildMagicTrickSteps()),
        PresetAction("morse_sos", "摩斯 SOS", "参考 SDK sports_morse_code", buildMorseSosSteps()),
        PresetAction("sign_showcase", "手语组", "参考 SDK gesture_sign_language_basic", buildSignLanguageShowcaseSteps()),
        PresetAction("memory_sequence", "记忆序列", "参考 SDK game_memory_sequence", buildMemorySequenceSteps()),
        PresetAction("reaction_pulse", "反应测试", "参考 SDK game_reaction_game", buildReactionPulseSteps()),
        PresetAction("whack_a_mole", "打地鼠", "参考 SDK game_whack_a_mole", buildWhackAMoleSteps()),
        PresetAction("joint_isolation", "单关节", "参考 SDK demo_joint_isolation_test", buildJointIsolationSteps()),
        PresetAction("range_motion", "活动范围", "参考 SDK demo_range_of_motion", buildRangeMotionSteps()),
        PresetAction("speed_burst", "速度测试", "参考 SDK demo_speed_calibration", buildSpeedCalibrationSteps()),
        PresetAction("screw_twist", "拧螺丝", "参考 SDK precision_screw_twist", buildScrewTwistSteps()),
        PresetAction("object_grip", "物体抓取", "参考 SDK precision_object_manipulation", buildObjectGripSteps()),
        PresetAction("hello", "你好", "参考 SDK 基础手语", listOf(step(500, helloPose), step(300, relaxedOpen))),
        PresetAction("thanks", "谢谢", "参考 SDK 基础手语", listOf(step(500, thanksPose), step(300, relaxedOpen))),
        PresetAction("love_you", "我爱你", "参考 SDK 基础手语", listOf(step(500, loveYouPose), step(300, relaxedOpen)))
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
