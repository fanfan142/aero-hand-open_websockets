package com.aerohand.gesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

// 7 actuation values matching AeroHandConstants:
// 0: thumb_cmc_abd (拇指外展)
// 1: thumb_cmc_flex (拇指CMC屈曲)
// 2: thumb_tendon (拇指肌腱)
// 3: index_tendon (食指肌腱)
// 4: middle_tendon (中指肌腱)
// 5: ring_tendon (无名指肌腱)
// 6: pinky_tendon (小指肌腱)
data class FingerAngles(
    val thumbAbd: Float = 0f,      // 0: thumb_cmc_abd
    val thumbCmcFlex: Float = 0f, // 1: thumb_cmc_flex
    val thumbTendon: Float = 0f,  // 2: thumb_tendon
    val indexTendon: Float = 0f,   // 3: index_tendon
    val middleTendon: Float = 0f,   // 4: middle_tendon
    val ringTendon: Float = 0f,    // 5: ring_tendon
    val pinkyTendon: Float = 0f    // 6: pinky_tendon
)

data class GestureCameraState(
    val isRunning: Boolean = false,
    val handDetected: Boolean = false,
    val handedness: String = "",  // normalized detected hand: "Left" or "Right"
    val targetHand: GestureTargetHand = GestureTargetHand.AUTO,
    val targetHandMatched: Boolean = true,
    val feedbackMessage: String = "",
    val rawAngles: FingerAngles = FingerAngles(),
    val smoothedAngles: FingerAngles = FingerAngles(),
    val calibratedAngles: FingerAngles = FingerAngles(),  // display + control range after calibration
    val calibrationState: CalibrationState = CalibrationState.NOT_CALIBRATED,
    val fps: Float = 0f,
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
