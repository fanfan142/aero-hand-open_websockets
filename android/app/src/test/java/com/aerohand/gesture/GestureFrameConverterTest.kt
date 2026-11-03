package com.aerohand.gesture

import android.graphics.Color
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureFrameConverterTest {
    @Test
    fun cropAndRowPaddingAreAppliedOnceAndBitmapIsReused() {
        val image = rgbaImage(Rect(1, 1, 3, 3), rotation = 90)
        val converter = GestureFrameConverter(256)
        val frame = converter.convert(image)

        assertEquals(2, frame.bitmap.width)
        assertEquals(2, frame.bitmap.height)
        assertEquals(Color.rgb(40, 40, 20), frame.bitmap.getPixel(0, 0))
        assertEquals(Color.rgb(80, 40, 20), frame.bitmap.getPixel(1, 0))
        assertEquals(Color.rgb(40, 80, 20), frame.bitmap.getPixel(0, 1))
        assertEquals(Color.rgb(80, 80, 20), frame.bitmap.getPixel(1, 1))
        assertEquals(90, frame.rotationDegrees)
        assertSame(frame.bitmap, converter.convert(image).bitmap)
    }

    @Test
    fun downsamplingPreservesCropExtentAndUnrotatedPixelCoordinates() {
        val converter = GestureFrameConverter(2)
        val frame = converter.convert(rgbaImage(Rect(0, 0, 4, 3), rotation = 270))
        assertEquals(2, frame.detectionSize.width)
        assertEquals(2, frame.detectionSize.height)
        assertEquals(4, frame.cropWidth)
        assertEquals(3, frame.cropHeight)
        assertEquals(Color.rgb(0, 0, 20), frame.bitmap.getPixel(0, 0))
        assertEquals(Color.rgb(80, 40, 20), frame.bitmap.getPixel(1, 1))
    }

    private fun rgbaImage(crop: Rect, rotation: Int): ImageProxy {
        val rowStride = 20
        val bytes = ByteBuffer.allocate(rowStride * 3)
        for (row in 0 until 3) {
            for (column in 0 until 4) {
                val offset = row * rowStride + column * 4
                bytes.put(offset, (column * 40).toByte())
                bytes.put(offset + 1, (row * 40).toByte())
                bytes.put(offset + 2, 20.toByte())
                bytes.put(offset + 3, 255.toByte())
            }
        }
        val plane = mock(ImageProxy.PlaneProxy::class.java)
        `when`(plane.buffer).thenReturn(bytes)
        `when`(plane.pixelStride).thenReturn(4)
        `when`(plane.rowStride).thenReturn(rowStride)
        val info = mock(ImageInfo::class.java)
        `when`(info.rotationDegrees).thenReturn(rotation)
        return mock(ImageProxy::class.java).also {
            `when`(it.width).thenReturn(4)
            `when`(it.height).thenReturn(3)
            `when`(it.cropRect).thenReturn(crop)
            `when`(it.imageInfo).thenReturn(info)
            `when`(it.planes).thenReturn(arrayOf(plane))
        }
    }
}
