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
    fun calibrationRejectsSubDegreeThumbRange() {
        val profile = calibrationProfile(openThumbSwing = 10f, thumbInSwing = 9.92f)

        val error = GestureCalibrationMapper.validate(profile)

        assertNotNull(error)
        assertTrue(error!!.contains("拇指"))
    }

    @Test
    fun calibrationMapsRecordedEndpointsToFullControlRange() {
        val profile = calibrationProfile(openThumbSwing = 30f, thumbInSwing = 10f)

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
    fun handednessResolutionDoesNotDependOnSelectedTargetOrCalibration() {
        assertEquals(
            "Left",
            GestureHandednessResolver.resolve(
                rawHandedness = "Right",
                inferredHandedness = "Right",
                mirrorMode = GestureMirrorMode.SELFIE
            )
        )
        assertEquals(
            "Right",
            GestureHandednessResolver.resolve(
                rawHandedness = "Right",
                inferredHandedness = "Left",
                mirrorMode = GestureMirrorMode.NORMAL
            )
        )
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
        thumbInSwing: Float = 10f,
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
