package com.aerohand.gesture

data class FingerAngles(
    val thumbAbd: Float = 0f,
    val thumbCmcFlex: Float = 0f,
    val thumbTendon: Float = 0f,
    val indexTendon: Float = 0f,
    val middleTendon: Float = 0f,
    val ringTendon: Float = 0f,
    val pinkyTendon: Float = 0f,
    val thumbSwing: Float = 0f
) {
    fun toControlArray(): FloatArray = floatArrayOf(
        thumbAbd,
        thumbCmcFlex,
        thumbTendon,
        indexTendon,
        middleTendon,
        ringTendon,
        pinkyTendon
    )
}

data class GestureLandmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

enum class GestureCameraFacing(val label: String) {
    FRONT("前摄"),
    BACK("后摄")
}

enum class GestureMirrorMode(val label: String) {
    SELFIE("自拍镜像"),
    NORMAL("正常")
}

enum class GestureTuningChannel(
    val label: String,
    val maxValue: Float
) {
    THUMB_ABD("拇指外展", 100f),
    THUMB_CMC_FLEX("拇指CMC", 55f),
    THUMB_TENDON("拇指肌腱", 90f),
    INDEX_TENDON("食指", 90f),
    MIDDLE_TENDON("中指", 90f),
    RING_TENDON("无名指", 90f),
    PINKY_TENDON("小指", 90f);

    companion object {
        fun fromIndex(index: Int): GestureTuningChannel? = entries.getOrNull(index)
    }
}

data class GestureTuningProfile(
    val schemaVersion: Int = 1,
    val gains: FloatArray = FloatArray(GestureTuningChannel.entries.size) { 1f },
    val offsets: FloatArray = FloatArray(GestureTuningChannel.entries.size) { 0f }
) {
    fun gainAt(channel: GestureTuningChannel): Float = gains.getOrElse(channel.ordinal) { 1f }

    fun offsetAt(channel: GestureTuningChannel): Float = offsets.getOrElse(channel.ordinal) { 0f }

    fun withAdjustment(
        channel: GestureTuningChannel,
        gainDelta: Float,
        offsetDelta: Float
    ): GestureTuningProfile {
        val nextGains = gains.copyOf(GestureTuningChannel.entries.size)
        val nextOffsets = offsets.copyOf(GestureTuningChannel.entries.size)
        nextGains[channel.ordinal] = (nextGains[channel.ordinal] + gainDelta).coerceIn(0.55f, 1.65f)
        nextOffsets[channel.ordinal] = (nextOffsets[channel.ordinal] + offsetDelta).coerceIn(-25f, 25f)
        return copy(gains = nextGains, offsets = nextOffsets)
    }

    fun applyTo(angles: FingerAngles): FingerAngles {
        val raw = angles.toControlArray()
        val tuned = FloatArray(GestureTuningChannel.entries.size)
        GestureTuningChannel.entries.forEach { channel ->
            tuned[channel.ordinal] = (raw[channel.ordinal] * gainAt(channel) + offsetAt(channel))
                .coerceIn(0f, channel.maxValue)
        }
        return anglesFromArray(tuned, angles.thumbSwing)
    }

    companion object {
        fun default(): GestureTuningProfile = GestureTuningProfile()
    }
}

data class GestureCalibrationProfile(
    val schemaVersion: Int = 5,
    val handSide: String = "",
    val cameraFacing: GestureCameraFacing = GestureCameraFacing.FRONT,
    val mirrorMode: GestureMirrorMode = GestureMirrorMode.SELFIE,
    val openAngles: FloatArray = FloatArray(GestureTuningChannel.entries.size) { 0f },
    val fistAngles: FloatArray = FloatArray(GestureTuningChannel.entries.size) { 0f },
    val openThumbSwing: Float = 0f,
    val thumbInSwing: Float = 0f
) {
    val key: String
        get() = profileKey(handSide, cameraFacing, mirrorMode)

    fun matchesHand(hand: String): Boolean = handSide.equals(hand, ignoreCase = true)

    fun matchesContext(hand: String, facing: GestureCameraFacing, mirror: GestureMirrorMode): Boolean {
        return matchesHand(hand) && cameraFacing == facing && mirrorMode == mirror
    }

    companion object {
        fun profileKey(
            hand: String,
            facing: GestureCameraFacing,
            mirror: GestureMirrorMode
        ): String = "${hand.lowercase()}_${facing.name}_${mirror.name}"
    }
}

data class GestureControlFrame(
    val allowed: Boolean,
    val angles: FingerAngles = FingerAngles(),
    val message: String = "",
    val capturedAtMs: Long = 0L
)

data class GestureCameraState(
    val isRunning: Boolean = false,
    val handDetected: Boolean = false,
    val handedness: String = "",
    val targetHand: GestureTargetHand = GestureTargetHand.AUTO,
    val targetHandMatched: Boolean = true,
    val feedbackMessage: String = "",
    val rawAngles: FingerAngles = FingerAngles(),
    val smoothedAngles: FingerAngles = FingerAngles(),
    val calibratedAngles: FingerAngles = FingerAngles(),
    val calibrationState: CalibrationState = CalibrationState.NOT_CALIBRATED,
    val calibrationProfile: GestureCalibrationProfile? = null,
    val tuningProfile: GestureTuningProfile = GestureTuningProfile.default(),
    val cameraFacing: GestureCameraFacing = GestureCameraFacing.FRONT,
    val mirrorMode: GestureMirrorMode = GestureMirrorMode.SELFIE,
    val fps: Float = 0f,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val trackerBackend: String = "",
    val landmarks: List<GestureLandmark> = emptyList()
)

enum class CalibrationState {
    NOT_CALIBRATED,
    CALIBRATING_OPEN,
    CALIBRATING_FIST,
    CALIBRATING_THUMB_IN,
    CALIBRATED
}

enum class GestureTargetHand(val label: String) {
    AUTO("自动"),
    LEFT("左手"),
    RIGHT("右手");

    fun matches(detectedHand: String): Boolean {
        return when (this) {
            AUTO -> true
            LEFT -> detectedHand.equals("Left", ignoreCase = true)
            RIGHT -> detectedHand.equals("Right", ignoreCase = true)
        }
    }
}

fun anglesFromArray(values: FloatArray, thumbSwing: Float): FingerAngles {
    return FingerAngles(
        thumbAbd = values.getOrElse(0) { 0f }.coerceIn(0f, GestureTuningChannel.THUMB_ABD.maxValue),
        thumbCmcFlex = values.getOrElse(1) { 0f }.coerceIn(0f, GestureTuningChannel.THUMB_CMC_FLEX.maxValue),
        thumbTendon = values.getOrElse(2) { 0f }.coerceIn(0f, GestureTuningChannel.THUMB_TENDON.maxValue),
        indexTendon = values.getOrElse(3) { 0f }.coerceIn(0f, GestureTuningChannel.INDEX_TENDON.maxValue),
        middleTendon = values.getOrElse(4) { 0f }.coerceIn(0f, GestureTuningChannel.MIDDLE_TENDON.maxValue),
        ringTendon = values.getOrElse(5) { 0f }.coerceIn(0f, GestureTuningChannel.RING_TENDON.maxValue),
        pinkyTendon = values.getOrElse(6) { 0f }.coerceIn(0f, GestureTuningChannel.PINKY_TENDON.maxValue),
        thumbSwing = thumbSwing
    )
}
