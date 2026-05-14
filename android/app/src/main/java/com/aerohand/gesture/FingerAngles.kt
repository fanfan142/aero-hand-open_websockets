package com.aerohand.gesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

data class FingerAngles(
    val thumbAbd: Float = 0f,
    val thumbCmcFlex: Float = 0f,
    val thumbTendon: Float = 0f,
    val indexTendon: Float = 0f,
    val middleTendon: Float = 0f,
    val ringTendon: Float = 0f,
    val pinkyTendon: Float = 0f,
    val thumbSwing: Float = 0f
)

enum class GestureCameraFacing(val label: String) {
    FRONT("前摄"),
    BACK("后摄")
}

enum class GestureMirrorMode(val label: String) {
    SELFIE("自拍镜像"),
    NORMAL("正常")
}

data class GestureCalibrationProfile(
    val schemaVersion: Int = 2,
    val handSide: String = "",
    val cameraFacing: GestureCameraFacing = GestureCameraFacing.FRONT,
    val mirrorMode: GestureMirrorMode = GestureMirrorMode.SELFIE,
    val openAngles: FloatArray = FloatArray(7) { 0f },
    val fistAngles: FloatArray = FloatArray(7) { 0f },
    val openThumbSwing: Float = 0f,
    val thumbInSwing: Float = 0f
) {
    fun matchesHand(hand: String): Boolean = handSide.equals(hand, ignoreCase = true)

    fun matchesContext(hand: String, facing: GestureCameraFacing, mirror: GestureMirrorMode): Boolean {
        return matchesHand(hand) && cameraFacing == facing && mirrorMode == mirror
    }
}

data class GestureControlFrame(
    val allowed: Boolean,
    val angles: FingerAngles = FingerAngles(),
    val message: String = ""
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
    val cameraFacing: GestureCameraFacing = GestureCameraFacing.FRONT,
    val mirrorMode: GestureMirrorMode = GestureMirrorMode.SELFIE,
    val fps: Float = 0f,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val landmarks: List<NormalizedLandmark> = emptyList()
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
