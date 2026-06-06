package com.aerohand.gesture

object GestureLandmarkTransforms {
    fun forPreview(
        landmarks: List<GestureLandmark>,
        rotationDegrees: Int,
        mirrorMode: GestureMirrorMode
    ): List<GestureLandmark> {
        if (landmarks.isEmpty()) return landmarks
        // PreviewView 与 ImageAnalysis 通过同一个 ViewPort 绑定后，水平方向已经一致；
        // 这里只隔离原先耦合在 tracker 里的显示旋转，避免污染舵机控制角度。
        @Suppress("UNUSED_VARIABLE")
        val sharedViewportMirrorMode = mirrorMode
        return landmarks.map { rotateForDisplay(it, rotationDegrees) }
    }

    fun rotateForDisplay(landmark: GestureLandmark, rotationDegrees: Int): GestureLandmark {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        return when (normalizedRotation) {
            90 -> landmark.copy(x = 1f - landmark.y, y = landmark.x)
            180 -> landmark.copy(x = 1f - landmark.x, y = 1f - landmark.y)
            270 -> landmark.copy(x = landmark.y, y = 1f - landmark.x)
            else -> landmark
        }
    }
}
