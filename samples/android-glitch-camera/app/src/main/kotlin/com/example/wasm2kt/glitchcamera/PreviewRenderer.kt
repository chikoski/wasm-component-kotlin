package com.example.wasm2kt.glitchcamera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws processed camera frames into the [Surface] of a Compose `AndroidExternalSurface` (a
 * SurfaceView: its own layer, composited by the system) from the analysis worker thread,
 * bypassing Compose.
 *
 * Every Bitmap the renderer draws is written and drawn on the same thread ([drawExecutor], the
 * analysis worker), so a single preview Bitmap can be reused: `unlockCanvasAndPost` on a hardware
 * canvas returns only after the render thread has synced the frame, which uploads the Bitmap, and
 * the surface's buffer queue keeps the posted buffer away from the next draw. The worker can
 * therefore overwrite the Bitmap for the next frame without the preview showing a half-written
 * one. (The MP4 recorder relies on the same property.)
 *
 * The surface is created, resized and destroyed on the main thread ([attach], [resize],
 * [detach]). [lock] makes [detach] wait for a draw in progress, as `SurfaceHolder.Callback`
 * requires: the surface must no longer be touched once `surfaceDestroyed` returns. The surface
 * belongs to the SurfaceView, so it is never released here.
 */
class PreviewRenderer(private val drawExecutor: Executor) {
    private val lock = Any()

    // Guarded by lock.
    private var surface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    /** Fills the area the frame does not cover. Set from the UI. */
    @Volatile
    var backgroundColor: Int = Color.BLACK

    // Only touched on the worker thread.
    private var lastFrame: Bitmap? = null
    private var fallback: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect()

    /** A surface was created. Call on the main thread. */
    fun attach(target: Surface, width: Int, height: Int) {
        synchronized(lock) {
            surface = target
            surfaceWidth = width
            surfaceHeight = height
        }
        requestRedraw()
    }

    /** [target] changed size. Call on the main thread. */
    fun resize(target: Surface, width: Int, height: Int) {
        synchronized(lock) {
            if (target !== surface) return
            surfaceWidth = width
            surfaceHeight = height
        }
        requestRedraw()
    }

    /** [target] is being destroyed. Call on the main thread; returns once no draw uses it. */
    fun detach(target: Surface) {
        synchronized(lock) {
            // A surface created for a new layout may already be attached.
            if (target === surface) surface = null
        }
    }

    /** Draws [bitmap] now. Call on the worker thread; [bitmap] may be overwritten once this returns. */
    fun render(bitmap: Bitmap) {
        lastFrame = bitmap
        draw(bitmap)
    }

    /** Shown until the first camera frame. Call on the worker thread. */
    fun setFallback(bitmap: Bitmap) {
        fallback = bitmap
        if (lastFrame == null) draw(bitmap)
    }

    private fun requestRedraw() {
        try {
            drawExecutor.execute { draw(lastFrame ?: fallback) }
        } catch (e: RejectedExecutionException) {
            // The worker was shut down: nothing more to draw.
        }
    }

    private fun draw(bitmap: Bitmap?) {
        synchronized(lock) { drawLocked(bitmap) }
    }

    private fun drawLocked(bitmap: Bitmap?) {
        val target = surface ?: return
        val width = surfaceWidth
        val height = surfaceHeight
        if (!target.isValid || width <= 0 || height <= 0) return
        val canvas = try {
            target.lockHardwareCanvas()
        } catch (e: IllegalStateException) {
            return
        } catch (e: IllegalArgumentException) {
            return
        }
        try {
            canvas.drawColor(backgroundColor)
            if (bitmap != null) {
                // Center-crop: fill the view, keeping the frame's aspect ratio.
                val scale = max(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
                val dstWidth = (bitmap.width * scale).roundToInt()
                val dstHeight = (bitmap.height * scale).roundToInt()
                val left = (width - dstWidth) / 2
                val top = (height - dstHeight) / 2
                dstRect.set(left, top, left + dstWidth, top + dstHeight)
                canvas.drawBitmap(bitmap, null, dstRect, paint)
            }
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
    }
}
