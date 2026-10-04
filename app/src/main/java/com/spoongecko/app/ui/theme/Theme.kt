package com.spoongecko.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Blue80,
    secondary = BlueGrey40,
    secondaryContainer = BlueGrey80,
    tertiary = Teal40,
    tertiaryContainer = Teal80,
    background = SurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight
)

private val DarkScheme = darkColorScheme(
    primary = Blue80,
    primaryContainer = Blue40,
    secondary = BlueGrey80,
    secondaryContainer = BlueGrey40,
    tertiary = Teal80,
    tertiaryContainer = Teal40,
    background = SurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark
)

@Composable
fun SpoonGeckoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SpoonGeckoTypography,
        content = content
    )
}
