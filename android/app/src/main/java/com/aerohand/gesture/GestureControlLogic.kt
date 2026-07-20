package com.aerohand.gesture

import kotlin.math.abs
import kotlin.math.roundToInt

internal class GestureSmoother(
    private val emaAlpha: FloatArray = floatArrayOf(0.46f, 0.44f, 0.42f, 0.42f, 0.42f, 0.42f, 0.42f),
    private val deadband: FloatArray = floatArrayOf(0.35f, 0.3f, 0.4f, 0.45f, 0.45f, 0.45f, 0.45f),
    private val thumbSwingAlpha: Float = 0.42f,
    private val thumbSwingDeadband: Float = 0.4f
) {
    private val values = FloatArray(GestureTuningChannel.entries.size)
    private var thumbSwing = 0f
    private var initialized = false

    init {
        require(emaAlpha.size == values.size)
        require(deadband.size == values.size)
    }

    fun apply(angles: FingerAngles): FingerAngles {
        val raw = angles.toControlArray()
        if (!initialized) {
            raw.copyInto(values)
            thumbSwing = angles.thumbSwing
            initialized = true
            return anglesFromArray(values, thumbSwing)
        }

        for (index in raw.indices) {
            if (abs(raw[index] - values[index]) >= deadband[index]) {
                values[index] += (raw[index] - values[index]) * emaAlpha[index]
            }
        }
        if (abs(angles.thumbSwing - thumbSwing) >= thumbSwingDeadband) {
            thumbSwing += (angles.thumbSwing - thumbSwing) * thumbSwingAlpha
        }
        return anglesFromArray(values, thumbSwing)
    }

    fun currentValues(): FloatArray = values.copyOf()

    fun currentThumbSwing(): Float = thumbSwing

    fun reset() {
        values.fill(0f)
        thumbSwing = 0f
        initialized = false
    }
}

internal object GestureCalibrationMapper {
    private const val MIN_RANGE_DEGREES = 12f

    fun validate(profile: GestureCalibrationProfile): String? {
        GestureTuningChannel.entries
            .filter { it != GestureTuningChannel.THUMB_ABD }
            .forEach { channel ->
                val index = channel.ordinal
                if (abs(profile.fistAngles[index] - profile.openAngles[index]) < MIN_RANGE_DEGREES) {
                    return "校准失败：${channel.label}张开/握拳差值太小，请重新记录"
                }
            }
        if (abs(profile.openThumbSwing - profile.thumbInSwing) < MIN_RANGE_DEGREES) {
            return "校准失败：拇指内收幅度太小，请重新记录"
        }
        return null
    }

    fun remap(
        profile: GestureCalibrationProfile,
        values: FloatArray,
        thumbSwing: Float
    ): FingerAngles {
        val mapped = FloatArray(GestureTuningChannel.entries.size)
        GestureTuningChannel.entries.forEach { channel ->
            val index = channel.ordinal
            val range = profile.fistAngles[index] - profile.openAngles[index]
            mapped[index] = if (abs(range) < 0.001f) {
                0f
            } else {
                ((values[index] - profile.openAngles[index]) / range * channel.maxValue)
                    .coerceIn(0f, channel.maxValue)
            }
        }

        val thumbSwingRange = profile.openThumbSwing - profile.thumbInSwing
        if (abs(thumbSwingRange) >= MIN_RANGE_DEGREES) {
            mapped[GestureTuningChannel.THUMB_ABD.ordinal] =
                ((profile.openThumbSwing - thumbSwing) / thumbSwingRange * GestureTuningChannel.THUMB_ABD.maxValue)
                    .coerceIn(0f, GestureTuningChannel.THUMB_ABD.maxValue)
        }
        return anglesFromArray(mapped, thumbSwing)
    }
}

internal object GestureCalibrationProfiles {
    fun exact(
        profiles: Map<String, GestureCalibrationProfile>,
        hand: String,
        facing: GestureCameraFacing,
        mirror: GestureMirrorMode
    ): GestureCalibrationProfile? {
        if (hand.isBlank()) return null
        return profiles[GestureCalibrationProfile.profileKey(hand, facing, mirror)]
    }

    fun sameHand(
        profiles: Map<String, GestureCalibrationProfile>,
        hand: String,
        activeKey: String?
    ): GestureCalibrationProfile? {
        if (hand.isBlank()) return null
        val active = activeKey?.let(profiles::get)
        return active?.takeIf { it.matchesHand(hand) }
            ?: profiles.values.firstOrNull { it.matchesHand(hand) }
    }
}

internal object GestureHandednessResolver {
    fun resolve(
        rawHandedness: String,
        inferredHandedness: String,
        mirrorMode: GestureMirrorMode
    ): String {
        val raw = canonical(rawHandedness)
        if (raw.isNotBlank()) {
            return if (mirrorMode == GestureMirrorMode.SELFIE) swap(raw) else raw
        }
        return canonical(inferredHandedness)
    }

    private fun canonical(hand: String): String = when {
        hand.equals("Left", ignoreCase = true) -> "Left"
        hand.equals("Right", ignoreCase = true) -> "Right"
        else -> ""
    }

    private fun swap(hand: String): String = when (hand) {
        "Left" -> "Right"
        "Right" -> "Left"
        else -> ""
    }
}

internal data class GestureCommand(
    val values: Map<String, Float>,
    val durationMs: Int
)

internal class GestureCommandScheduler(
    private val minSendIntervalMs: Long = 16L,
    private val minDelta: Float = 0.35f,
    private val defaultDurationMs: Long = 50L,
    private val minDurationMs: Long = 40L,
    private val maxDurationMs: Long = 120L
) {
    private var ready = false
    private var lastSentValues: Map<String, Float>? = null
    private var lastSentAtMs = 0L
    private var lastFrameAtMs = 0L

    fun markReady() {
        if (!ready) {
            ready = true
            lastSentValues = null
            lastSentAtMs = 0L
            lastFrameAtMs = 0L
        }
    }

    fun plan(values: Map<String, Float>, nowMs: Long): GestureCommand? {
        val framePeriodMs = if (lastFrameAtMs > 0L) {
            (nowMs - lastFrameAtMs).coerceAtLeast(1L)
        } else {
            defaultDurationMs
        }
        lastFrameAtMs = nowMs
        if (!ready) return null

        val previous = lastSentValues
        val changedEnough = previous == null || values.any { (key, value) ->
            abs((previous[key] ?: value) - value) >= minDelta
        }
        if (!changedEnough) return null
        if (previous != null && nowMs - lastSentAtMs < minSendIntervalMs) return null

        val durationMs = (framePeriodMs * 1.25f).roundToInt().toLong()
            .coerceIn(minDurationMs, maxDurationMs)
            .toInt()
        return GestureCommand(values.toMap(), durationMs)
    }

    fun markSent(command: GestureCommand, sentAtMs: Long) {
        lastSentValues = command.values
        lastSentAtMs = sentAtMs
    }

    fun reset() {
        ready = false
        lastSentValues = null
        lastSentAtMs = 0L
        lastFrameAtMs = 0L
    }
}
