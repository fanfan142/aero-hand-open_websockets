package com.aerohand.gesture

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class GestureCameraService(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private data class GestureSample(
        val hand: String,
        val facing: GestureCameraFacing,
        val mirrorMode: GestureMirrorMode,
        val angles: FingerAngles
    )

    private data class ResolvedHand(
        val tracked: TrackedHand,
        val handedness: String
    )

    companion object {
        private const val TAG = "GestureCameraService"
        private const val UI_STATE_INTERVAL_MS = 33L
        private const val VIDEO_FRAME_INTERVAL_MS = 33L
        private const val FPS_WINDOW = 12
        private const val PERF_LOG_INTERVAL_FRAMES = 30
        private const val HAND_STATE_LOG_INTERVAL_MS = 1500L
        private const val DETECTION_BITMAP_MAX_SIDE = 256
        private const val CALIBRATION_SAMPLE_MIN = 5
        private const val CALIBRATION_SAMPLE_TARGET = 14
        private const val SAMPLE_HISTORY_LIMIT = 40
        private const val MAX_LOST_FRAME_MS = 240L
        private const val MIN_FINGER_RANGE = 12f
        private const val MIN_THUMB_SWING_RANGE = 0.08f
        private val FRONT_ANALYSIS_TARGET_SIZE = Size(320, 240)
        private val BACK_ANALYSIS_TARGET_SIZE = Size(320, 240)
        private val EMA_ALPHA = floatArrayOf(0.46f, 0.44f, 0.42f, 0.42f, 0.42f, 0.42f, 0.42f)
        private val DEADBAND = floatArrayOf(0.35f, 0.3f, 0.4f, 0.45f, 0.45f, 0.45f, 0.45f)
        private const val THUMB_SWING_ALPHA = 0.42f
        private const val THUMB_SWING_DEADBAND = 0.01f
    }

    private val calibrationStore = GestureCalibrationStore(context)
    private val frameConverter = GestureFrameConverter(DETECTION_BITMAP_MAX_SIDE)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var handTracker: HandTracker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraPreviewView: PreviewView? = null
    private var preferredDelegate: Delegate? = Delegate.GPU

    private val _state = MutableStateFlow(GestureCameraState(tuningProfile = calibrationStore.loadTuning()))
    val state: StateFlow<GestureCameraState> = _state
    private val _controlFrame = MutableStateFlow(GestureControlFrame(false, message = "相机未运行"))
    val controlFrame: StateFlow<GestureControlFrame> = _controlFrame

    private val profiles = calibrationStore.loadProfiles().toMutableMap()
    private var tuningProfile = calibrationStore.loadTuning()
    private var targetHand = GestureTargetHand.AUTO
    private var useFrontCamera = true

    private var pendingOpen = FloatArray(GestureTuningChannel.entries.size) { 0f }
    private var pendingFist = FloatArray(GestureTuningChannel.entries.size) { 0f }
    private var pendingOpenThumbSwing = 0f
    private var pendingHandSide = ""
    private var pendingFacing = currentFacing()
    private var pendingMirror = currentMirror()

    private var smoothedValues = FloatArray(GestureTuningChannel.entries.size) { 0f }
    private var smoothedThumbSwing = 0f
    private var needsInitialUpdate = true
    private val sampleHistory = ArrayDeque<GestureSample>()

    private var latestProcessingFps = 0f
    private var lastResultTimeNs = 0L
    private val resultFrameTimeBuffer = mutableListOf<Long>()
    private var lastHandDetectedMs = 0L
    private var lastUiStatePublishMs = 0L
    private var lastHandStateLogMs = 0L
    private var lastHandStateSignature = ""
    private var processedFrameCount = 0L
    private val videoTimestampMs = AtomicLong(0L)

    fun startCamera(previewView: PreviewView) {
        cameraPreviewView = previewView
        if (cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            setupCamera(previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    @Suppress("DEPRECATION")
    private fun setupCamera(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        resetDetectionLoop(clearSmoothing = true)
        val targetRotation = previewView.display.rotation
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(analysisTargetSize())
            .setTargetRotation(targetRotation)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy -> processImage(imageProxy) }
            }

        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            publishUiState(
                _state.value.copy(
                    isRunning = true,
                    cameraFacing = currentFacing(),
                    mirrorMode = currentMirror(),
                    calibrationState = activeCalibrationState(_state.value.handedness),
                    calibrationProfile = activeProfileFor(_state.value.handedness),
                    tuningProfile = tuningProfile,
                    trackerBackend = handTracker?.backendName.orEmpty(),
                    feedbackMessage = "相机已启动，请将目标手完整放入画面"
                ),
                force = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            publishUiState(
                _state.value.copy(feedbackMessage = "相机启动失败：${e.message ?: "未知错误"}"),
                force = true
            )
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        var frame: GestureFrame? = null
        try {
            val frameStartNs = System.nanoTime()
            val tracker = ensureTracker() ?: run {
                val size = resolvedDisplaySize(imageProxy.width, imageProxy.height, imageProxy.imageInfo.rotationDegrees)
                markNoHand(latestProcessingFps, "手势跟踪器未就绪", size.width, size.height)
                return
            }

            val convertStartNs = System.nanoTime()
            frame = frameConverter.convert(imageProxy)
            val detectStartNs = System.nanoTime()
            val hands = tracker.detect(
                bitmap = frame.bitmap,
                rotationDegrees = frame.rotationDegrees,
                timestampMs = nextVideoTimestamp(imageProxy.imageInfo.timestamp / 1_000_000L)
            )
            val detectEndNs = System.nanoTime()
            val fps = updateProcessingFps()
            logFramePerf(
                backend = tracker.backendName,
                convertMs = elapsedMs(convertStartNs, detectStartNs),
                detectMs = elapsedMs(detectStartNs, detectEndNs),
                totalMs = elapsedMs(frameStartNs, detectEndNs),
                fps = fps,
                sourceSize = frame.sourceSize,
                detectionSize = frame.detectionSize
            )
            processHands(hands, fps, frame.displaySize.width, frame.displaySize.height)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
            if (handTracker?.backendName?.contains("GPU") == true) {
                preferredDelegate = Delegate.CPU
                Log.w(TAG, "GPU tracker failed during frame processing; falling back to CPU")
            }
            resetTracker()
            val size = frame?.displaySize
                ?: resolvedDisplaySize(imageProxy.width, imageProxy.height, imageProxy.imageInfo.rotationDegrees)
            markNoHand(
                latestProcessingFps,
                "手势识别异常，已重置跟踪器",
                size.width,
                size.height
            )
        } finally {
            runCatching { frame?.bitmap?.recycle() }
            imageProxy.close()
        }
    }

    private fun processHands(
        hands: List<TrackedHand>,
        fps: Float,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (hands.isEmpty()) {
            markNoHand(fps, "未检测到手，请将目标手完整放入画面", frameWidth, frameHeight)
            return
        }

        val resolvedHands = hands.map { hand ->
            ResolvedHand(hand, resolveHandedness(hand))
        }
        val chosen = selectHand(resolvedHands) ?: run {
            markNoHand(fps, "无法确认目标手，请调整手掌方向", frameWidth, frameHeight)
            return
        }
        val detectedHand = chosen.handedness
        if (detectedHand.isBlank()) {
            markNoHand(fps, "已检测到手，但无法确认左右手", frameWidth, frameHeight)
            return
        }
        val targetMatched = targetHand.matches(detectedHand)
        val rawAngles = GestureAngleEstimator.estimate(chosen.tracked.landmarks, detectedHand)
        val smoothedAngles = applySmoothing(rawAngles)
        rememberSample(detectedHand, smoothedAngles)

        val activeProfile = activeProfileFor(detectedHand)
        val calibrationState = activeCalibrationState(detectedHand)
        val calibratedAngles = if (activeProfile != null) {
            tuningProfile.applyTo(remapByCalibration(activeProfile, smoothedValues, smoothedThumbSwing))
        } else {
            smoothedAngles
        }
        val feedback = feedbackFor(
            detectedHand = detectedHand,
            targetMatched = targetMatched,
            profile = activeProfile,
            calibrationState = calibrationState
        )
        logHandState(
            rawHand = chosen.tracked.handedness,
            resolvedHand = detectedHand,
            targetMatched = targetMatched,
            calibrationState = calibrationState,
            profile = activeProfile,
            confidence = chosen.tracked.confidence,
            fps = fps
        )

        lastHandDetectedMs = System.currentTimeMillis()
        val oldState = _state.value
        _controlFrame.value = if (targetMatched && activeProfile != null) {
            GestureControlFrame(true, calibratedAngles)
        } else {
            GestureControlFrame(false, message = feedback.ifBlank { "未满足手势控制条件" })
        }

        val nextState = oldState.copy(
            isRunning = true,
            handDetected = true,
            handedness = detectedHand,
            targetHand = targetHand,
            targetHandMatched = targetMatched,
            feedbackMessage = feedback,
            rawAngles = rawAngles,
            smoothedAngles = smoothedAngles,
            calibratedAngles = calibratedAngles,
            calibrationState = calibrationState,
            calibrationProfile = activeProfile,
            tuningProfile = tuningProfile,
            cameraFacing = currentFacing(),
            mirrorMode = currentMirror(),
            fps = fps,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            trackerBackend = handTracker?.backendName.orEmpty(),
            landmarks = chosen.tracked.landmarks
        )
        publishUiState(
            nextState,
            force = !oldState.handDetected ||
                oldState.handedness != detectedHand ||
                oldState.targetHandMatched != targetMatched ||
                oldState.calibrationState != calibrationState ||
                oldState.cameraFacing != currentFacing() ||
                oldState.mirrorMode != currentMirror() ||
                oldState.feedbackMessage != feedback ||
                oldState.frameWidth != frameWidth ||
                oldState.frameHeight != frameHeight ||
                oldState.trackerBackend != nextState.trackerBackend
        )
    }

    private fun selectHand(hands: List<ResolvedHand>): ResolvedHand? {
        if (hands.isEmpty()) return null
        val targetMatches = hands.filter { targetHand.matches(it.handedness) && it.handedness.isNotBlank() }
        val candidates = if (targetHand == GestureTargetHand.AUTO || targetMatches.isEmpty()) {
            hands
        } else {
            targetMatches
        }
        return candidates.maxWithOrNull(
            compareBy<ResolvedHand> { handSelectionScore(it) }
                .thenBy { it.tracked.confidence }
        )
    }

    private fun handSelectionScore(hand: ResolvedHand): Int {
        var score = 0
        if (hand.handedness.isNotBlank()) score += 2
        if (targetHand.matches(hand.handedness)) score += 6
        if (exactProfileFor(hand.handedness) != null) score += 4
        if (sameHandProfileFor(hand.handedness) != null) score += 1
        return score
    }

    private fun resolveHandedness(hand: TrackedHand): String {
        val raw = GestureAngleEstimator.canonicalHandedness(hand.handedness)
        val mirrorAdjusted = if (raw.isBlank()) "" else if (currentMirror() == GestureMirrorMode.SELFIE) {
            swapHandedness(raw)
        } else {
            raw
        }
        val inferred = GestureAngleEstimator.inferHandedness(hand.landmarks)
        val candidates = listOf(mirrorAdjusted, raw, inferred).filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return ""
        return candidates.maxBy { candidate ->
            var score = 0
            if (candidate == mirrorAdjusted) score += 4
            if (candidate == inferred) score += 2
            if (targetHand.matches(candidate)) score += 5
            if (exactProfileFor(candidate) != null) score += 4
            if (sameHandProfileFor(candidate) != null) score += 1
            score
        }
    }

    fun toggleCamera(): Boolean {
        useFrontCamera = !useFrontCamera
        cameraProvider?.unbindAll()
        resetDetectionLoop(clearSmoothing = true)
        cameraPreviewView?.let { setupCamera(it) }
        return useFrontCamera
    }

    fun isFrontCamera(): Boolean = useFrontCamera

    fun setTargetHand(targetHand: GestureTargetHand) {
        this.targetHand = targetHand
        resetDetectionLoop(clearSmoothing = true)
        val hand = _state.value.handedness
        val matched = targetHand.matches(hand)
        publishUiState(
            _state.value.copy(
                targetHand = targetHand,
                targetHandMatched = matched,
                calibrationState = activeCalibrationState(hand),
                calibrationProfile = activeProfileFor(hand),
                feedbackMessage = when {
                    hand.isBlank() -> "未检测到手，请将目标手完整放入画面"
                    matched -> calibrationFeedbackFor(hand, activeProfileFor(hand))
                    else -> "当前检测到${handLabel(hand)}，请切换到${targetHand.label}"
                }
            ),
            force = true
        )
    }

    fun startCalibration() {
        pendingOpen = FloatArray(GestureTuningChannel.entries.size) { 0f }
        pendingFist = FloatArray(GestureTuningChannel.entries.size) { 0f }
        pendingOpenThumbSwing = 0f
        pendingHandSide = ""
        pendingFacing = currentFacing()
        pendingMirror = currentMirror()
        resetDetectionLoop(clearSmoothing = true)
        publishUiState(
            _state.value.copy(
                calibrationState = CalibrationState.CALIBRATING_OPEN,
                calibrationProfile = null,
                feedbackMessage = "校准 1/3：张开目标手，稳定一秒后记录张开"
            ),
            force = true
        )
    }

    fun recordCalibrationPose() {
        val state = _state.value
        if (!state.handDetected || state.handedness.isBlank()) {
            publishUiState(state.copy(feedbackMessage = "校准失败：未检测到可识别的左右手"), force = true)
            return
        }
        if (!state.targetHandMatched) {
            publishUiState(state.copy(feedbackMessage = "校准失败：检测手与目标手不一致"), force = true)
            return
        }
        val sample = medianCalibrationSample(state.handedness)
        if (sample == null) {
            publishUiState(state.copy(feedbackMessage = "校准失败：请保持手掌稳定后再记录"), force = true)
            return
        }
        when (state.calibrationState) {
            CalibrationState.CALIBRATING_OPEN -> {
                pendingOpen = sample.toControlArray()
                pendingOpenThumbSwing = sample.thumbSwing
                pendingHandSide = state.handedness
                pendingFacing = currentFacing()
                pendingMirror = currentMirror()
                publishUiState(
                    state.copy(
                        calibrationState = CalibrationState.CALIBRATING_FIST,
                        feedbackMessage = "校准 2/3：保持同一只${handLabel(state.handedness)}握拳，稳定后记录握拳"
                    ),
                    force = true
                )
            }
            CalibrationState.CALIBRATING_FIST -> {
                if (!sameCalibrationContext(state.handedness)) {
                    publishUiState(state.copy(feedbackMessage = "校准失败：手别或摄像头已变化，请重新开始"), force = true)
                    return
                }
                pendingFist = sample.toControlArray()
                publishUiState(
                    state.copy(
                        calibrationState = CalibrationState.CALIBRATING_THUMB_IN,
                        feedbackMessage = "校准 3/3：张开手并让拇指内收，稳定后记录拇指内收"
                    ),
                    force = true
                )
            }
            CalibrationState.CALIBRATING_THUMB_IN -> {
                if (!sameCalibrationContext(state.handedness)) {
                    publishUiState(state.copy(feedbackMessage = "校准失败：手别或摄像头已变化，请重新开始"), force = true)
                    return
                }
                val profile = GestureCalibrationProfile(
                    schemaVersion = GestureCalibrationStore.SCHEMA_VERSION,
                    handSide = state.handedness,
                    cameraFacing = currentFacing(),
                    mirrorMode = currentMirror(),
                    openAngles = pendingOpen.copyOf(),
                    fistAngles = pendingFist.copyOf(),
                    openThumbSwing = pendingOpenThumbSwing,
                    thumbInSwing = sample.thumbSwing
                )
                val validation = validateCalibration(profile)
                if (validation != null) {
                    publishUiState(state.copy(feedbackMessage = validation), force = true)
                    return
                }
                profiles[profile.key] = profile
                calibrationStore.saveProfile(profile)
                val calibrated = tuningProfile.applyTo(remapByCalibration(profile, smoothedValues, smoothedThumbSwing))
                publishUiState(
                    state.copy(
                        calibrationState = CalibrationState.CALIBRATED,
                        calibrationProfile = profile,
                        calibratedAngles = calibrated,
                        tuningProfile = tuningProfile,
                        feedbackMessage = "校准完成，当前${profile.cameraFacing.label}/${handLabel(profile.handSide)}实时跟随已启用"
                    ),
                    force = true
                )
            }
            else -> startCalibration()
        }
    }

    fun adjustTuning(
        channel: GestureTuningChannel,
        gainDelta: Float = 0f,
        offsetDelta: Float = 0f
    ) {
        tuningProfile = tuningProfile.withAdjustment(channel, gainDelta, offsetDelta)
        calibrationStore.saveTuning(tuningProfile)
        val state = _state.value
        val profile = activeProfileFor(state.handedness)
        val tunedAngles = profile
            ?.let { tuningProfile.applyTo(remapByCalibration(it, smoothedValues, smoothedThumbSwing)) }
            ?: tuningProfile.applyTo(state.smoothedAngles)
        if (state.targetHandMatched && profile != null) {
            _controlFrame.value = GestureControlFrame(true, tunedAngles)
        }
        publishUiState(
            state.copy(
                tuningProfile = tuningProfile,
                calibratedAngles = tunedAngles,
                feedbackMessage = "${channel.label}微调已更新"
            ),
            force = true
        )
    }

    fun resetTuning() {
        tuningProfile = GestureTuningProfile.default()
        calibrationStore.saveTuning(tuningProfile)
        val state = _state.value
        val profile = activeProfileFor(state.handedness)
        val tunedAngles = profile
            ?.let { tuningProfile.applyTo(remapByCalibration(it, smoothedValues, smoothedThumbSwing)) }
            ?: state.smoothedAngles
        if (state.targetHandMatched && profile != null) {
            _controlFrame.value = GestureControlFrame(true, tunedAngles)
        }
        publishUiState(
            state.copy(
                tuningProfile = tuningProfile,
                calibratedAngles = tunedAngles,
                feedbackMessage = "微调已重置"
            ),
            force = true
        )
    }

    fun getControlFrame(): GestureControlFrame = _controlFrame.value

    fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        resetDetectionLoop(clearSmoothing = true)
        _controlFrame.value = GestureControlFrame(false, message = "相机未运行")
        publishUiState(
            _state.value.copy(
                isRunning = false,
                handDetected = false,
                feedbackMessage = "相机未运行",
                landmarks = emptyList()
            ),
            force = true
        )
    }

    fun release() {
        stopCamera()
        resetTracker()
        cameraExecutor.shutdown()
    }

    private fun ensureTracker(): HandTracker? {
        handTracker?.let { return it }
        handTracker = MediaPipeHandTracker.create(context, preferredDelegate = preferredDelegate)
        if (handTracker?.backendName?.contains("CPU") == true) {
            preferredDelegate = Delegate.CPU
        }
        return handTracker.also { tracker ->
            if (tracker != null) {
                publishUiState(_state.value.copy(trackerBackend = tracker.backendName), force = true)
            }
        }
    }

    private fun resetTracker() {
        handTracker?.close()
        handTracker = null
    }

    private fun applySmoothing(angles: FingerAngles): FingerAngles {
        val raw = angles.toControlArray()
        for (i in raw.indices) {
            val diff = abs(raw[i] - smoothedValues[i])
            if (needsInitialUpdate || diff >= DEADBAND[i]) {
                smoothedValues[i] = smoothedValues[i] * (1f - EMA_ALPHA[i]) + raw[i] * EMA_ALPHA[i]
            }
        }
        val swingDiff = abs(angles.thumbSwing - smoothedThumbSwing)
        if (needsInitialUpdate || swingDiff >= THUMB_SWING_DEADBAND) {
            smoothedThumbSwing =
                smoothedThumbSwing * (1f - THUMB_SWING_ALPHA) + angles.thumbSwing * THUMB_SWING_ALPHA
        }
        needsInitialUpdate = false
        return anglesFromArray(smoothedValues, smoothedThumbSwing)
    }

    private fun rememberSample(hand: String, angles: FingerAngles) {
        sampleHistory.addLast(
            GestureSample(
                hand = hand,
                facing = currentFacing(),
                mirrorMode = currentMirror(),
                angles = angles
            )
        )
        while (sampleHistory.size > SAMPLE_HISTORY_LIMIT) {
            sampleHistory.removeFirst()
        }
    }

    private fun medianCalibrationSample(hand: String): FingerAngles? {
        val candidates = sampleHistory
            .filter {
                it.hand == hand &&
                    it.facing == currentFacing() &&
                    it.mirrorMode == currentMirror()
            }
            .takeLast(CALIBRATION_SAMPLE_TARGET)
            .map { it.angles }
        if (candidates.size < CALIBRATION_SAMPLE_MIN) return null
        val values = FloatArray(GestureTuningChannel.entries.size) { index ->
            candidates.map { it.toControlArray()[index] }.median()
        }
        val thumbSwing = candidates.map { it.thumbSwing }.median()
        return anglesFromArray(values, thumbSwing)
    }

    private fun remapByCalibration(
        profile: GestureCalibrationProfile,
        values: FloatArray,
        thumbSwing: Float
    ): FingerAngles {
        val mapped = FloatArray(GestureTuningChannel.entries.size)
        GestureTuningChannel.entries.forEach { channel ->
            val index = channel.ordinal
            val range = profile.fistAngles[index] - profile.openAngles[index]
            mapped[index] = if (abs(range) < 0.001f) {
                0f
            } else {
                ((values[index] - profile.openAngles[index]) / range * channel.maxValue)
                    .coerceIn(0f, channel.maxValue)
            }
        }

        val thumbSwingRange = profile.openThumbSwing - profile.thumbInSwing
        if (abs(thumbSwingRange) >= 0.001f) {
            mapped[GestureTuningChannel.THUMB_ABD.ordinal] =
                ((profile.openThumbSwing - thumbSwing) / thumbSwingRange * GestureTuningChannel.THUMB_ABD.maxValue)
                    .coerceIn(0f, GestureTuningChannel.THUMB_ABD.maxValue)
        }
        return anglesFromArray(mapped, thumbSwing)
    }

    private fun validateCalibration(profile: GestureCalibrationProfile): String? {
        GestureTuningChannel.entries
            .filter { it != GestureTuningChannel.THUMB_ABD }
            .forEach { channel ->
                val index = channel.ordinal
                if (abs(profile.fistAngles[index] - profile.openAngles[index]) < MIN_FINGER_RANGE) {
                    return "校准失败：${channel.label}张开/握拳差值太小，请重新记录"
                }
            }
        if (abs(profile.openThumbSwing - profile.thumbInSwing) < MIN_THUMB_SWING_RANGE) {
            return "校准失败：拇指内收幅度太小，请重新记录"
        }
        return null
    }

    private fun activeCalibrationState(hand: String): CalibrationState {
        val current = _state.value.calibrationState
        if (current == CalibrationState.CALIBRATING_OPEN ||
            current == CalibrationState.CALIBRATING_FIST ||
            current == CalibrationState.CALIBRATING_THUMB_IN
        ) {
            return current
        }
        return if (hand.isNotBlank() && activeProfileFor(hand) != null) {
            CalibrationState.CALIBRATED
        } else {
            CalibrationState.NOT_CALIBRATED
        }
    }

    private fun feedbackFor(
        detectedHand: String,
        targetMatched: Boolean,
        profile: GestureCalibrationProfile?,
        calibrationState: CalibrationState
    ): String {
        if (!targetMatched) {
            return "当前检测到${handLabel(detectedHand)}，请切换到${targetHand.label}"
        }
        if (calibrationState != CalibrationState.CALIBRATED) {
            return calibrationFeedbackFor(detectedHand, profile)
        }
        if (profile != null && !profile.matchesContext(detectedHand, currentFacing(), currentMirror())) {
            return "已沿用${handLabel(profile.handSide)}标定；建议为${currentFacing().label}重新校准"
        }
        return ""
    }

    private fun calibrationFeedbackFor(hand: String, profile: GestureCalibrationProfile?): String {
        if (hand.isBlank()) return "未检测到手，请将目标手完整放入画面"
        if (profile == null) return "未校准，请先完成三步标定"
        if (!profile.matchesHand(hand)) {
            return "历史标定属于${handLabel(profile.handSide)}，当前为${handLabel(hand)}"
        }
        return if (profile.matchesContext(hand, currentFacing(), currentMirror())) {
            ""
        } else {
            "已保存${handLabel(profile.handSide)}标定；当前摄像头建议重新校准"
        }
    }

    private fun activeProfileFor(hand: String): GestureCalibrationProfile? {
        if (hand.isBlank()) return null
        return exactProfileFor(hand) ?: sameHandProfileFor(hand)
    }

    private fun exactProfileFor(hand: String): GestureCalibrationProfile? {
        if (hand.isBlank()) return null
        return profiles[GestureCalibrationProfile.profileKey(hand, currentFacing(), currentMirror())]
    }

    private fun sameHandProfileFor(hand: String): GestureCalibrationProfile? {
        if (hand.isBlank()) return null
        val activeKey = calibrationStore.activeProfileKey()
        val active = activeKey?.let { profiles[it] }
        if (active?.matchesHand(hand) == true) return active
        return profiles.values.firstOrNull { it.matchesHand(hand) }
    }

    private fun sameCalibrationContext(hand: String): Boolean {
        return pendingHandSide == hand &&
            pendingFacing == currentFacing() &&
            pendingMirror == currentMirror()
    }

    private fun markNoHand(
        fps: Float,
        message: String,
        frameWidth: Int = _state.value.frameWidth,
        frameHeight: Int = _state.value.frameHeight
    ) {
        val now = System.currentTimeMillis()
        val oldState = _state.value
        val keepLastAngles = now - lastHandDetectedMs < MAX_LOST_FRAME_MS
        val keepControl = keepLastAngles &&
            oldState.targetHandMatched &&
            oldState.calibrationState == CalibrationState.CALIBRATED
        _controlFrame.value = if (keepControl) {
            GestureControlFrame(true, oldState.calibratedAngles, message)
        } else {
            GestureControlFrame(false, message = message)
        }
        publishUiState(
            oldState.copy(
                handDetected = false,
                handedness = "",
                targetHand = targetHand,
                targetHandMatched = targetHand == GestureTargetHand.AUTO,
                feedbackMessage = message,
                fps = fps,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                trackerBackend = handTracker?.backendName.orEmpty(),
                landmarks = emptyList()
            ),
            force = oldState.handDetected || oldState.feedbackMessage != message
        )
    }

    private fun publishUiState(nextState: GestureCameraState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastUiStatePublishMs >= UI_STATE_INTERVAL_MS) {
            lastUiStatePublishMs = now
            _state.value = nextState
        }
    }

    private fun analysisTargetSize(): Size = if (useFrontCamera) {
        FRONT_ANALYSIS_TARGET_SIZE
    } else {
        BACK_ANALYSIS_TARGET_SIZE
    }

    private fun currentFacing(): GestureCameraFacing = if (useFrontCamera) {
        GestureCameraFacing.FRONT
    } else {
        GestureCameraFacing.BACK
    }

    private fun currentMirror(): GestureMirrorMode = if (useFrontCamera) {
        GestureMirrorMode.SELFIE
    } else {
        GestureMirrorMode.NORMAL
    }

    private fun swapHandedness(hand: String): String = when (hand) {
        "Left" -> "Right"
        "Right" -> "Left"
        else -> ""
    }

    private fun handLabel(hand: String): String = when (hand) {
        "Left" -> "左手"
        "Right" -> "右手"
        else -> "未知手"
    }

    private fun nextVideoTimestamp(frameTimestampMs: Long): Long {
        return if (frameTimestampMs > 0) {
            val previous = videoTimestampMs.get()
            val next = if (frameTimestampMs > previous) {
                frameTimestampMs
            } else {
                previous + VIDEO_FRAME_INTERVAL_MS
            }
            videoTimestampMs.set(next)
            next
        } else {
            videoTimestampMs.addAndGet(VIDEO_FRAME_INTERVAL_MS)
        }
    }

    private fun updateProcessingFps(): Float {
        val now = System.nanoTime()
        if (lastResultTimeNs != 0L) {
            val deltaMs = ((now - lastResultTimeNs) / 1_000_000L).coerceAtLeast(1L)
            resultFrameTimeBuffer.add(deltaMs)
            if (resultFrameTimeBuffer.size > FPS_WINDOW) {
                resultFrameTimeBuffer.removeAt(0)
            }
            val avgDelta = resultFrameTimeBuffer.average().toFloat()
            latestProcessingFps = if (avgDelta > 0f) 1000f / avgDelta else latestProcessingFps
        }
        lastResultTimeNs = now
        return latestProcessingFps
    }

    private fun elapsedMs(startNs: Long, endNs: Long): Float {
        return ((endNs - startNs).coerceAtLeast(0L) / 1_000_000f)
    }

    private fun logFramePerf(
        backend: String,
        convertMs: Float,
        detectMs: Float,
        totalMs: Float,
        fps: Float,
        sourceSize: Size,
        detectionSize: Size
    ) {
        processedFrameCount += 1
        if (processedFrameCount % PERF_LOG_INTERVAL_FRAMES != 0L) return
        Log.i(
            TAG,
            "perf backend=$backend source=${sourceSize.width}x${sourceSize.height} " +
                "detectSize=${detectionSize.width}x${detectionSize.height} " +
                "convert=${"%.1f".format(convertMs)}ms detect=${"%.1f".format(detectMs)}ms " +
                "total=${"%.1f".format(totalMs)}ms fps=${"%.1f".format(fps)}"
        )
    }

    private fun logHandState(
        rawHand: String,
        resolvedHand: String,
        targetMatched: Boolean,
        calibrationState: CalibrationState,
        profile: GestureCalibrationProfile?,
        confidence: Float,
        fps: Float
    ) {
        val now = System.currentTimeMillis()
        val signature = "$rawHand/$resolvedHand/$targetMatched/$calibrationState/${profile?.key.orEmpty()}"
        if (signature == lastHandStateSignature && now - lastHandStateLogMs < HAND_STATE_LOG_INTERVAL_MS) {
            return
        }
        lastHandStateSignature = signature
        lastHandStateLogMs = now
        Log.i(
            TAG,
            "hand raw=$rawHand resolved=$resolvedHand target=${targetHand.name} matched=$targetMatched " +
                "calibration=$calibrationState profile=${profile?.key ?: "none"} " +
                "camera=${currentFacing().name}/${currentMirror().name} conf=${"%.2f".format(confidence)} " +
                "fps=${"%.1f".format(fps)}"
        )
    }

    private fun resetDetectionLoop(clearSmoothing: Boolean) {
        videoTimestampMs.set(0L)
        lastResultTimeNs = 0L
        resultFrameTimeBuffer.clear()
        latestProcessingFps = 0f
        processedFrameCount = 0L
        lastHandStateLogMs = 0L
        lastHandStateSignature = ""
        sampleHistory.clear()
        if (clearSmoothing) {
            smoothedValues.fill(0f)
            smoothedThumbSwing = 0f
            needsInitialUpdate = true
        }
    }

    private fun resolvedDisplaySize(width: Int, height: Int, rotation: Int): Size {
        return if (rotation == 90 || rotation == 270) Size(height, width) else Size(width, height)
    }

    private fun List<Float>.median(): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) * 0.5f
        } else {
            sorted[mid]
        }
    }
}
