package com.aerohand.gesture

import android.util.Log

/** Compatibility entry point; actual 21-landmark to seven-channel mapping lives in AeroHandRetargeter. */
object GestureAngleEstimator {
    private const val TAG = "GestureAngleEstimator"

    fun estimate(landmarks: List<GestureLandmark>, handedness: String): FingerAngles {
        val physicalHand = canonicalHandedness(handedness)
        if (landmarks.size != 21 || physicalHand.isBlank()) {
            Log.w(TAG, "Cannot retarget landmarks without 21 points and known physical handedness")
            return FingerAngles()
        }
        return AeroHandRetargeter.estimate(landmarks, physicalHand)
    }

    fun canonicalHandedness(hand: String): String = when {
        hand.equals("Left", ignoreCase = true) -> "Left"
        hand.equals("Right", ignoreCase = true) -> "Right"
        else -> ""
    }
}
