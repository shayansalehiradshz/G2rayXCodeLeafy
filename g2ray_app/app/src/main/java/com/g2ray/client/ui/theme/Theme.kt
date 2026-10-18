package com.g2ray.client.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Accent,
    tertiary = PrimaryDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = Color(0xFF003258),
    onSecondary = Color(0xFF002E42),
    onTertiary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primaryContainer = Color(0xFF183C53),
    onPrimaryContainer = Color(0xFFD3E8F6),
    surfaceVariant = Color(0xFF303439),
    onSurfaceVariant = Color(0xFFD1D5DB),
    errorContainer = Color(0xFF5A2025),
    onErrorContainer = Color(0xFFFFDAD9)
)

@Composable
fun G2RayTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
