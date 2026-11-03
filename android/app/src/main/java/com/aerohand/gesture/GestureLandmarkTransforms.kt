package com.aerohand.gesture

/** MediaPipe landmarks are normalized to the cropped detection bitmap.
 * CameraX maps those crop-relative coordinates to the visible PreviewView bounds.
 * The raw MediaPipe normalized/world landmarks are never mutated.
 */
object GestureLandmarkTransforms {
    fun forPreview(
        landmarks: List<GestureLandmark>,
        cropWidth: Int,
        cropHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        mapPoint: (FloatArray) -> Unit
    ): List<GestureLandmark> {
        if (cropWidth <= 0 || cropHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) {
            return emptyList()
        }
        return landmarks.map { landmark ->
            val point = floatArrayOf(
                landmark.x * cropWidth,
                landmark.y * cropHeight
            )
            mapPoint(point)
            landmark.copy(x = point[0] / previewWidth, y = point[1] / previewHeight)
        }
    }
}
