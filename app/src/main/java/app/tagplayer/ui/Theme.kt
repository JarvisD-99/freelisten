package app.tagplayer.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val AppColors = darkColorScheme(
    primary = Color(0xFFE0A15A),
    onPrimary = Color(0xFF2A1C0C),
    background = Color(0xFF14120F),
    surface = Color(0xFF221F1B),
    onBackground = Color(0xFFF4EFE8),
    onSurface = Color(0xFFF4EFE8),
    surfaceVariant = Color(0xFF3A342C),
    onSurfaceVariant = Color(0xFFD9CBB8),
    error = Color(0xFFE07A5F),
)

@Composable
fun TagPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors) {
        CompositionLocalProvider(LocalContentColor provides AppColors.onBackground, content = content)
    }
}
