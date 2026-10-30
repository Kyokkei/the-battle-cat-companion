package com.projectnyanko.companion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Palette taken from the supplied UI template: warm paper, heavy black ink, one yellow accent.
 *
 * `Ink` is not pure black — the template's outlines sit a little warm — and the paper tones stay
 * within two steps of each other so panels read as layered paper rather than as separate cards.
 */
val Paper = Color(0xFFF8EEDA)
val PaperDeep = Color(0xFFF2E0C4)
val PaperLight = Color(0xFFFFFBF1)
val Ink = Color(0xFF2B2118)
val InkSoft = Color(0xFF6E5C46)
val Accent = Color(0xFFF5C55A)
val AccentDeep = Color(0xFFD9992B)
val Brown = Color(0xFF3D2E1F)
val BrownSoft = Color(0xFF7A5C3D)
val Danger = Color(0xFFC0453A)
val Good = Color(0xFF4E8C3A)

private val NyankoColors = lightColorScheme(
    primary = AccentDeep,
    onPrimary = Ink,
    secondary = BrownSoft,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = PaperLight,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = Ink,
    error = Danger,
    onError = PaperLight,
)

/**
 * The template's headings are a heavy display face. Without a bundled font the closest honest match
 * is the system sans at Black weight with a little letter spacing; dropping a TTF into `res/font` and
 * pointing [DisplayFamily] at it would give the drawn letterforms.
 */
private val DisplayFamily = FontFamily.SansSerif

private val NyankoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.5.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.3.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Black,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Black,
        fontSize = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val NyankoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun BattleCatsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NyankoColors,
        typography = NyankoTypography,
        shapes = NyankoShapes,
        content = content,
    )
}
