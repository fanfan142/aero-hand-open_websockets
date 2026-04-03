package com.aerohand.gesture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun GestureCameraPanel(
    cameraState: GestureCameraState,
    onStartCalibration: () -> Unit,
    onRecordCalibrationPose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "手势控制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (cameraState.calibrationState) {
                            CalibrationState.NOT_CALIBRATED -> "未校准"
                            CalibrationState.CALIBRATING_OPEN -> "校准中：张开手"
                            CalibrationState.CALIBRATING_FIST -> "校准中：握拳"
                            CalibrationState.CALIBRATING_THUMB_IN -> "校准中：拇指内收"
                            CalibrationState.CALIBRATED -> "已校准"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (cameraState.handDetected) Color(0xFF34D399)
                                else Color(0xFFF87171)
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (cameraState.handDetected) "检测到手部" else "未检测",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cameraState.handDetected) Color(0xFF166534)
                        else Color(0xFF991B1B)
                    )
                }
            }

            // FPS indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "FPS: ${"%.1f".format(cameraState.fps)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (cameraState.calibrationState == CalibrationState.CALIBRATED) {
                    Text(
                        "实时控制已启用",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF166534)
                    )
                }
            }

            // Finger status bars
            FingerStatusBars(angles = cameraState.smoothedAngles)

            // Calibration buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasCameraPermission) {
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("授予相机权限")
                    }
                } else if (cameraState.calibrationState != CalibrationState.CALIBRATED) {
                    Button(
                        onClick = onStartCalibration,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("开始校准")
                    }
                    if (cameraState.calibrationState != CalibrationState.NOT_CALIBRATED &&
                        cameraState.calibrationState != CalibrationState.CALIBRATING_THUMB_IN
                    ) {
                        Button(
                            onClick = onRecordCalibrationPose,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("记录姿势")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onStartCalibration,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("重新校准")
                    }
                }
            }
        }
    }
}

@Composable
private fun FingerStatusBars(angles: FingerAngles) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FingerBar("拇指外展", angles.thumbAbd, 0f, 100f, Color(0xFFF59E0B))
        FingerBar("拇指屈曲", angles.thumbFlex, 0f, 55f, Color(0xFFFB923C))
        FingerBar("食指", angles.indexFlex, 0f, 90f, Color(0xFF3B82F6))
        FingerBar("中指", angles.middleFlex, 0f, 90f, Color(0xFF10B981))
        FingerBar("无名指", angles.ringFlex, 0f, 90f, Color(0xFF8B5CF6))
        FingerBar("小指", angles.pinkyFlex, 0f, 90f, Color(0xFFEC4899))
    }
}

@Composable
private fun FingerBar(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "%.0f".format(value),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
        LinearProgressIndicator(
            progress = { ((value - min) / (max - min)).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
