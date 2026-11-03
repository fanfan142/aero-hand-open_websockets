package com.aerohand.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.aerohand.gesture.CalibrationCaptureNotice
import com.aerohand.gesture.CalibrationCapturePhase
import com.aerohand.gesture.CalibrationRetryState
import com.aerohand.gesture.CalibrationState
import com.aerohand.gesture.GestureCameraState
import com.aerohand.gesture.GestureTargetHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GestureCalibrationControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun introductionExplainsPosesAndSingleTapAndFitsRealSmallContainer() {
        show(GestureCameraState())
        compose.onNodeWithText("三步校准：张开 → 握拳 → 拇指内收").assertIsDisplayed()
        compose.onNodeWithText("1. 张开五指和拇指", substring = true).assertIsDisplayed()
        compose.onNodeWithText("只点一次，不用连点5次").performScrollTo().assertIsDisplayed()
        assertInsidePreview("开始校准")
        assertPanelInsidePreview()
    }

    @Test
    fun startCollectingIsEnabledWithRecognizedMatchingHandAndZeroSamples() {
        var clicks = 0
        show(readyState(), onCapture = { clicks++ })
        assertInsidePreview("开始采集拇指内收")
        compose.onNodeWithText("开始采集拇指内收").assertIsEnabled().performClick()
        assertEquals(1, clicks)
        compose.onNodeWithText("只点一次，不用连点5次").performScrollTo().assertIsDisplayed()
        assertPanelInsidePreview()
    }

    @Test
    fun collectingShowsProgressAndDisablesRepeatTapWithoutOverflow() {
        show(readyState().copy(
            calibrationCapturePhase = CalibrationCapturePhase.COLLECTING,
            calibrationSampleCount = 3,
            calibrationCaptureElapsedMs = 300L
        ))
        compose.onNodeWithText("稳定采集 3/5 · 300/600 ms").performScrollTo().assertIsDisplayed()
        assertInsidePreview("采集中…")
        compose.onNodeWithText("采集中…").assertIsNotEnabled()
        compose.onNodeWithText("记录拇指内收").assertDoesNotExist()
        assertPanelInsidePreview()
    }

    @Test
    fun missingHandCannotArmEvenIfOldCountWasFive() {
        show(readyState().copy(handDetected = false, calibrationSampleCount = 5))
        compose.onNodeWithText("开始采集拇指内收").assertIsNotEnabled()
        compose.onNodeWithText("请将一只目标手完整放入画面。").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun wrongOrUnrecognizedHandCannotArm() {
        show(readyState().copy(
            targetHand = GestureTargetHand.RIGHT,
            handedness = "Left",
            targetHandMatched = false
        ))
        compose.onNodeWithText("开始采集拇指内收").assertIsNotEnabled()
        compose.onNodeWithText("手别不匹配，请换成右手。").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unknownHandCannotArmInAutoMode() {
        show(readyState().copy(handedness = "Unknown"))
        compose.onNodeWithText("开始采集拇指内收").assertIsNotEnabled()
    }

    @Test
    fun interruptedCaptureRequiresFreshClickAndShowsRecovery() {
        show(readyState().copy(calibrationCaptureNotice = CalibrationCaptureNotice.INTERRUPTED))
        compose.onNodeWithText("上次采集已中断并丢弃，请重新开始采集。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始采集拇指内收").assertIsEnabled()
        assertInsidePreview("开始采集拇指内收")
    }

    @Test
    fun typedSecondStepRetryShowsBothActionsWithoutParsingFeedback() {
        var selected: CalibrationState? = null
        show(readyState().copy(
            calibrationState = CalibrationState.CALIBRATING_FIST,
            calibrationRetryState = CalibrationRetryState.OPEN_FIST,
            feedbackMessage = "该姿势的活动范围不足"
        ), onRetry = { selected = it })
        assertInsidePreview("重采张开")
        assertInsidePreview("重采握拳")
        compose.onNodeWithText("重采张开").performClick()
        assertEquals(CalibrationState.CALIBRATING_OPEN, selected)
        compose.onNodeWithText("重采握拳").performClick()
        assertEquals(CalibrationState.CALIBRATING_FIST, selected)
        compose.onNodeWithText("开始采集握拳").assertDoesNotExist()
        assertPanelInsidePreview()
    }

    @Test
    fun thumbRetryIsVisibleInsideSmallContainer() {
        var selected: CalibrationState? = null
        show(readyState().copy(
            calibrationRetryState = CalibrationRetryState.THUMB_IN,
            feedbackMessage = "拇指内收时请保持其它手指张开"
        ), onRetry = { selected = it })
        assertInsidePreview("重采拇指内收")
        compose.onNodeWithText("重采拇指内收").performClick()
        assertEquals(CalibrationState.CALIBRATING_THUMB_IN, selected)
        assertPanelInsidePreview()
    }

    private fun show(
        state: GestureCameraState,
        onCapture: () -> Unit = {},
        onRetry: (CalibrationState) -> Unit = {}
    ) {
        compose.setContent {
            MaterialTheme {
                GestureCameraPreviewContainer(
                    modifier = Modifier.width(280.dp).height(240.dp).testTag("preview"),
                    controls = {
                        GestureCalibrationControls(
                            state = state,
                            onStartCalibration = {},
                            onRecordCalibrationPose = onCapture,
                            onRetryCalibrationPose = onRetry
                        )
                    }
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
    }

    private fun assertInsidePreview(text: String) {
        val node = compose.onNodeWithText(text).assertIsDisplayed().getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithTag("preview").getUnclippedBoundsInRoot()
        assertTrue("$text must fit horizontally", node.left >= preview.left && node.right <= preview.right)
        assertTrue("$text must fit vertically", node.top >= preview.top && node.bottom <= preview.bottom)
    }

    private fun assertPanelInsidePreview() {
        val panel = compose.onNodeWithTag("calibration-panel").getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithTag("preview").getUnclippedBoundsInRoot()
        assertTrue(panel.top >= preview.top && panel.bottom <= preview.bottom)
        assertTrue(panel.left >= preview.left && panel.right <= preview.right)
        assertEquals(280.dp, preview.width)
        assertEquals(240.dp, preview.height)
    }

    private fun readyState() = GestureCameraState(
        calibrationState = CalibrationState.CALIBRATING_THUMB_IN,
        handDetected = true,
        handedness = "Right",
        targetHandMatched = true,
        calibrationSampleCount = 0
    )
}
