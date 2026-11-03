package com.aerohand.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aerohand.gesture.CalibrationState
import com.aerohand.gesture.GestureCameraState

@Composable
internal fun GestureCameraPreviewContainer(
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        content()
        Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { controls() }
    }
}

@Composable
internal fun GestureCalibrationControls(
    state: GestureCameraState,
    onStartCalibration: () -> Unit,
    onRecordCalibrationPose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pose = when (state.calibrationState) {
        CalibrationState.CALIBRATING_OPEN -> "张开"
        CalibrationState.CALIBRATING_FIST -> "握拳"
        CalibrationState.CALIBRATING_THUMB_IN -> "拇指内收"
        else -> null
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (pose != null) {
                Text(
                    text = "有效采样 ${state.calibrationSampleCount}/${state.calibrationSamplesRequired} · 保持同一只手入镜",
                    style = MaterialTheme.typography.labelMedium
                )
                if (state.feedbackMessage.isNotBlank()) {
                    Text(state.feedbackMessage, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRecordCalibrationPose,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("记录$pose")
                    }
                    Button(onClick = onStartCalibration) { Text("重来") }
                }
            } else {
                if (state.calibrationState == CalibrationState.CALIBRATED) {
                    Text("已校准 · 跟随仍受连接、手别和新鲜帧安全门控", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onStartCalibration, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.calibrationState == CalibrationState.CALIBRATED) "重新校准" else "开始校准")
                }
            }
        }
    }
}
