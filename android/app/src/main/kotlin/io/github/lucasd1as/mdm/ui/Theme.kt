package io.github.lucasd1as.mdm.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1B5FD1),
    secondary = Color(0xFF4A5F83),
    tertiary = Color(0xFF00796B),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFADC6FF),
    secondary = Color(0xFFB2C6EF),
    tertiary = Color(0xFF7ED8C8),
)

@Composable
fun MdmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Android 12+ lends the wallpaper's colours; older versions get the brand's.
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
