package com.pokeemu.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's own palette: a deep charcoal night with Poké Ball red and a warm gold. */
object PokeColors {
    val Background = Color(0xFF0F1115)
    val Surface = Color(0xFF15181E)
    val SurfaceLow = Color(0xFF191C23)
    val Surface1 = Color(0xFF1D2129)
    val Surface2 = Color(0xFF232833)
    val Surface3 = Color(0xFF2B313D)
    val Outline = Color(0xFF39404D)
    val OutlineSoft = Color(0xFF2A2F39)
    val Red = Color(0xFFFF5A5F)
    val RedDeep = Color(0xFF7A1F25)
    val Gold = Color(0xFFFFC94A)
    val GoldDeep = Color(0xFF5C4410)
    val Teal = Color(0xFF5ED3C4)
    val Text = Color(0xFFECEFF4)
    val TextDim = Color(0xFF9AA3B2)
}

private val colors = darkColorScheme(
    primary = PokeColors.Red,
    onPrimary = Color.White,
    primaryContainer = PokeColors.RedDeep,
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = PokeColors.Gold,
    onSecondary = Color(0xFF2A1F00),
    secondaryContainer = PokeColors.Surface3,
    onSecondaryContainer = PokeColors.Text,
    tertiary = PokeColors.Teal,
    onTertiary = Color(0xFF00201C),
    tertiaryContainer = Color(0xFF15433E),
    onTertiaryContainer = Color(0xFFB8F2EA),
    background = PokeColors.Background,
    onBackground = PokeColors.Text,
    surface = PokeColors.Surface,
    onSurface = PokeColors.Text,
    surfaceVariant = PokeColors.Surface2,
    onSurfaceVariant = PokeColors.TextDim,
    surfaceContainerLowest = PokeColors.Background,
    surfaceContainerLow = PokeColors.SurfaceLow,
    surfaceContainer = PokeColors.Surface1,
    surfaceContainerHigh = PokeColors.Surface2,
    surfaceContainerHighest = PokeColors.Surface3,
    surfaceBright = PokeColors.Surface3,
    surfaceDim = PokeColors.Background,
    outline = PokeColors.Outline,
    outlineVariant = PokeColors.OutlineSoft,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = PokeColors.Text,
    inverseOnSurface = PokeColors.Background,
    scrim = Color.Black,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val typography = base.copy(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
)

@Composable
fun PokeEmuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, typography = typography, content = content)
}

/** A small uppercase section heading in gold, as used across the app. */
val SectionHeadingStyle = TextStyle(color = PokeColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

/**
 * The app's top bar: the title drawn in the pixel font, on the background
 * colour, with a back arrow when [onBack] is set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokeTopBar(title: String, onBack: (() -> Unit)? = null, navigationIcon: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Box(Modifier.padding(horizontal = 8.dp)) { PixelText(title, pixel = 2.5.dp) } },
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                onBack != null -> IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = PokeColors.Background, scrolledContainerColor = PokeColors.Surface),
    )
}
