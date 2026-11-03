package com.aerohand.gesture

import android.graphics.Bitmap

/** Exact per-hand MediaPipe result, before preview transforms or Aero Hand retargeting.
 * The category is the model's original label; it is not silently swapped.
 */
data class MediaPipeRawHand(
    val normalizedLandmarks: List<GestureLandmark>,
    val category: String,
    val confidence: Float,
    val worldLandmarks: List<GestureLandmark> = emptyList()
)

data class TrackedHand(val raw: MediaPipeRawHand) {
    val landmarks: List<GestureLandmark> get() = raw.normalizedLandmarks
    val handedness: String get() = raw.category
    val confidence: Float get() = raw.confidence
}

interface HandTracker : AutoCloseable {
    val backendName: String

    fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int,
        timestampMs: Long
    ): List<TrackedHand>
}
