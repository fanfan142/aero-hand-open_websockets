package com.aerohand.gesture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

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
                                if (cameraState.handDetected) ComposeColor(0xFF34D399)
                                else ComposeColor(0xFFF87171)
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (cameraState.handDetected) "检测到手部" else "未检测",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cameraState.handDetected) ComposeColor(0xFF166534)
                        else ComposeColor(0xFF991B1B)
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
                        color = ComposeColor(0xFF166534)
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
        FingerBar("拇指外展", angles.thumbAbd, 0f, 100f, ComposeColor(0xFFF59E0B))
        FingerBar("拇指屈曲", angles.thumbFlex, 0f, 55f, ComposeColor(0xFFFB923C))
        FingerBar("食指", angles.indexFlex, 0f, 90f, ComposeColor(0xFF3B82F6))
        FingerBar("中指", angles.middleFlex, 0f, 90f, ComposeColor(0xFF10B981))
        FingerBar("无名指", angles.ringFlex, 0f, 90f, ComposeColor(0xFF8B5CF6))
        FingerBar("小指", angles.pinkyFlex, 0f, 90f, ComposeColor(0xFFEC4899))
    }
}

// MediaPipe hand landmark connections
private val HAND_CONNECTIONS = listOf(
    // Thumb
    Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
    // Index
    Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
    // Middle
    Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
    // Ring
    Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
    // Pinky
    Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
    // Palm
    Pair(5, 9), Pair(9, 13), Pair(13, 17)
)

@Composable
fun SkeletonOverlay(
    landmarks: List<NormalizedLandmark>,
    modifier: Modifier = Modifier
) {
    if (landmarks.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Convert landmarks to pixel coordinates (mirrored for front camera)
        val points = landmarks.map { lm ->
            Offset(
                x = (1f - lm.x()) * w,  // Mirror horizontally for front camera
                y = lm.y() * h
            )
        }

        // Draw connections (green lines)
        HAND_CONNECTIONS.forEach { (start, end) ->
            if (start < points.size && end < points.size) {
                drawLine(
                    color = ComposeColor.Green,
                    start = points[start],
                    end = points[end],
                    strokeWidth = 4f
                )
            }
        }

        // Draw landmark points (blue with dark border)
        points.forEach { point ->
            // Outer dark circle
            drawCircle(
                color = ComposeColor(0xFF141414),
                radius = 10f,
                center = point,
                style = Stroke(width = 2f)
            )
            // Inner blue circle
            drawCircle(
                color = ComposeColor(0xFFB4AFFF),
                radius = 7f,
                center = point
            )
        }
    }
}

@Composable
private fun FingerBar(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    color: ComposeColor
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
            progress = ((value - min) / (max - min)).coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
