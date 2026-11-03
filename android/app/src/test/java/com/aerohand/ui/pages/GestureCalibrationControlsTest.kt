package com.aerohand.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.aerohand.gesture.CalibrationState
import com.aerohand.gesture.GestureCameraState
import org.junit.Assert.assertEquals
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
    fun thirdPoseCanBeClickedOnNarrowScreenAndShowsSampleCountAndFailure() {
        var clicks = 0
        var restarts = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    GestureCalibrationControls(
                        state = GestureCameraState(
                            calibrationState = CalibrationState.CALIBRATING_THUMB_IN,
                            calibrationSampleCount = 5,
                            feedbackMessage = "校准失败：拇指内收幅度太小，请重新记录"
                        ),
                        onStartCalibration = { restarts++ },
                        onRecordCalibrationPose = { clicks++ }
                    )
                }
            }
        }
        compose.onNodeWithText("有效采样 5/5", substring = true).assertIsDisplayed()
        compose.onNodeWithText("拇指内收幅度太小", substring = true).assertIsDisplayed()
        compose.onNodeWithText("记录拇指内收").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("重来").performClick()
        assertEquals(1, clicks)
        assertEquals(1, restarts)
    }
}
