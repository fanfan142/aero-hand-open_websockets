package com.aerohand.gesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

data class FingerAngles(
    val thumbAbd: Float = 0f,
    val thumbFlex: Float = 0f,
    val indexFlex: Float = 0f,
    val middleFlex: Float = 0f,
    val ringFlex: Float = 0f,
    val pinkyFlex: Float = 0f
)

data class GestureCameraState(
    val isRunning: Boolean = false,
    val hasPermission: Boolean = false,
    val handDetected: Boolean = false,
    val rawAngles: FingerAngles = FingerAngles(),
    val smoothedAngles: FingerAngles = FingerAngles(),
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
