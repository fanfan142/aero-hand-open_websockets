package com.aerohand.gesture

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicLong

class GestureCameraService(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "GestureCameraService"
        private const val NUM_HANDS = 1
        private const val MIN_HAND_DETECTION_CONFIDENCE = 0.3f
        private const val MIN_HAND_PRESENCE_CONFIDENCE = 0.3f
        private const val MIN_TRACKING_CONFIDENCE = 0.3f
        private const val HAND_LANDMARKER_MODEL_ASSET = "hand_landmarker.task"
        private const val EMA_ALPHA = 0.7f
        private const val DEADBAND = 2f
        private const val FPS_WINDOW = 10
        private const val VIDEO_FRAME_INTERVAL_MS = 33L
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("gesture_calib", Context.MODE_PRIVATE)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(GestureCameraState())
    val state: StateFlow<GestureCameraState> = _state

    private var openAngles = FloatArray(7) { 0f }
    private var fistAngles = FloatArray(7) { 0f }
    private var thumbInSwing = 0f
    private var openThumbSwing = 0f

    private var smoothedValues = FloatArray(7) { 0f }
    private var needsInitialUpdate = true  // Skip DEADBAND on first update after calibration reset
    private var lastFrameTime = System.nanoTime()
    private var frameTimeBuffer = mutableListOf<Long>()
    private val videoTimestampMs = AtomicLong(0L)

    init {
        loadCalibration()
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            setupImageAnalysis(previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun setupImageAnalysis(previewView: PreviewView) {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(previewView.display.rotation)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            _state.value = _state.value.copy(isRunning = true)
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
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
                val mpImage = BitmapImageBuilder(bitmap).build()
                detectHand(mpImage, fps, imageProxy.imageInfo.timestamp / 1_000_000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
            _state.value = _state.value.copy(handDetected = false)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val isFrontCamera = true // Front camera is used

            val matrix = Matrix()
            // First handle rotation
            if (rotation != 0) {
                matrix.postRotate(rotation.toFloat())
            }
            // Mirror horizontally for front camera
            if (isFrontCamera) {
                matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }

            if (rotation != 0 || isFrontCamera) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }

    private fun detectHand(mpImage: com.google.mediapipe.framework.image.MPImage, fps: Float, frameTimestampMs: Long) {
        if (handLandmarker == null) {
            initializeHandLandmarker()
            if (handLandmarker == null) {
                _state.value = _state.value.copy(handDetected = false, fps = fps)
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
        if (result != null) {
            processResult(result, fps)
        } else {
            _state.value = _state.value.copy(handDetected = false, fps = fps)
        }
    }

    private fun processResult(result: HandLandmarkerResult, fps: Float) {
        val landmarks = result.landmarks()
        val handDetected = landmarks.isNotEmpty()
        if (handDetected) {
            val angles = computeFingerAngles(landmarks[0])
            val smoothed = applySmoothing(angles)

            // Debug: log thumb angles every 60 frames (~once per second at 60fps)
            if (System.currentTimeMillis() / 16 % 60 == 0L) {
                Log.d(TAG, "Thumb angles: abd=${angles.thumbAbd}, cmcFlex=${angles.thumbCmcFlex}, tendon=${angles.thumbTendon}")
            }

            // Extract handedness from result
            val handednessList = result.handedness()
            val handedness = if (handednessList.isNotEmpty() && handednessList[0].isNotEmpty()) {
                handednessList[0][0].categoryName()
            } else ""

            val calibState = _state.value.calibrationState

            // Compute calibrated angles when calibrated
            val calibrated = if (calibState == CalibrationState.CALIBRATED) {
                remapByCalibration(smoothedValues)
            } else {
                smoothed
            }

            _state.value = _state.value.copy(
                handDetected = true,
                handedness = handedness,
                rawAngles = angles,
                smoothedAngles = smoothed,
                calibratedAngles = calibrated,
                fps = fps,
                calibrationState = calibState,
                landmarks = landmarks[0]
            )
        } else {
            _state.value = _state.value.copy(handDetected = false, handedness = "", fps = fps, landmarks = emptyList())
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

    // MediaPipe hand landmarks (21 points):
    // 0: WRIST
    // 1-4: THUMB (CMC, MCP, IP, TIP)
    // 5-8: INDEX (MCP, PIP, DIP, TIP)
    // 9-12: MIDDLE (MCP, PIP, DIP, TIP)
    // 13-16: RING (MCP, PIP, DIP, TIP)
    // 17-20: PINKY (MCP, PIP, DIP, TIP)
    //
    // Aero Hand 7 actuation mapping:
    // 0: thumb_cmc_abd  - thumb abduction (spread from index)
    // 1: thumb_cmc_flex - thumb CMC flexion (WRIST-CMC-MCP angle)
    // 2: thumb_tendon   - thumb tendon/movement (MCP-IP-TIP angle)
    // 3: index_tendon   - index finger tendon (MCP-PIP-DIP angle)
    // 4: middle_tendon  - middle finger tendon (MCP-PIP-DIP angle)
    // 5: ring_tendon    - ring finger tendon (MCP-PIP-DIP angle)
    // 6: pinky_tendon   - pinky finger tendon (MCP-PIP-DIP angle)

    private fun computeFingerAngles(landmarks: List<NormalizedLandmark>): FingerAngles {
        if (landmarks.size < 21) {
            Log.w(TAG, "Unexpected landmarks size: ${landmarks.size}")
            return FingerAngles()
        }

        fun angle(p1: NormalizedLandmark, p2: NormalizedLandmark, p3: NormalizedLandmark): Float {
            val v1x = p1.x() - p2.x()
            val v1y = p1.y() - p2.y()
            val v2x = p3.x() - p2.x()
            val v2y = p3.y() - p2.y()

            val dot = v1x * v2x + v1y * v2y
            val mag1 = sqrt(v1x * v1x + v1y * v1y)
            val mag2 = sqrt(v2x * v2x + v2y * v2y)

            if (mag1 < 0.0001f || mag2 < 0.0001f) return 0f
            val cosVal = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cosVal.toDouble()).toDouble()).toFloat()
        }

        // 0: thumb_cmc_abd - thumb abduction (x-distance between thumb tip and index MCP)
        val thumbTipX = landmarks[4].x()
        val indexMcpX = landmarks[5].x()
        val thumbAbd = abs(thumbTipX - indexMcpX) * 100f

        // 1: thumb_cmc_flex - thumb CMC flexion (WRIST-CMC-MCP angle)
        val thumbCmcFlex = angle(landmarks[0], landmarks[1], landmarks[2])

        // 2: thumb_tendon - thumb tendon/movement (MCP-IP-TIP angle)
        val thumbTendon = angle(landmarks[2], landmarks[3], landmarks[4])

        // 3-6: finger tendons - MCP-PIP-DIP consecutive joint angles
        val indexTendon = angle(landmarks[5], landmarks[6], landmarks[7])
        val middleTendon = angle(landmarks[9], landmarks[10], landmarks[11])
        val ringTendon = angle(landmarks[13], landmarks[14], landmarks[15])
        val pinkyTendon = angle(landmarks[17], landmarks[18], landmarks[19])

        return FingerAngles(
            thumbAbd = thumbAbd.coerceIn(0f, 100f),
            thumbCmcFlex = thumbCmcFlex.coerceIn(0f, 90f),
            thumbTendon = thumbTendon.coerceIn(0f, 90f),
            indexTendon = indexTendon.coerceIn(0f, 90f),
            middleTendon = middleTendon.coerceIn(0f, 90f),
            ringTendon = ringTendon.coerceIn(0f, 90f),
            pinkyTendon = pinkyTendon.coerceIn(0f, 90f)
        )
    }

    private fun applySmoothing(angles: FingerAngles): FingerAngles {
        val raw = floatArrayOf(
            angles.thumbAbd, angles.thumbCmcFlex, angles.thumbTendon,
            angles.indexTendon, angles.middleTendon, angles.ringTendon, angles.pinkyTendon
        )

        for (i in raw.indices) {
            val diff = abs(raw[i] - smoothedValues[i])
            // Skip DEADBAND check on first update after calibration reset
            if (needsInitialUpdate || diff >= DEADBAND) {
                smoothedValues[i] = EMA_ALPHA * raw[i] + (1 - EMA_ALPHA) * smoothedValues[i]
            }
        }
        needsInitialUpdate = false  // Clear after first update

        return FingerAngles(
            thumbAbd = smoothedValues[0],
            thumbCmcFlex = smoothedValues[1],
            thumbTendon = smoothedValues[2],
            indexTendon = smoothedValues[3],
            middleTendon = smoothedValues[4],
            ringTendon = smoothedValues[5],
            pinkyTendon = smoothedValues[6]
        )
    }

    fun startCalibration() {
        smoothedValues.fill(0f)
        needsInitialUpdate = true  // Allow first frame to update without DEADBAND
        _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATING_OPEN)
    }

    fun recordCalibrationPose() {
        val current = _state.value.smoothedAngles

        // Validate hand is detected and angles are non-zero
        if (!_state.value.handDetected) {
            Log.w(TAG, "Calibration skipped: hand not detected")
            return
        }

        // Check if angles are near zero (hand might not be in proper view)
        val totalAngle = current.thumbAbd + current.thumbCmcFlex + current.thumbTendon +
                         current.indexTendon + current.middleTendon + current.ringTendon + current.pinkyTendon
        if (totalAngle < 5f) {
            Log.w(TAG, "Calibration skipped: angles too small (hand may not be visible)")
            return
        }

        when (_state.value.calibrationState) {
            CalibrationState.CALIBRATING_OPEN -> {
                openAngles = floatArrayOf(
                    current.thumbAbd, current.thumbCmcFlex, current.thumbTendon,
                    current.indexTendon, current.middleTendon,
                    current.ringTendon, current.pinkyTendon
                )
                Log.i(TAG, "Calibration: open pose recorded - thumbAbd=${current.thumbAbd}, fingers=${current.indexTendon}")
                _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATING_FIST)
            }
            CalibrationState.CALIBRATING_FIST -> {
                fistAngles = floatArrayOf(
                    current.thumbAbd, current.thumbCmcFlex, current.thumbTendon,
                    current.indexTendon, current.middleTendon,
                    current.ringTendon, current.pinkyTendon
                )
                Log.i(TAG, "Calibration: fist pose recorded - thumbAbd=${current.thumbAbd}, fingers=${current.indexTendon}")
                _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATING_THUMB_IN)
            }
            CalibrationState.CALIBRATING_THUMB_IN -> {
                thumbInSwing = current.thumbAbd
                openThumbSwing = openAngles[0]
                Log.i(TAG, "Calibration: thumb-in recorded - thumbInSwing=$thumbInSwing, openThumbSwing=$openThumbSwing")
                saveCalibration()
                _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATED)
            }
            else -> {}
        }
    }

    fun getCalibratedAngles(): FingerAngles {
        return remapByCalibration(smoothedValues)
    }

    private fun remapByCalibration(values: FloatArray): FingerAngles {
        fun remap(value: Float, min: Float, max: Float): Float {
            if (max - min < 0.001f) return 0f
            return ((value - min) / (max - min) * 100f).coerceIn(0f, 100f)
        }

        // Index 3-6: finger tendons (index, middle, ring, pinky)
        val indexTendon = remap(values[3], openAngles[3], fistAngles[3])
        val middleTendon = remap(values[4], openAngles[4], fistAngles[4])
        val ringTendon = remap(values[5], openAngles[5], fistAngles[5])
        val pinkyTendon = remap(values[6], openAngles[6], fistAngles[6])

        // Thumb abduction (index 0)
        val thumbSwingRange = openThumbSwing - thumbInSwing
        val thumbAbd = if (thumbSwingRange > 0.001f) {
            ((openThumbSwing - values[0]) / thumbSwingRange * 100f).coerceIn(0f, 100f)
        } else 0f

        // Thumb CMC flexion (index 1)
        val thumbCmcFlex = remap(values[1], openAngles[1], fistAngles[1])

        // Thumb tendon (index 2)
        val thumbTendon = remap(values[2], openAngles[2], fistAngles[2])

        return FingerAngles(
            thumbAbd = thumbAbd,
            thumbCmcFlex = thumbCmcFlex,
            thumbTendon = thumbTendon,
            indexTendon = indexTendon,
            middleTendon = middleTendon,
            ringTendon = ringTendon,
            pinkyTendon = pinkyTendon
        )
    }

    private fun saveCalibration() {
        prefs.edit().apply {
            putString("openAngles", openAngles.joinToString(","))
            putString("fistAngles", fistAngles.joinToString(","))
            putFloat("thumbInSwing", thumbInSwing)
            putFloat("openThumbSwing", openThumbSwing)
            apply()
        }
    }

    private fun loadCalibration() {
        val openStr = prefs.getString("openAngles", null)
        val fistStr = prefs.getString("fistAngles", null)
        if (openStr != null && fistStr != null) {
            try {
                openAngles = openStr.split(",").map { it.toFloat() }.toFloatArray()
                fistAngles = fistStr.split(",").map { it.toFloat() }.toFloatArray()
                thumbInSwing = prefs.getFloat("thumbInSwing", 0f)
                openThumbSwing = prefs.getFloat("openThumbSwing", 0f)
                if (openAngles.size == 7 && fistAngles.size == 7) {
                    _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATED)
                } else {
                    Log.w(TAG, "Invalid calibration data size, reset required")
                    openAngles = FloatArray(7) { 0f }
                    fistAngles = FloatArray(7) { 0f }
                    thumbInSwing = 0f
                    openThumbSwing = 0f
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse calibration data, reset required", e)
                openAngles = FloatArray(7) { 0f }
                fistAngles = FloatArray(7) { 0f }
                thumbInSwing = 0f
                openThumbSwing = 0f
            }
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        videoTimestampMs.set(0L)
        _state.value = _state.value.copy(isRunning = false)
    }

    fun release() {
        stopCamera()
        handLandmarker?.close()
        handLandmarker = null
        cameraExecutor.shutdown()
    }
}
