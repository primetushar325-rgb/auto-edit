package com.autoedit.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.autoedit.engine.Adjustments
import com.autoedit.engine.EasingType
import com.autoedit.engine.FrameState
import com.autoedit.engine.ProjectModel
import com.autoedit.engine.TimelineMath
import com.autoedit.engine.TransitionType
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Software-canvas frame renderer used by the export pipeline.
 * Mirrors [PreviewRenderer] exactly (same math, same transitions).
 */
class ExportRenderer(
    private val w: Int,
    private val h: Int,
    private val adjustments: Adjustments,
    private val easing: EasingType
) {
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val matrix2 = Matrix()
    private val flashPaint = Paint().apply { color = Color.WHITE }
    private val vignettePaint = Paint()
    private var smallBmp: Bitmap? = null
    private var smallW = 0
    private var smallH = 0

    init {
        if (adjustments.needsColorFilter()) {
            bmpPaint.colorFilter = ColorMatrixColorFilter(adjustments.toColorMatrix())
        }
        if (adjustments.vignette > 0f) {
            val alpha = ((adjustments.vignette / 100f) * 180f).toInt().coerceIn(0, 255)
            vignettePaint.shader = RadialGradient(
                w / 2f,
                h / 2f,
                (hypot(w.toDouble(), h.toDouble()) / 2.0).toFloat() * 1.15f,
                Color.TRANSPARENT,
                Color.argb(alpha, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
    }

    fun render(canvas: Canvas, p: ProjectModel, state: FrameState, bitmaps: (Int) -> Bitmap?) {
        canvas.drawColor(Color.BLACK)
        val clipDur = p.effectiveClipDuration()
        val transition = p.transition
        if (state.prevIndex >= 0 && transition != TransitionType.NONE) {
            val b = TimelineMath.easing(state.blend, EasingType.EASE_IN_OUT).toFloat()
            val prev = state.prevIndex
            when (transition) {
                TransitionType.FADE ->
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, (b * 255).toInt(), 1f, 0f, 0f)
                TransitionType.CROSS_DISSOLVE -> {
                    drawClip(canvas, bitmaps, p, prev, clipDur, clipDur, 255, 1f, 0f, 0f)
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, (b * 255).toInt(), 1f, 0f, 0f)
                }
                TransitionType.BLUR -> {
                    drawClip(canvas, bitmaps, p, prev, clipDur, clipDur, 255, 1f + 0.06f * b, 0f, 0f)
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, (b * 255).toInt(), 1f, 0f, 0f)
                }
                TransitionType.SLIDE_LEFT -> {
                    drawClip(canvas, bitmaps, p, prev, clipDur, clipDur, 255, 1f, -b * w * 0.55f, 0f)
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, 255, 1f, (1f - b) * w, 0f)
                }
                TransitionType.SLIDE_RIGHT -> {
                    drawClip(canvas, bitmaps, p, prev, clipDur, clipDur, 255, 1f, b * w * 0.55f, 0f)
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, 255, 1f, -(1f - b) * w, 0f)
                }
                TransitionType.SLIDE_UP -> {
                    drawClip(canvas, bitmaps, p, prev, clipDur, clipDur, 255, 1f, 0f, -b * h * 0.55f)
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, 255, 1f, 0f, (1f - b) * h)
                }
                TransitionType.SLIDE_DOWN -> {
                    drawClip(canvas, bitmaps, p, prev, clipDur, clipDur, 255, 1f, 0f, b * h * 0.55f)
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, 255, 1f, 0f, -(1f - b) * h)
                }
                TransitionType.ZOOM -> {
                    drawClip(canvas, bitmaps, p, prev, clipDur, clipDur, 255, 1f, 0f, 0f)
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, (b * 255).toInt(), 1f + 0.12f * (1f - b), 0f, 0f)
                }
                TransitionType.FLASH -> {
                    drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, 255, 1f, 0f, 0f)
                    flashPaint.alpha = (sin(b * Math.PI) * 190f).toInt().coerceIn(0, 255)
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), flashPaint)
                }
                TransitionType.NONE -> Unit
            }
        } else {
            drawClip(canvas, bitmaps, p, state.clipIndex, state.localT, clipDur, 255, 1f, 0f, 0f)
        }
        if (adjustments.vignette > 0f) {
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignettePaint)
        }
    }

    private fun drawClip(
        canvas: Canvas,
        bitmaps: (Int) -> Bitmap?,
        p: ProjectModel,
        idx: Int,
        localT: Double,
        clipDur: Double,
        alpha: Int,
        extraScale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        if (alpha <= 0) return
        val bmp = bitmaps(idx) ?: return
        val motion = p.clips.getOrNull(idx)?.motion
        val tr = FrameMath.transformAt(motion, localT, clipDur, easing)
        val cover = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val s = cover * tr.scale * extraScale
        val dx = (w - bmp.width * s) / 2f + tr.xFrac * w + offsetX
        val dy = (h - bmp.height * s) / 2f + tr.yFrac * h + offsetY
        bmpPaint.alpha = alpha

        val blur = adjustments.blur
        if (blur > 0) {
            // cheap downscale-upscale blur, keeps the motion transform
            val factor = 2f + blur * 1.2f
            val sw = (bmp.width / factor).toInt().coerceAtLeast(16)
            val sh = (bmp.height / factor).toInt().coerceAtLeast(16)
            val sb = smallBitmapFor(sw, sh)
            val bc = Canvas(sb)
            bc.drawColor(Color.TRANSPARENT)
            matrix2.reset()
            matrix2.postScale(sw / bmp.width.toFloat(), sh / bmp.height.toFloat())
            bc.drawBitmap(bmp, matrix2, null)
            matrix.reset()
            matrix.postScale(s * bmp.width / sw.toFloat(), s * bmp.height / sh.toFloat())
            canvas.save()
            canvas.translate(dx, dy)
            canvas.concat(matrix)
            canvas.drawBitmap(sb, 0f, 0f, bmpPaint)
            canvas.restore()
        } else {
            matrix.reset()
            matrix.postScale(s, s)
            canvas.save()
            canvas.translate(dx, dy)
            canvas.concat(matrix)
            canvas.drawBitmap(bmp, 0f, 0f, bmpPaint)
            canvas.restore()
        }
    }

    private fun smallBitmapFor(sw: Int, sh: Int): Bitmap {
        val cur = smallBmp
        if (cur != null && !cur.isRecycled && cur.width == sw && cur.height == sh) return cur
        try { cur?.recycle() } catch (_: Exception) {}
        val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        smallBmp = b
        smallW = sw
        smallH = sh
        return b
    }

    fun release() {
        try { smallBmp?.recycle() } catch (_: Exception) {}
        smallBmp = null
        smallW = 0
        smallH = 0
    }
}
