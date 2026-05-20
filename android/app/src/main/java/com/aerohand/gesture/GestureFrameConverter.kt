package com.aerohand.gesture

import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

data class GestureFrame(
    val bitmap: Bitmap,
    val sourceSize: Size,
    val detectionSize: Size,
    val displaySize: Size,
    val rotationDegrees: Int
)

class GestureFrameConverter(
    private val maxDetectionSide: Int
) {
    fun convert(imageProxy: ImageProxy): GestureFrame {
        val bitmap = rgbaImageProxyToBitmap(imageProxy)
        val rotation = imageProxy.imageInfo.rotationDegrees
        val displaySize = if (rotation == 90 || rotation == 270) {
            Size(bitmap.height, bitmap.width)
        } else {
            Size(bitmap.width, bitmap.height)
        }
        return GestureFrame(
            bitmap = bitmap,
            sourceSize = Size(imageProxy.width, imageProxy.height),
            detectionSize = Size(bitmap.width, bitmap.height),
            displaySize = displaySize,
            rotationDegrees = rotation
        )
    }

    private fun rgbaImageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes.firstOrNull()
            ?: throw IllegalStateException("CameraX RGBA plane unavailable")
        val buffer = plane.buffer
        buffer.rewind()

        val cropRect = imageProxy.cropRect
        val width = cropRect.width().coerceAtLeast(1)
        val height = cropRect.height().coerceAtLeast(1)
        val outputSize = detectionBitmapSize(width, height)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val bitmap = Bitmap.createBitmap(outputSize.width, outputSize.height, Bitmap.Config.ARGB_8888)

        val usesFullBuffer = cropRect.left == 0 &&
            cropRect.top == 0 &&
            cropRect.width() == imageProxy.width &&
            cropRect.height() == imageProxy.height
        if (
            usesFullBuffer &&
            outputSize.width == width &&
            outputSize.height == height &&
            pixelStride == 4 &&
            rowStride == width * 4
        ) {
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        val packed = ByteArray(outputSize.width * outputSize.height * 4)
        var dst = 0
        for (row in 0 until outputSize.height) {
            val cropY = (row.toFloat() * height / outputSize.height).toInt().coerceIn(0, height - 1)
            val sourceY = cropRect.top + cropY
            val rowStart = sourceY * rowStride
            if (rowStart >= buffer.limit()) break
            for (col in 0 until outputSize.width) {
                val cropX = (col.toFloat() * width / outputSize.width).toInt().coerceIn(0, width - 1)
                val sourceX = cropRect.left + cropX
                val src = rowStart + sourceX * pixelStride
                if (src + 3 >= buffer.limit() || dst + 3 >= packed.size) break
                packed[dst++] = buffer.get(src)
                packed[dst++] = buffer.get(src + 1)
                packed[dst++] = buffer.get(src + 2)
                packed[dst++] = buffer.get(src + 3)
            }
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
        return bitmap
    }

    private fun detectionBitmapSize(width: Int, height: Int): Size {
        val maxSide = max(width, height)
        if (maxSide <= maxDetectionSide) {
            return Size(width, height)
        }
        val scale = maxDetectionSide.toFloat() / maxSide.toFloat()
        return Size(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1)
        )
    }
}
