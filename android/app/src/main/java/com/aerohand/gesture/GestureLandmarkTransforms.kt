package com.aerohand.gesture

object GestureLandmarkTransforms {
    @Suppress("UNUSED_PARAMETER")
    fun forPreview(
        landmarks: List<GestureLandmark>,
        rotationDegrees: Int,
        mirrorMode: GestureMirrorMode
    ): List<GestureLandmark> {
        if (landmarks.isEmpty()) return landmarks
        // MediaPipe 的 ImageProcessingOptions 已经按 ImageProxy.rotationDegrees 处理图像。
        // 返回的归一化坐标属于已处理后的图像坐标系，绘制层只需要按预览尺寸缩放。
        return landmarks
    }
}
