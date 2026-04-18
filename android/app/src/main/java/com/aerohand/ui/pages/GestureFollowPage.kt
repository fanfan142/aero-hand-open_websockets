package com.aerohand.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aerohand.gesture.CalibrationState
import com.aerohand.gesture.FingerAngles
import com.aerohand.gesture.GestureCameraService
import com.aerohand.gesture.GestureCameraState
import com.aerohand.gesture.GestureTargetHand
import com.aerohand.gesture.SkeletonOverlay

@Composable
fun GestureFollowPage(
    gestureService: GestureCameraService,
    cameraState: GestureCameraState,
    onTargetHandChange: (GestureTargetHand) -> Unit,
    onStartCalibration: () -> Unit,
    onRecordCalibrationPose: () -> Unit,
    onCameraFlip: () -> Unit,
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

    // Visibility state for status overlay
    var showStatusOverlay by remember { mutableStateOf(true) }
    var isFrontCamera by remember { mutableStateOf(true) }

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
            // Camera preview - portrait ratio
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    CameraPreview(
                        gestureService = gestureService,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Skeleton overlay
                    SkeletonOverlay(
                        landmarks = cameraState.landmarks,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AnimatedVisibility(visible = showStatusOverlay) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (cameraState.handDetected && cameraState.targetHandMatched) Color(0xFF34D399)
                                                else Color(0xFFF87171)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = buildString {
                                            append(if (cameraState.handDetected) "已检测" else "未检测")
                                            if (cameraState.handedness.isNotEmpty()) append(" ${cameraState.handedness}")
                                            if (!cameraState.targetHandMatched && cameraState.handDetected) {
                                                append(" · 请切换到${cameraState.targetHand.label}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier.clickable { showStatusOverlay = !showStatusOverlay },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = if (showStatusOverlay) "隐藏状态" else "显示状态",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            Surface(
                                modifier = Modifier.clickable {
                                    isFrontCamera = gestureService.toggleCamera()
                                    onCameraFlip()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = if (isFrontCamera) "📷 前" else "📷 后",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // No camera permission
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("需要相机权限", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("授予权限")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "手势跟随",
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
                Text(
                    "FPS: ${"%.1f".format(cameraState.fps)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GestureTargetHand.entries.forEach { target ->
                    FilterChip(
                        selected = cameraState.targetHand == target,
                        onClick = { onTargetHandChange(target) },
                        label = { Text(target.label) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            Text(
                text = buildString {
                    append("检测手：")
                    append(if (cameraState.handedness.isBlank()) "未识别" else cameraState.handedness)
                    append(" · 控制目标：${cameraState.targetHand.label}")
                    if (cameraState.handDetected && !cameraState.targetHandMatched) {
                        append(" · 当前手别不匹配")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (cameraState.handDetected && !cameraState.targetHandMatched) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (cameraState.feedbackMessage.isNotBlank()) {
                Text(
                    text = cameraState.feedbackMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cameraState.feedbackMessage.contains("失败") || cameraState.feedbackMessage.contains("不一致")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            // Finger status bars - show calibrated angles when available
            FingerStatusBars(
                angles = if (cameraState.calibrationState == CalibrationState.CALIBRATED)
                    cameraState.calibratedAngles else cameraState.smoothedAngles
            )

            // Calibration buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasCameraPermission && cameraState.calibrationState != CalibrationState.CALIBRATED) {
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
                    if (cameraState.calibrationState == CalibrationState.CALIBRATING_OPEN ||
                        cameraState.calibrationState == CalibrationState.CALIBRATING_FIST ||
                        cameraState.calibrationState == CalibrationState.CALIBRATING_THUMB_IN
                    ) {
                        Button(
                            onClick = onRecordCalibrationPose,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                when (cameraState.calibrationState) {
                                    CalibrationState.CALIBRATING_OPEN -> "记录张开"
                                    CalibrationState.CALIBRATING_FIST -> "记录握拳"
                                    CalibrationState.CALIBRATING_THUMB_IN -> "记录拇指内收"
                                    else -> "记录姿势"
                                }
                            )
                        }
                    }
                } else if (cameraState.calibrationState == CalibrationState.CALIBRATED) {
                    Text(
                        "实时手势控制已启用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF166534),
                        modifier = Modifier.weight(1f)
                    )
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
private fun CameraPreview(
    gestureService: GestureCameraService,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Create a visible PreviewView
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Start camera with visible preview
    LaunchedEffect(previewView) {
        gestureService.startCamera(previewView)
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
private fun FingerStatusBars(angles: FingerAngles) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FingerBar("拇指外展", angles.thumbAbd, 0f, 100f, Color(0xFFF59E0B))
        FingerBar("拇指CMC屈曲", angles.thumbCmcFlex, 0f, 55f, Color(0xFFFB923C))
        FingerBar("拇指肌腱", angles.thumbTendon, 0f, 90f, Color(0xFFF97316))
        FingerBar("食指肌腱", angles.indexTendon, 0f, 90f, Color(0xFF3B82F6))
        FingerBar("中指肌腱", angles.middleTendon, 0f, 90f, Color(0xFF10B981))
        FingerBar("无名指肌腱", angles.ringTendon, 0f, 90f, Color(0xFF8B5CF6))
        FingerBar("小指肌腱", angles.pinkyTendon, 0f, 90f, Color(0xFFEC4899))
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
