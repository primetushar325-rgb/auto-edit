package com.autoedit.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.autoedit.engine.EasingType
import com.autoedit.engine.FormulaCatalog
import com.autoedit.engine.ProjectModel
import com.autoedit.engine.TimelineMath
import com.autoedit.engine.TransitionType
import kotlin.math.hypot

/**
 * Compose-side frame renderer for the preview player.
 * Mirrors [ExportRenderer] (same math, same transition logic) so what you
 * preview is what you export.
 */
object PreviewRenderer {

    fun DrawScope.drawFrame(p: ProjectModel, state: com.autoedit.engine.FrameState, bitmaps: Map<Int, ImageBitmap>) {
        drawRect(Color.Black)
        val w = size.width
        val h = size.height
        val clipDur = p.effectiveClipDuration()
        val easing = FormulaCatalog.byId(p.formulaId)?.easing ?: EasingType.EASE_IN_OUT
        val colorFilter = if (p.adjustments.needsColorFilter()) {
            ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(p.adjustments.toColorMatrix()))
        } else null

        fun drawClip(
            idx: Int,
            localT: Double,
            alpha: Float,
            extraScale: Float,
            offX: Float,
            offY: Float
        ) {
            val img = bitmaps[idx] ?: return
            if (alpha <= 0f) return
            val motion = p.clips.getOrNull(idx)?.motion
            val tr = FrameMath.transformAt(motion, localT, clipDur, easing)
            val cover = maxOf(w / img.width, h / img.height)
            val s = cover * tr.scale * extraScale
            val dx = (w - img.width * s) / 2f + tr.xFrac * w + offX
            val dy = (h - img.height * s) / 2f + tr.yFrac * h + offY
            drawImage(
                image = img,
                srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                dstSize = androidx.compose.ui.unit.IntSize((img.width * s).toInt(), (img.height * s).toInt()),
                dstOffset = androidx.compose.ui.unit.IntOffset(dx.toInt(), dy.toInt()),
                alpha = alpha,
                colorFilter = colorFilter
            )
        }

        val transition = p.transition
        if (state.prevIndex >= 0 && transition != TransitionType.NONE) {
            val b = TimelineMath.easing(state.blend, EasingType.EASE_IN_OUT).toFloat()
            val prev = state.prevIndex
            when (transition) {
                TransitionType.FADE ->
                    drawClip(state.clipIndex, state.localT, b, 1f, 0f, 0f)
                TransitionType.CROSS_DISSOLVE -> {
                    drawClip(prev, clipDur, 1f, 1f, 0f, 0f)
                    drawClip(state.clipIndex, state.localT, b, 1f, 0f, 0f)
                }
                TransitionType.BLUR -> {
                    drawClip(prev, clipDur, 1f, 1f + 0.06f * b, 0f, 0f)
                    drawClip(state.clipIndex, state.localT, b, 1f, 0f, 0f)
                }
                TransitionType.SLIDE_LEFT -> {
                    drawClip(prev, clipDur, 1f, 1f, -b * w * 0.55f, 0f)
                    drawClip(state.clipIndex, state.localT, 1f, 1f, (1f - b) * w, 0f)
                }
                TransitionType.SLIDE_RIGHT -> {
                    drawClip(prev, clipDur, 1f, 1f, b * w * 0.55f, 0f)
                    drawClip(state.clipIndex, state.localT, 1f, 1f, -(1f - b) * w, 0f)
                }
                TransitionType.SLIDE_UP -> {
                    drawClip(prev, clipDur, 1f, 1f, 0f, -b * h * 0.55f)
                    drawClip(state.clipIndex, state.localT, 1f, 1f, 0f, (1f - b) * h)
                }
                TransitionType.SLIDE_DOWN -> {
                    drawClip(prev, clipDur, 1f, 1f, 0f, b * h * 0.55f)
                    drawClip(state.clipIndex, state.localT, 1f, 1f, 0f, -(1f - b) * h)
                }
                TransitionType.ZOOM -> {
                    drawClip(prev, clipDur, 1f, 1f, 0f, 0f)
                    drawClip(state.clipIndex, state.localT, b, 1f + 0.12f * (1f - b), 0f, 0f)
                }
                TransitionType.FLASH -> {
                    drawClip(state.clipIndex, state.localT, 1f, 1f, 0f, 0f)
                    val a = (kotlin.math.sin((b * 3.14159265f).toDouble()) * 0.75).coerceIn(0.0, 1.0).toFloat()
                    drawRect(Color.White.copy(alpha = a))
                }
                TransitionType.NONE -> Unit
            }
        } else {
            drawClip(state.clipIndex, state.localT, 1f, 1f, 0f, 0f)
        }

        if (p.adjustments.vignette > 0f) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = (p.adjustments.vignette / 100f) * 0.7f)
                    ),
                    center = Offset(w / 2f, h / 2f),
                    radius = (hypot(w, h) / 2f) * 1.15f
                )
            )
        }
    }
}
