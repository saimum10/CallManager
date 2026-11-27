package com.saimum.callmanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    secondary = BrandSecondary
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    secondary = BrandSecondaryDark
)

/**
 * Root theme wrapper.
 *
 * [darkTheme] defaults to the system setting for now. The Settings screen's
 * Light/Dark/System-default choice will drive this parameter explicitly
 * once that feature is built — this composable already supports it.
 */
@Composable
fun CallManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CallManagerTypography,
        content = content
    )
}
