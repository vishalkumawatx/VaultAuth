package com.example.authanticator.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.authanticator.R

val LocalReducedMotion = staticCompositionLocalOf { false }

@OptIn(ExperimentalTextApi::class)
private val InterfaceFont = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

@OptIn(ExperimentalTextApi::class)
val CodeFont = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Medium,
    variationSettings = FontVariation.Settings(FontVariation.weight(500))))

private fun textStyle(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = InterfaceFont, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp
)

private val VaultTypography = Typography(
    displayLarge = textStyle(48, 56, FontWeight.SemiBold), displayMedium = textStyle(40, 48, FontWeight.SemiBold),
    displaySmall = textStyle(32, 40, FontWeight.SemiBold), headlineLarge = textStyle(30, 38, FontWeight.SemiBold),
    headlineMedium = textStyle(28, 36, FontWeight.SemiBold), headlineSmall = textStyle(24, 32, FontWeight.SemiBold),
    titleLarge = textStyle(22, 30, FontWeight.SemiBold), titleMedium = textStyle(16, 24, FontWeight.SemiBold),
    titleSmall = textStyle(14, 20, FontWeight.SemiBold), bodyLarge = textStyle(16, 25),
    bodyMedium = textStyle(14, 22), bodySmall = textStyle(12, 18),
    labelLarge = textStyle(14, 20, FontWeight.SemiBold), labelMedium = textStyle(12, 18, FontWeight.Medium),
    labelSmall = textStyle(11, 16, FontWeight.Medium)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DD9B9), onPrimary = Color(0xFF123B28), primaryContainer = Color(0xFF234837), onPrimaryContainer = Color(0xFFD0F3DE),
    secondary = Color(0xFFADC6D9), onSecondary = Color(0xFF193344), secondaryContainer = Color(0xFF2F414B), onSecondaryContainer = Color(0xFFD5E8F4),
    background = Color(0xFF111516), onBackground = Color(0xFFEEF2F2), surface = Color(0xFF111516), onSurface = Color(0xFFEEF2F2),
    surfaceContainerLowest = Color(0xFF0C1011), surfaceContainerLow = Color(0xFF191E20), surfaceContainer = Color(0xFF202628),
    surfaceContainerHigh = Color(0xFF293033), surfaceContainerHighest = Color(0xFF323A3D), onSurfaceVariant = Color(0xFFB4C0C3),
    outline = Color(0xFF839296), outlineVariant = Color(0xFF354145), error = Color(0xFFFFB4AA)
)
private val Light = lightColorScheme(
    primary = Color(0xFF166341), onPrimary = Color.White, primaryContainer = Color(0xFFDDEFE4), onPrimaryContainer = Color(0xFF123B28),
    secondary = Color(0xFF466477), onSecondary = Color.White, secondaryContainer = Color(0xFFE0ECF4), onSecondaryContainer = Color(0xFF263E4E),
    background = Color(0xFFF6F8F8), onBackground = Color(0xFF182225), surface = Color(0xFFF6F8F8), onSurface = Color(0xFF182225),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4F4), surfaceContainer = Color(0xFFE9EEEE),
    surfaceContainerHigh = Color(0xFFE2E8E8), surfaceContainerHighest = Color(0xFFDBE2E2), onSurfaceVariant = Color(0xFF526367),
    outline = Color(0xFF718487), outlineVariant = Color(0xFFD1DDDD), error = Color(0xFFAC332B)
)

@Composable
fun VaultTheme(light: Boolean, reducedMotion: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(colorScheme = if (light) Light else Dark, typography = VaultTypography,
            shapes = Shapes(RoundedCornerShape(4.dp), RoundedCornerShape(8.dp), RoundedCornerShape(8.dp), RoundedCornerShape(16.dp), RoundedCornerShape(20.dp)),
            content = content)
    }
}