package br.edu.fileshare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandBlue = Color(0xFF1565C0)
private val BrandBlueContainer = Color(0xFFD6E4FF)
private val BrandTeal = Color(0xFF00796B)
private val BrandTealContainer = Color(0xFFCFF2EC)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = BrandTealContainer,
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF6),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFCFF2EC),
    background = Color(0xFF111418),
    surface = Color(0xFF1A1F26),
    surfaceVariant = Color(0xFF28303B),
)

@Composable
fun FileShareTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

