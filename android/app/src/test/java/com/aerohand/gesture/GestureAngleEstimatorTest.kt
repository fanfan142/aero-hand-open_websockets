package com.aerohand.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureAngleEstimatorTest {
    @Test
    fun estimateReportsStraightAndBentFingers() {
        val landmarks = sampleLandmarks()

        val angles = GestureAngleEstimator.estimate(landmarks, "Right")

        assertTrue(angles.indexTendon in 85f..90f)
        assertTrue(angles.middleTendon < 2f)
        assertTrue(angles.ringTendon in 85f..90f)
        assertTrue(angles.pinkyTendon < 2f)
    }

    @Test
    fun previewUsesMappedCropCoordinatesWithoutMutatingMediaPipeResults() {
        val input = listOf(GestureLandmark(0.2f, 0.3f, 0.4f))
        val display = GestureLandmarkTransforms.forPreview(
            landmarks = input,
            cropWidth = 200,
            cropHeight = 100,
            previewWidth = 100,
            previewHeight = 200
        ) { point ->
            val x = point[0]
            point[0] = 100f - point[1]
            point[1] = x
        }.single()

        assertEquals(0.7f, display.x, 0.0001f)
        assertEquals(0.2f, display.y, 0.0001f)
        assertEquals(0.4f, display.z, 0f)
        assertEquals(GestureLandmark(0.2f, 0.3f, 0.4f), input.single())
    }

    @Test
    fun rawMediaPipeResultsRemainUnchangedAcrossRetargetingAndPreview() {
        val landmarks = sampleLandmarks().toList()
        val raw = MediaPipeRawHand(landmarks, "Right", 0.87f)
        val withWorld = raw.copy(worldLandmarks = listOf(GestureLandmark(0.1f, 0.2f, 0.3f)))
        val before = withWorld.copy(normalizedLandmarks = withWorld.normalizedLandmarks.toList())

        val angles = AeroHandRetargeter.retarget(withWorld, "Right")
        val display = GestureLandmarkTransforms.forPreview(
            landmarks = withWorld.normalizedLandmarks,
            cropWidth = 200,
            cropHeight = 100,
            previewWidth = 100,
            previewHeight = 200
        ) { point ->
            val x = point[0]
            point[0] = 100f - point[1]
            point[1] = x
        }

        assertEquals(before, withWorld)
        assertEquals(GestureLandmark(0.1f, 0.2f, 0.3f), withWorld.worldLandmarks.single())
        assertEquals(21, raw.normalizedLandmarks.size)
        assertEquals("Right", raw.category)
        assertEquals(0.87f, raw.confidence, 0f)
        assertTrue(angles.indexTendon in 85f..90f)
        assertEquals(7, angles.toControlArray().size)
        assertTrue(display != withWorld.normalizedLandmarks)
    }

    @Test
    fun palmFacingInwardThumbSwingIncreasesForMirroredHandsAndRotations() {
        for (hand in listOf("Left", "Right")) {
            for (rotation in 0..3) {
                val open = GestureAngleEstimator.estimate(palmFacingThumbPose(hand, false, rotation), hand)
                val inward = GestureAngleEstimator.estimate(palmFacingThumbPose(hand, true, rotation), hand)
                assertTrue("$hand rotation $rotation inward thumb must increase signed swing",
                    inward.thumbSwing - open.thumbSwing >= 12f)
            }
        }
    }

    @Test
    fun tuningProfileAppliesGainAndOffsetWithinChannelLimits() {
        val profile = GestureTuningProfile.default()
            .withAdjustment(GestureTuningChannel.INDEX_TENDON, gainDelta = 0.5f, offsetDelta = 20f)

        val tuned = profile.applyTo(FingerAngles(indexTendon = 80f))

        assertEquals(90f, tuned.indexTendon, 0.001f)
    }

    private fun palmFacingThumbPose(hand: String, inward: Boolean, rotation: Int): List<GestureLandmark> {
        // Unmirrored palm-facing left hand: index and thumb are left of the ring MCP.
        val points = sampleLandmarks()
        points[13] = GestureLandmark(0.6f, 0.5f)
        points[5] = GestureLandmark(0.4f, 0.5f)
        points[1] = GestureLandmark(0.4f, 0.8f)
        points[2] = if (inward) GestureLandmark(0.38f, 0.6f) else GestureLandmark(0.2f, 0.7f)
        return points.map { point ->
            var x = if (hand == "Right") 1f - point.x else point.x
            var y = point.y
            repeat(rotation) {
                val previousX = x
                x = 1f - y
                y = previousX
            }
            point.copy(x = x, y = y)
        }
    }

    private fun sampleLandmarks(): MutableList<GestureLandmark> {
        val landmarks = MutableList(21) { GestureLandmark(0f, 0f, 0f) }

        landmarks[0] = GestureLandmark(0f, -1f)
        landmarks[1] = GestureLandmark(0f, 0f)
        landmarks[2] = GestureLandmark(1f, 0f)
        landmarks[3] = GestureLandmark(2f, 0f)
        landmarks[4] = GestureLandmark(3f, 0f)

        landmarks[5] = GestureLandmark(0f, 0f)
        landmarks[6] = GestureLandmark(1f, 0f)
        landmarks[7] = GestureLandmark(1f, 1f)
        landmarks[8] = GestureLandmark(1f, 2f)

        landmarks[9] = GestureLandmark(0f, 1f)
        landmarks[10] = GestureLandmark(1f, 1f)
        landmarks[11] = GestureLandmark(2f, 1f)
        landmarks[12] = GestureLandmark(3f, 1f)

        landmarks[13] = GestureLandmark(0f, 2f)
        landmarks[14] = GestureLandmark(1f, 2f)
        landmarks[15] = GestureLandmark(1f, 3f)
        landmarks[16] = GestureLandmark(1f, 4f)

        landmarks[17] = GestureLandmark(0f, 3f)
        landmarks[18] = GestureLandmark(1f, 3f)
        landmarks[19] = GestureLandmark(2f, 3f)
        landmarks[20] = GestureLandmark(3f, 3f)

        return landmarks
    }
}
