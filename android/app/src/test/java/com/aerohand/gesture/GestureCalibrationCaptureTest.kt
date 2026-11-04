package com.aerohand.gesture

import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GestureCalibrationCaptureTest {
    private val context = RuntimeEnvironment.getApplication()
    private val service = GestureCameraService(context, mock(LifecycleOwner::class.java))
    private val open = pose(10f, 50f)
    private val fist = pose(50f, 30f)
    private val thumbIn = pose(10f, 90f)

    @After
    fun close() {
        service.release()
        context.getSharedPreferences("gesture_follow_v2", 0).edit().clear().commit()
    }

    @Test
    fun tapArmsFreshCaptureAndEachPoseAutoConfirmsWithoutAnotherTap() {
        service.startCalibration()
        detected()
        repeat(8) { frame(fist, 150L) } // Pre-tap frames are never pending calibration samples.
        assertEquals(0, service.state.value.calibrationSampleCount)
        service.recordCalibrationPose()
        assertEquals(CalibrationCapturePhase.COLLECTING, service.state.value.calibrationCapturePhase)
        repeat(4) { frame(open, 10L) }
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        assertEquals(1, service.state.value.calibrationSampleCount)
        repeat(4) { frame(open, 150L) }
        assertEquals(CalibrationState.CALIBRATING_FIST, service.state.value.calibrationState)
        assertEquals(CalibrationCapturePhase.IDLE, service.state.value.calibrationCapturePhase)
        assertEquals(0, service.state.value.calibrationSampleCount)
        repeat(6) { frame(fist, 150L) }
        assertEquals(CalibrationState.CALIBRATING_FIST, service.state.value.calibrationState)
        capture(fist)
        assertEquals(CalibrationState.CALIBRATING_THUMB_IN, service.state.value.calibrationState)
        capture(thumbIn)
        assertEquals(CalibrationState.CALIBRATED, service.state.value.calibrationState)
        assertEquals(90f, service.state.value.calibrationProfile!!.thumbInSwing, 0f)
        assertTrue(GestureCalibrationStore(context).loadProfiles().isNotEmpty())
        assertFalse("Completion must wait for a NEW live control frame", service.controlFrame.value.allowed)
    }

    @Test
    fun picturedPosesCompleteWhenThumbCmcStaysStill() {
        service.startCalibration()
        val openPose = pose(10f, 30f).copy(thumbCmcFlex = 20f)
        val fistPose = pose(80f, 30f).copy(thumbCmcFlex = 20f)
        val thumbPose = pose(10f, 70f).copy(thumbCmcFlex = 20f, thumbTendon = 45f)
        capture(openPose)
        capture(fistPose)
        assertEquals(CalibrationState.CALIBRATING_THUMB_IN, service.state.value.calibrationState)
        capture(thumbPose)
        assertEquals(CalibrationState.CALIBRATED, service.state.value.calibrationState)
        assertEquals(70f, service.state.value.calibrationProfile!!.thumbInSwing, 0f)
    }

    @Test
    fun repeatedTapDuringCollectionDoesNotResetProgress() {
        service.startCalibration()
        detected()
        service.recordCalibrationPose()
        frame(open, 1L)
        frame(open, 150L)
        service.recordCalibrationPose()
        assertEquals(2, service.state.value.calibrationSampleCount)
        assertEquals(150L, service.state.value.calibrationCaptureElapsedMs)
    }

    @Test
    fun transitionResetsStableWindowInsteadOfMixingPoses() {
        service.startCalibration()
        detected()
        service.recordCalibrationPose()
        frame(fist, 1L)
        frame(open, 150L)
        assertEquals(1, service.state.value.calibrationSampleCount)
        assertEquals(0L, service.state.value.calibrationCaptureElapsedMs)
        repeat(3) { frame(open, 150L) }
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        frame(open, 150L)
        assertEquals(CalibrationState.CALIBRATING_FIST, service.state.value.calibrationState)
        assertEquals(10f, pending("pendingOpen")[GestureTuningChannel.INDEX_TENDON.ordinal], 0f)
    }

    @Test
    fun openFistRangeFailsAtSecondStepAndRejectedFistIsCleared() {
        service.startCalibration()
        capture(open)
        capture(pose(12f, 30f))
        assertEquals(CalibrationState.CALIBRATING_FIST, service.state.value.calibrationState)
        assertEquals(CalibrationRetryState.OPEN_FIST, service.state.value.calibrationRetryState)
        assertTrue(pending("pendingFist").all { it == 0f })
        service.recordCalibrationPose()
        assertEquals(CalibrationCapturePhase.IDLE, service.state.value.calibrationCapturePhase)
        service.retryCalibrationPose(CalibrationState.CALIBRATING_FIST)
        assertEquals(CalibrationRetryState.NONE, service.state.value.calibrationRetryState)
        assertEquals(10f, pending("pendingOpen")[3], 0f)
        capture(fist)
        assertEquals(CalibrationState.CALIBRATING_THUMB_IN, service.state.value.calibrationState)
        capture(thumbIn)
        assertEquals(CalibrationState.CALIBRATED, service.state.value.calibrationState)
    }

    @Test
    fun retryOpenClearsBothEndpointsAndRequiresNewClick() {
        service.startCalibration()
        capture(open)
        capture(pose(12f, 30f))
        service.retryCalibrationPose(CalibrationState.CALIBRATING_OPEN)
        assertTrue(pending("pendingOpen").all { it == 0f })
        assertTrue(pending("pendingFist").all { it == 0f })
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        repeat(6) { frame(open, 150L) }
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        assertEquals(CalibrationCapturePhase.IDLE, service.state.value.calibrationCapturePhase)
    }

    @Test
    fun thumbCaptureRejectsCurledOtherFingersAndAllowsFreshRetry() {
        service.startCalibration()
        capture(open)
        capture(fist)
        capture(pose(50f, 90f))
        assertEquals(CalibrationRetryState.THUMB_IN, service.state.value.calibrationRetryState)
        assertEquals(CalibrationState.CALIBRATING_THUMB_IN, service.state.value.calibrationState)
        assertFalse(service.controlFrame.value.allowed)
        service.retryCalibrationPose(CalibrationState.CALIBRATING_THUMB_IN)
        capture(thumbIn)
        assertEquals(CalibrationState.CALIBRATED, service.state.value.calibrationState)
    }

    @Test
    fun insufficientThumbRangeDiscardsAttemptAndRequiresFreshRetry() {
        service.startCalibration()
        capture(open)
        capture(fist)
        capture(pose(10f, 49f))
        assertEquals(CalibrationRetryState.THUMB_IN, service.state.value.calibrationRetryState)
        assertEquals(0, service.state.value.calibrationSampleCount)
        service.retryCalibrationPose(CalibrationState.CALIBRATING_THUMB_IN)
        capture(thumbIn)
        assertEquals(CalibrationState.CALIBRATED, service.state.value.calibrationState)
    }

    @Test
    fun outwardThumbPoseCannotBeSavedOrAuthorizeControl() {
        service.startCalibration()
        capture(open)
        capture(fist)
        capture(pose(10f, 10f))
        assertEquals(CalibrationState.CALIBRATING_THUMB_IN, service.state.value.calibrationState)
        assertEquals(CalibrationRetryState.THUMB_IN, service.state.value.calibrationRetryState)
        assertTrue(GestureCalibrationStore(context).loadProfiles().isEmpty())
        assertFalse(service.controlFrame.value.allowed)
    }

    @Test
    fun lossDiscardsAttemptAndReappearingHandCannotAutoResume() {
        service.startCalibration()
        detected()
        service.recordCalibrationPose()
        frame(open, 1L)
        noHand()
        assertEquals(CalibrationCaptureNotice.INTERRUPTED, service.state.value.calibrationCaptureNotice)
        assertEquals(CalibrationCapturePhase.IDLE, service.state.value.calibrationCapturePhase)
        detected()
        repeat(6) { frame(open, 150L) }
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        assertEquals(0, service.state.value.calibrationSampleCount)
        assertFalse(service.controlFrame.value.allowed)
        capture(open)
        assertEquals(CalibrationState.CALIBRATING_FIST, service.state.value.calibrationState)
    }

    @Test
    fun wrongHandAndContextChangesDiscardActiveCapture() {
        service.startCalibration()
        detected()
        service.recordCalibrationPose()
        frame(open, 1L)
        frame(open, 150L, hand = "Left", matched = false)
        assertEquals(CalibrationCaptureNotice.INTERRUPTED, service.state.value.calibrationCaptureNotice)
        detected()
        service.recordCalibrationPose()
        frame(open, 1L)
        service.setTargetHand(GestureTargetHand.RIGHT)
        assertEquals(CalibrationCapturePhase.IDLE, service.state.value.calibrationCapturePhase)
        repeat(6) { frame(open, 150L) }
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        service.recordCalibrationPose()
        frame(open, 1L)
        service.toggleCamera()
        assertEquals(CalibrationCapturePhase.IDLE, service.state.value.calibrationCapturePhase)
        assertEquals(0, service.state.value.calibrationSampleCount)
        assertFalse(service.controlFrame.value.allowed)
    }

    @Test
    fun excessiveFrameGapDiscardsAttemptRatherThanAcceptingStaleSamples() {
        service.startCalibration()
        detected()
        service.recordCalibrationPose()
        frame(open, 1L)
        frame(open, 401L)
        assertEquals(CalibrationCaptureNotice.INTERRUPTED, service.state.value.calibrationCaptureNotice)
        repeat(6) { frame(open, 150L) }
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
    }

    @Test
    fun twoDistinctHandsNeverChooseAutomaticControlSourceOrCaptureSample() {
        service.startCalibration()
        detected()
        service.recordCalibrationPose()
        frame(open, 1L)
        val hands = listOf("Left", "Right").map {
            TrackedHand(MediaPipeRawHand(List(21) { GestureLandmark(0.5f, 0.5f) }, it, 0.95f))
        }
        val cameraFrame = GestureFrame(
            bitmap = mock(android.graphics.Bitmap::class.java),
            sourceSize = android.util.Size(640, 480),
            detectionSize = android.util.Size(256, 192),
            displaySize = android.util.Size(480, 640),
            rotationDegrees = 90, cropWidth = 640, cropHeight = 480
        )
        val source = androidx.camera.view.transform.OutputTransform(android.graphics.Matrix(), android.util.Size(640, 480))
        GestureCameraService::class.java.getDeclaredMethod(
            "processHands", List::class.java, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            GestureFrame::class.java, androidx.camera.view.transform.OutputTransform::class.java
        ).apply { isAccessible = true }.invoke(service, hands, 30f, 480, 640, "test", cameraFrame, source)
        assertFalse(service.state.value.handDetected)
        assertFalse(service.controlFrame.value.allowed)
        assertEquals(CalibrationCapturePhase.IDLE, service.state.value.calibrationCapturePhase)
        assertEquals(0, service.state.value.calibrationSampleCount)
        assertEquals(listOf("Left", "Right"), service.state.value.rawHands.map { it.category })
    }

    private fun capture(angles: FingerAngles) {
        detected()
        service.recordCalibrationPose()
        frame(angles, 1L)
        repeat(4) { frame(angles, 150L) }
        assertFalse(service.controlFrame.value.allowed)
    }

    private fun frame(angles: FingerAngles, deltaMs: Long, hand: String = "Right", matched: Boolean = true) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(deltaMs))
        service.updateCalibrationCapture(hand, matched, angles, SystemClock.elapsedRealtime())
    }

    private fun noHand() {
        GestureCameraService::class.java.getDeclaredMethod(
            "markNoHand", Float::class.javaPrimitiveType, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java
        ).apply { isAccessible = true }.invoke(service, 30f, "未检测到手", 640, 480, emptyList<MediaPipeRawHand>())
    }

    @Suppress("UNCHECKED_CAST")
    private fun detected() {
        val state = GestureCameraService::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(service) as MutableStateFlow<GestureCameraState>
        state.value = state.value.copy(handDetected = true, handedness = "Right", targetHandMatched = true)
    }

    private fun pending(name: String): FloatArray = GestureCameraService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service) as FloatArray

    private fun pose(flex: Float, swing: Float) = FingerAngles(
        thumbCmcFlex = flex, thumbTendon = flex, indexTendon = flex,
        middleTendon = flex, ringTendon = flex, pinkyTendon = flex, thumbSwing = swing
    )
}
