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
    private data class SamplingLayout(
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val pixelStride: Int,
        val rowStride: Int
    )

    private var reusableBitmap: Bitmap? = null
    private var packedPixels = ByteArray(0)
    private var packedBuffer: ByteBuffer? = null
    private var samplingLayout: SamplingLayout? = null
    private var sourceXOffsets = IntArray(0)
    private var sourceRowOffsets = IntArray(0)

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
        val bitmap = obtainBitmap(outputSize)

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

        val layout = SamplingLayout(
            cropLeft = cropRect.left,
            cropTop = cropRect.top,
            cropWidth = width,
            cropHeight = height,
            outputWidth = outputSize.width,
            outputHeight = outputSize.height,
            pixelStride = pixelStride,
            rowStride = rowStride
        )
        prepareSampling(layout)
        var dst = 0
        for (row in 0 until outputSize.height) {
            val rowStart = sourceRowOffsets[row]
            if (rowStart >= buffer.limit()) break
            for (col in 0 until outputSize.width) {
                val src = rowStart + sourceXOffsets[col]
                if (src + 3 >= buffer.limit() || dst + 3 >= packedPixels.size) break
                packedPixels[dst++] = buffer.get(src)
                packedPixels[dst++] = buffer.get(src + 1)
                packedPixels[dst++] = buffer.get(src + 2)
                packedPixels[dst++] = buffer.get(src + 3)
            }
        }
        packedBuffer!!.rewind()
        bitmap.copyPixelsFromBuffer(packedBuffer)
        return bitmap
    }

    private fun obtainBitmap(size: Size): Bitmap {
        val current = reusableBitmap
        if (current != null && !current.isRecycled && current.width == size.width && current.height == size.height) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
            reusableBitmap = it
        }
    }

    private fun prepareSampling(layout: SamplingLayout) {
        val requiredBytes = layout.outputWidth * layout.outputHeight * 4
        if (packedPixels.size != requiredBytes) {
            packedPixels = ByteArray(requiredBytes)
            packedBuffer = ByteBuffer.wrap(packedPixels)
        }
        if (samplingLayout == layout) return

        sourceXOffsets = IntArray(layout.outputWidth) { column ->
            val cropX = (column * layout.cropWidth / layout.outputWidth).coerceIn(0, layout.cropWidth - 1)
            (layout.cropLeft + cropX) * layout.pixelStride
        }
        sourceRowOffsets = IntArray(layout.outputHeight) { row ->
            val cropY = (row * layout.cropHeight / layout.outputHeight).coerceIn(0, layout.cropHeight - 1)
            (layout.cropTop + cropY) * layout.rowStride
        }
        samplingLayout = layout
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
