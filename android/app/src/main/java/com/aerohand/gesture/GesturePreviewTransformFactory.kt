package com.aerohand.gesture

import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory

@OptIn(TransformExperimental::class)
internal fun gesturePreviewTransformFactory(): ImageProxyTransformFactory =
    ImageProxyTransformFactory().apply {
        setUsingCropRect(true)
        setUsingRotationDegrees(true)
    }
