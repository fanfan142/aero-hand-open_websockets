package com.aerohand.gesture

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GestureCalibrationCaptureTest {
    private val context = RuntimeEnvironment.getApplication()
    private val service = GestureCameraService(context, mock(LifecycleOwner::class.java))

    @After
    fun close() {
        service.release()
        context.getSharedPreferences("gesture_follow_v2", 0).edit().clear().commit()
    }

    @Test
    fun everyPoseRequiresFiveNewSamplesAndThumbInCompletesCalibration() {
        service.startCalibration()
        assertFalse(service.controlFrame.value.allowed)
        capture(FingerAngles(thumbCmcFlex = 10f, thumbTendon = 10f, indexTendon = 10f,
            middleTendon = 10f, ringTendon = 10f, pinkyTendon = 10f, thumbSwing = 50f))
        assertEquals(CalibrationState.CALIBRATING_FIST, service.state.value.calibrationState)
        assertNull(sample())

        capture(FingerAngles(thumbCmcFlex = 60f, thumbTendon = 60f, indexTendon = 60f,
            middleTendon = 60f, ringTendon = 60f, pinkyTendon = 60f, thumbSwing = 30f))
        assertEquals(CalibrationState.CALIBRATING_THUMB_IN, service.state.value.calibrationState)
        assertNull(sample())

        capture(FingerAngles(thumbSwing = 10f))
        assertEquals(CalibrationState.CALIBRATED, service.state.value.calibrationState)
        assertEquals(10f, service.state.value.calibrationProfile!!.thumbInSwing, 0f)
        assertTrue(GestureCalibrationStore(context).loadProfiles().isNotEmpty())
        assertFalse("Recording alone must not revive an old control frame", service.controlFrame.value.allowed)
    }

    @Test
    fun liveFrameFeedbackKeepsThirdPoseInstructionVisible() {
        val method = GestureCameraService::class.java.getDeclaredMethod(
            "feedbackFor", String::class.java, Boolean::class.javaPrimitiveType,
            GestureCalibrationProfile::class.java, CalibrationState::class.java
        ).apply { isAccessible = true }
        val feedback = method.invoke(service, "Right", true, null, CalibrationState.CALIBRATING_THUMB_IN) as String
        assertTrue(feedback, feedback.contains("3/3") && feedback.contains("拇指内收"))
    }

    @Test
    fun rejectionRemainsVisibleAcrossLiveFeedbackUpdates() {
        service.startCalibration()
        setDetected()
        service.recordCalibrationPose()
        val method = GestureCameraService::class.java.getDeclaredMethod(
            "feedbackFor", String::class.java, Boolean::class.javaPrimitiveType,
            GestureCalibrationProfile::class.java, CalibrationState::class.java
        ).apply { isAccessible = true }
        repeat(5) {
            val feedback = method.invoke(service, "Right", true, null, CalibrationState.CALIBRATING_OPEN) as String
            assertTrue(feedback, feedback.contains("有效采样不足"))
            assertTrue(feedback, feedback.contains("1/3"))
        }
    }

    @Test
    fun trackingLossDiscardsSamplesSoReappearingHandCannotRecordOldPose() {
        service.startCalibration()
        setDetected()
        repeat(5) { remember(FingerAngles(thumbSwing = 50f)) }
        val method = GestureCameraService::class.java.getDeclaredMethod(
            "markNoHand", Float::class.javaPrimitiveType, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java
        ).apply { isAccessible = true }
        method.invoke(service, 30f, "未检测到手", 640, 480, emptyList<MediaPipeRawHand>())
        setDetected()
        assertNull("Lost tracking must clear all pose samples", sample())
        service.recordCalibrationPose()
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        assertFalse(service.controlFrame.value.allowed)
    }

    private fun capture(angles: FingerAngles) {
        setDetected()
        repeat(4) { remember(angles) }
        service.recordCalibrationPose()
        assertFalse(service.controlFrame.value.allowed)
        remember(angles)
        service.recordCalibrationPose()
    }

    @Test
    fun twoDistinctHandsNeverChooseAnAutomaticControlSourceOrCalibrationSample() {
        service.startCalibration()
        setDetected()
        repeat(5) { remember(FingerAngles(thumbSwing = 50f)) }
        val hands = listOf("Left", "Right").map {
            TrackedHand(MediaPipeRawHand(List(21) { GestureLandmark(0.5f, 0.5f) }, it, 0.95f))
        }
        val frame = GestureFrame(
            bitmap = mock(android.graphics.Bitmap::class.java),
            sourceSize = android.util.Size(640, 480),
            detectionSize = android.util.Size(256, 192),
            displaySize = android.util.Size(480, 640),
            rotationDegrees = 90,
            cropWidth = 640,
            cropHeight = 480
        )
        val source = androidx.camera.view.transform.OutputTransform(
            android.graphics.Matrix(), android.util.Size(640, 480)
        )
        val method = GestureCameraService::class.java.getDeclaredMethod(
            "processHands", List::class.java, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, GestureFrame::class.java,
            androidx.camera.view.transform.OutputTransform::class.java
        ).apply { isAccessible = true }
        method.invoke(service, hands, 30f, 480, 640, "test", frame, source)
        assertFalse(service.state.value.handDetected)
        assertFalse(service.controlFrame.value.allowed)
        assertTrue(service.state.value.feedbackMessage.contains("多只手"))
        assertNull(sample())
    }

    @Test
    fun expiredSamplesCannotRecordPose() {
        service.startCalibration()
        setDetected()
        repeat(5) { remember(FingerAngles(thumbSwing = 50f)) }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1501))
        assertNull(sample())
        service.recordCalibrationPose()
        assertEquals(CalibrationState.CALIBRATING_OPEN, service.state.value.calibrationState)
        assertFalse(service.controlFrame.value.allowed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setDetected() {
        val state = GestureCameraService::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(service) as MutableStateFlow<GestureCameraState>
        state.value = state.value.copy(handDetected = true, handedness = "Right", targetHandMatched = true)
    }

    private fun remember(angles: FingerAngles) {
        GestureCameraService::class.java.getDeclaredMethod("rememberSample", String::class.java, FingerAngles::class.java)
            .apply { isAccessible = true }.invoke(service, "Right", angles)
    }

    private fun sample(): FingerAngles? =
        GestureCameraService::class.java.getDeclaredMethod("medianCalibrationSample", String::class.java)
            .apply { isAccessible = true }.invoke(service, "Right") as FingerAngles?
}
