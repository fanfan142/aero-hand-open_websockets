package com.aerohand.gesture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@OptIn(TransformExperimental::class)
class GestureCameraService(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
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
        const val CALIBRATION_SAMPLE_MIN = 5
        const val CALIBRATION_SAMPLE_INTERVAL_MS = 150L
        const val CALIBRATION_SAMPLE_MIN_SPAN_MS = 600L
        private val FRONT_ANALYSIS_TARGET_SIZE = Size(320, 240)
        private val BACK_ANALYSIS_TARGET_SIZE = Size(320, 240)
    }

    private val calibrationStore = GestureCalibrationStore(context)
    private val frameConverter = GestureFrameConverter(DETECTION_BITMAP_MAX_SIDE)
    private val imageProxyTransformFactory = gesturePreviewTransformFactory()
    private val smoother = GestureSmoother()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var handTracker: HandTracker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraPreviewView: PreviewView? = null
    private var previewTransformListener: View.OnLayoutChangeListener? = null
    @Volatile
    private var previewOutputTransform: OutputTransform? = null
    @Volatile
    private var previewSize = Size(0, 0)
    @Volatile
    private var previewStreamState = PreviewView.StreamState.IDLE
    private var previewStreamObserver: Observer<PreviewView.StreamState>? = null
    private var preferredDelegate: Delegate? = Delegate.GPU
    private val analysisGeneration = AtomicLong(0L)
    private val processingContextGeneration = AtomicLong(0L)
    private val cameraRequestGeneration = AtomicLong(0L)
    @Volatile
    private var desiredRunning = false
    @Volatile
    private var released = false
    @Volatile
    private var cameraSetupInProgress = false

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

    private val calibrationCapture = CalibrationPoseCapture(
        minimumSamples = CALIBRATION_SAMPLE_MIN,
        minimumSampleIntervalMs = CALIBRATION_SAMPLE_INTERVAL_MS,
        minimumSpanMs = CALIBRATION_SAMPLE_MIN_SPAN_MS
    )
    private var calibrationIssue = ""

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
            detachPreviewTransformObservers()
            cameraPreviewView = previewView
            attachPreviewTransformObservers(previewView)
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

    @OptIn(TransformExperimental::class)
    private fun attachPreviewTransformObservers(previewView: PreviewView) {
        detachPreviewTransformObservers()
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val previewTransformListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            refreshPreviewTransform(previewView)
            if (previewView.viewPort != null && !_state.value.isRunning && !cameraSetupInProgress) {
                setupCamera(previewView, cameraRequestGeneration.get())
            }
        }.also(previewView::addOnLayoutChangeListener)
        this.previewTransformListener = previewTransformListener
        val observer = Observer<PreviewView.StreamState> { state ->
            previewStreamState = state ?: PreviewView.StreamState.IDLE
            refreshPreviewTransform(previewView)
        }
        previewStreamObserver = observer
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        previewView.post {
            refreshPreviewTransform(previewView)
            if (previewView.viewPort != null && desiredRunning && !_state.value.isRunning && !cameraSetupInProgress) {
                setupCamera(previewView, cameraRequestGeneration.get())
            }
        }
    }

    private fun detachPreviewTransformObservers() {
        val previewView = cameraPreviewView
        if (previewView != null) {
            previewTransformListener?.let(previewView::removeOnLayoutChangeListener)
            previewStreamObserver?.let(previewView.previewStreamState::removeObserver)
        }
        previewTransformListener = null
        previewStreamObserver = null
        previewOutputTransform = null
        previewSize = Size(0, 0)
        previewStreamState = PreviewView.StreamState.IDLE
    }

    @OptIn(TransformExperimental::class)
    private fun refreshPreviewTransform(previewView: PreviewView) {
        if (previewStreamState != PreviewView.StreamState.STREAMING) return
        previewOutputTransform = previewView.outputTransform
        previewSize = Size(previewView.width, previewView.height)
    }

    @OptIn(TransformExperimental::class)
    private fun coordinateTransformFor(source: OutputTransform): CoordinateTransform? {
        val target = previewOutputTransform ?: return null
        if (previewStreamState != PreviewView.StreamState.STREAMING) return null
        return CoordinateTransform(source, target)
    }

    @Suppress("DEPRECATION")
    private fun setupCamera(previewView: PreviewView, requestGeneration: Long) {
        if (!isCameraRequestCurrent(requestGeneration, previewView) || cameraSetupInProgress) return
        cameraSetupInProgress = true
        val provider = cameraProvider ?: run {
            cameraSetupInProgress = false
            return
        }
        val generation = analysisGeneration.incrementAndGet()
        provider.unbindAll()
        synchronized(this) {
            if (!isCameraRequestCurrent(requestGeneration, previewView)) {
                cameraSetupInProgress = false
                return
            }
            resetDetectionLoop(clearSmoothing = true)
            _state.value = _state.value.copy(
                isRunning = false,
                handDetected = false,
                rawHands = emptyList(),
                landmarks = emptyList()
            )
            previewOutputTransform = null
            previewSize = Size(0, 0)
            previewStreamState = PreviewView.StreamState.IDLE
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
            if (!isCameraRequestCurrent(requestGeneration, previewView)) {
                cameraSetupInProgress = false
                return
            }
            val viewPort = previewView.viewPort
            if (viewPort == null) {
                Log.w(TAG, "PreviewView viewport unavailable; waiting for layout before binding")
                cameraSetupInProgress = false
                previewView.postDelayed({
                    if (isCameraRequestCurrent(requestGeneration, previewView) && !_state.value.isRunning) {
                        setupCamera(previewView, requestGeneration)
                    }
                }, 100L)
                return
            }
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(analysis)
                .setViewPort(viewPort)
                .build()
            provider.bindToLifecycle(lifecycleOwner, selector, useCaseGroup)
            synchronized(this) {
                if (!isCameraRequestCurrent(requestGeneration, previewView)) {
                    analysis.clearAnalyzer()
                    provider.unbindAll()
                    cameraSetupInProgress = false
                    return
                }
                imageAnalysis = analysis
                cameraSetupInProgress = false
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
            cameraSetupInProgress = false
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
            val imageOutputTransform = imageProxyTransformFactory.getOutputTransform(imageProxy)
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
                    trackerBackend = tracker.backendName,
                    frame = frame,
                    imageOutputTransform = imageOutputTransform
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
        trackerBackend: String,
        frame: GestureFrame,
        imageOutputTransform: OutputTransform
    ) {
        if (hands.isEmpty()) {
            markNoHand(fps, "未检测到手，请将目标手完整放入画面", frameWidth, frameHeight)
            return
        }

        if (hands.size > 1) {
            markNoHand(fps, "检测到多只手，请仅保留目标手入镜", frameWidth, frameHeight, hands.map { it.raw })
            return
        }

        val resolvedHands = hands.map { hand ->
            ResolvedHand(hand, resolveHandedness(hand))
        }
        if (resolvedHands.size != resolvedHands.map { it.handedness }.filter { it.isNotBlank() }.toSet().size) {
            markNoHand(fps, "手别结果不明确，请仅放入一只手", frameWidth, frameHeight, hands.map { it.raw })
            return
        }
        val chosen = selectHand(resolvedHands) ?: run {
            markNoHand(fps, "无法确认目标手，请调整手掌方向", frameWidth, frameHeight, hands.map { it.raw })
            return
        }
        val detectedHand = chosen.handedness
        if (detectedHand.isBlank()) {
            markNoHand(fps, "已检测到手，但无法确认左右手", frameWidth, frameHeight, hands.map { it.raw })
            return
        }
        val targetMatched = targetHand.matches(detectedHand)
        val transform = coordinateTransformFor(imageOutputTransform)
        val mappedPreviewLandmarks = if (transform != null && previewSize.width > 0 && previewSize.height > 0) {
            GestureLandmarkTransforms.forPreview(
                landmarks = chosen.tracked.landmarks,
                cropWidth = frame.cropWidth,
                cropHeight = frame.cropHeight,
                previewWidth = previewSize.width,
                previewHeight = previewSize.height
            ) { transform.mapPoints(it) }
        } else {
            emptyList()
        }
        if (mappedPreviewLandmarks.isEmpty()) {
            markNoHand(fps, "预览坐标尚未就绪，暂不输出骨架控制", frameWidth, frameHeight, hands.map { it.raw })
            return
        }
        val rawAngles = AeroHandRetargeter.retarget(chosen.tracked.raw, detectedHand)
        val frameCapturedAtMs = SystemClock.elapsedRealtime()
        val smoothedAngles = smoother.apply(rawAngles, detectedHand, frameCapturedAtMs)

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
            calibrationCaptureElapsedMs = oldState.calibrationCaptureElapsedMs,
            calibrationSampleCount = if (calibrationCapture.isCollecting) calibrationCapture.sampleCount else 0,
            calibrationProfile = activeProfile,
            tuningProfile = tuningProfile,
            cameraFacing = currentFacing(),
            mirrorMode = currentMirror(),
            fps = fps,
            frameWidth = previewSize.width,
            frameHeight = previewSize.height,
            trackerBackend = trackerBackend,
            rawHands = hands.map { it.raw },
            landmarks = mappedPreviewLandmarks
        )
        publishUiState(
            nextState,
            force = !oldState.handDetected ||
                oldState.handedness != detectedHand ||
                oldState.targetHandMatched != targetMatched ||
                oldState.calibrationState != calibrationState ||
                oldState.calibrationSampleCount != nextState.calibrationSampleCount ||
                oldState.cameraFacing != currentFacing() ||
                oldState.mirrorMode != currentMirror() ||
                oldState.feedbackMessage != feedback ||
                oldState.frameWidth != previewSize.width ||
                oldState.frameHeight != previewSize.height ||
                oldState.trackerBackend != nextState.trackerBackend
        )
        updateCalibrationCapture(
            hand = detectedHand,
            targetMatched = targetMatched,
            rawAngles = rawAngles,
            capturedAtMs = frameCapturedAtMs
        )
    }

    private fun selectHand(hands: List<ResolvedHand>): ResolvedHand? {
        if (hands.isEmpty()) return null
        val candidates = hands.filter { it.handedness.isNotBlank() && targetHand.matches(it.handedness) }
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
            inputMirrored = false
        )
    }

    fun toggleCamera(): Boolean {
        val (frontCamera, restartRequest) = synchronized(this) {
            analysisGeneration.incrementAndGet()
            processingContextGeneration.incrementAndGet()
            useFrontCamera = !useFrontCamera
            cameraSetupInProgress = false
            resetDetectionLoop(clearSmoothing = true)
            _state.value = _state.value.copy(
                isRunning = false,
                handDetected = false,
                rawHands = emptyList(),
                landmarks = emptyList()
            )
            _controlFrame.value = GestureControlFrame(false, message = "相机正在切换")
            val previewView = cameraPreviewView?.takeIf { desiredRunning }
            useFrontCamera to previewView?.let { it to cameraRequestGeneration.get() }
        }
        if (restartRequest != null) {
            cameraProvider?.unbindAll()
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
                calibrationSampleCount = 0,
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
        pendingOpen.fill(0f)
        pendingFist.fill(0f)
        pendingOpenThumbSwing = 0f
        pendingHandSide = ""
        pendingFacing = currentFacing()
        pendingMirror = currentMirror()
        calibrationIssue = ""
        resetDetectionLoop(clearSmoothing = true)
        _controlFrame.value = GestureControlFrame(false, message = "校准进行中")
        publishUiState(
            _state.value.copy(
                calibrationState = CalibrationState.CALIBRATING_OPEN,
                calibrationCapturePhase = CalibrationCapturePhase.IDLE,
                calibrationCaptureNotice = CalibrationCaptureNotice.NONE,
                calibrationCaptureElapsedMs = 0L,
                calibrationRetryState = CalibrationRetryState.NONE,
                calibrationSampleCount = 0,
                calibrationProfile = null,
                feedbackMessage = "张开手，拇指向外展开。只放入一只目标手，摆好姿势后点击开始采集。"
            ),
            force = true
        )
    }

    @Synchronized
    fun retryCalibrationPose(calibrationStep: CalibrationState) {
        val state = _state.value
        val allowed = when (state.calibrationRetryState) {
            CalibrationRetryState.OPEN_FIST -> calibrationStep == CalibrationState.CALIBRATING_OPEN ||
                calibrationStep == CalibrationState.CALIBRATING_FIST
            CalibrationRetryState.THUMB_IN -> calibrationStep == CalibrationState.CALIBRATING_THUMB_IN
            CalibrationRetryState.NONE -> false
        }
        if (!allowed) return
        if (!sameCalibrationContext(state.handedness)) {
            startCalibration()
            return
        }
        if (calibrationStep == CalibrationState.CALIBRATING_OPEN) {
            pendingOpen.fill(0f)
            pendingOpenThumbSwing = 0f
            pendingFist.fill(0f)
        } else if (calibrationStep == CalibrationState.CALIBRATING_FIST) {
            pendingFist.fill(0f)
        }
        calibrationIssue = ""
        processingContextGeneration.incrementAndGet()
        resetDetectionLoop(clearSmoothing = true)
        publishUiState(
            _state.value.copy(
                calibrationState = calibrationStep,
                calibrationRetryState = CalibrationRetryState.NONE,
                calibrationCapturePhase = CalibrationCapturePhase.IDLE,
                calibrationCaptureNotice = CalibrationCaptureNotice.NONE,
                calibrationCaptureElapsedMs = 0L,
                calibrationSampleCount = 0,
                feedbackMessage = "旧样本已丢弃。摆好姿势后，请重新点击开始采集。"
            ),
            force = true
        )
    }

    // Kept as the existing screen callback: tapping now ARMS capture, never records old frames.
    @Synchronized
    fun recordCalibrationPose() {
        val state = _state.value
        if (!isCalibrating(state.calibrationState) || calibrationCapture.isCollecting ||
            state.calibrationRetryState != CalibrationRetryState.NONE
        ) return
        if (!state.handDetected || state.rawHands.size > 1 || state.handedness !in setOf("Left", "Right") ||
            !state.targetHandMatched || !targetHand.matches(state.handedness)
        ) {
            calibrationFailure(state, "请只放入一只可识别的目标手，再点击开始采集。")
            return
        }
        if (state.calibrationState != CalibrationState.CALIBRATING_OPEN &&
            !sameCalibrationContext(state.handedness)
        ) {
            startCalibration()
            return
        }
        // Invalidate inference started before this tap, and clear ALL old sample history.
        processingContextGeneration.incrementAndGet()
        resetDetectionLoop(clearSmoothing = true)
        calibrationIssue = ""
        calibrationCapture.arm(
            CalibrationCaptureContext(state.handedness, currentFacing(), currentMirror(), targetHand),
            SystemClock.elapsedRealtime()
        )
        _controlFrame.value = GestureControlFrame(false, message = "校准采集中")
        publishUiState(
            _state.value.copy(
                calibrationCapturePhase = CalibrationCapturePhase.COLLECTING,
                calibrationCaptureNotice = CalibrationCaptureNotice.NONE,
                calibrationCaptureElapsedMs = 0L,
                calibrationSampleCount = 0,
                feedbackMessage = "采集已开始，保持不动；稳定后自动确认。只点一次，不用连点5次。"
            ),
            force = true
        )
    }

    private fun completeCalibrationPose(sample: FingerAngles) {
        val state = _state.value
        calibrationIssue = ""
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
                    _state.value.copy(
                        calibrationState = CalibrationState.CALIBRATING_FIST,
                        calibrationCaptureNotice = CalibrationCaptureNotice.READY,
                        feedbackMessage = "张开已自动确认。只保留同一只手，握紧拳头后点击开始采集握拳。"
                    ),
                    force = true
                )
            }
            CalibrationState.CALIBRATING_FIST -> {
                pendingFist = sample.toControlArray()
                val pair = pendingProfile(sample.thumbSwing)
                val failure = GestureCalibrationMapper.validateOpenFist(pair)
                if (failure != null) {
                    // The rejected fist can never be silently reused. Open is retained only for an explicit retry.
                    pendingFist.fill(0f)
                    calibrationValidationFailure(failure, CalibrationState.CALIBRATING_FIST)
                    return
                }
                processingContextGeneration.incrementAndGet()
                resetDetectionLoop(clearSmoothing = true)
                publishUiState(
                    _state.value.copy(
                        calibrationState = CalibrationState.CALIBRATING_THUMB_IN,
                        calibrationCaptureNotice = CalibrationCaptureNotice.READY,
                        feedbackMessage = "握拳已自动确认。其它四指张开，仅将拇指靠向食指，再点击开始采集拇指内收。"
                    ),
                    force = true
                )
            }
            CalibrationState.CALIBRATING_THUMB_IN -> {
                val poseFailure = GestureCalibrationMapper.validateThumbPose(pendingOpen, sample.toControlArray())
                if (poseFailure != null) {
                    calibrationValidationFailure(poseFailure, CalibrationState.CALIBRATING_THUMB_IN)
                    return
                }
                val profile = pendingProfile(sample.thumbSwing)
                val failure = GestureCalibrationMapper.validateOpenFist(profile)
                    ?: GestureCalibrationMapper.validateThumbRange(profile)
                if (failure != null) {
                    if (failure.retryState == CalibrationRetryState.OPEN_FIST) pendingFist.fill(0f)
                    calibrationValidationFailure(
                        failure,
                        if (failure.retryState == CalibrationRetryState.OPEN_FIST) CalibrationState.CALIBRATING_FIST
                        else CalibrationState.CALIBRATING_THUMB_IN
                    )
                    return
                }
                profiles[profile.key] = profile
                calibrationStore.saveProfile(profile)
                processingContextGeneration.incrementAndGet()
                resetDetectionLoop(clearSmoothing = true)
                _controlFrame.value = GestureControlFrame(false, message = "校准完成，等待新的有效跟随帧")
                publishUiState(
                    _state.value.copy(
                        calibrationState = CalibrationState.CALIBRATED,
                        calibrationCaptureNotice = CalibrationCaptureNotice.READY,
                        calibrationRetryState = CalibrationRetryState.NONE,
                        calibrationProfile = profile,
                        feedbackMessage = "校准完成，等待新的有效跟随帧。"
                    ),
                    force = true
                )
            }
            else -> Unit
        }
    }

    private fun pendingProfile(thumbInSwing: Float) = GestureCalibrationProfile(
        handSide = pendingHandSide,
        cameraFacing = pendingFacing,
        mirrorMode = pendingMirror,
        openAngles = pendingOpen.copyOf(),
        fistAngles = pendingFist.copyOf(),
        openThumbSwing = pendingOpenThumbSwing,
        thumbInSwing = thumbInSwing
    )

    private fun isCalibrating(state: CalibrationState): Boolean = state == CalibrationState.CALIBRATING_OPEN ||
        state == CalibrationState.CALIBRATING_FIST || state == CalibrationState.CALIBRATING_THUMB_IN

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
            cameraSetupInProgress = false
            detachPreviewTransformObservers()
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
                    calibrationSampleCount = 0,
                    feedbackMessage = "相机未运行",
                    rawHands = emptyList(),
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

    // Deterministic retargeted-frame seam shared by the analyzer and focused capture tests.
    @Synchronized
    internal fun updateCalibrationCapture(
        hand: String,
        targetMatched: Boolean,
        rawAngles: FingerAngles,
        capturedAtMs: Long
    ) {
        if (!calibrationCapture.isCollecting) return
        val context = CalibrationCaptureContext(hand, currentFacing(), currentMirror(), targetHand)
        when (val result = calibrationCapture.addFrame(context, targetMatched, rawAngles, capturedAtMs)) {
            is CalibrationCaptureResult.Collecting -> {
                publishUiState(
                    _state.value.copy(
                        calibrationCapturePhase = CalibrationCapturePhase.COLLECTING,
                        calibrationCaptureNotice = CalibrationCaptureNotice.NONE,
                        calibrationCaptureElapsedMs = result.elapsedMs,
                        calibrationSampleCount = result.sampleCount,
                        feedbackMessage = if (result.restartedForInstability) {
                            "姿势变化较大，已重新计时。保持不动，无需再点。"
                        } else {
                            "采集中，保持不动；稳定后自动确认。"
                        }
                    ),
                    force = true
                )
            }
            is CalibrationCaptureResult.Complete -> completeCalibrationPose(result.angles)
            CalibrationCaptureResult.Cancelled -> interruptCalibrationCapture(
                "手别、摄像头或帧间隔已变化，本次采集已丢弃。恢复目标手后请重新点击开始采集。"
            )
            CalibrationCaptureResult.Ignored -> Unit
        }
    }

    private fun interruptCalibrationCapture(message: String) {
        calibrationCapture.cancel()
        calibrationIssue = message
        _controlFrame.value = GestureControlFrame(false, message = message)
        publishUiState(
            _state.value.copy(
                calibrationCapturePhase = CalibrationCapturePhase.IDLE,
                calibrationCaptureNotice = CalibrationCaptureNotice.INTERRUPTED,
                calibrationCaptureElapsedMs = 0L,
                calibrationSampleCount = 0,
                feedbackMessage = message
            ),
            force = true
        )
    }

    private fun calibrationFailure(state: GestureCameraState, message: String) {
        calibrationIssue = message
        publishUiState(state.copy(feedbackMessage = message, calibrationSampleCount = 0), force = true)
    }

    private fun calibrationValidationFailure(
        failure: CalibrationValidationFailure,
        step: CalibrationState
    ) {
        calibrationIssue = failure.message
        processingContextGeneration.incrementAndGet()
        resetDetectionLoop(clearSmoothing = true)
        _controlFrame.value = GestureControlFrame(false, message = failure.message)
        publishUiState(
            _state.value.copy(
                calibrationState = step,
                calibrationCapturePhase = CalibrationCapturePhase.IDLE,
                calibrationCaptureNotice = CalibrationCaptureNotice.NONE,
                calibrationCaptureElapsedMs = 0L,
                calibrationRetryState = failure.retryState,
                calibrationSampleCount = 0,
                calibrationProfile = null,
                feedbackMessage = failure.message
            ),
            force = true
        )
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
            val handFeedback = "当前检测到${handLabel(detectedHand)}，请切换到${targetHand.label}"
            return listOf(calibrationIssue, handFeedback).filter { it.isNotBlank() }.joinToString("\n")
        }
        if (isCalibrating(calibrationState)) {
            if (_state.value.calibrationRetryState != CalibrationRetryState.NONE ||
                _state.value.calibrationCaptureNotice == CalibrationCaptureNotice.INTERRUPTED
            ) return _state.value.feedbackMessage
            if (calibrationCapture.isCollecting) return _state.value.feedbackMessage
            return when (calibrationState) {
                CalibrationState.CALIBRATING_OPEN -> "只放入一只目标手，张开手和拇指后点击开始采集。"
                CalibrationState.CALIBRATING_FIST -> "只保留同一只目标手，握紧拳头后点击开始采集。"
                CalibrationState.CALIBRATING_THUMB_IN -> "只保留同一只目标手，其它四指张开，拇指靠向食指后点击开始采集。"
                else -> ""
            }
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
                if (calibrationStore.needsRecalibration()) {
                    "手别语义已修正，旧标定已失效；请核对左右手并重新完成三步标定"
                } else {
                    "未校准，请先完成三步标定"
                }
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
        frameHeight: Int = _state.value.frameHeight,
        rawHands: List<MediaPipeRawHand> = emptyList()
    ) {
        val capturedAtMs = SystemClock.elapsedRealtime()
        val oldState = _state.value
        val interrupted = calibrationCapture.cancel()
        val notice = if (interrupted) CalibrationCaptureNotice.INTERRUPTED else oldState.calibrationCaptureNotice
        val feedback = listOf(
            calibrationIssue.takeIf { oldState.calibrationRetryState != CalibrationRetryState.NONE },
            message,
            "本次采集已丢弃，请恢复目标手后重新点击开始采集。".takeIf { interrupted }
        ).filterNotNull().joinToString("\n")
        smoother.reset()
        _controlFrame.value = GestureControlFrame(false, message = message, capturedAtMs = capturedAtMs)
        publishUiState(
            oldState.copy(
                handDetected = false,
                handedness = "",
                targetHand = targetHand,
                targetHandMatched = targetHand == GestureTargetHand.AUTO,
                calibrationCapturePhase = CalibrationCapturePhase.IDLE,
                calibrationCaptureNotice = notice,
                calibrationCaptureElapsedMs = 0L,
                calibrationSampleCount = 0,
                feedbackMessage = feedback,
                fps = fps,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                trackerBackend = oldState.trackerBackend,
                rawHands = rawHands,
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
        val interrupted = calibrationCapture.cancel()
        _state.value = _state.value.copy(
            calibrationCapturePhase = CalibrationCapturePhase.IDLE,
            calibrationCaptureNotice = if (interrupted) CalibrationCaptureNotice.INTERRUPTED else CalibrationCaptureNotice.NONE,
            calibrationCaptureElapsedMs = 0L,
            calibrationSampleCount = 0
        )
        if (clearSmoothing) {
            smoother.reset()
        }
    }

    private fun resolvedDisplaySize(width: Int, height: Int, rotation: Int): Size {
        return if (rotation == 90 || rotation == 270) Size(height, width) else Size(width, height)
    }

}
