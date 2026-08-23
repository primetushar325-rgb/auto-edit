package com.autoedit.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AeBlack = Color(0xFF0B0B0D)
val AeCharcoal = Color(0xFF141417)
val AeCard = Color(0xFF1C1C21)
val AeCardHi = Color(0xFF26262D)
val AeGold = Color(0xFFE6C15A)
val AeGoldDim = Color(0xFF8A6D2B)
val AeText = Color(0xFFF5F5F7)
val AeTextDim = Color(0xFF9A9AA3)
val AeDivider = Color(0xFF2A2A31)
val AeDanger = Color(0xFFFF5A5A)

private val AeColorScheme = darkColorScheme(
    primary = AeGold,
    onPrimary = Color(0xFF1A1405),
    secondary = AeCardHi,
    background = AeBlack,
    surface = AeCharcoal,
    onBackground = AeText,
    onSurface = AeText,
    error = AeDanger
)

private fun ts(fontSize: androidx.compose.ui.unit.TextUnit,
               weight: FontWeight,
               spacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit(0f, androidx.compose.ui.unit.TextUnitType.Sp)): TextStyle =
    TextStyle.Default.copy(
        fontSize = fontSize,
        fontWeight = weight,
        letterSpacing = spacing
    )

private val AeTypography = Typography(
    displaySmall = ts(28.sp, FontWeight.Black, 2.sp),
    headlineMedium = ts(22.sp, FontWeight.ExtraBold),
    titleLarge = ts(18.sp, FontWeight.Bold),
    titleMedium = ts(16.sp, FontWeight.SemiBold),
    titleSmall = ts(14.sp, FontWeight.SemiBold),
    bodyLarge = ts(15.sp, FontWeight.Medium),
    bodyMedium = ts(14.sp, FontWeight.Normal),
    bodySmall = ts(12.sp, FontWeight.Normal),
    labelLarge = ts(13.sp, FontWeight.Bold),
    labelMedium = ts(12.sp, FontWeight.SemiBold),
    labelSmall = ts(11.sp, FontWeight.Medium)
)

private val AeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AutoEditTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AeColorScheme,
        typography = AeTypography,
        shapes = AeShapes,
        content = content
    )
}
