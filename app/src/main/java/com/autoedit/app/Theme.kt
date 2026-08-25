package com.autoedit.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- palette
// Deep, warm-charcoal dark theme. No pure black (#000) / no pure white:
// surfaces step up in elevation so cards/panels read as layered.
val AeBg       = Color(0xFF0E0E11)   // app background
val AeSurface  = Color(0xFF16161B)   // primary surface (cards)
val AeSurface2 = Color(0xFF1E1E25)   // elevated surface (chips, inputs)
val AeSurface3 = Color(0xFF2A2A33)   // pressed/highlight
val AeGold     = Color(0xFFE6C15A)   // brand accent
val AeGoldSoft = Color(0xFFFFE08A)   // on-gold text / glows
val AeGoldDim  = Color(0xFF8A6D2B)
val AeText     = Color(0xFFF3F3F6)
val AeTextDim  = Color(0xFF9A9AA5)
val AeTextFaint= Color(0xFF6B6B76)
val AeLine     = Color(0xFF2B2B33)
val AeDanger   = Color(0xFFFF5A5A)
val AeSuccess  = Color(0xFF5AD18A)

// Backwards-compat aliases (older files still reference these).
val AeBlack    = AeBg
val AeCharcoal = AeSurface
val AeCard     = AeSurface
val AeCardHi   = AeSurface2
val AeDivider  = AeLine

private val AeColorScheme = darkColorScheme(
    primary = AeGold,
    onPrimary = Color(0xFF1A1405),
    secondary = AeSurface3,
    background = AeBg,
    surface = AeSurface,
    surfaceVariant = AeSurface2,
    onBackground = AeText,
    onSurface = AeText,
    outline = AeLine,
    error = AeDanger
)

// ---------------------------------------------------------------- typography
private fun ts(
    fontSize: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    spacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit(0f, androidx.compose.ui.unit.TextUnitType.Sp)
): TextStyle = TextStyle.Default.copy(fontSize = fontSize, fontWeight = weight, letterSpacing = spacing)

private val AeTypography = Typography(
    displaySmall  = ts(28.sp, FontWeight.Black, 2.sp),
    headlineLarge = ts(26.sp, FontWeight.ExtraBold),
    headlineMedium= ts(22.sp, FontWeight.ExtraBold),
    titleLarge    = ts(18.sp, FontWeight.Bold),
    titleMedium   = ts(16.sp, FontWeight.SemiBold),
    titleSmall    = ts(14.sp, FontWeight.SemiBold),
    bodyLarge     = ts(15.sp, FontWeight.Medium),
    bodyMedium    = ts(14.sp, FontWeight.Normal),
    bodySmall     = ts(12.sp, FontWeight.Normal),
    labelLarge    = ts(13.sp, FontWeight.Bold),
    labelMedium   = ts(12.sp, FontWeight.SemiBold),
    labelSmall    = ts(11.sp, FontWeight.Medium)
)

// ---------------------------------------------------------------- shapes
private val AeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// ---------------------------------------------------------------- spacing
/** Unified 4dp spacing scale so margins never feel random. */
object Space {
    val xxs = 4.dp; val xs = 6.dp; val s = 8.dp; val m = 12.dp
    val l = 16.dp; val xl = 20.dp; val xxl = 24.dp; val xxxl = 32.dp
}

object AeElevation {
    /** Soft, premium shadow tokens used on elevated cards/sheets. */
    const val card = 6
    const val sheet = 16
    const val button = 4
}

@Composable
fun AutoEditTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AeColorScheme,
        typography = AeTypography,
        shapes = AeShapes,
        content = content
    )
}
