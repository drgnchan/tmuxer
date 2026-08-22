package com.tmuxer.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Neutral dark surfaces keep the terminal content dominant; mint is reserved for actions/status.
val Ink = Color(0xFF080C0B)
val DeepSurface = Color(0xFF101513)
val RaisedSurface = Color(0xFF171E1B)
val Mint = Color(0xFF72E3AA)
val MintDim = Color(0xFF285A43)
val Amber = Color(0xFFFFB86A)
val TerminalBlue = Color(0xFF83C8FF)
val TextPrimary = Color(0xFFF0F5F2)
val TextSecondary = Color(0xFF98A69F)
val Outline = Color(0xFF28332E)
val Error = Color(0xFFFF8D95)

private val TmuxerColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF002113),
    primaryContainer = Color(0xFF173D2E),
    onPrimaryContainer = Color(0xFFAAF2CE),
    secondary = Amber,
    onSecondary = Color(0xFF321900),
    secondaryContainer = Color(0xFF4A2B0C),
    onSecondaryContainer = Color(0xFFFFDCC0),
    tertiary = TerminalBlue,
    background = Ink,
    onBackground = TextPrimary,
    surface = DeepSurface,
    onSurface = TextPrimary,
    surfaceVariant = RaisedSurface,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = Error,
    onError = Color(0xFF3A0005)
)

private val BaseTypography = Typography()
private val TmuxerTypography = Typography(
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontSize = 27.sp,
        lineHeight = 33.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

private val TmuxerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(15.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TmuxerTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Ink.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = TmuxerColors,
        typography = TmuxerTypography,
        shapes = TmuxerShapes,
        content = content
    )
}
