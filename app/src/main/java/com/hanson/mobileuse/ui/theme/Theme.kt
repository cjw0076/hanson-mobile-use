package com.hanson.mobileuse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ObsidianColorScheme = darkColorScheme(
    primary = ObsidianColors.Accent,
    onPrimary = ObsidianColors.Background,
    primaryContainer = ObsidianColors.AccentDim,
    onPrimaryContainer = ObsidianColors.TextPrimary,
    secondary = ObsidianColors.AccentPurple,
    onSecondary = ObsidianColors.TextPrimary,
    tertiary = ObsidianColors.Success,
    background = ObsidianColors.Background,
    onBackground = ObsidianColors.TextPrimary,
    surface = ObsidianColors.Surface,
    onSurface = ObsidianColors.TextPrimary,
    surfaceVariant = ObsidianColors.SurfaceHigh,
    onSurfaceVariant = ObsidianColors.TextSecondary,
    error = ObsidianColors.Error,
    outline = ObsidianColors.GlassBorder
)

@Composable
fun HansonObsidianTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObsidianColorScheme,
        content = content
    )
}
