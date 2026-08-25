package com.autoedit.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.autoedit.engine.Adjustments
import com.autoedit.engine.ClipRef
import com.autoedit.engine.ClipType
import com.autoedit.engine.EasingType
import com.autoedit.engine.FrameState
import com.autoedit.engine.ProjectModel
import com.autoedit.engine.TimelineMath
import com.autoedit.engine.TransitionType
import com.autoedit.media.ProjectStorage
import com.autoedit.media.YuvFrame
import com.autoedit.render.FrameMath
import com.autoedit.render.MotionTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * GPU frame renderer: OpenGL ES 2.0 over an EGL window surface backed by the
 * MediaCodec INPUT SURFACE (hardware H.264 encoder).
 *
 * Per-frame pipeline (no CPU YUV conversion, no per-frame Bitmap copies):
 *   - image clip:   texture (uploaded once per clip) --motion transform +
 *                   color/vignette--> encoder surface (1 pass)
 *   - transition:   prev -> FBO A, next -> FBO B, composite pass -> surface
 *   - video clip:   decoder YUV_420_888 planes -> 3 luminance textures,
 *                   YUV->RGB in the fragment shader
 *
 * Textures: LRU of a few clip textures; one bitmap is decoded at a time,
 * uploaded, and recycled - a 500-image project never holds more than a
 * handful of decoded images in RAM.
 */
class GpuFrameRenderer(
    private val ctx: Context,
    private val w: Int,
    private val h: Int,
    private val adjustments: Adjustments,
    private val easing: EasingType
) {
    class GpuException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private var display: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var initialized = false

    private var progClipRgba = 0
    private var progClipYuv = 0
    private var progComp = 0

    // Note: the platform stub's java.nio.FloatBuffer lacks allocateDirect, so the
    // direct buffer is created via ByteBuffer and reinterpreted.
    private val quad: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))
            position(0)
        }

    // ------------------------------------------------ texture cache (images)

    private class TexEntry(val id: Int, val w: Int, val h: Int)

    private val texCache = LinkedHashMap<String, TexEntry>()
    private val texCap = 3
    private val maxDim = (maxOf(w, h) * 1.5f).toInt()

    // video YUV textures (reallocated when the decoded size changes)
    private var yuvTexY = 0
    private var yuvTexU = 0
    private var yuvTexV = 0
    private var yuvW = 0
    private var yuvH = 0
    private var yuvScratch: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024)

    // FBOs for transitions (and the blur downscale)
    private var fboA = 0
    private var fboTexA = 0
    private var fboB = 0
    private var fboTexB = 0
    private var fboSmall = 0
    private var fboTexSmall = 0
    private val smallW = (w / 4).coerceAtLeast(2)
    private val smallH = (h / 4).coerceAtLeast(2)

    private val model = FloatArray(16)
    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f
    )

    fun init(encoderSurface: android.view.Surface) {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d == EGL14.EGL_NO_DISPLAY) throw GpuException("EGL: no display")
        display = d
        val ver = intArrayOf(1, 0)
        if (!EGL14.eglInitialize(d, ver, 0, ver, 1) && EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
            throw GpuException("EGL: initialize failed (error=${EGL14.eglGetError()})")
        }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(d, attrs, 0, configs, 0, 1, num, 0) || num[0] < 1) {
            throw GpuException("EGL: chooseConfig failed")
        }
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val c = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        if (c == EGL14.EGL_NO_CONTEXT) throw GpuException("EGL: createContext failed")
        eglContext = c
        val s = EGL14.eglCreateWindowSurface(d, configs[0], encoderSurface, null, 0)
        if (s == EGL14.EGL_NO_SURFACE) {
            throw GpuException("EGL: createWindowSurface failed on the codec input surface (error=${EGL14.eglGetError()})")
        }
        eglSurface = s
        if (!EGL14.eglMakeCurrent(d, s, s, c)) {
            throw GpuException("EGL: makeCurrent failed (error=${EGL14.eglGetError()})")
        }
        initGl()
        initialized = true
        Log.i(TAG, "EGL initialized: ES2 context + window surface on the encoder input surface")
    }

    private fun initGl() {
        progClipRgba = buildProgram(VERT, FRAG_CLIP_RGBA)
        progClipYuv = buildProgram(VERT, FRAG_CLIP_YUV)
        progComp = buildProgram(VERT, FRAG_COMP)
        checkGl("initGl")
    }

    // ============================================================ frame API

    /** Render one frame of the timeline into the encoder surface. */
    fun renderFrame(
        p: ProjectModel,
        state: FrameState,
        durations: List<Double>,
        videoFrame: (Int, Long) -> YuvFrame?
    ) {
        if (!initialized) throw GpuException("renderer not initialized")
        // A black video frequently means draws happen on the wrong/un-current EGL
        // context. Verify the context + surface are current before each draw.
        if (EGL14.eglGetCurrentContext() != eglContext ||
            EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglSurface
        ) {
            throw GpuException(
                "EGL context/surface not current before frame " +
                    "(ctx=${EGL14.eglGetCurrentContext()}, surf=${EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)})"
            )
        }
        val clipDur = durations.getOrNull(state.clipIndex) ?: p.effectiveClipDuration()
        val prevDur = if (state.prevIndex >= 0) durations.getOrNull(state.prevIndex) ?: clipDur else clipDur
        val transition = p.junctionTransitions[state.clipIndex] ?: p.transition
        val inTrans = state.prevIndex >= 0 && transition != TransitionType.NONE

        if (inTrans) {
            ensureFbos()
            bindFbo(fboA)
            drawClipToTarget(p, state.prevIndex, prevDur, prevDur, videoFrame, w, h)
            bindFbo(fboB)
            drawClipToTarget(p, state.clipIndex, state.localT, clipDur, videoFrame, w, h)
            bindFbo(0)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(progComp)
            bindQuad()
            GLES20.glUniformMatrix4fv(loc(progComp, "uModel"), 1, false, identity, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexA)
            GLES20.glUniform1i(loc(progComp, "uTexA"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexB)
            GLES20.glUniform1i(loc(progComp, "uTexB"), 1)
            GLES20.glUniform1i(loc(progComp, "uType"), transitionShaderId(transition))
            GLES20.glUniform1f(
                loc(progComp, "uBlend"),
                TimelineMath.easing(state.blend, EasingType.EASE_IN_OUT).toFloat()
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGl("composite")
        } else {
            bindFbo(0)
            GLES20.glViewport(0, 0, w, h)
            drawClipToTarget(p, state.clipIndex, state.localT, clipDur, videoFrame, w, h)
        }
    }

    /**
     * Draw one clip (motion + adjustments) into the CURRENTLY BOUND target
     * (window surface = fbo 0, or a transition FBO). If the user enabled a
     * blur adjustment, the clip is rendered through a 1/4-size FBO first.
     */
    private fun drawClipToTarget(
        p: ProjectModel,
        idx: Int,
        localT: Double,
        clipDur: Double,
        videoFrame: (Int, Long) -> YuvFrame?,
        targetW: Int,
        targetH: Int
    ) {
        val clip = p.clips.getOrNull(idx)
        if (clip == null) {
            clearBlack()
            return
        }
        if (adjustments.blur > 0) {
            ensureSmallFbo()
            // the current binding is our target (window surface OR a transition FBO)
            val fboArr = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fboArr, 0)
            val targetFbo = fboArr[0]
            bindFbo(fboSmall)
            GLES20.glViewport(0, 0, smallW, smallH)
            drawClipQuad(clip, idx, localT, clipDur, videoFrame, smallW, smallH)
            bindFbo(targetFbo)
            // upscale the blurred small texture back to the target
            GLES20.glViewport(0, 0, targetW, targetH)
            GLES20.glUseProgram(progClipRgba)
            bindQuad()
            GLES20.glUniformMatrix4fv(loc(progClipRgba, "uModel"), 1, false, identity, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexSmall)
            GLES20.glUniform1i(loc(progClipRgba, "uTex"), 0)
            applyAdjustUniforms(progClipRgba)
            GLES20.glUniform1f(loc(progClipRgba, "uAlpha"), 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGl("blur upscale")
            return
        }
        drawClipQuad(clip, idx, localT, clipDur, videoFrame, targetW, targetH)
    }

    /** Draw the quad for one clip into the current target. */
    private fun drawClipQuad(
        clip: ClipRef,
        idx: Int,
        localT: Double,
        clipDur: Double,
        videoFrame: (Int, Long) -> YuvFrame?,
        targetW: Int,
        targetH: Int
    ) {
        val prepared = prepareClip(clip, idx, localT, videoFrame)
        if (prepared == null) {
            clearBlack()
            return
        }
        val (tw, th) = prepared
        val motion = clip.resolvedMotion()
        val tr = FrameMath.transformAt(motion, localT, clipDur, easing)
        val cover = maxOf(targetW.toFloat() / tw, targetH.toFloat() / th)
        computeModel(targetW, targetH, tw, th, cover, tr, 1f)

        if (clip.type == ClipType.IMAGE) {
            val tex = preparedImageTex!!
            GLES20.glUseProgram(progClipRgba)
            bindQuad()
            GLES20.glUniformMatrix4fv(loc(progClipRgba, "uModel"), 1, false, model, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(loc(progClipRgba, "uTex"), 0)
            applyAdjustUniforms(progClipRgba)
            GLES20.glUniform1f(loc(progClipRgba, "uAlpha"), 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } else {
            GLES20.glUseProgram(progClipYuv)
            bindQuad()
            GLES20.glUniformMatrix4fv(loc(progClipYuv, "uModel"), 1, false, model, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTexY)
            GLES20.glUniform1i(loc(progClipYuv, "uY"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTexU)
            GLES20.glUniform1i(loc(progClipYuv, "uU"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTexV)
            GLES20.glUniform1i(loc(progClipYuv, "uV"), 2)
            applyAdjustUniforms(progClipYuv)
            GLES20.glUniform1f(loc(progClipYuv, "uAlpha"), 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        checkGl("clip")
    }

    private var preparedImageTex: Int? = null

    /**
     * Make a clip drawable: returns (texW, texH) or null (target cleared).
     * For image clips the texture id is stashed in [preparedImageTex].
     */
    private fun prepareClip(
        clip: ClipRef,
        idx: Int,
        localT: Double,
        videoFrame: (Int, Long) -> YuvFrame?
    ): Pair<Int, Int>? {
        preparedImageTex = null
        return when (clip.type) {
            ClipType.IMAGE -> {
                val e = imageTexture(clip) ?: return null
                preparedImageTex = e.id
                e.w to e.h
            }
            ClipType.VIDEO -> {
                val neededMs = clip.videoInMs + (localT * 1000.0).toLong()
                val yf = videoFrame(idx, neededMs) ?: return null
                ensureYuvTextures(yf.width, yf.height)
                uploadYuv(yf.image)
                yf.width to yf.height
            }
        }
    }

    private fun clearBlack() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun bindFbo(fbo: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
    }

    private fun loc(prog: Int, name: String): Int {
        val l = GLES20.glGetUniformLocation(prog, name)
        if (l < 0) throw GpuException("uniform not found: $name")
        return l
    }

    // ------------------------------------------------------------- geometry

    /**
     * Column-major model matrix mapping the unit quad (-1..1) into a
     * [targetW]x[targetH] frame.
     *
     * The unit quad already spans the full NDC range [-1,1], which the GL
     * viewport maps to the full [targetW]x[targetH] frame. To "cover" the
     * frame with an image of size [tw]x[th] (center-crop, matching the
     * Compose preview), we scale the unit quad by
     *
     *   cover * tw/targetW   (x)   and   cover * th/targetH  (y)
     *
     * where cover = max(targetW/tw, targetH/th). The negative Y accounts
     * for GL's bottom-up NDC vs. top-down image rows.
     *
     * (The previous code divided by targetW/2 in pixels, which shrank the
     * quad to ~0.001x - a sub-pixel, invisible quad - producing a black
     * export with working audio.)
     */
    private fun computeModel(
        targetW: Int,
        targetH: Int,
        tw: Int,
        th: Int,
        cover: Float,
        tr: MotionTransform,
        extraScale: Float
    ) {
        val m = cover * tr.scale * extraScale
        val sx = m * tw / targetW
        val sy = -m * th / targetH
        val tx = tr.xFrac * 2f
        val ty = -tr.yFrac * 2f
        model[0] = sx; model[1] = 0f; model[2] = 0f; model[3] = 0f
        model[4] = 0f; model[5] = sy; model[6] = 0f; model[7] = 0f
        model[8] = 0f; model[9] = 0f; model[10] = 1f; model[11] = 0f
        model[12] = tx; model[13] = ty; model[14] = 0f; model[15] = 1f
    }

    private fun transitionShaderId(t: TransitionType): Int = when (t) {
        TransitionType.FADE -> 0
        TransitionType.CROSS_DISSOLVE -> 1
        TransitionType.SLIDE_LEFT -> 2
        TransitionType.SLIDE_RIGHT -> 3
        TransitionType.SLIDE_UP -> 4
        TransitionType.SLIDE_DOWN -> 5
        TransitionType.ZOOM -> 6
        TransitionType.BLUR -> 7
        TransitionType.FLASH -> 8
        TransitionType.NONE -> 1
    }

    // ------------------------------------------------------------- textures

    private fun imageTexture(clip: ClipRef): TexEntry? {
        val key = clip.uri
        synchronized(texCache) {
            texCache[key]?.let { return it }
        }
        val bmp = decodeScaled(ctx, key, maxDim) ?: return null
        val id = glGenTexture()
        var ok = false
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bmp.width, bmp.height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp.width, bmp.height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, IntBuffer.wrap(pixels)
            )
            checkGl("texture upload")
            ok = true
        } catch (e: Exception) {
            Log.w(TAG, "texture upload failed for $key", e)
        } finally {
            try { bmp.recycle() } catch (_: Exception) {}
        }
        if (!ok) {
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            return null
        }
        val entry = TexEntry(id, bmp.width, bmp.height)
        Log.i(TAG, "clip texture decoded: $key -> ${bmp.width}x${bmp.height} (maxDim=$maxDim), GL tex=$id")
        synchronized(texCache) {
            texCache.remove(key)
            texCache[key] = entry
            while (texCache.size > texCap) {
                val oldest = texCache.keys.first()
                val evicted = texCache.remove(oldest)
                if (evicted != null) {
                    GLES20.glDeleteTextures(1, intArrayOf(evicted.id), 0)
                    Log.v(TAG, "evicted LRU texture for $oldest")
                }
            }
        }
        return entry
    }

    private fun decodeScaled(ctx: Context, uriOrPath: String, maxDim: Int): Bitmap? = try {
        val first = ProjectStorage.openInput(ctx, uriOrPath) ?: return null
        val size = run {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            first.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        } ?: return null
        var sample = 1
        val longest = maxOf(size.first, size.second)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ProjectStorage.openInput(ctx, uriOrPath)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (e: Exception) {
        Log.w(TAG, "image decode failed: $uriOrPath", e)
        null
    }

    private fun ensureYuvTextures(vw: Int, vh: Int) {
        if (yuvTexY != 0 && yuvW == vw && yuvH == vh) return
        if (yuvTexY != 0) {
            val old = intArrayOf(yuvTexY, yuvTexU, yuvTexV)
            GLES20.glDeleteTextures(old.size, old, 0)
        }
        yuvW = vw
        yuvH = vh
        yuvTexY = glGenTexture()
        yuvTexU = glGenTexture()
        yuvTexV = glGenTexture()
        for (t in intArrayOf(yuvTexY, yuvTexU, yuvTexV)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTexY)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, vw, vh, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null
        )
        val cw = vw / 2
        val ch = vh / 2
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTexU)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, cw, ch, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTexV)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, cw, ch, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null
        )
        checkGl("yuv textures")
    }

    private fun uploadYuv(img: Image) {
        uploadLuminance(yuvTexY, img.planes[0], yuvW, yuvH)
        uploadLuminance(yuvTexU, img.planes[1], yuvW / 2, yuvH / 2)
        uploadLuminance(yuvTexV, img.planes[2], yuvW / 2, yuvH / 2)
    }

    private fun uploadLuminance(tex: Int, plane: Image.Plane, pw: Int, ph: Int) {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (ps == 1 && rs == pw) {
            val dup = buf.duplicate()
            dup.position(0)
            dup.limit(ph * rs)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, pw, ph,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, dup
            )
        } else {
            val need = pw * ph
            if (yuvScratch.capacity() < need) yuvScratch = ByteBuffer.allocateDirect(need)
            val scratch = yuvScratch
            for (yy in 0 until ph) {
                val dup = buf.duplicate()
                dup.position(yy * rs)
                dup.limit(yy * rs + pw)
                scratch.put(dup)
            }
            scratch.position(0)
            scratch.limit(need)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, pw, ph,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, scratch
            )
        }
    }

    // ------------------------------------------------------------- FBOs

    private fun ensureFbos() {
        if (fboA != 0) return
        val (a, ta) = createFbo(w, h)
        fboA = a
        fboTexA = ta
        val (b, tb) = createFbo(w, h)
        fboB = b
        fboTexB = tb
        checkGl("fbo")
        Log.v(TAG, "transition FBOs created (${w}x$h)")
    }

    private fun ensureSmallFbo() {
        if (fboSmall != 0) return
        val (f, t) = createFbo(smallW, smallH)
        fboSmall = f
        fboTexSmall = t
    }

    private fun createFbo(fw: Int, fh: Int): Pair<Int, Int> {
        val tex = glGenTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val fbo = glGenFramebuffer()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw GpuException("FBO incomplete (status=$status)")
        }
        return fbo to tex
    }

    // ------------------------------------------------------- EGL surface ops

    /** Set the presentation timestamp (ns) for the NEXT swap. */
    fun setPresentationTimeNs(ns: Long) {
        if (!initialized) return
        val d = display ?: return
        val s = eglSurface ?: return
        EGLExt.eglPresentationTimeANDROID(d, s, ns)
    }

    /** Present the rendered frame to the encoder input surface. */
    fun swap() {
        val d = display ?: throw GpuException("not initialized")
        val s = eglSurface ?: throw GpuException("not initialized")
        if (!EGL14.eglSwapBuffers(d, s)) {
            throw GpuException("EGL swapBuffers failed (error=${EGL14.eglGetError()})")
        }
    }

    fun release() {
        if (!initialized) return
        try {
            val textures = ArrayList<Int>()
            synchronized(texCache) {
                textures.addAll(texCache.values.map { it.id })
                texCache.clear()
            }
            if (yuvTexY != 0) textures.addAll(listOf(yuvTexY, yuvTexU, yuvTexV))
            if (fboTexA != 0) textures.add(fboTexA)
            if (fboTexB != 0) textures.add(fboTexB)
            if (fboTexSmall != 0) textures.add(fboTexSmall)
            if (textures.isNotEmpty()) {
                val t = IntArray(textures.size) { textures[it] }
                GLES20.glDeleteTextures(t.size, t, 0)
            }
            val fbos = listOf(fboA, fboB, fboSmall).filter { it != 0 }
            if (fbos.isNotEmpty()) {
                val f = IntArray(fbos.size) { fbos[it] }
                GLES20.glDeleteFramebuffers(f.size, f, 0)
            }
            if (progClipRgba != 0) GLES20.glDeleteProgram(progClipRgba)
            if (progClipYuv != 0) GLES20.glDeleteProgram(progClipYuv)
            if (progComp != 0) GLES20.glDeleteProgram(progComp)
            val d = display
            val s = eglSurface
            val c = eglContext
            if (d != null && c != null) {
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (d != null && s != null) EGL14.eglDestroySurface(d, s)
            if (d != null && c != null) EGL14.eglDestroyContext(d, c)
            if (d != null) EGL14.eglTerminate(d)
            Log.i(TAG, "EGL released")
        } catch (e: Exception) {
            Log.w(TAG, "EGL release failed", e)
        } finally {
            display = null
            eglContext = null
            eglSurface = null
            initialized = false
        }
    }

    // --------------------------------------------------------------- GL util

    private fun glGenTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        return t[0]
    }

    private fun glGenFramebuffer(): Int {
        val t = IntArray(1)
        GLES20.glGenFramebuffers(1, t, 0)
        return t[0]
    }

    private fun bindQuad() {
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(0)
    }

    private fun applyAdjustUniforms(prog: Int) {
        val k = 1f + adjustments.contrast / 50f
        val b01 = (adjustments.brightness / 50f) * 40f / 255f
        val off = b01 + 0.5f * (1f - k)
        val s = 1f + adjustments.saturation / 100f
        val sat = floatArrayOf(
            0.213f + 0.787f * s, 0.213f - 0.213f * s, 0.213f - 0.213f * s,
            0.715f - 0.715f * s, 0.715f + 0.285f * s, 0.715f - 0.715f * s,
            0.072f - 0.072f * s, 0.072f - 0.072f * s, 0.072f + 0.928f * s
        )
        GLES20.glUniform3f(loc(prog, "uScale"), k, k, k)
        GLES20.glUniform3f(loc(prog, "uOffset"), off, off, off)
        GLES20.glUniformMatrix3fv(loc(prog, "uSat"), 1, false, sat, 0)
        GLES20.glUniform1f(loc(prog, "uVig"), adjustments.vignette / 100f)
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glBindAttribLocation(prog, 0, "aPos")
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            throw GpuException("shader link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            throw GpuException("shader compile failed: ${GLES20.glGetShaderInfoLog(s)}")
        }
        return s
    }

    private fun checkGl(what: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            throw GpuException("GL error 0x${Integer.toHexString(err)} in $what")
        }
    }

    // --------------------------------------------------------------- shaders

    companion object {
        private const val TAG = "AutoEditExport"

        private const val VERT = """
        uniform mat4 uModel;
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = uModel * vec4(aPos, 0.0, 1.0);
        }
    """

    private const val FRAG_CLIP_RGBA = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform vec3 uScale;
        uniform vec3 uOffset;
        uniform mat3 uSat;
        uniform float uVig;
        uniform float uAlpha;
        void main() {
            vec4 c = texture2D(uTex, vUv);
            c.rgb = c.rgb * uScale + uOffset;
            c.rgb = uSat * c.rgb;
            c.rgb = clamp(c.rgb, 0.0, 1.0);
            float d = distance(vUv, vec2(0.5));
            c.rgb *= (1.0 - uVig * smoothstep(0.45, 0.95, d));
            gl_FragColor = vec4(c.rgb, c.a * uAlpha);
        }
    """

    private const val FRAG_CLIP_YUV = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uY;
        uniform sampler2D uU;
        uniform sampler2D uV;
        uniform vec3 uScale;
        uniform vec3 uOffset;
        uniform mat3 uSat;
        uniform float uVig;
        uniform float uAlpha;
        void main() {
            float y = texture2D(uY, vUv).r;
            float u = texture2D(uU, vUv).r - 0.5;
            float v = texture2D(uV, vUv).r - 0.5;
            vec3 rgb;
            rgb.r = y + 1.402 * v;
            rgb.g = y - 0.344136 * v - 0.714136 * u;
            rgb.b = y + 1.772 * u;
            rgb = rgb * uScale + uOffset;
            rgb = uSat * rgb;
            rgb = clamp(rgb, 0.0, 1.0);
            float d = distance(vUv, vec2(0.5));
            rgb *= (1.0 - uVig * smoothstep(0.45, 0.95, d));
            gl_FragColor = vec4(rgb, uAlpha);
        }
    """

    private const val FRAG_COMP = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexA;
        uniform sampler2D uTexB;
        uniform int uType;
        uniform float uBlend;
        void main() {
            vec4 a = texture2D(uTexA, clamp(vUv, vec2(0.001), vec2(0.999)));
            vec4 b = texture2D(uTexB, clamp(vUv, vec2(0.001), vec2(0.999)));
            vec4 outC = vec4(0.0);
            if (uType == 0) {
                outC = b * uBlend;
            } else if (uType == 1) {
                outC = mix(a, b, uBlend);
            } else if (uType == 2 || uType == 3 || uType == 4 || uType == 5) {
                vec2 offA = vec2(0.0);
                vec2 offB = vec2(0.0);
                if (uType == 2) { offA = vec2(-uBlend * 0.55, 0.0); offB = vec2(1.0 - uBlend, 0.0); }
                if (uType == 3) { offA = vec2(uBlend * 0.55, 0.0); offB = vec2(-(1.0 - uBlend), 0.0); }
                if (uType == 4) { offA = vec2(0.0, -uBlend * 0.55); offB = vec2(0.0, 1.0 - uBlend); }
                if (uType == 5) { offA = vec2(0.0, uBlend * 0.55); offB = vec2(0.0, -(1.0 - uBlend)); }
                vec2 uvA = vUv - offA;
                vec2 uvB = vUv - offB;
                bool inA = uvA.x >= 0.0 && uvA.x <= 1.0 && uvA.y >= 0.0 && uvA.y <= 1.0;
                bool inB = uvB.x >= 0.0 && uvB.x <= 1.0 && uvB.y >= 0.0 && uvB.y <= 1.0;
                if (inA) outC = texture2D(uTexA, uvA);
                if (inB) outC = texture2D(uTexB, uvB);
            } else if (uType == 6) {
                float s = 1.0 + 0.12 * (1.0 - uBlend);
                vec2 uvB = (vUv - 0.5) / s + 0.5;
                bool inB = uvB.x >= 0.0 && uvB.x <= 1.0 && uvB.y >= 0.0 && uvB.y <= 1.0;
                outC = a;
                if (inB) outC = mix(a, texture2D(uTexB, uvB), uBlend);
            } else if (uType == 7) {
                float s = 1.0 + 0.06 * uBlend;
                vec2 uvA = (vUv - 0.5) / s + 0.5;
                bool inA = uvA.x >= 0.0 && uvA.x <= 1.0 && uvA.y >= 0.0 && uvA.y <= 1.0;
                outC = inA ? texture2D(uTexA, uvA) : vec4(0.0);
                outC = mix(outC, b, uBlend);
            } else if (uType == 8) {
                float f = sin(uBlend * 3.14159265) * 0.75;
                outC = clamp(b + vec4(1.0, 1.0, 1.0, 0.0) * f, 0.0, 1.0);
            }
            gl_FragColor = outC;
        }
    """
    }
}
