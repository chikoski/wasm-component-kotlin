package com.example.wasm2kt.glitchcamera

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Names of the trace sections around each stage of the frame pipeline. They show up in Perfetto
 * and are measured by `FramePipelineBenchmark` (the `:baselineprofile` module), which repeats
 * these names, so keep both in sync.
 */
object FrameTrace {
    /*
     * The engine stage awaits the glitch engine, whose calls suspend and may resume on another
     * thread, so its sections ([ANALYZE], [FROM_PIXELS] ... [RENDER_RGBA], [SYNTHETIC_RUN]) are
     * async sections ([asyncSection]); the others are ordinary thread sections ([section]).
     */

    /** Conversion stage: one camera (or synthetic) frame, rotated, mirrored and downscaled. */
    const val CONVERT = "glitch.convert"

    /** Engine stage: one converted frame, from the engine calls to the hand-off. */
    const val ANALYZE = "glitch.analyze"
    const val FROM_PIXELS = "glitch.fromPixels"
    const val RELOAD_PIXELS = "glitch.reloadPixels"
    const val SET_RECIPE = "glitch.setRecipe"
    const val APPLY = "glitch.apply"
    const val RENDER_RGBA = "glitch.renderRgba"
    const val PROCESS_FRAME = "glitch.processFrame"

    /** Presentation thread: one frame, from the Bitmap copy to the encoder. */
    const val PRESENT = "glitch.present"
    const val COPY_TO_BITMAP = "glitch.copyToBitmap"
    const val DRAW = "glitch.draw"
    const val ENCODE = "glitch.encode"

    /** Engine stage: a whole synthetic run (see `GlitchViewModel.runSyntheticFrames`). */
    const val SYNTHETIC_RUN = "glitch.syntheticRun"

    /**
     * False in host JVM unit tests, where `Build.VERSION.SDK_INT` is 0 and `android.os.Trace` is a
     * stub that throws. (Section tracing exists since API 18; the app's minSdk is 26.)
     */
    @PublishedApi
    internal val enabled: Boolean = Build.VERSION.SDK_INT >= 18

    /** Async sections exist since API 29; below that they are not recorded. */
    @PublishedApi
    internal val asyncEnabled: Boolean = Build.VERSION.SDK_INT >= 29

    @PublishedApi
    internal val nextCookie = AtomicInteger()

    /** Runs [block] inside the trace section [name]. Sections nest and must end on the same thread. */
    inline fun <T> section(name: String, block: () -> T): T {
        val on = enabled
        if (on) Trace.beginSection(name)
        try {
            return block()
        } finally {
            if (on) Trace.endSection()
        }
    }

    /**
     * Runs [block] inside the async trace section [name], which may start and end on different
     * threads: use it around suspending calls. Recorded on API 29 and later.
     */
    inline fun <T> asyncSection(name: String, block: () -> T): T {
        val on = asyncEnabled
        val cookie = if (on) nextCookie.incrementAndGet() else 0
        if (on) Trace.beginAsyncSection(name, cookie)
        try {
            return block()
        } finally {
            if (on) Trace.endAsyncSection(name, cookie)
        }
    }
}
