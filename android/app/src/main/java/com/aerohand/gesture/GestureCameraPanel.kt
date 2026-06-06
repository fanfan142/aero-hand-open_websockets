package com.aerohand.gesture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

private val HAND_CONNECTIONS = listOf(
    Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
    Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
    Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
    Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
    Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
    Pair(5, 9), Pair(9, 13), Pair(13, 17)
)

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

@Composable
fun SkeletonOverlay(
    landmarks: List<GestureLandmark>,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    mirrorX: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (landmarks.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val points = mapWithFitCenter(landmarks, w, h, frameWidth, frameHeight, mirrorX)
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
