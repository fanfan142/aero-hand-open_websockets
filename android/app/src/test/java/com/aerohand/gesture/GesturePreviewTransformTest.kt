package com.aerohand.gesture

import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(TransformExperimental::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GesturePreviewTransformTest {
    @Test
    fun croppedMediaPipePointsMatchPreviewAcrossRotationsMirroringAndLetterboxing() {
        val landmarks = listOf(
            GestureLandmark(0f, 0f, 0.1f),
            GestureLandmark(1f, 0f, 0.2f),
            GestureLandmark(0f, 1f, 0.3f),
            GestureLandmark(1f, 1f, 0.4f),
            GestureLandmark(0.2f, 0.7f, 0.5f)
        )
        val before = landmarks.toList()
        for (crop in listOf(Rect(0, 0, 640, 480), Rect(80, 60, 560, 420))) {
            for (rotation in listOf(0, 90, 180, 270)) {
                for (mirrored in listOf(false, true)) {
                    for (viewSize in listOf(Size(900, 1600), Size(1600, 900))) {
                        val image = mockImage(crop, rotation)
                        val source = gesturePreviewTransformFactory().getOutputTransform(image)
                        val transform = CoordinateTransform(source, previewTransform(crop, rotation, mirrored, viewSize))
                        val mapped = GestureLandmarkTransforms.forPreview(
                            landmarks = landmarks,
                            cropLeft = crop.left,
                            cropTop = crop.top,
                            cropWidth = crop.width(),
                            cropHeight = crop.height(),
                            previewWidth = viewSize.width,
                            previewHeight = viewSize.height
                        ) { transform.mapPoints(it) }

                        landmarks.zip(mapped).forEach { (raw, actual) ->
                            val expected = previewPoint(raw, crop, rotation, mirrored, viewSize)
                            val label = "crop=$crop rotation=$rotation mirror=$mirrored view=$viewSize raw=$raw"
                            assertEquals(label, expected[0] / viewSize.width, actual.x, 0.0001f)
                            assertEquals(label, expected[1] / viewSize.height, actual.y, 0.0001f)
                            assertEquals(raw.z, actual.z, 0f)
                        }
                    }
                }
            }
        }
        assertEquals(before, landmarks)
    }

    private fun mockImage(crop: Rect, rotation: Int): ImageProxy {
        val info = mock(ImageInfo::class.java)
        `when`(info.rotationDegrees).thenReturn(rotation)
        return mock(ImageProxy::class.java).also { image ->
            `when`(image.width).thenReturn(640)
            `when`(image.height).thenReturn(480)
            `when`(image.cropRect).thenReturn(crop)
            `when`(image.imageInfo).thenReturn(info)
        }
    }

    private fun previewTransform(crop: Rect, rotation: Int, mirrored: Boolean, viewSize: Size): OutputTransform {
        val normalized = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val corners = listOf(
            GestureLandmark(0f, 0f), GestureLandmark(1f, 0f),
            GestureLandmark(0f, 1f), GestureLandmark(1f, 1f)
        ).flatMap { previewPoint(it, crop, rotation, mirrored, viewSize).toList() }.toFloatArray()
        val matrix = Matrix().apply { setPolyToPoly(normalized, 0, corners, 0, 4) }
        return OutputTransform(matrix, Size(crop.width(), crop.height()))
    }

    private fun previewPoint(
        point: GestureLandmark,
        crop: Rect,
        rotation: Int,
        mirrored: Boolean,
        viewSize: Size
    ): FloatArray {
        val rotated = when (rotation) {
            90 -> floatArrayOf(1f - point.y, point.x)
            180 -> floatArrayOf(1f - point.x, 1f - point.y)
            270 -> floatArrayOf(point.y, 1f - point.x)
            else -> floatArrayOf(point.x, point.y)
        }
        if (mirrored) rotated[0] = 1f - rotated[0]
        val width = if (rotation % 180 == 0) crop.width() else crop.height()
        val height = if (rotation % 180 == 0) crop.height() else crop.width()
        val scale = minOf(viewSize.width.toFloat() / width, viewSize.height.toFloat() / height)
        return floatArrayOf(
            (viewSize.width - width * scale) / 2f + rotated[0] * width * scale,
            (viewSize.height - height * scale) / 2f + rotated[1] * height * scale
        )
    }
}
