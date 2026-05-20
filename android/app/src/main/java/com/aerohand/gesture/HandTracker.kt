package com.aerohand.gesture

import android.graphics.Bitmap

data class TrackedHand(
    val landmarks: List<GestureLandmark>,
    val handedness: String,
    val confidence: Float
)

interface HandTracker : AutoCloseable {
    val backendName: String

    fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int,
        timestampMs: Long
    ): List<TrackedHand>
}
