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

@Composable
fun SkeletonOverlay(
    landmarks: List<GestureLandmark>,
    modifier: Modifier = Modifier
) {
    if (landmarks.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Points already include CameraX rotation, mirror and PreviewView letterboxing.
        val points = landmarks.map { Offset(it.x * w, it.y * h) }
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
