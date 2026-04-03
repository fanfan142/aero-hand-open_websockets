package com.aerohand.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FF),
    onPrimaryContainer = Color(0xFF0A1B52),
    secondary = Color(0xFF0EA5E9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F2FF),
    onSecondaryContainer = Color(0xFF082F49),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    background = Color(0xFFF4F8FF),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFCFDFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEFF4FB),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun AeroHandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
