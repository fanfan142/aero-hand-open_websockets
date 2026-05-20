package com.aerohand.gesture

import android.util.Log
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

object GestureAngleEstimator {
    private const val TAG = "GestureAngleEstimator"

    fun estimate(landmarks: List<GestureLandmark>, handedness: String): FingerAngles {
        if (landmarks.size < 21) {
            Log.w(TAG, "Unexpected landmarks size: ${landmarks.size}")
            return FingerAngles()
        }

        val normalizedHand = canonicalHandedness(handedness).ifBlank {
            inferHandedness(landmarks)
        }

        val palmAxis = vector(landmarks[13], landmarks[5])
        val thumbAxis = normalize(vector(landmarks[1], landmarks[2]))
        val palmAxisNorm = normalize(palmAxis)
        val imageThumbAngle = Math.toDegrees(
            atan2(
                (palmAxisNorm.first * thumbAxis.second - palmAxisNorm.second * thumbAxis.first).toDouble(),
                (palmAxisNorm.first * thumbAxis.first + palmAxisNorm.second * thumbAxis.second).toDouble()
            )
        ).toFloat()
        val handSign = if (normalizedHand == "Right") -1f else 1f
        val thumbSwing = imageThumbAngle * handSign
        val thumbAbd = ((thumbSwing + 45f) / 90f * 100f).coerceIn(0f, 100f)

        return FingerAngles(
            thumbAbd = thumbAbd,
            thumbCmcFlex = (flexionDegrees(landmarks[0], landmarks[1], landmarks[2]) * (55f / 90f)).coerceIn(0f, 55f),
            thumbTendon = flexionDegrees(landmarks[2], landmarks[3], landmarks[4]).coerceIn(0f, 90f),
            indexTendon = flexionDegrees(landmarks[5], landmarks[6], landmarks[7]).coerceIn(0f, 90f),
            middleTendon = flexionDegrees(landmarks[9], landmarks[10], landmarks[11]).coerceIn(0f, 90f),
            ringTendon = flexionDegrees(landmarks[13], landmarks[14], landmarks[15]).coerceIn(0f, 90f),
            pinkyTendon = flexionDegrees(landmarks[17], landmarks[18], landmarks[19]).coerceIn(0f, 90f),
            thumbSwing = thumbSwing
        )
    }

    fun canonicalHandedness(hand: String): String {
        return when {
            hand.equals("Left", ignoreCase = true) -> "Left"
            hand.equals("Right", ignoreCase = true) -> "Right"
            else -> ""
        }
    }

    fun inferHandedness(landmarks: List<GestureLandmark>): String {
        if (landmarks.size < 21) return ""
        val indexX = landmarks[5].x
        val pinkyX = landmarks[17].x
        val thumbX = landmarks[4].x
        val palmWidth = kotlin.math.abs(indexX - pinkyX)
        if (palmWidth < 0.03f) return ""
        val thumbOutsideLeft = thumbX < minOf(indexX, pinkyX)
        val thumbOutsideRight = thumbX > maxOf(indexX, pinkyX)
        return when {
            thumbOutsideLeft -> "Right"
            thumbOutsideRight -> "Left"
            else -> ""
        }
    }

    private fun angleDegrees(p1: GestureLandmark, p2: GestureLandmark, p3: GestureLandmark): Float {
        val v1x = p1.x - p2.x
        val v1y = p1.y - p2.y
        val v2x = p3.x - p2.x
        val v2y = p3.y - p2.y
        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 < 0.0001f || mag2 < 0.0001f) return 0f
        val cosVal = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosVal.toDouble())).toFloat()
    }

    private fun flexionDegrees(p1: GestureLandmark, p2: GestureLandmark, p3: GestureLandmark): Float {
        return (180f - angleDegrees(p1, p2, p3)).coerceIn(0f, 90f)
    }

    private fun vector(from: GestureLandmark, to: GestureLandmark): Pair<Float, Float> {
        return (to.x - from.x) to (to.y - from.y)
    }

    private fun normalize(vec: Pair<Float, Float>): Pair<Float, Float> {
        val mag = sqrt(vec.first * vec.first + vec.second * vec.second)
        return if (mag < 0.0001f) 0f to 0f else (vec.first / mag) to (vec.second / mag)
    }
}
