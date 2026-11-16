package com.pokeemu.app.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun PokeEmuTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        darkColorScheme(primary = Color(0xFFEF5350), secondary = Color(0xFF90CAF9))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
