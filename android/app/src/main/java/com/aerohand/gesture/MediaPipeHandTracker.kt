package com.aerohand.gesture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

class MediaPipeHandTracker private constructor(
    private val landmarker: HandLandmarker,
    private val delegate: Delegate
) : HandTracker {
    override val backendName: String = "MediaPipe/$delegate"

    override fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int,
        timestampMs: Long
    ): List<TrackedHand> {
        var mpImage: MPImage? = null
        return try {
            mpImage = BitmapImageBuilder(bitmap).build()
            val options = ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()
            val result = landmarker.detectForVideo(mpImage, options, timestampMs)
            result.landmarks().mapIndexedNotNull { index, landmarks ->
                if (landmarks.size < 21) return@mapIndexedNotNull null
                val category = result.handedness().getOrNull(index)?.firstOrNull()
                TrackedHand(
                    landmarks = landmarks.map {
                        val originalX = it.x()
                        val originalY = it.y()
                        val (rotatedX, rotatedY) = when (rotationDegrees) {
                            90 -> Pair(1.0f - originalY, originalX)
                            180 -> Pair(1.0f - originalX, 1.0f - originalY)
                            270, -90 -> Pair(originalY, 1.0f - originalX)
                            else -> Pair(originalX, originalY)
                        }
                        GestureLandmark(rotatedX, rotatedY, it.z())
                    },
                    handedness = GestureAngleEstimator.canonicalHandedness(category?.categoryName().orEmpty()),
                    confidence = category?.score() ?: 0f
                )
            }
        } finally {
            runCatching { mpImage?.close() }
        }
    }

    override fun close() {
        landmarker.close()
    }

    companion object {
        private const val TAG = "MediaPipeHandTracker"
        private const val HAND_LANDMARKER_MODEL_ASSET = "hand_landmarker.task"
        private const val MIN_HAND_DETECTION_CONFIDENCE = 0.42f
        private const val MIN_HAND_PRESENCE_CONFIDENCE = 0.25f
        private const val MIN_TRACKING_CONFIDENCE = 0.25f

        fun create(context: Context, preferredDelegate: Delegate? = null): MediaPipeHandTracker? {
            val hasModelAsset = runCatching {
                context.assets.open(HAND_LANDMARKER_MODEL_ASSET).use { true }
            }.getOrDefault(false)
            val delegatesToTry = when (preferredDelegate) {
                Delegate.CPU -> listOf(Delegate.CPU, Delegate.GPU)
                Delegate.GPU -> listOf(Delegate.GPU, Delegate.CPU)
                else -> listOf(Delegate.CPU, Delegate.GPU)
            }
            for (delegate in delegatesToTry) {
                val landmarker = createLandmarker(context, hasModelAsset, delegate) ?: continue
                return MediaPipeHandTracker(landmarker, delegate)
            }
            return null
        }

        private fun createLandmarker(
            context: Context,
            hasModelAsset: Boolean,
            delegate: Delegate
        ): HandLandmarker? {
            return try {
                val baseOptionsBuilder = BaseOptions.builder()
                    .setDelegate(delegate)
                if (hasModelAsset) {
                    baseOptionsBuilder.setModelAssetPath(HAND_LANDMARKER_MODEL_ASSET)
                }

                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(MIN_HAND_DETECTION_CONFIDENCE)
                    .setMinHandPresenceConfidence(MIN_HAND_PRESENCE_CONFIDENCE)
                    .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                    .build()

                HandLandmarker.createFromOptions(context, options).also {
                    Log.i(TAG, "Initialized hand tracker delegate=$delegate customModel=$hasModelAsset")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Hand tracker init failed delegate=$delegate", e)
                null
            }
        }
    }
}
