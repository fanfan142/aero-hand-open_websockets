package com.aerohand.gesture

import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory

@OptIn(TransformExperimental::class)
internal fun gesturePreviewTransformFactory(): ImageProxyTransformFactory =
    ImageProxyTransformFactory().apply {
        // The bitmap is cropped manually; MediaPipe returns unrotated crop-relative points.
        setUsingCropRect(true)
        setUsingRotationDegrees(false)
    }
