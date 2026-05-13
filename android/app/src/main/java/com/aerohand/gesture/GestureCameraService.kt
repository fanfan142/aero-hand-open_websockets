package com.aerohand.gesture

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

class GestureCameraService(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "GestureCameraService"
        private const val NUM_HANDS = 1
        private const val MIN_HAND_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_HAND_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val HAND_LANDMARKER_MODEL_ASSET = "hand_landmarker.task"
        private const val FPS_WINDOW = 10
        private const val VIDEO_FRAME_INTERVAL_MS = 33L
        private const val UI_STATE_INTERVAL_MS = 100L
        private val ANALYSIS_TARGET_SIZE = Size(480, 360)
        private const val CALIB_SCHEMA = 2
        private const val MIN_FINGER_RANGE = 12f
        private const val MIN_THUMB_SWING_RANGE = 10f
        private const val MAX_LOST_FRAME_MS = 300L
        private val EMA_ALPHA = floatArrayOf(0.78f, 0.78f, 0.8f, 0.82f, 0.82f, 0.82f, 0.82f)
        private val DEADBAND = floatArrayOf(0.25f, 0.25f, 0.3f, 0.35f, 0.35f, 0.35f, 0.35f)
        private const val THUMB_SWING_ALPHA = 0.65f
        private const val THUMB_SWING_DEADBAND = 0.75f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("gesture_calib", Context.MODE_PRIVATE)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(GestureCameraState())
    val state: StateFlow<GestureCameraState> = _state
    private val _controlFrame = MutableStateFlow(GestureControlFrame(false, message = "未检测到手"))
    val controlFrame: StateFlow<GestureControlFrame> = _controlFrame

    private var profile: GestureCalibrationProfile? = null
    private var pendingOpen = FloatArray(7) { 0f }
    private var pendingFist = FloatArray(7) { 0f }
    private var pendingOpenThumbSwing = 0f
    private var pendingHandSide = ""
    private var pendingFacing = currentFacing()
    private var pendingMirror = currentMirror()

    private var smoothedValues = FloatArray(7) { 0f }
    private var smoothedThumbSwing = 0f
    private var needsInitialUpdate = true
    private var lastFrameTime = System.nanoTime()
    private var lastHandDetectedMs = 0L
    private var lastUiStatePublishMs = 0L
    private var frameTimeBuffer = mutableListOf<Long>()
    private val videoTimestampMs = AtomicLong(0L)
    private var targetHand: GestureTargetHand = GestureTargetHand.AUTO
    private var useFrontCamera: Boolean = true
    private var cameraPreviewView: PreviewView? = null

    init {
        loadCalibration()
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun startCamera(previewView: PreviewView) {
        cameraPreviewView = previewView
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            setupImageAnalysis(previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    @Suppress("DEPRECATION")
    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun setupImageAnalysis(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(ANALYSIS_TARGET_SIZE)
            .setTargetRotation(previewView.display.rotation)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy -> processImage(imageProxy) }
            }

        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            publishUiState(_state.value.copy(
                isRunning = true,
                cameraFacing = currentFacing(),
                mirrorMode = currentMirror(),
                calibrationState = activeCalibrationState(_state.value.handedness),
                calibrationProfile = profile
            ), force = true)
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            publishUiState(_state.value.copy(feedbackMessage = "相机启动失败：${e.message ?: "未知错误"}"), force = true)
        }
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        try {
            val currentTime = System.nanoTime()
            val delta = (currentTime - lastFrameTime) / 1_000_000f
            lastFrameTime = currentTime
            frameTimeBuffer.add(delta.toLong())
            if (frameTimeBuffer.size > FPS_WINDOW) frameTimeBuffer.removeAt(0)
            val avgDelta = frameTimeBuffer.average().toFloat()
            val fps = if (avgDelta > 0) 1000f / avgDelta else 0f

            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                detectHand(BitmapImageBuilder(bitmap).build(), fps, imageProxy.imageInfo.timestamp / 1_000_000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
            markNoHand(0f, "图像处理失败")
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val bitmap = rgbaImageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix()
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (useFrontCamera) matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            if (rotation != 0 || useFrontCamera) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) else bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }

    private fun rgbaImageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val width = imageProxy.width
        val height = imageProxy.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (pixelStride == 4 && rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        val packed = ByteArray(width * height * 4)
        var dst = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (rowStart >= buffer.limit()) break
            buffer.position(rowStart)
            for (col in 0 until width) {
                val src = rowStart + col * pixelStride
                if (src + 3 >= buffer.limit() || dst + 3 >= packed.size) break
                packed[dst++] = buffer.get(src)
                packed[dst++] = buffer.get(src + 1)
                packed[dst++] = buffer.get(src + 2)
                packed[dst++] = buffer.get(src + 3)
            }
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
        return bitmap
    }

    private fun detectHand(mpImage: MPImage, fps: Float, frameTimestampMs: Long) {
        if (handLandmarker == null) {
            initializeHandLandmarker()
            if (handLandmarker == null) {
                markNoHand(fps, "MediaPipe 初始化失败")
                return
            }
        }
        val result = try {
            val ts = if (frameTimestampMs > 0) {
                val prev = videoTimestampMs.get()
                val next = if (frameTimestampMs > prev) frameTimestampMs else prev + VIDEO_FRAME_INTERVAL_MS
                videoTimestampMs.set(next)
                next
            } else {
                videoTimestampMs.addAndGet(VIDEO_FRAME_INTERVAL_MS)
            }
            handLandmarker?.detectForVideo(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "Hand detection failed", e)
            null
        }
        if (result != null) processResult(result, fps) else markNoHand(fps, "未检测到手")
    }

    private fun processResult(result: HandLandmarkerResult, fps: Float) {
        val landmarks = result.landmarks()
        if (landmarks.isEmpty()) {
            markNoHand(fps, "未检测到手，请将目标手完整放入画面")
            return
        }

        val rawMpHand = result.handedness().firstOrNull()?.firstOrNull()?.categoryName().orEmpty()
        val actualHand = resolveHandedness(rawMpHand)
        val targetMatched = targetHand.matches(actualHand)
        val rawAngles = computeFingerAngles(landmarks[0], actualHand)
        val smoothed = applySmoothing(rawAngles)
        val calibrationState = activeCalibrationState(actualHand)
        val calibrated = if (calibrationState == CalibrationState.CALIBRATED) remapByCalibration(smoothedValues) else smoothed
        val feedback = when {
            !targetMatched -> "当前检测到 $actualHand，请切换到${targetHand.label}"
            calibrationState == CalibrationState.NOT_CALIBRATED -> calibrationFeedbackFor(actualHand)
            else -> ""
        }

        lastHandDetectedMs = System.currentTimeMillis()
        val oldState = _state.value
        _controlFrame.value = if (targetMatched && calibrationState == CalibrationState.CALIBRATED) {
            GestureControlFrame(true, calibrated)
        } else {
            GestureControlFrame(false, message = feedback.ifBlank { "未满足手势控制条件" })
        }
        val nextState = oldState.copy(
            handDetected = true,
            handedness = actualHand,
            targetHand = targetHand,
            targetHandMatched = targetMatched,
            feedbackMessage = feedback,
            rawAngles = rawAngles,
            smoothedAngles = smoothed,
            calibratedAngles = calibrated,
            calibrationState = calibrationState,
            calibrationProfile = profile,
            cameraFacing = currentFacing(),
            mirrorMode = currentMirror(),
            fps = fps,
            landmarks = landmarks[0]
        )
        publishUiState(
            nextState,
            force = !oldState.handDetected ||
                oldState.targetHandMatched != targetMatched ||
                oldState.calibrationState != calibrationState ||
                oldState.cameraFacing != currentFacing() ||
                oldState.mirrorMode != currentMirror() ||
                oldState.feedbackMessage != feedback
        )
    }

    private fun markNoHand(fps: Float, message: String) {
        val now = System.currentTimeMillis()
        val keepLastAngles = now - lastHandDetectedMs < MAX_LOST_FRAME_MS
        val oldState = _state.value
        _controlFrame.value = GestureControlFrame(false, message = message)
        publishUiState(oldState.copy(
            handDetected = false,
            handedness = "",
            targetHand = targetHand,
            targetHandMatched = targetHand == GestureTargetHand.AUTO,
            feedbackMessage = message,
            calibratedAngles = if (keepLastAngles) _state.value.calibratedAngles else FingerAngles(),
            fps = fps,
            landmarks = emptyList()
        ), force = oldState.handDetected || oldState.feedbackMessage != message)
    }

    private fun publishUiState(nextState: GestureCameraState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastUiStatePublishMs >= UI_STATE_INTERVAL_MS) {
            lastUiStatePublishMs = now
            _state.value = nextState
        }
    }

    private fun initializeHandLandmarker() {
        try {
            val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(NUM_HANDS)
                .setMinHandDetectionConfidence(MIN_HAND_DETECTION_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_HAND_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            val hasModelAsset = runCatching {
                context.assets.open(HAND_LANDMARKER_MODEL_ASSET).use { true }
            }.getOrElse { false }
            if (hasModelAsset) {
                optionsBuilder.setBaseOptions(
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                        .setModelAssetPath(HAND_LANDMARKER_MODEL_ASSET)
                        .build()
                )
            }
            handLandmarker = HandLandmarker.createFromOptions(context, optionsBuilder.build())
            Log.i(TAG, "Hand landmarker initialized (customModel=$hasModelAsset)")
        } catch (e: Exception) {
            Log.e(TAG, "Hand landmarker initialization failed", e)
            handLandmarker = null
        }
    }

    fun toggleCamera(): Boolean {
        useFrontCamera = !useFrontCamera
        cameraProvider?.unbindAll()
        cameraPreviewView?.let { setupImageAnalysis(it) }
        return useFrontCamera
    }

    fun isFrontCamera(): Boolean = useFrontCamera

    fun setTargetHand(targetHand: GestureTargetHand) {
        this.targetHand = targetHand
        val matched = targetHand.matches(_state.value.handedness)
        _state.value = _state.value.copy(
            targetHand = targetHand,
            targetHandMatched = matched,
            calibrationState = activeCalibrationState(_state.value.handedness),
            feedbackMessage = when {
                _state.value.handedness.isBlank() -> "未检测到手，请将目标手完整放入画面"
                matched -> calibrationFeedbackFor(_state.value.handedness)
                else -> "当前检测到 ${_state.value.handedness}，请切换到${targetHand.label}"
            }
        )
    }

    private fun canonicalHandedness(mpHand: String): String {
        return when {
            mpHand.equals("Left", true) -> "Left"
            mpHand.equals("Right", true) -> "Right"
            else -> ""
        }
    }

    private fun swapHandedness(hand: String): String {
        return when (hand) {
            "Left" -> "Right"
            "Right" -> "Left"
            else -> ""
        }
    }

    private fun resolveHandedness(mpHand: String): String {
        val hand = canonicalHandedness(mpHand)
        if (hand.isBlank()) return ""

        val preferredByCamera = if (currentMirror() == GestureMirrorMode.NORMAL) {
            swapHandedness(hand)
        } else {
            hand
        }
        val alternate = swapHandedness(preferredByCamera)
        val preferredScore = handednessScore(preferredByCamera, preferredByCamera)
        val alternateScore = handednessScore(alternate, preferredByCamera)
        return if (alternateScore > preferredScore) alternate else preferredByCamera
    }

    private fun handednessScore(candidate: String, preferredByCamera: String): Int {
        if (candidate.isBlank()) return Int.MIN_VALUE

        var score = 0
        if (targetHand != GestureTargetHand.AUTO) {
            score += if (targetHand.matches(candidate)) 8 else -8
        }
        profile?.let { saved ->
            if (saved.matchesHand(candidate)) {
                score += 6
            }
        }
        if (candidate == preferredByCamera) {
            score += 1
        }
        return score
    }

    private fun currentFacing(): GestureCameraFacing = if (useFrontCamera) GestureCameraFacing.FRONT else GestureCameraFacing.BACK

    private fun currentMirror(): GestureMirrorMode = if (useFrontCamera) GestureMirrorMode.SELFIE else GestureMirrorMode.NORMAL

    private fun computeFingerAngles(landmarks: List<NormalizedLandmark>, handedness: String): FingerAngles {
        if (landmarks.size < 21) {
            Log.w(TAG, "Unexpected landmarks size: ${landmarks.size}")
            return FingerAngles()
        }

        fun angleDegrees(p1: NormalizedLandmark, p2: NormalizedLandmark, p3: NormalizedLandmark): Float {
            val v1x = p1.x() - p2.x()
            val v1y = p1.y() - p2.y()
            val v2x = p3.x() - p2.x()
            val v2y = p3.y() - p2.y()
            val dot = v1x * v2x + v1y * v2y
            val mag1 = sqrt(v1x * v1x + v1y * v1y)
            val mag2 = sqrt(v2x * v2x + v2y * v2y)
            if (mag1 < 0.0001f || mag2 < 0.0001f) return 0f
            val cosVal = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cosVal.toDouble())).toFloat()
        }

        fun flexionDegrees(p1: NormalizedLandmark, p2: NormalizedLandmark, p3: NormalizedLandmark): Float {
            return (180f - angleDegrees(p1, p2, p3)).coerceIn(0f, 90f)
        }

        fun point(index: Int): Pair<Float, Float> = landmarks[index].x() to landmarks[index].y()
        fun vector(from: Pair<Float, Float>, to: Pair<Float, Float>) = (to.first - from.first) to (to.second - from.second)
        fun normalize(vec: Pair<Float, Float>): Pair<Float, Float> {
            val mag = sqrt(vec.first * vec.first + vec.second * vec.second)
            return if (mag < 0.0001f) 0f to 0f else (vec.first / mag) to (vec.second / mag)
        }

        val palmAxis = vector(point(13), point(5))
        val thumbAxis = normalize(vector(point(1), point(2)))
        val palmAxisNorm = normalize(palmAxis)
        val imageThumbAngle = Math.toDegrees(
            atan2(
                (palmAxisNorm.first * thumbAxis.second - palmAxisNorm.second * thumbAxis.first).toDouble(),
                (palmAxisNorm.first * thumbAxis.first + palmAxisNorm.second * thumbAxis.second).toDouble()
            )
        ).toFloat()
        val handSign = if (handedness == "Right") -1f else 1f
        val thumbSwing = imageThumbAngle * handSign
        val thumbAbd = ((thumbSwing + 45f) / 90f * 100f).coerceIn(0f, 100f)

        val thumbCmcFlex = (flexionDegrees(landmarks[0], landmarks[1], landmarks[2]) * (55f / 90f)).coerceIn(0f, 55f)
        val thumbTendon = flexionDegrees(landmarks[2], landmarks[3], landmarks[4])
        val indexTendon = flexionDegrees(landmarks[5], landmarks[6], landmarks[7])
        val middleTendon = flexionDegrees(landmarks[9], landmarks[10], landmarks[11])
        val ringTendon = flexionDegrees(landmarks[13], landmarks[14], landmarks[15])
        val pinkyTendon = flexionDegrees(landmarks[17], landmarks[18], landmarks[19])

        return FingerAngles(
            thumbAbd = thumbAbd,
            thumbCmcFlex = thumbCmcFlex,
            thumbTendon = thumbTendon.coerceIn(0f, 90f),
            indexTendon = indexTendon.coerceIn(0f, 90f),
            middleTendon = middleTendon.coerceIn(0f, 90f),
            ringTendon = ringTendon.coerceIn(0f, 90f),
            pinkyTendon = pinkyTendon.coerceIn(0f, 90f),
            thumbSwing = thumbSwing
        )
    }

    private fun applySmoothing(angles: FingerAngles): FingerAngles {
        val raw = floatArrayOf(
            angles.thumbAbd,
            angles.thumbCmcFlex,
            angles.thumbTendon,
            angles.indexTendon,
            angles.middleTendon,
            angles.ringTendon,
            angles.pinkyTendon
        )
        for (i in raw.indices) {
            val diff = abs(raw[i] - smoothedValues[i])
            if (needsInitialUpdate || diff >= DEADBAND[i]) {
                smoothedValues[i] = EMA_ALPHA[i] * raw[i] + (1 - EMA_ALPHA[i]) * smoothedValues[i]
            }
        }
        val swingDiff = abs(angles.thumbSwing - smoothedThumbSwing)
        if (needsInitialUpdate || swingDiff >= THUMB_SWING_DEADBAND) {
            smoothedThumbSwing =
                THUMB_SWING_ALPHA * angles.thumbSwing + (1 - THUMB_SWING_ALPHA) * smoothedThumbSwing
        }
        needsInitialUpdate = false
        return anglesFromArray(smoothedValues, smoothedThumbSwing)
    }

    fun startCalibration() {
        smoothedValues.fill(0f)
        smoothedThumbSwing = 0f
        needsInitialUpdate = true
        pendingHandSide = ""
        _state.value = _state.value.copy(
            calibrationState = CalibrationState.CALIBRATING_OPEN,
            feedbackMessage = "请用目标手张开手掌，保持手掌完整入镜后记录张开"
        )
    }

    fun recordCalibrationPose() {
        val state = _state.value
        val current = state.smoothedAngles
        if (!state.handDetected) {
            _state.value = state.copy(feedbackMessage = "校准失败：未检测到手")
            return
        }
        if (!state.targetHandMatched) {
            _state.value = state.copy(feedbackMessage = "校准失败：检测手与目标手不一致")
            return
        }
        val hand = state.handedness
        if (hand.isBlank()) {
            _state.value = state.copy(feedbackMessage = "校准失败：无法确认左右手")
            return
        }

        when (state.calibrationState) {
            CalibrationState.CALIBRATING_OPEN -> {
                pendingOpen = current.toArray()
                pendingOpenThumbSwing = current.thumbSwing
                pendingHandSide = hand
                pendingFacing = currentFacing()
                pendingMirror = currentMirror()
                _state.value = state.copy(
                    calibrationState = CalibrationState.CALIBRATING_FIST,
                    feedbackMessage = "已记录张开手，请保持同一只${handLabel(hand)}并记录握拳"
                )
            }
            CalibrationState.CALIBRATING_FIST -> {
                if (!sameCalibrationContext(hand)) {
                    _state.value = state.copy(feedbackMessage = "校准失败：手别或摄像头已变化，请重新开始")
                    return
                }
                pendingFist = current.toArray()
                _state.value = state.copy(
                    calibrationState = CalibrationState.CALIBRATING_THUMB_IN,
                    feedbackMessage = "已记录握拳，请张开手并做拇指内收后记录"
                )
            }
            CalibrationState.CALIBRATING_THUMB_IN -> {
                if (!sameCalibrationContext(hand)) {
                    _state.value = state.copy(feedbackMessage = "校准失败：手别或摄像头已变化，请重新开始")
                    return
                }
                val nextProfile = GestureCalibrationProfile(
                    schemaVersion = CALIB_SCHEMA,
                    handSide = hand,
                    cameraFacing = currentFacing(),
                    mirrorMode = currentMirror(),
                    openAngles = pendingOpen.copyOf(),
                    fistAngles = pendingFist.copyOf(),
                    openThumbSwing = pendingOpenThumbSwing,
                    thumbInSwing = current.thumbSwing
                )
                val validation = validateCalibration(nextProfile)
                if (validation != null) {
                    _state.value = state.copy(feedbackMessage = validation)
                    return
                }
                profile = nextProfile
                saveCalibration(nextProfile)
                _state.value = state.copy(
                    calibrationState = CalibrationState.CALIBRATED,
                    calibrationProfile = nextProfile,
                    calibratedAngles = remapByCalibration(smoothedValues),
                    feedbackMessage = "校准完成，实时手势控制已启用"
                )
            }
            else -> {}
        }
    }

    fun getControlFrame(): GestureControlFrame {
        return _controlFrame.value
    }

    private fun remapByCalibration(values: FloatArray): FingerAngles {
        val active = profile ?: return anglesFromArray(values, smoothedThumbSwing)
        fun remap(value: Float, open: Float, fist: Float, targetMax: Float): Float {
            val range = fist - open
            if (abs(range) < 0.001f) return 0f
            return ((value - open) / range * targetMax).coerceIn(0f, targetMax)
        }
        val thumbSwingRange = active.openThumbSwing - active.thumbInSwing
        val thumbAbd = if (abs(thumbSwingRange) >= 0.001f) {
            ((active.openThumbSwing - smoothedThumbSwing) / thumbSwingRange * 100f).coerceIn(0f, 100f)
        } else 0f
        return FingerAngles(
            thumbAbd = thumbAbd,
            thumbCmcFlex = remap(values[1], active.openAngles[1], active.fistAngles[1], 55f),
            thumbTendon = remap(values[2], active.openAngles[2], active.fistAngles[2], 90f),
            indexTendon = remap(values[3], active.openAngles[3], active.fistAngles[3], 90f),
            middleTendon = remap(values[4], active.openAngles[4], active.fistAngles[4], 90f),
            ringTendon = remap(values[5], active.openAngles[5], active.fistAngles[5], 90f),
            pinkyTendon = remap(values[6], active.openAngles[6], active.fistAngles[6], 90f),
            thumbSwing = smoothedThumbSwing
        )
    }

    private fun activeCalibrationState(hand: String): CalibrationState {
        val p = profile ?: return _state.value.calibrationState.takeIf { it != CalibrationState.CALIBRATED } ?: CalibrationState.NOT_CALIBRATED
        return if (hand.isNotBlank() && p.matchesHand(hand)) {
            CalibrationState.CALIBRATED
        } else if (_state.value.calibrationState != CalibrationState.CALIBRATED) {
            _state.value.calibrationState
        } else {
            CalibrationState.NOT_CALIBRATED
        }
    }

    private fun calibrationFeedbackFor(hand: String): String {
        val p = profile ?: return "未校准，请先完成三步标定"
        return if (hand.isBlank()) {
            "已保存标定，请将${handLabel(p.handSide)}放入画面"
        } else if (!p.matchesHand(hand)) {
            "当前为${handLabel(hand)}，历史标定属于${handLabel(p.handSide)}，请切换目标手后再控制"
        } else if (!p.matchesContext(hand, currentFacing(), currentMirror())) {
            "已沿用${handLabel(p.handSide)}标定（历史${p.cameraFacing.label}，当前${currentFacing().label}）"
        } else ""
    }

    private fun sameCalibrationContext(hand: String): Boolean {
        return pendingHandSide == hand && pendingFacing == currentFacing() && pendingMirror == currentMirror()
    }

    private fun validateCalibration(p: GestureCalibrationProfile): String? {
        val fingerNames = listOf("拇指CMC", "拇指肌腱", "食指", "中指", "无名指", "小指")
        val indexes = listOf(1, 2, 3, 4, 5, 6)
        indexes.forEachIndexed { i, index ->
            if (abs(p.fistAngles[index] - p.openAngles[index]) < MIN_FINGER_RANGE) {
                return "校准失败：${fingerNames[i]}张开/握拳差值太小，请重新记录"
            }
        }
        if (abs(p.openThumbSwing - p.thumbInSwing) < MIN_THUMB_SWING_RANGE) {
            return "校准失败：拇指内收幅度太小，请重新记录"
        }
        return null
    }

    private fun saveCalibration(p: GestureCalibrationProfile) {
        prefs.edit().apply {
            putInt("schemaVersion", CALIB_SCHEMA)
            putString("handSide", p.handSide)
            putString("cameraFacing", p.cameraFacing.name)
            putString("mirrorMode", p.mirrorMode.name)
            putString("openAngles", p.openAngles.joinToString(","))
            putString("fistAngles", p.fistAngles.joinToString(","))
            putFloat("thumbInSwing", p.thumbInSwing)
            putFloat("openThumbSwing", p.openThumbSwing)
            apply()
        }
    }

    private fun loadCalibration() {
        val openStr = prefs.getString("openAngles", null) ?: return
        val fistStr = prefs.getString("fistAngles", null) ?: return
        try {
            val open = openStr.split(",").map { it.toFloat() }.toFloatArray()
            val fist = fistStr.split(",").map { it.toFloat() }.toFloatArray()
            if (open.size != 7 || fist.size != 7) return
            val version = prefs.getInt("schemaVersion", 1)
            val hand = prefs.getString("handSide", null).orEmpty()
            if (version < CALIB_SCHEMA || hand.isBlank()) {
                _state.value = _state.value.copy(
                    calibrationState = CalibrationState.NOT_CALIBRATED,
                    feedbackMessage = "检测到旧版标定，请重新校准一次"
                )
                return
            }
            val facing = runCatching { GestureCameraFacing.valueOf(prefs.getString("cameraFacing", GestureCameraFacing.FRONT.name)!!) }.getOrDefault(GestureCameraFacing.FRONT)
            val mirror = runCatching { GestureMirrorMode.valueOf(prefs.getString("mirrorMode", GestureMirrorMode.SELFIE.name)!!) }.getOrDefault(GestureMirrorMode.SELFIE)
            val loaded = GestureCalibrationProfile(
                schemaVersion = CALIB_SCHEMA,
                handSide = hand,
                cameraFacing = facing,
                mirrorMode = mirror,
                openAngles = open,
                fistAngles = fist,
                openThumbSwing = prefs.getFloat("openThumbSwing", 0f),
                thumbInSwing = prefs.getFloat("thumbInSwing", 0f)
            )
            profile = loaded
            _state.value = _state.value.copy(
                calibrationProfile = loaded,
                calibrationState = if (loaded.matchesHand(hand)) CalibrationState.CALIBRATED else CalibrationState.NOT_CALIBRATED,
                feedbackMessage = "已加载${handLabel(hand)}标定（记录于${loaded.cameraFacing.label}）"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse calibration data, reset required", e)
        }
    }

    private fun anglesFromArray(values: FloatArray, thumbSwing: Float): FingerAngles {
        return FingerAngles(
            thumbAbd = values[0].coerceIn(0f, 100f),
            thumbCmcFlex = values[1].coerceIn(0f, 55f),
            thumbTendon = values[2].coerceIn(0f, 90f),
            indexTendon = values[3].coerceIn(0f, 90f),
            middleTendon = values[4].coerceIn(0f, 90f),
            ringTendon = values[5].coerceIn(0f, 90f),
            pinkyTendon = values[6].coerceIn(0f, 90f),
            thumbSwing = thumbSwing
        )
    }

    private fun FingerAngles.toArray(): FloatArray {
        return floatArrayOf(thumbAbd, thumbCmcFlex, thumbTendon, indexTendon, middleTendon, ringTendon, pinkyTendon)
    }

    private fun handLabel(hand: String): String {
        return when (hand) {
            "Left" -> "左手"
            "Right" -> "右手"
            else -> "未知手"
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        videoTimestampMs.set(0L)
        _controlFrame.value = GestureControlFrame(false, message = "相机未运行")
        publishUiState(_state.value.copy(isRunning = false), force = true)
    }

    fun release() {
        stopCamera()
        handLandmarker?.close()
        handLandmarker = null
        cameraExecutor.shutdown()
    }
}
