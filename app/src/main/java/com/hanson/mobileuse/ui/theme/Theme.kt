package com.hanson.mobileuse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun HansonObsidianTheme(content: @Composable () -> Unit) {
    val isDark = ObsidianColors.isDark
    val scheme = if (isDark) {
        darkColorScheme(
            primary = ObsidianColors.Accent,
            onPrimary = ObsidianColors.OnAccent,
            primaryContainer = ObsidianColors.SurfaceHigh,
            onPrimaryContainer = ObsidianColors.TextPrimary,
            secondary = ObsidianColors.TextSecondary,
            onSecondary = ObsidianColors.OnAccent,
            tertiary = ObsidianColors.TextPrimary,
            background = ObsidianColors.Background,
            onBackground = ObsidianColors.TextPrimary,
            surface = ObsidianColors.Surface,
            onSurface = ObsidianColors.TextPrimary,
            surfaceVariant = ObsidianColors.SurfaceHigh,
            onSurfaceVariant = ObsidianColors.TextSecondary,
            error = ObsidianColors.Error,
            outline = ObsidianColors.GlassBorder
        )
    } else {
        lightColorScheme(
            primary = ObsidianColors.Accent,
            onPrimary = ObsidianColors.OnAccent,
            primaryContainer = ObsidianColors.SurfaceHigh,
            onPrimaryContainer = ObsidianColors.TextPrimary,
            secondary = ObsidianColors.TextSecondary,
            onSecondary = ObsidianColors.OnAccent,
            tertiary = ObsidianColors.TextPrimary,
            background = ObsidianColors.Background,
            onBackground = ObsidianColors.TextPrimary,
            surface = ObsidianColors.Surface,
            onSurface = ObsidianColors.TextPrimary,
            surfaceVariant = ObsidianColors.SurfaceHigh,
            onSurfaceVariant = ObsidianColors.TextSecondary,
            error = ObsidianColors.Error,
            outline = ObsidianColors.GlassBorder
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
