package com.topsearch.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue        = Color(0xFF1A73E8)
private val BlueDark    = Color(0xFF8AB4F8)
private val Surface     = Color(0xFFF8F9FA)
private val SurfaceDark = Color(0xFF202124)

private val LightColors = lightColorScheme(
    primary          = Blue,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD2E3FC),
    background       = Surface,
    surface          = Color.White,
    onBackground     = Color(0xFF202124),
    onSurface        = Color(0xFF202124),
    error            = Color(0xFFD93025),
)

private val DarkColors = darkColorScheme(
    primary          = BlueDark,
    onPrimary        = Color(0xFF003087),
    primaryContainer = Color(0xFF004ABE),
    background       = SurfaceDark,
    surface          = Color(0xFF303134),
    onBackground     = Color(0xFFE8EAED),
    onSurface        = Color(0xFFE8EAED),
)

@Composable
fun TopSearchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content     = content,
    )
}
