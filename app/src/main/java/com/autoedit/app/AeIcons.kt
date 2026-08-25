package com.autoedit.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom vector icons, drawn with Canvas so they render identically on every
 * device (the previous UI used Unicode arrow/geometric glyphs which on some
 * ROM fonts fell back to a black "tofu"/arrow box that could bleed through
 * screen transitions).
 */
object AeIcon {
    enum class Kind {
        BACK, PLAY, PAUSE, RESTART, EXPAND, CLOSE, PLUS, CHECK,
        SETTINGS, IMAGE, VIDEO, MUSIC, VOICE, ADJUST,
        DOWNLOAD, SHARE, FOLDER, DRAG, CHEVRON_RIGHT, TRASH,
        FILM, SPARKLES
    }
}

@Composable
fun AeIcon(
    icon: AeIcon.Kind,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawAeIcon(icon, s, tint)
    }
}

/** Draw [icon] into a 24x24 grid scaled by [s]. */
fun DrawScope.drawAeIcon(icon: AeIcon.Kind, s: Float, color: Color) {
    val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (icon) {
        AeIcon.Kind.BACK -> {
            val p = Path().apply {
                moveTo(15f * s, 5f * s); lineTo(8f * s, 12f * s); lineTo(15f * s, 19f * s)
            }
            drawPath(p, color, style = stroke)
        }
        AeIcon.Kind.CHEVRON_RIGHT -> {
            val p = Path().apply {
                moveTo(9f * s, 6f * s); lineTo(15f * s, 12f * s); lineTo(9f * s, 18f * s)
            }
            drawPath(p, color, style = stroke)
        }
        AeIcon.Kind.PLAY -> {
            val p = Path().apply {
                moveTo(8f * s, 6f * s); lineTo(18f * s, 12f * s); lineTo(8f * s, 18f * s); close()
            }
            drawPath(p, color, style = Fill)
        }
        AeIcon.Kind.PAUSE -> {
            drawRoundRect(color, topLeft = Offset(8f * s, 6f * s),
                size = androidx.compose.ui.geometry.Size(3f * s, 12f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * s))
            drawRoundRect(color, topLeft = Offset(13f * s, 6f * s),
                size = androidx.compose.ui.geometry.Size(3f * s, 12f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * s))
        }
        AeIcon.Kind.RESTART -> {
            val p = Path().apply {
                moveTo(4f * s, 12f * s)
                arcTo(androidx.compose.ui.geometry.Rect(5f * s, 5f * s, 19f * s, 19f * s),
                    180f, 270f, false)
            }
            drawPath(p, color, style = stroke)
            drawPath(Path().apply {
                moveTo(4f * s, 7f * s); lineTo(4f * s, 12f * s); lineTo(9f * s, 12f * s)
            }, color, style = stroke)
        }
        AeIcon.Kind.EXPAND -> {
            drawPath(Path().apply {
                moveTo(4f*s,9f*s); lineTo(4f*s,4f*s); lineTo(9f*s,4f*s)
                moveTo(20f*s,9f*s); lineTo(20f*s,4f*s); lineTo(15f*s,4f*s)
                moveTo(4f*s,15f*s); lineTo(4f*s,20f*s); lineTo(9f*s,20f*s)
                moveTo(20f*s,15f*s); lineTo(20f*s,20f*s); lineTo(15f*s,20f*s)
            }, color, style = stroke)
        }
        AeIcon.Kind.CLOSE -> drawPath(Path().apply {
            moveTo(6f*s,6f*s); lineTo(18f*s,18f*s); moveTo(18f*s,6f*s); lineTo(6f*s,18f*s)
        }, color, style = stroke)
        AeIcon.Kind.PLUS -> drawPath(Path().apply {
            moveTo(12f*s,5f*s); lineTo(12f*s,19f*s); moveTo(5f*s,12f*s); lineTo(19f*s,12f*s)
        }, color, style = stroke)
        AeIcon.Kind.CHECK -> drawPath(Path().apply {
            moveTo(5f*s,12.5f*s); lineTo(10f*s,17.5f*s); lineTo(19f*s,7f*s)
        }, color, style = stroke)
        AeIcon.Kind.SETTINGS -> {
            drawCircle(color, radius = 3f*s, center = center, style = stroke)
            drawCircle(color, radius = 8.5f*s, center = center, style = stroke)
        }
        AeIcon.Kind.IMAGE -> {
            drawRoundRect(color, topLeft = Offset(4f*s,5f*s),
                size = androidx.compose.ui.geometry.Size(16f*s,14f*s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f*s), style = stroke)
            drawCircle(color, radius = 1.6f*s, center = Offset(8.5f*s,9.5f*s), style = Fill)
            drawPath(Path().apply {
                moveTo(7f*s,16f*s); lineTo(11f*s,12f*s); lineTo(15f*s,16f*s)
            }, color, style = stroke)
        }
        AeIcon.Kind.VIDEO -> {
            drawRoundRect(color, topLeft = Offset(3f*s,6f*s),
                size = androidx.compose.ui.geometry.Size(14f*s,12f*s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f*s), style = stroke)
            drawPath(Path().apply {
                moveTo(17f*s,10f*s); lineTo(21f*s,7.5f*s); lineTo(21f*s,16.5f*s); lineTo(17f*s,14f*s); close()
            }, color, style = Fill)
        }
        AeIcon.Kind.MUSIC -> drawPath(Path().apply {
            moveTo(9f*s,18f*s); arcTo(androidx.compose.ui.geometry.Rect(5f*s,14f*s,9f*s,18f*s),0f,-180f,false)
            lineTo(9f*s,6f*s); lineTo(19f*s,4f*s); lineTo(19f*s,15f*s)
            moveTo(19f*s,15f*s); arcTo(androidx.compose.ui.geometry.Rect(15f*s,15f*s,19f*s,19f*s),90f,-180f,false)
        }, color, style = stroke)
        AeIcon.Kind.VOICE -> drawPath(Path().apply {
            moveTo(12f*s,4f*s); lineTo(12f*s,15f*s)
            moveTo(8f*s,10f*s); arcTo(androidx.compose.ui.geometry.Rect(8f*s,5f*s,16f*s,13f*s),90f,-180f,false)
            moveTo(6f*s,12f*s); cubicTo(6f*s,16f*s,9f*s,19f*s,12f*s,19f*s)
            moveTo(18f*s,12f*s); cubicTo(18f*s,16f*s,15f*s,19f*s,12f*s,19f*s)
        }, color, style = stroke)
        AeIcon.Kind.ADJUST -> {
            drawCircle(color, 9f*s, center, style = stroke)
            drawCircle(color, 5f*s, center, style = Fill)
        }
        AeIcon.Kind.DOWNLOAD -> drawPath(Path().apply {
            moveTo(12f*s,4f*s); lineTo(12f*s,15f*s); moveTo(7f*s,11f*s); lineTo(12f*s,16f*s); lineTo(17f*s,11f*s)
            moveTo(5f*s,19f*s); lineTo(19f*s,19f*s)
        }, color, style = stroke)
        AeIcon.Kind.SHARE -> drawPath(Path().apply {
            drawCircle(color, 2f*s, Offset(18f*s,6f*s), style=Fill)
            drawCircle(color, 2f*s, Offset(6f*s,12f*s), style=Fill)
            drawCircle(color, 2f*s, Offset(18f*s,18f*s), style=Fill)
            moveTo(16f*s,7.5f*s); lineTo(8f*s,10.5f*s); moveTo(16f*s,16.5f*s); lineTo(8f*s,13.5f*s)
        }, color, style = stroke)
        AeIcon.Kind.FOLDER -> drawPath(Path().apply {
            moveTo(4f*s,7f*s); lineTo(10f*s,7f*s); lineTo(12f*s,9f*s); lineTo(20f*s,9f*s)
            lineTo(20f*s,18f*s); lineTo(4f*s,18f*s); close()
        }, color, style = stroke)
        AeIcon.Kind.DRAG -> {
            for (yy in listOf(7f,12f,17f)) for (xx in listOf(8f,16f))
                drawCircle(color, 1.4f*s, Offset(xx*s, yy*s), style=Fill)
        }
        AeIcon.Kind.TRASH -> drawPath(Path().apply {
            moveTo(5f*s,7f*s); lineTo(19f*s,7f*s); moveTo(10f*s,7f*s); lineTo(10f*s,5f*s); lineTo(14f*s,5f*s); lineTo(14f*s,7f*s)
            moveTo(7f*s,9f*s); lineTo(8f*s,19f*s); lineTo(16f*s,19f*s); lineTo(17f*s,9f*s)
        }, color, style = stroke)
        AeIcon.Kind.FILM -> {
            drawRoundRect(color, Offset(3f*s,4f*s), androidx.compose.ui.geometry.Size(18f*s,16f*s),
                androidx.compose.ui.geometry.CornerRadius(2f*s), style = stroke)
            for (yy in listOf(7f,12f,17f)) drawLine(color, Offset(3f*s,yy*s), Offset(21f*s,yy*s), 1.4f*s)
            for (xx in listOf(7f,12f,17f)) drawLine(color, Offset(xx*s,4f*s), Offset(xx*s,9f*s), 1.4f*s)
        }
        AeIcon.Kind.SPARKLES -> drawPath(Path().apply {
            moveTo(12f*s,3f*s); lineTo(13.5f*s,9.5f*s); lineTo(20f*s,12f*s)
            lineTo(13.5f*s,14.5f*s); lineTo(12f*s,21f*s); lineTo(10.5f*s,14.5f*s)
            lineTo(4f*s,12f*s); lineTo(10.5f*s,9.5f*s); close()
        }, color, style = Fill)
    }
}
