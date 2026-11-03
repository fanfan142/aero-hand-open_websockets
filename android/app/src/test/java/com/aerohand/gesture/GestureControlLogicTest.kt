package com.aerohand.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureControlLogicTest {
    @Test
    fun smootherSeedsFirstFrameWithoutArtificialLag() {
        val smoother = GestureSmoother()
        val first = FingerAngles(
            thumbAbd = 80f,
            thumbCmcFlex = 40f,
            thumbTendon = 70f,
            indexTendon = 60f,
            middleTendon = 50f,
            ringTendon = 40f,
            pinkyTendon = 30f,
            thumbSwing = 24f
        )

        val smoothed = smoother.apply(first)

        assertEquals(first, smoothed)
    }

    @Test
    fun smoothingDoesNotBlendAcrossHandsOrExtendedDetectionGaps() {
        val smoother = GestureSmoother()
        val bent = FingerAngles(indexTendon = 90f, thumbSwing = 30f)
        val straight = FingerAngles(indexTendon = 0f, thumbSwing = -30f)

        assertEquals(bent, smoother.apply(bent, hand = "Right", capturedAtMs = 1_000L))
        assertEquals(straight, smoother.apply(straight, hand = "Left", capturedAtMs = 1_033L))
        assertEquals(bent, smoother.apply(bent, hand = "Left", capturedAtMs = 1_300L))
    }

    @Test
    fun expiredControlFrameIsNoLongerAllowed() {
        val frame = GestureControlFrame(true, FingerAngles(indexTendon = 90f), capturedAtMs = 1_000L)

        assertTrue(frame.isFreshAt(nowMs = 1_239L, maxAgeMs = 240L))
        assertFalse(frame.isFreshAt(nowMs = 1_240L, maxAgeMs = 240L))
        assertFalse(frame.isFreshAt(nowMs = 900L, maxAgeMs = 240L))
        assertFalse(frame.copy(capturedAtMs = 0L).isFreshAt(nowMs = 1_000L, maxAgeMs = 240L))
    }

    @Test
    fun calibrationRejectsSubDegreeThumbRange() {
        val profile = calibrationProfile(openThumbSwing = 10f, thumbInSwing = 9.92f)

        val error = GestureCalibrationMapper.validate(profile)

        assertNotNull(error)
        assertTrue(error!!.contains("拇指"))
    }

    @Test
    fun calibrationRejectsThumbMovingAwayFromIndexForEitherHand() {
        for (hand in listOf("Left", "Right")) {
            val profile = calibrationProfile(
                hand = hand, openThumbSwing = 50f, thumbInSwing = 10f
            )
            val failure = GestureCalibrationMapper.validateThumbRange(profile)
            assertNotNull("Outward movement must not become the thumb-in endpoint", failure)
            assertEquals(CalibrationRetryState.THUMB_IN, failure!!.retryState)
        }
    }

    @Test
    fun calibrationAcceptsInwardThumbMovementAtMinimumRange() {
        for (hand in listOf("Left", "Right")) {
            val profile = calibrationProfile(
                hand = hand, openThumbSwing = 30f, thumbInSwing = 42f
            )
            assertNull(GestureCalibrationMapper.validateThumbRange(profile))
        }
    }

    @Test
    fun captureIgnoresPreTapFramesAndRequiresFiveSpacedStableFrames() {
        val capture = CalibrationPoseCapture()
        val context = CalibrationCaptureContext("Right", GestureCameraFacing.FRONT, GestureMirrorMode.SELFIE, GestureTargetHand.AUTO)
        val angles = FingerAngles(indexTendon = 10f, thumbSwing = 50f)
        assertEquals(CalibrationCaptureResult.Ignored, capture.addFrame(context, true, angles, 900L))
        capture.arm(context, 1_000L)
        assertEquals(CalibrationCaptureResult.Ignored, capture.addFrame(context, true, angles, 999L))
        assertEquals(CalibrationCaptureResult.Ignored, capture.addFrame(context, true, angles, 1_000L))
        repeat(5) { index ->
            assertTrue(capture.addFrame(context, true, angles, 1_001L + index) is CalibrationCaptureResult.Collecting)
        }
        assertEquals(1, capture.sampleCount)
        listOf(1_151L, 1_301L, 1_451L).forEach { time ->
            assertTrue(capture.addFrame(context, true, angles, time) is CalibrationCaptureResult.Collecting)
        }
        val complete = capture.addFrame(context, true, angles, 1_601L) as CalibrationCaptureResult.Complete
        assertEquals(600L, complete.elapsedMs)
        assertEquals(angles.indexTendon, complete.angles.indexTendon, 0f)
        assertFalse(capture.isCollecting)
        assertEquals(0, capture.sampleCount)
    }

    @Test
    fun captureChecksMotionBetweenSampleSlotsAndRestartsStableWindow() {
        val capture = CalibrationPoseCapture()
        val context = CalibrationCaptureContext("Right", GestureCameraFacing.FRONT, GestureMirrorMode.SELFIE, GestureTargetHand.AUTO)
        capture.arm(context, 1_000L)
        capture.addFrame(context, true, FingerAngles(indexTendon = 10f), 1_001L)
        val changed = capture.addFrame(context, true, FingerAngles(indexTendon = 30f), 1_050L) as CalibrationCaptureResult.Collecting
        assertTrue(changed.restartedForInstability)
        assertEquals(0L, changed.elapsedMs)
        assertEquals(1, changed.sampleCount)
        listOf(1_200L, 1_350L, 1_500L).forEach { time ->
            assertTrue(capture.addFrame(context, true, FingerAngles(indexTendon = 30f), time) is CalibrationCaptureResult.Collecting)
        }
        assertTrue(capture.addFrame(context, true, FingerAngles(indexTendon = 30f), 1_650L) is CalibrationCaptureResult.Complete)
    }

    @Test
    fun captureCancellationCannotResumeWithoutArm() {
        val capture = CalibrationPoseCapture()
        val context = CalibrationCaptureContext("Right", GestureCameraFacing.FRONT, GestureMirrorMode.SELFIE, GestureTargetHand.AUTO)
        capture.arm(context, 1_000L)
        capture.addFrame(context, true, FingerAngles(), 1_001L)
        assertEquals(CalibrationCaptureResult.Cancelled, capture.addFrame(context.copy(hand = "Left"), true, FingerAngles(), 1_151L))
        assertEquals(CalibrationCaptureResult.Ignored, capture.addFrame(context, true, FingerAngles(), 1_301L))
        capture.arm(context, 2_000L)
        assertEquals(CalibrationCaptureResult.Cancelled, capture.addFrame(context.copy(mirrorMode = GestureMirrorMode.NORMAL), true, FingerAngles(), 2_001L))
        capture.arm(context, 3_000L)
        capture.addFrame(context, true, FingerAngles(), 3_001L)
        assertEquals(CalibrationCaptureResult.Cancelled, capture.addFrame(context, true, FingerAngles(), 3_402L))
    }

    @Test
    fun calibrationRejectsSwappedFourFingerPosesAndNamesChannel() {
        val profile = calibrationProfile()
        val inverted = profile.copy(openAngles = profile.openAngles.copyOf().apply { this[3] = 80f },
            fistAngles = profile.fistAngles.copyOf().apply { this[3] = 10f })
        val failure = GestureCalibrationMapper.validateOpenFist(inverted)!!
        assertEquals(CalibrationRetryState.OPEN_FIST, failure.retryState)
        assertTrue(failure.message, failure.message.contains("食指"))
        assertTrue(failure.message, failure.message.contains("张开/握拳"))
    }

    @Test
    fun calibrationMapsRecordedEndpointsToFullControlRange() {
        val profile = calibrationProfile(openThumbSwing = 30f, thumbInSwing = 50f)

        val open = GestureCalibrationMapper.remap(
            profile = profile,
            values = profile.openAngles.copyOf(),
            thumbSwing = profile.openThumbSwing
        )
        val fist = GestureCalibrationMapper.remap(
            profile = profile,
            values = profile.fistAngles.copyOf(),
            thumbSwing = profile.thumbInSwing
        )

        assertEquals(0f, open.indexTendon, 0.001f)
        assertEquals(0f, open.thumbAbd, 0.001f)
        assertEquals(90f, fist.indexTendon, 0.001f)
        assertEquals(100f, fist.thumbAbd, 0.001f)
    }

    @Test
    fun unmirroredCameraFramesKeepMediaPipeSubjectHandLabels() {
        assertEquals("Left", GestureHandednessResolver.resolve("Left", inputMirrored = false))
        assertEquals("Right", GestureHandednessResolver.resolve("Right", inputMirrored = false))
    }

    @Test
    fun mirroredInputSwapsMediaPipeSubjectHandLabels() {
        assertEquals("Right", GestureHandednessResolver.resolve("Left", inputMirrored = true))
        assertEquals("Left", GestureHandednessResolver.resolve("Right", inputMirrored = true))
    }

    @Test
    fun missingOrUnknownCategoryCannotAuthorizeEvenAutoControl() {
        for (raw in listOf("", "Unknown", "Both")) {
            val resolved = GestureHandednessResolver.resolve(raw, inputMirrored = false)
            assertEquals("", resolved)
            assertFalse(resolved.isNotBlank() && GestureTargetHand.AUTO.matches(resolved))
        }
    }

    @Test
    fun commandSchedulerUsesMeasuredFramePeriodAndBackpressureSafeCommit() {
        val scheduler = GestureCommandScheduler()
        val first = compactState(10f)
        val second = compactState(30f)

        assertNull(scheduler.plan(first, nowMs = 1_000L))
        scheduler.markReady()

        val initial = scheduler.plan(first, nowMs = 1_033L)
        assertNotNull(initial)
        assertTrue(initial!!.durationMs >= 40)
        scheduler.markSent(initial, sentAtMs = 1_033L)

        assertNull(scheduler.plan(second, nowMs = 1_040L))
        val next = scheduler.plan(second, nowMs = 1_073L)
        assertNotNull(next)
        assertTrue(next!!.durationMs in 40..120)
        assertEquals(second, next.values)
    }

    @Test
    fun commandSchedulerKeepsFrameCadenceAcrossStableFrames() {
        val scheduler = GestureCommandScheduler()
        val stable = compactState(10f)
        val changed = compactState(30f)
        scheduler.markReady()

        val initial = requireNotNull(scheduler.plan(stable, nowMs = 1_000L))
        scheduler.markSent(initial, sentAtMs = 1_000L)
        assertNull(scheduler.plan(stable, nowMs = 1_033L))

        val next = requireNotNull(scheduler.plan(changed, nowMs = 1_066L))
        assertTrue(next.durationMs in 40..60)
    }

    @Test
    fun exactCalibrationContextIsRequiredForControl() {
        val profile = calibrationProfile(
            hand = "Right",
            facing = GestureCameraFacing.FRONT,
            mirror = GestureMirrorMode.SELFIE
        )
        val profiles = mapOf(profile.key to profile)

        assertNotNull(
            GestureCalibrationProfiles.exact(
                profiles,
                "Right",
                GestureCameraFacing.FRONT,
                GestureMirrorMode.SELFIE
            )
        )
        assertNull(
            GestureCalibrationProfiles.exact(
                profiles,
                "Right",
                GestureCameraFacing.BACK,
                GestureMirrorMode.NORMAL
            )
        )
        assertFalse(profile.matchesContext("Right", GestureCameraFacing.BACK, GestureMirrorMode.NORMAL))
    }

    private fun calibrationProfile(
        openThumbSwing: Float = 30f,
        thumbInSwing: Float = 50f,
        hand: String = "Right",
        facing: GestureCameraFacing = GestureCameraFacing.FRONT,
        mirror: GestureMirrorMode = GestureMirrorMode.SELFIE
    ): GestureCalibrationProfile {
        return GestureCalibrationProfile(
            handSide = hand,
            cameraFacing = facing,
            mirrorMode = mirror,
            openAngles = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
            fistAngles = floatArrayOf(100f, 55f, 90f, 90f, 90f, 90f, 90f),
            openThumbSwing = openThumbSwing,
            thumbInSwing = thumbInSwing
        )
    }

    private fun compactState(value: Float): Map<String, Float> {
        return mapOf(
            "thumb_cmc_abd" to value,
            "thumb_cmc_flex" to value.coerceAtMost(55f),
            "thumb_mcp_ip" to value,
            "index_flexion" to value,
            "middle_flexion" to value,
            "ring_flexion" to value,
            "pinky_flexion" to value
        )
    }
}
