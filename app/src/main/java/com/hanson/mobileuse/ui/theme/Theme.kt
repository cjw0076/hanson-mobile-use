package com.hanson.mobileuse.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun HansonObsidianTheme(content: @Composable () -> Unit) {
    val isDark = ObsidianColors.isDark
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = AndroidColor.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = AndroidColor.TRANSPARENT
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

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
