package com.cpm.cleave.ui.theme

import android.app.Activity
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
    primary = LogoCyan,
    secondary = LogoBlue,
    tertiary = Color(0xFF80DEEA),
    background = LogoDarkBg,
    surface = LogoDarkBg,
    onPrimary = Color(0xFF00382E)
)

private val LightColorScheme = lightColorScheme(
    primary = LogoBlue,
    secondary = LightTealSecondary, 
    tertiary = Color(0xFF1E88E5),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White
)

@Composable
fun CleaveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
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