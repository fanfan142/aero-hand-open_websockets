package com.aerohand.gesture

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot

private val HAND_CONNECTIONS = listOf(
    Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
    Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
    Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
    Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
    Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
    Pair(5, 9), Pair(9, 13), Pair(13, 17)
)

private fun mapWithOutputTransform(
    landmarks: List<GestureLandmark>,
    previewView: PreviewView?
): List<Offset>? {
    val transform = previewView
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { view -> runCatching { view.outputTransform?.matrix }.getOrNull() }
        ?: return null

    val mapped = FloatArray(landmarks.size * 2)
    landmarks.forEachIndexed { index, landmark ->
        mapped[index * 2] = landmark.x
        mapped[index * 2 + 1] = landmark.y
    }
    transform.mapPoints(mapped)
    val points = landmarks.indices.map { index ->
        Offset(mapped[index * 2], mapped[index * 2 + 1])
    }
    return points.takeIf { candidate ->
        candidate.all { it.x.isFinite() && it.y.isFinite() }
    }
}

private fun mapWithFitCenter(
    landmarks: List<GestureLandmark>,
    canvasWidth: Float,
    canvasHeight: Float,
    frameWidth: Int,
    frameHeight: Int,
    mirrorX: Boolean
): List<Offset> {
    val sourceWidth = if (frameWidth > 0) frameWidth.toFloat() else canvasWidth
    val sourceHeight = if (frameHeight > 0) frameHeight.toFloat() else canvasHeight
    val scale = minOf(canvasWidth / sourceWidth, canvasHeight / sourceHeight)
    val contentWidth = sourceWidth * scale
    val contentHeight = sourceHeight * scale
    val offsetX = (canvasWidth - contentWidth) * 0.5f
    val offsetY = (canvasHeight - contentHeight) * 0.5f
    return landmarks.map { landmark ->
        Offset(
            x = offsetX + (if (mirrorX) 1f - landmark.x else landmark.x) * contentWidth,
            y = offsetY + landmark.y * contentHeight
        )
    }
}

private fun mapWithCanvasNormalized(
    landmarks: List<GestureLandmark>,
    canvasWidth: Float,
    canvasHeight: Float,
    mirrorX: Boolean
): List<Offset> {
    return landmarks.map { landmark ->
        Offset(
            x = (if (mirrorX) 1f - landmark.x else landmark.x) * canvasWidth,
            y = landmark.y * canvasHeight
        )
    }
}

private fun scoreMappedPoints(
    points: List<Offset>,
    canvasWidth: Float,
    canvasHeight: Float
): Float {
    if (points.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return Float.NEGATIVE_INFINITY
    if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return Float.NEGATIVE_INFINITY

    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val bboxWidth = maxX - minX
    val bboxHeight = maxY - minY

    if (bboxWidth <= 2f || bboxHeight <= 2f) return -10f

    val marginX = canvasWidth * 0.2f + 16f
    val marginY = canvasHeight * 0.2f + 16f
    val insideRatio = points.count { point ->
        point.x in -marginX..(canvasWidth + marginX) &&
            point.y in -marginY..(canvasHeight + marginY)
    } / points.size.toFloat()

    val widthRatio = bboxWidth / canvasWidth
    val heightRatio = bboxHeight / canvasHeight
    val sizePenalty = when {
        widthRatio < 0.04f || heightRatio < 0.04f -> 4f
        widthRatio > 1.1f || heightRatio > 1.1f -> 4f
        else -> 0f
    }

    val centerX = (minX + maxX) * 0.5f
    val centerY = (minY + maxY) * 0.5f
    val centerDistance = hypot(centerX - canvasWidth * 0.5f, centerY - canvasHeight * 0.5f)
    val maxCenterDistance = hypot(canvasWidth * 0.5f, canvasHeight * 0.5f).coerceAtLeast(1f)
    val centerPenalty = centerDistance / maxCenterDistance

    return insideRatio * 6f +
        widthRatio.coerceIn(0f, 1f) +
        heightRatio.coerceIn(0f, 1f) -
        sizePenalty -
        centerPenalty
}

private fun pickBestMappedPoints(
    landmarks: List<GestureLandmark>,
    previewView: PreviewView?,
    canvasWidth: Float,
    canvasHeight: Float,
    frameWidth: Int,
    frameHeight: Int,
    mirrorX: Boolean
): List<Offset> {
    val candidates = buildList {
        mapWithOutputTransform(landmarks, previewView)?.let(::add)
        add(mapWithFitCenter(landmarks, canvasWidth, canvasHeight, frameWidth, frameHeight, mirrorX))
        add(mapWithCanvasNormalized(landmarks, canvasWidth, canvasHeight, mirrorX))
    }
    return candidates.maxByOrNull { scoreMappedPoints(it, canvasWidth, canvasHeight) }.orEmpty()
}

@Composable
fun SkeletonOverlay(
    landmarks: List<GestureLandmark>,
    previewView: PreviewView? = null,
    mirrorX: Boolean = false,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    modifier: Modifier = Modifier
) {
    if (landmarks.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val points = pickBestMappedPoints(
            landmarks = landmarks,
            previewView = previewView,
            canvasWidth = w,
            canvasHeight = h,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            mirrorX = mirrorX
        )
        val pointStroke = 2.5f
        val pointRadius = 6f
        val lineStroke = 4f

        HAND_CONNECTIONS.forEach { (start, end) ->
            if (start < points.size && end < points.size) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = points[start],
                    end = points[end],
                    strokeWidth = lineStroke + 2f
                )
                drawLine(
                    color = Color(0xFF34D399),
                    start = points[start],
                    end = points[end],
                    strokeWidth = lineStroke
                )
            }
        }

        points.forEach { point ->
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = pointRadius + 3f,
                center = point
            )
            drawCircle(
                color = Color(0xFF141414),
                radius = pointRadius + 1.5f,
                center = point,
                style = Stroke(width = pointStroke)
            )
            drawCircle(
                color = Color(0xFFB4AFFF),
                radius = pointRadius,
                center = point
            )
        }
    }
}
