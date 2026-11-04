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
    private var lastHand: String? = null
    private var lastCapturedAtMs = 0L

    init {
        require(emaAlpha.size == values.size)
        require(deadband.size == values.size)
    }

    fun apply(angles: FingerAngles, hand: String? = null, capturedAtMs: Long = 0L): FingerAngles {
        if (hand != null && lastHand != null && hand != lastHand ||
            capturedAtMs > 0L && lastCapturedAtMs > 0L &&
                (capturedAtMs < lastCapturedAtMs || capturedAtMs - lastCapturedAtMs >= 240L)) {
            reset()
        }
        if (hand != null) lastHand = hand
        if (capturedAtMs > 0L) lastCapturedAtMs = capturedAtMs
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
        lastHand = null
        lastCapturedAtMs = 0L
    }
}

internal data class CalibrationCaptureContext(
    val hand: String,
    val facing: GestureCameraFacing,
    val mirrorMode: GestureMirrorMode,
    val targetHand: GestureTargetHand
)

internal sealed interface CalibrationCaptureResult {
    data class Collecting(
        val sampleCount: Int,
        val elapsedMs: Long,
        val restartedForInstability: Boolean = false
    ) : CalibrationCaptureResult
    data class Complete(val angles: FingerAngles, val elapsedMs: Long) : CalibrationCaptureResult
    data object Cancelled : CalibrationCaptureResult
    data object Ignored : CalibrationCaptureResult
}

internal class CalibrationPoseCapture(
    private val minimumSamples: Int = 5,
    private val minimumSampleIntervalMs: Long = 150L,
    private val minimumSpanMs: Long = 600L,
    private val maximumFrameGapMs: Long = 400L,
    private val maxChannelVariationDegrees: Float = 5f
) {
    private data class Sample(val angles: FingerAngles, val capturedAtMs: Long)

    private var context: CalibrationCaptureContext? = null
    private var armedAtMs = 0L
    private var lastSampleAtMs = 0L
    private val samples = mutableListOf<Sample>()

    val sampleCount: Int
        get() = samples.size

    val isCollecting: Boolean
        get() = context != null

    fun arm(context: CalibrationCaptureContext, armedAtMs: Long) {
        this.context = context
        this.armedAtMs = armedAtMs
        lastSampleAtMs = armedAtMs
        samples.clear()
    }

    fun cancel(): Boolean {
        val wasCollecting = context != null
        context = null
        samples.clear()
        armedAtMs = 0L
        lastSampleAtMs = 0L
        return wasCollecting
    }

    fun addFrame(
        context: CalibrationCaptureContext,
        targetMatched: Boolean,
        angles: FingerAngles,
        capturedAtMs: Long
    ): CalibrationCaptureResult {
        val armedContext = this.context ?: return CalibrationCaptureResult.Ignored
        if (!targetMatched || context != armedContext ||
            !(angles.toControlArray() + angles.thumbSwing).all { it.isFinite() }
        ) {
            cancel()
            return CalibrationCaptureResult.Cancelled
        }
        // Never accept a frame captured before (or at) the arming boundary.
        if (capturedAtMs <= armedAtMs) return CalibrationCaptureResult.Ignored
        if (capturedAtMs < lastSampleAtMs || capturedAtMs - lastSampleAtMs > maximumFrameGapMs) {
            cancel()
            return CalibrationCaptureResult.Cancelled
        }

        // Inspect even frames between sample slots, so a transient movement cannot be skipped.
        val candidateSamples = samples + Sample(angles, capturedAtMs)
        if (candidateSamples.size > 1 && !isStable(candidateSamples)) {
            samples.clear()
            samples += Sample(angles, capturedAtMs)
            lastSampleAtMs = capturedAtMs
            return CalibrationCaptureResult.Collecting(1, 0L, restartedForInstability = true)
        }
        if (samples.isNotEmpty() && capturedAtMs - lastSampleAtMs < minimumSampleIntervalMs) {
            return CalibrationCaptureResult.Collecting(samples.size, elapsedMs())
        }

        lastSampleAtMs = capturedAtMs
        samples += Sample(angles, capturedAtMs)
        val spanMs = elapsedMs()
        if (samples.size >= minimumSamples && spanMs >= minimumSpanMs) {
            val median = medianSample(samples.map { it.angles })
            val completedSpanMs = spanMs
            cancel()
            return CalibrationCaptureResult.Complete(median, completedSpanMs)
        }
        return CalibrationCaptureResult.Collecting(samples.size, spanMs)
    }

    private fun elapsedMs(): Long = if (samples.size < 2) {
        0L
    } else {
        samples.last().capturedAtMs - samples.first().capturedAtMs
    }

    private fun isStable(candidates: List<Sample>): Boolean {
        val channels = candidates.map { sample ->
            sample.angles.toControlArray() + sample.angles.thumbSwing
        }
        return channels.first().indices.all { index ->
            val values = channels.map { it[index] }
            values.maxOrNull()!! - values.minOrNull()!! <= maxChannelVariationDegrees
        }
    }

    private fun medianSample(candidates: List<FingerAngles>): FingerAngles {
        val values = FloatArray(GestureTuningChannel.entries.size) { index ->
            median(candidates.map { it.toControlArray()[index] })
        }
        return anglesFromArray(values, median(candidates.map { it.thumbSwing }))
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5f
        } else {
            sorted[middle]
        }
    }
}

internal data class CalibrationValidationFailure(
    val message: String,
    val retryState: CalibrationRetryState
)

internal object GestureCalibrationMapper {
    private const val MIN_RANGE_DEGREES = 12f
    private const val THUMB_POSE_FINGER_TOLERANCE_DEGREES = 18f

    fun validateOpenFist(profile: GestureCalibrationProfile): CalibrationValidationFailure? {
        // Thumb CMC is a 2D proxy and often barely changes during a correct fist.
        // Four-finger flexion still has to move in the closing direction.
        GestureTuningChannel.entries
            .filter { it != GestureTuningChannel.THUMB_ABD && it != GestureTuningChannel.THUMB_CMC_FLEX }
            .forEach { channel ->
                val index = channel.ordinal
                val difference = profile.fistAngles[index] - profile.openAngles[index]
                val validRange = if (index >= GestureTuningChannel.INDEX_TENDON.ordinal) {
                    difference >= MIN_RANGE_DEGREES
                } else {
                    abs(difference) >= MIN_RANGE_DEGREES
                }
                if (!validRange) {
                    return CalibrationValidationFailure(
                        "校准失败：${channel.label}张开/握拳范围不足或方向相反，请确认张开后再握拳，并重采该姿势",
                        CalibrationRetryState.OPEN_FIST
                    )
                }
            }
        return null
    }

    fun validateThumbPose(openAngles: FloatArray, thumbInAngles: FloatArray): CalibrationValidationFailure? {
        GestureTuningChannel.entries
            .filter { it.ordinal >= GestureTuningChannel.INDEX_TENDON.ordinal }
            .forEach { channel ->
                val index = channel.ordinal
                if (abs(thumbInAngles.getOrElse(index) { 0f } - openAngles.getOrElse(index) { 0f }) >
                    THUMB_POSE_FINGER_TOLERANCE_DEGREES
                ) {
                    return CalibrationValidationFailure(
                        "校准失败：拇指内收时请保持其它手指张开，并重新采集",
                        CalibrationRetryState.THUMB_IN
                    )
                }
            }
        return null
    }

    fun validateThumbRange(profile: GestureCalibrationProfile): CalibrationValidationFailure? {
        val inwardRange = profile.thumbInSwing - profile.openThumbSwing
        if (!inwardRange.isFinite() || inwardRange < MIN_RANGE_DEGREES) {
            return CalibrationValidationFailure(
                "校准失败：拇指内收幅度不足或方向相反，请保持掌心朝向镜头，让拇指靠向食指后重新采集",
                CalibrationRetryState.THUMB_IN
            )
        }
        return null
    }

    fun validate(profile: GestureCalibrationProfile): String? =
        (validateOpenFist(profile) ?: validateThumbRange(profile))?.message

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
        if (thumbSwingRange <= -MIN_RANGE_DEGREES) {
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
    /** MediaPipe Tasks labels the subject's hand; swap only when input pixels were mirrored. */
    fun resolve(rawHandedness: String, inputMirrored: Boolean): String {
        val raw = canonical(rawHandedness)
        if (raw.isBlank() || !inputMirrored) return raw
        return if (raw == "Left") "Right" else "Left"
    }

    private fun canonical(hand: String): String = when {
        hand.equals("Left", ignoreCase = true) -> "Left"
        hand.equals("Right", ignoreCase = true) -> "Right"
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
