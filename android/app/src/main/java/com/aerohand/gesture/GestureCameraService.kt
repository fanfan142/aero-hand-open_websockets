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
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("gesture_calib", Context.MODE_PRIVATE)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(GestureCameraState())
    val state: StateFlow<GestureCameraState> = _state

    private var openAngles = FloatArray(6) { 0f }
    private var fistAngles = FloatArray(6) { 0f }
    private var thumbInSwing = 0f
    private var openThumbSwing = 0f

    private var smoothedValues = FloatArray(6) { 0f }
    private var lastFrameTime = System.nanoTime()
    private var frameTimeBuffer = mutableListOf<Long>()

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
                detectHand(mpImage, fps)
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
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }

    private fun detectHand(mpImage: com.google.mediapipe.framework.image.MPImage, fps: Float) {
        if (handLandmarker == null) {
            initializeHandLandmarker()
            if (handLandmarker == null) {
                _state.value = _state.value.copy(handDetected = false, fps = fps)
                return
            }
        }

        val result = try {
            handLandmarker?.detectForVideo(mpImage, System.currentTimeMillis())
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

            val calibState = when {
                _state.value.calibrationState == CalibrationState.CALIBRATING_OPEN -> CalibrationState.CALIBRATING_FIST
                _state.value.calibrationState == CalibrationState.CALIBRATING_FIST -> CalibrationState.CALIBRATING_THUMB_IN
                _state.value.calibrationState == CalibrationState.CALIBRATING_THUMB_IN -> CalibrationState.CALIBRATED
                else -> _state.value.calibrationState
            }

            _state.value = _state.value.copy(
                handDetected = true,
                rawAngles = angles,
                smoothedAngles = smoothed,
                fps = fps,
                calibrationState = calibState
            )
        } else {
            _state.value = _state.value.copy(handDetected = false, fps = fps)
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
                context.assets.list("")?.contains(HAND_LANDMARKER_MODEL_ASSET) == true
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

    // MediaPipe landmarks list
    private fun computeFingerAngles(landmarks: List<NormalizedLandmark>): FingerAngles {
        // MediaPipe hand landmarks (21 points):
        // 0: WRIST
        // 1-4: THUMB (CMC, MCP, IP, TIP)
        // 5-8: INDEX (MCP, PIP, DIP, TIP)
        // 9-12: MIDDLE (MCP, PIP, DIP, TIP)
        // 13-16: RING (MCP, PIP, DIP, TIP)
        // 17-20: PINKY (MCP, PIP, DIP, TIP)

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

        // Index finger: wrist[0] - MCP[5] - PIP[6]
        val indexFlex = angle(landmarks[0], landmarks[5], landmarks[6])
        // Middle finger: wrist[0] - MCP[9] - PIP[10]
        val middleFlex = angle(landmarks[0], landmarks[9], landmarks[10])
        // Ring finger: wrist[0] - MCP[13] - PIP[14]
        val ringFlex = angle(landmarks[0], landmarks[13], landmarks[14])
        // Pinky: wrist[0] - MCP[17] - PIP[18]
        val pinkyFlex = angle(landmarks[0], landmarks[17], landmarks[18])

        // Thumb: wrist[0] - MCP[2] - IP[3]
        val thumbMcpAngle = angle(landmarks[0], landmarks[2], landmarks[3])
        val thumbTipX = landmarks[4].x()
        val indexMcpX = landmarks[5].x()
        val thumbAbd = abs(thumbTipX - indexMcpX) * 100f

        // Normalize thumb flexion to 0-55 range
        val thumbFlex = (thumbMcpAngle / 90f * 55f).coerceIn(0f, 55f)

        return FingerAngles(
            thumbAbd = thumbAbd.coerceIn(0f, 100f),
            thumbFlex = thumbFlex,
            indexFlex = indexFlex.coerceIn(0f, 90f),
            middleFlex = middleFlex.coerceIn(0f, 90f),
            ringFlex = ringFlex.coerceIn(0f, 90f),
            pinkyFlex = pinkyFlex.coerceIn(0f, 90f)
        )
    }

    private fun applySmoothing(angles: FingerAngles): FingerAngles {
        val raw = floatArrayOf(
            angles.thumbAbd, angles.thumbFlex,
            angles.indexFlex, angles.middleFlex,
            angles.ringFlex, angles.pinkyFlex
        )

        for (i in raw.indices) {
            val diff = abs(raw[i] - smoothedValues[i])
            if (diff >= DEADBAND) {
                smoothedValues[i] = EMA_ALPHA * raw[i] + (1 - EMA_ALPHA) * smoothedValues[i]
            }
        }

        return FingerAngles(
            thumbAbd = smoothedValues[0],
            thumbFlex = smoothedValues[1],
            indexFlex = smoothedValues[2],
            middleFlex = smoothedValues[3],
            ringFlex = smoothedValues[4],
            pinkyFlex = smoothedValues[5]
        )
    }

    fun startCalibration() {
        smoothedValues.fill(0f)
        _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATING_OPEN)
    }

    fun recordCalibrationPose() {
        val current = _state.value.smoothedAngles
        when (_state.value.calibrationState) {
            CalibrationState.CALIBRATING_OPEN -> {
                openAngles = floatArrayOf(
                    current.thumbAbd, current.thumbFlex,
                    current.indexFlex, current.middleFlex,
                    current.ringFlex, current.pinkyFlex
                )
                _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATING_FIST)
            }
            CalibrationState.CALIBRATING_FIST -> {
                fistAngles = floatArrayOf(
                    current.thumbAbd, current.thumbFlex,
                    current.indexFlex, current.middleFlex,
                    current.ringFlex, current.pinkyFlex
                )
                _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATING_THUMB_IN)
            }
            CalibrationState.CALIBRATING_THUMB_IN -> {
                thumbInSwing = current.thumbAbd
                openThumbSwing = openAngles[0]
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

        val indexFlex = remap(values[2], openAngles[2], fistAngles[2])
        val middleFlex = remap(values[3], openAngles[3], fistAngles[3])
        val ringFlex = remap(values[4], openAngles[4], fistAngles[4])
        val pinkyFlex = remap(values[5], openAngles[5], fistAngles[5])

        val thumbSwingRange = openThumbSwing - thumbInSwing
        val thumbAbd = if (thumbSwingRange > 0.001f) {
            ((openThumbSwing - values[0]) / thumbSwingRange * 100f).coerceIn(0f, 100f)
        } else 0f

        val thumbFlex = remap(values[1], openAngles[1], fistAngles[1]) * 55f / 100f

        return FingerAngles(
            thumbAbd = thumbAbd,
            thumbFlex = thumbFlex.coerceIn(0f, 55f),
            indexFlex = indexFlex,
            middleFlex = middleFlex,
            ringFlex = ringFlex,
            pinkyFlex = pinkyFlex
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
            openAngles = openStr.split(",").map { it.toFloat() }.toFloatArray()
            fistAngles = fistStr.split(",").map { it.toFloat() }.toFloatArray()
            thumbInSwing = prefs.getFloat("thumbInSwing", 0f)
            openThumbSwing = prefs.getFloat("openThumbSwing", 0f)
            if (openAngles.size == 6 && fistAngles.size == 6) {
                _state.value = _state.value.copy(calibrationState = CalibrationState.CALIBRATED)
            }
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        _state.value = _state.value.copy(isRunning = false)
    }

    fun release() {
        stopCamera()
        handLandmarker?.close()
        handLandmarker = null
        cameraExecutor.shutdown()
    }
}
