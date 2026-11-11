package com.example.idautble.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val GraphiteColorScheme = DarkColorScheme.copy(
    background = Color(0xFF24272B),
    onBackground = Color(0xFFE7EAEE),
    surface = Color(0xFF292D32),
    onSurface = Color(0xFFE7EAEE),
    surfaceDim = Color(0xFF24272B),
    surfaceBright = Color(0xFF3A4047),
    surfaceVariant = Color(0xFF3A4047),
    onSurfaceVariant = Color(0xFFC5CAD1),
    surfaceContainerLowest = Color(0xFF24272B),
    surfaceContainerLow = Color(0xFF2B3036),
    surfaceContainer = Color(0xFF30363D),
    surfaceContainerHigh = Color(0xFF363C44),
    surfaceContainerHighest = Color(0xFF3D444D),
    outline = Color(0xFF8F97A1),
    outlineVariant = Color(0xFF4E565F),
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFE1DDE8),

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun IDAUTBLETheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    graphite: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        graphite -> GraphiteColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
