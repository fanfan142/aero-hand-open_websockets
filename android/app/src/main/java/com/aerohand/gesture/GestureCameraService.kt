package com.aerohand.gesture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
        private val FRONT_ANALYSIS_TARGET_SIZE = Size(320, 240)
        private val BACK_ANALYSIS_TARGET_SIZE = Size(320, 240)
    }

    private val calibrationStore = GestureCalibrationStore(context)
    private val frameConverter = GestureFrameConverter(DETECTION_BITMAP_MAX_SIDE)
    private val smoother = GestureSmoother()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var handTracker: HandTracker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraPreviewView: PreviewView? = null
    private var preferredDelegate: Delegate? = Delegate.GPU
    private val analysisGeneration = AtomicLong(0L)
    private val processingContextGeneration = AtomicLong(0L)
    private val cameraRequestGeneration = AtomicLong(0L)
    @Volatile
    private var desiredRunning = false
    @Volatile
    private var released = false

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
        val requestGeneration = synchronized(this) {
            if (released) return
            desiredRunning = true
            cameraPreviewView = previewView
            if (cameraExecutor.isShutdown) {
                cameraExecutor = Executors.newSingleThreadExecutor()
            }
            cameraRequestGeneration.incrementAndGet()
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (!isCameraRequestCurrent(requestGeneration, previewView)) return@addListener
            val provider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable", e)
                synchronized(this) {
                    if (isCameraRequestCurrent(requestGeneration, previewView)) {
                        publishUiState(
                            _state.value.copy(feedbackMessage = "相机服务不可用：${e.message ?: "未知错误"}"),
                            force = true
                        )
                    }
                }
                return@addListener
            }
            synchronized(this) {
                if (!isCameraRequestCurrent(requestGeneration, previewView)) return@addListener
                cameraProvider = provider
            }
            setupCamera(previewView, requestGeneration)
        }, ContextCompat.getMainExecutor(context))
    }

    @Synchronized
    private fun isCameraRequestCurrent(requestGeneration: Long, previewView: PreviewView): Boolean {
        return !released &&
            desiredRunning &&
            cameraRequestGeneration.get() == requestGeneration &&
            cameraPreviewView === previewView
    }

    @Suppress("DEPRECATION")
    private fun setupCamera(previewView: PreviewView, requestGeneration: Long) {
        if (!isCameraRequestCurrent(requestGeneration, previewView)) return
        val provider = cameraProvider ?: return
        val generation = analysisGeneration.incrementAndGet()
        provider.unbindAll()
        synchronized(this) {
            if (!isCameraRequestCurrent(requestGeneration, previewView)) return
            resetDetectionLoop(clearSmoothing = true)
            _controlFrame.value = GestureControlFrame(false, message = "相机正在切换")
        }
        val targetRotation = previewView.display.rotation
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(analysisTargetSize())
            .setTargetRotation(targetRotation)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy -> processImage(imageProxy, generation) }
            }
        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            if (!isCameraRequestCurrent(requestGeneration, previewView)) return
            val viewPort = previewView.viewPort
            if (viewPort == null) {
                Log.w(TAG, "PreviewView viewport unavailable; binding camera without shared viewport")
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } else {
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .setViewPort(viewPort)
                    .build()
                provider.bindToLifecycle(lifecycleOwner, selector, useCaseGroup)
            }
            synchronized(this) {
                if (!isCameraRequestCurrent(requestGeneration, previewView)) {
                    analysis.clearAnalyzer()
                    provider.unbindAll()
                    return
                }
                imageAnalysis = analysis
                publishUiState(
                    _state.value.copy(
                        isRunning = true,
                        cameraFacing = currentFacing(),
                        mirrorMode = currentMirror(),
                        calibrationState = activeCalibrationState(_state.value.handedness),
                        calibrationProfile = activeProfileFor(_state.value.handedness),
                        tuningProfile = tuningProfile,
                        feedbackMessage = "相机已启动，请将目标手完整放入画面"
                    ),
                    force = true
                )
            }
        } catch (e: Exception) {
            analysis.clearAnalyzer()
            Log.e(TAG, "Camera binding failed", e)
            synchronized(this) {
                if (isCameraRequestCurrent(requestGeneration, previewView)) {
                    publishUiState(
                        _state.value.copy(feedbackMessage = "相机启动失败：${e.message ?: "未知错误"}"),
                        force = true
                    )
                }
            }
        }
    }

    private fun processImage(imageProxy: ImageProxy, generation: Long) {
        val frameContextGeneration = processingContextGeneration.get()
        var frame: GestureFrame? = null
        try {
            if (!isFrameCurrent(generation, frameContextGeneration)) return
            val frameStartNs = System.nanoTime()
            val tracker = ensureTracker() ?: run {
                val size = resolvedDisplaySize(imageProxy.width, imageProxy.height, imageProxy.imageInfo.rotationDegrees)
                synchronized(this) {
                    if (isFrameCurrent(generation, frameContextGeneration)) {
                        markNoHand(latestProcessingFps, "手势跟踪器未就绪", size.width, size.height)
                    }
                }
                return
            }

            val convertStartNs = System.nanoTime()
            frame = frameConverter.convert(imageProxy)
            if (!isFrameCurrent(generation, frameContextGeneration)) return
            val detectStartNs = System.nanoTime()
            val hands = tracker.detect(
                bitmap = frame.bitmap,
                rotationDegrees = frame.rotationDegrees,
                timestampMs = nextVideoTimestamp(imageProxy.imageInfo.timestamp / 1_000_000L)
            )
            val detectEndNs = System.nanoTime()
            if (!isFrameCurrent(generation, frameContextGeneration)) return
            synchronized(this) {
                if (!isFrameCurrent(generation, frameContextGeneration)) return
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
                processHands(
                    hands = hands,
                    fps = fps,
                    frameWidth = frame.displaySize.width,
                    frameHeight = frame.displaySize.height,
                    rotationDegrees = frame.rotationDegrees,
                    trackerBackend = tracker.backendName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
            if (handTracker?.backendName?.contains("GPU") == true) {
                preferredDelegate = Delegate.CPU
                Log.w(TAG, "GPU tracker failed during frame processing; falling back to CPU")
            }
            resetTracker()
            if (!isFrameCurrent(generation, frameContextGeneration)) return
            val size = frame?.displaySize
                ?: resolvedDisplaySize(imageProxy.width, imageProxy.height, imageProxy.imageInfo.rotationDegrees)
            synchronized(this) {
                if (isFrameCurrent(generation, frameContextGeneration)) {
                    markNoHand(
                        latestProcessingFps,
                        "手势识别异常，已重置跟踪器",
                        size.width,
                        size.height
                    )
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun isFrameCurrent(generation: Long, frameContextGeneration: Long): Boolean {
        return !released &&
            analysisGeneration.get() == generation &&
            processingContextGeneration.get() == frameContextGeneration
    }

    private fun processHands(
        hands: List<TrackedHand>,
        fps: Float,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        trackerBackend: String
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
        val controlLandmarks = chosen.tracked.landmarks
        val previewLandmarks = GestureLandmarkTransforms.forPreview(
            landmarks = controlLandmarks,
            rotationDegrees = rotationDegrees,
            mirrorMode = currentMirror()
        )
        val rawAngles = GestureAngleEstimator.estimate(controlLandmarks, detectedHand)
        val smoothedAngles = smoother.apply(rawAngles)
        rememberSample(detectedHand, smoothedAngles)

        val activeProfile = activeProfileFor(detectedHand)
        val calibrationState = activeCalibrationState(detectedHand)
        val calibratedAngles = if (activeProfile != null) {
            tuningProfile.applyTo(
                GestureCalibrationMapper.remap(activeProfile, smoothedAngles.toControlArray(), smoothedAngles.thumbSwing)
            )
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
        val capturedAtMs = SystemClock.elapsedRealtime()
        _controlFrame.value = if (
            targetMatched &&
            activeProfile != null &&
            calibrationState == CalibrationState.CALIBRATED
        ) {
            GestureControlFrame(true, calibratedAngles, capturedAtMs = capturedAtMs)
        } else {
            GestureControlFrame(
                false,
                message = feedback.ifBlank { "未满足手势控制条件" },
                capturedAtMs = capturedAtMs
            )
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
            trackerBackend = trackerBackend,
            landmarks = previewLandmarks
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
        return GestureHandednessResolver.resolve(
            rawHandedness = hand.handedness,
            inferredHandedness = GestureAngleEstimator.inferHandedness(hand.landmarks),
            mirrorMode = currentMirror()
        )
    }

    fun toggleCamera(): Boolean {
        val (frontCamera, restartRequest) = synchronized(this) {
            analysisGeneration.incrementAndGet()
            processingContextGeneration.incrementAndGet()
            useFrontCamera = !useFrontCamera
            resetDetectionLoop(clearSmoothing = true)
            _controlFrame.value = GestureControlFrame(false, message = "相机正在切换")
            val previewView = cameraPreviewView?.takeIf { desiredRunning }
            useFrontCamera to previewView?.let { it to cameraRequestGeneration.get() }
        }
        if (restartRequest != null) {
            setupCamera(restartRequest.first, restartRequest.second)
        } else {
            cameraProvider?.unbindAll()
        }
        return frontCamera
    }

    @Synchronized
    fun isFrontCamera(): Boolean = useFrontCamera

    @Synchronized
    fun setTargetHand(targetHand: GestureTargetHand) {
        processingContextGeneration.incrementAndGet()
        this.targetHand = targetHand
        resetDetectionLoop(clearSmoothing = true)
        _controlFrame.value = GestureControlFrame(false, message = "目标手已切换，等待重新识别")
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

    @Synchronized
    fun startCalibration() {
        processingContextGeneration.incrementAndGet()
        pendingOpen = FloatArray(GestureTuningChannel.entries.size) { 0f }
        pendingFist = FloatArray(GestureTuningChannel.entries.size) { 0f }
        pendingOpenThumbSwing = 0f
        pendingHandSide = ""
        pendingFacing = currentFacing()
        pendingMirror = currentMirror()
        resetDetectionLoop(clearSmoothing = true)
        _controlFrame.value = GestureControlFrame(
            false,
            message = "校准进行中",
            capturedAtMs = SystemClock.elapsedRealtime()
        )
        publishUiState(
            _state.value.copy(
                calibrationState = CalibrationState.CALIBRATING_OPEN,
                calibrationProfile = null,
                feedbackMessage = "校准 1/3：张开目标手，稳定一秒后记录张开"
            ),
            force = true
        )
    }

    @Synchronized
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
                processingContextGeneration.incrementAndGet()
                resetDetectionLoop(clearSmoothing = true)
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
                processingContextGeneration.incrementAndGet()
                resetDetectionLoop(clearSmoothing = true)
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
                val validation = GestureCalibrationMapper.validate(profile)
                if (validation != null) {
                    publishUiState(state.copy(feedbackMessage = validation), force = true)
                    return
                }
                profiles[profile.key] = profile
                calibrationStore.saveProfile(profile)
                val smoothed = state.smoothedAngles
                val calibrated = tuningProfile.applyTo(
                    GestureCalibrationMapper.remap(profile, smoothed.toControlArray(), smoothed.thumbSwing)
                )
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

    @Synchronized
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
            ?.let {
                tuningProfile.applyTo(
                    GestureCalibrationMapper.remap(it, state.smoothedAngles.toControlArray(), state.smoothedAngles.thumbSwing)
                )
            }
            ?: tuningProfile.applyTo(state.smoothedAngles)
        if (
            state.targetHandMatched &&
            profile != null &&
            state.calibrationState == CalibrationState.CALIBRATED
        ) {
            _controlFrame.value = GestureControlFrame(
                true,
                tunedAngles,
                capturedAtMs = SystemClock.elapsedRealtime()
            )
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

    @Synchronized
    fun resetTuning() {
        tuningProfile = GestureTuningProfile.default()
        calibrationStore.saveTuning(tuningProfile)
        val state = _state.value
        val profile = activeProfileFor(state.handedness)
        val tunedAngles = profile
            ?.let {
                tuningProfile.applyTo(
                    GestureCalibrationMapper.remap(it, state.smoothedAngles.toControlArray(), state.smoothedAngles.thumbSwing)
                )
            }
            ?: state.smoothedAngles
        if (
            state.targetHandMatched &&
            profile != null &&
            state.calibrationState == CalibrationState.CALIBRATED
        ) {
            _controlFrame.value = GestureControlFrame(
                true,
                tunedAngles,
                capturedAtMs = SystemClock.elapsedRealtime()
            )
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
        val (provider, analysis) = synchronized(this) {
            desiredRunning = false
            cameraRequestGeneration.incrementAndGet()
            analysisGeneration.incrementAndGet()
            cameraPreviewView = null
            val currentProvider = cameraProvider
            val currentAnalysis = imageAnalysis
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
            currentProvider to currentAnalysis
        }
        analysis?.clearAnalyzer()
        provider?.unbindAll()
    }

    fun release() {
        synchronized(this) {
            if (released) return
            released = true
        }
        stopCamera()
        scheduleTrackerClose()
    }

    private fun ensureTracker(): HandTracker? {
        handTracker?.let { return it }
        handTracker = MediaPipeHandTracker.create(context, preferredDelegate = preferredDelegate)
        if (handTracker?.backendName?.contains("CPU") == true) {
            preferredDelegate = Delegate.CPU
        }
        return handTracker
    }

    private fun resetTracker() {
        val tracker = handTracker
        handTracker = null
        runCatching { tracker?.close() }
            .onFailure { Log.w(TAG, "Hand tracker close failed", it) }
    }

    private fun scheduleTrackerClose() {
        val executor = synchronized(this) { cameraExecutor }
        if (executor.isShutdown) return
        val queued = runCatching {
            executor.execute { resetTracker() }
        }.onFailure {
            Log.w(TAG, "Unable to queue hand tracker close", it)
        }.isSuccess
        executor.shutdown()
        if (!queued) {
            Log.w(TAG, "Hand tracker close was not queued")
        }
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
        if (profile == null) {
            return if (sameHandProfileFor(hand) != null) {
                "检测到其它摄像头的历史标定，请为当前${currentFacing().label}重新校准"
            } else {
                "未校准，请先完成三步标定"
            }
        }
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
        return exactProfileFor(hand)
    }

    private fun exactProfileFor(hand: String): GestureCalibrationProfile? {
        return GestureCalibrationProfiles.exact(profiles, hand, currentFacing(), currentMirror())
    }

    private fun sameHandProfileFor(hand: String): GestureCalibrationProfile? {
        return GestureCalibrationProfiles.sameHand(profiles, hand, calibrationStore.activeProfileKey())
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
        val capturedAtMs = SystemClock.elapsedRealtime()
        val oldState = _state.value
        val keepLastAngles = now - lastHandDetectedMs < MAX_LOST_FRAME_MS
        val keepControl = keepLastAngles &&
            oldState.targetHandMatched &&
            oldState.calibrationState == CalibrationState.CALIBRATED
        _controlFrame.value = if (keepControl) {
            GestureControlFrame(true, oldState.calibratedAngles, message, capturedAtMs)
        } else {
            GestureControlFrame(false, message = message, capturedAtMs = capturedAtMs)
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
                trackerBackend = oldState.trackerBackend,
                landmarks = emptyList()
            ),
            force = oldState.handDetected || oldState.feedbackMessage != message
        )
    }

    @Synchronized
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

    @Synchronized
    private fun resetDetectionLoop(clearSmoothing: Boolean) {
        videoTimestampMs.set(0L)
        lastResultTimeNs = 0L
        resultFrameTimeBuffer.clear()
        latestProcessingFps = 0f
        lastHandDetectedMs = 0L
        processedFrameCount = 0L
        lastHandStateLogMs = 0L
        lastHandStateSignature = ""
        sampleHistory.clear()
        if (clearSmoothing) {
            smoother.reset()
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
