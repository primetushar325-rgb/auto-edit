package com.autoedit.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AUTO EDIT logo: stylized "A" (mountain) + gold play button + motion lines + timeline bar.
 * Same mark as the launcher icon, drawn vector-sharp at any size.
 */
@Composable
fun AutoEditLogo(size: Dp, withWordmark: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(modifier = Modifier.size(size)) {
            drawMark(size.toPx())
        }
        if (withWordmark) {
            Spacer(modifier = Modifier.size(size * 0.18f))
            Text(
                text = "AUTO EDIT",
                style = MaterialTheme.typography.displaySmall,
                color = AeText,
                letterSpacing = 6.sp
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "IMAGE TO VIDEO, AUTOMATIC",
                style = MaterialTheme.typography.labelSmall,
                color = AeGold,
                letterSpacing = 2.4.sp
            )
        }
    }
}

/** Draws the logo mark into a canvas of [w] px (square). */
fun DrawScope.drawMark(w: Float) {
    val s = w / 108f
    val white = Color(0xFFF5F5F7)
    val gold = Color(0xFFE6C15A)
    val dim = Color(0xFF4A4A50)

    val a = Path().apply {
        moveTo(38f * s, 80f * s)
        lineTo(54f * s, 34f * s)
        lineTo(70f * s, 80f * s)
    }
    drawPath(a, white, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))

    val bar = Path().apply {
        moveTo(43.5f * s, 66f * s)
        lineTo(64.5f * s, 66f * s)
    }
    drawPath(bar, white, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f * s, cap = StrokeCap.Round))

    val play = Path().apply {
        moveTo(49f * s, 45f * s)
        lineTo(63f * s, 52f * s)
        lineTo(49f * s, 59f * s)
        close()
    }
    drawPath(play, gold)

    val motion = Path().apply {
        moveTo(26f * s, 44f * s); lineTo(34f * s, 44f * s)
        moveTo(26f * s, 52f * s); lineTo(32f * s, 52f * s)
    }
    drawPath(motion, gold, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5f * s, cap = StrokeCap.Round))

    val line = Path().apply {
        moveTo(36f * s, 86f * s); lineTo(72f * s, 86f * s)
    }
    drawPath(line, dim, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f * s, cap = StrokeCap.Round))
    val lineGold = Path().apply {
        moveTo(36f * s, 86f * s); lineTo(50f * s, 86f * s)
    }
    drawPath(lineGold, gold, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f * s, cap = StrokeCap.Round))
}
