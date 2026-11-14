package com.truong.findmycar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = MintAccent,
    onPrimary = OnMintAccent,
    primaryContainer = MintAccentContainer,
    onPrimaryContainer = MintAccent,
    secondary = MintAccent,
    onSecondary = OnMintAccent,
    background = NearBlackBg,
    onBackground = TextPrimary,
    surface = SurfaceLayer1,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceLayer2,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceLayer2,
    surfaceContainerHigh = SurfaceLayer3,
    outline = OutlineSubtle,
    error = ErrorRed,
    onError = NearBlackBg
)

private val LightColorScheme = lightColorScheme(
    primary = MintAccent,
    onPrimary = OnMintAccent,
    primaryContainer = MintAccentContainer,
    onPrimaryContainer = MintAccent,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    error = ErrorRed
)

@Composable
fun FindMyCarTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Dynamic Color is intentionally disabled to preserve FindMyCar's custom brand identity.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FindMyCarTypography,
        shapes = FindMyCarShapes,
        content = content
    )
}
