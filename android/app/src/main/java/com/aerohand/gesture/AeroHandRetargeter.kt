package com.aerohand.gesture

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** Maps MediaPipe's 21 unmodified image landmarks to Aero Hand's seven low-DOF channels.
 * Calibration, smoothing and actuator tuning remain separate downstream steps.
 */
object AeroHandRetargeter {
    fun retarget(hand: MediaPipeRawHand, physicalHand: String): FingerAngles {
        require(hand.normalizedLandmarks.size == 21)
        require(physicalHand == "Left" || physicalHand == "Right")
        return estimate(hand.normalizedLandmarks, physicalHand)
    }

    internal fun estimate(landmarks: List<GestureLandmark>, physicalHand: String): FingerAngles {
        if (landmarks.size < 21) return FingerAngles()
        val palmAxis = normalize(vector(landmarks[13], landmarks[5]))
        val thumbAxis = normalize(vector(landmarks[1], landmarks[2]))
        val imageThumbAngle = Math.toDegrees(
            atan2(
                (palmAxis.first * thumbAxis.second - palmAxis.second * thumbAxis.first).toDouble(),
                (palmAxis.first * thumbAxis.first + palmAxis.second * thumbAxis.second).toDouble()
            )
        ).toFloat()
        val thumbSwing = imageThumbAngle * if (physicalHand == "Right") -1f else 1f
        return FingerAngles(
            thumbAbd = ((thumbSwing + 45f) / 90f * 100f).coerceIn(0f, 100f),
            thumbCmcFlex = (flexionDegrees(landmarks[0], landmarks[1], landmarks[2]) * (55f / 90f)).coerceIn(0f, 55f),
            thumbTendon = flexionDegrees(landmarks[2], landmarks[3], landmarks[4]).coerceIn(0f, 90f),
            indexTendon = flexionDegrees(landmarks[5], landmarks[6], landmarks[7]).coerceIn(0f, 90f),
            middleTendon = flexionDegrees(landmarks[9], landmarks[10], landmarks[11]).coerceIn(0f, 90f),
            ringTendon = flexionDegrees(landmarks[13], landmarks[14], landmarks[15]).coerceIn(0f, 90f),
            pinkyTendon = flexionDegrees(landmarks[17], landmarks[18], landmarks[19]).coerceIn(0f, 90f),
            thumbSwing = thumbSwing
        )
    }

    private fun flexionDegrees(a: GestureLandmark, b: GestureLandmark, c: GestureLandmark): Float {
        val ax = a.x - b.x
        val ay = a.y - b.y
        val cx = c.x - b.x
        val cy = c.y - b.y
        val magnitude = sqrt(ax * ax + ay * ay) * sqrt(cx * cx + cy * cy)
        if (magnitude < 0.0001f) return 0f
        val angle = Math.toDegrees(acos(((ax * cx + ay * cy) / magnitude).coerceIn(-1f, 1f).toDouble())).toFloat()
        return (180f - angle).coerceIn(0f, 90f)
    }

    private fun vector(a: GestureLandmark, b: GestureLandmark): Pair<Float, Float> = b.x - a.x to b.y - a.y

    private fun normalize(v: Pair<Float, Float>): Pair<Float, Float> {
        val length = sqrt(v.first * v.first + v.second * v.second)
        return if (length < 0.0001f) 0f to 0f else v.first / length to v.second / length
    }
}
