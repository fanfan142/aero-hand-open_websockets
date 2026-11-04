package com.aerohand.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aerohand.gesture.CalibrationCaptureNotice
import com.aerohand.gesture.CalibrationCapturePhase
import com.aerohand.gesture.CalibrationRetryState
import com.aerohand.gesture.CalibrationState
import com.aerohand.gesture.GestureCameraState

@Composable
internal fun GestureCameraPreviewContainer(
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        content()
        Box(
            Modifier.align(Alignment.BottomCenter).padding(8.dp)
                .heightIn(max = (maxHeight - 16.dp).coerceAtLeast(0.dp))
        ) { controls() }
    }
}

@Composable
internal fun GestureCalibrationControls(
    state: GestureCameraState,
    onStartCalibration: () -> Unit,
    onRecordCalibrationPose: () -> Unit,
    onRetryCalibrationPose: (CalibrationState) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pose = when (state.calibrationState) {
        CalibrationState.CALIBRATING_OPEN -> "张开"
        CalibrationState.CALIBRATING_FIST -> "握拳"
        CalibrationState.CALIBRATING_THUMB_IN -> "拇指内收"
        else -> null
    }
    val step = when (state.calibrationState) {
        CalibrationState.CALIBRATING_OPEN -> "1/3"
        CalibrationState.CALIBRATING_FIST -> "2/3"
        CalibrationState.CALIBRATING_THUMB_IN -> "3/3"
        else -> ""
    }
    val collecting = state.calibrationCapturePhase == CalibrationCapturePhase.COLLECTING
    val retry = state.calibrationRetryState
    val canStart = state.handDetected && state.handedness in setOf("Left", "Right") &&
        state.targetHandMatched && state.targetHand.matches(state.handedness) &&
        state.rawHands.size <= 1 && !collecting && retry == CalibrationRetryState.NONE

    Surface(
        modifier = modifier.fillMaxWidth().testTag("calibration-panel"),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Only the explanation scrolls; actions always remain inside the camera container.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (pose == null) {
                    if (state.calibrationState == CalibrationState.CALIBRATED) {
                        Text("校准完成", style = MaterialTheme.typography.titleSmall)
                    } else {
                        Text("三步校准：张开 → 握拳 → 拇指内收", style = MaterialTheme.typography.titleSmall)
                        Text("1. 张开五指和拇指\n2. 握紧拳头\n3. 其它四指张开，拇指靠向食指", style = MaterialTheme.typography.bodySmall)
                        Text("每步只放入同一只目标手。摆好姿势后开始采集，保持不动约一秒，稳定后自动确认。", style = MaterialTheme.typography.bodySmall)
                        Text("只点一次，不用连点5次", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Text("${step} ${pose} · ${if (collecting) "采集中" else "待采集"}", style = MaterialTheme.typography.titleSmall)
                    if (retry != CalibrationRetryState.NONE) {
                        Text(state.feedbackMessage, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (retry == CalibrationRetryState.OPEN_FIST) "握拳样本已丢弃。可重采握拳，或重采张开并重做后续步骤。"
                            else "本次拇指样本已丢弃，请摆好姿势后重采。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            if (state.calibrationState == CalibrationState.CALIBRATING_THUMB_IN)
                                "只放入一只目标手，其它四指张开，拇指靠向食指。"
                            else "只放入一只目标手，保持${pose}不动。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (collecting) {
                            Text("稳定采集 ${state.calibrationSampleCount}/${state.calibrationSamplesRequired} · ${state.calibrationCaptureElapsedMs}/600 ms", style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(
                                progress = (state.calibrationSampleCount.toFloat() / state.calibrationSamplesRequired).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("保持不动，稳定后自动确认。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                when {
                                    !state.handDetected || state.handedness !in setOf("Left", "Right") -> "请将一只目标手完整放入画面。"
                                    !state.targetHandMatched || !state.targetHand.matches(state.handedness) -> "手别不匹配，请换成${state.targetHand.label}。"
                                    state.calibrationCaptureNotice == CalibrationCaptureNotice.INTERRUPTED -> "上次采集已中断并丢弃，请重新开始采集。"
                                    state.calibrationCaptureNotice == CalibrationCaptureNotice.READY -> "上一步已自动确认，摆好当前姿势后再开始。"
                                    else -> "摆好姿势后点击开始采集，约一秒后自动确认。"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("只点一次，不用连点5次", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (pose == null) {
                Button(onClick = onStartCalibration, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.calibrationState == CalibrationState.CALIBRATED) "重新校准" else "开始校准")
                }
            } else if (retry == CalibrationRetryState.OPEN_FIST) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { onRetryCalibrationPose(CalibrationState.CALIBRATING_OPEN) }, modifier = Modifier.weight(1f)) { Text("重采张开") }
                    Button(onClick = { onRetryCalibrationPose(CalibrationState.CALIBRATING_FIST) }, modifier = Modifier.weight(1f)) { Text("重采握拳") }
                }
            } else if (retry == CalibrationRetryState.THUMB_IN) {
                Button(onClick = { onRetryCalibrationPose(CalibrationState.CALIBRATING_THUMB_IN) }, modifier = Modifier.fillMaxWidth()) { Text("重采拇指内收") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onRecordCalibrationPose, enabled = canStart, modifier = Modifier.weight(1f)) {
                        Text(if (collecting) "采集中…" else "开始采集${pose}")
                    }
                    TextButton(onClick = onStartCalibration) { Text("重来") }
                }
            }
        }
    }
}
