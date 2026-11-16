package com.example.wasm2kt.glitchcamera

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class Mp4VideoRecorder internal constructor(
    val outputFile: File,
    val width: Int,
    val height: Int,
    backendFactory: () -> Backend,
) : AutoCloseable {
    constructor(
        outputFile: File,
        width: Int,
        height: Int,
        bitRate: Int = 6_000_000,
        frameRate: Int = 30,
    ) : this(
        outputFile = outputFile,
        width = width,
        height = height,
        backendFactory = { createAndroidBackend(outputFile, width, height, bitRate, frameRate) },
    )

    internal class SampleInfo(
        var offset: Int = 0,
        var size: Int = 0,
        var presentationTimeUs: Long = 0L,
        var flags: Int = 0,
    )

    internal interface Backend {
        fun drawFrame(bitmap: Bitmap)
        fun signalEndOfInputStream()
        fun dequeueOutputBuffer(info: SampleInfo, timeoutUs: Long): Int
        fun getOutputFormat(): Any
        fun getOutputBuffer(index: Int): ByteBuffer?
        fun releaseOutputBuffer(index: Int)
        fun addTrack(format: Any): Int
        fun startMuxer()
        fun writeSampleData(trackIndex: Int, data: ByteBuffer, info: SampleInfo)
        fun stopEncoder()
        fun releaseEncoder()
        fun releaseSurface()
        fun stopMuxer()
        fun releaseMuxer()
    }

    private class AndroidBackend(
        private val encoder: MediaCodec,
        private val inputSurface: Surface,
        private val muxer: MediaMuxer,
    ) : Backend {
        private val bufferInfo = MediaCodec.BufferInfo()

        override fun drawFrame(bitmap: Bitmap) {
            val canvas = inputSurface.lockHardwareCanvas()
            try {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            } finally {
                inputSurface.unlockCanvasAndPost(canvas)
            }
        }

        override fun signalEndOfInputStream() {
            encoder.signalEndOfInputStream()
        }

        override fun dequeueOutputBuffer(info: SampleInfo, timeoutUs: Long): Int {
            val status = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status >= 0) {
                info.offset = bufferInfo.offset
                info.size = bufferInfo.size
                info.presentationTimeUs = bufferInfo.presentationTimeUs
                info.flags = bufferInfo.flags
            }
            return status
        }

        override fun getOutputFormat(): Any = encoder.outputFormat

        override fun getOutputBuffer(index: Int): ByteBuffer? = encoder.getOutputBuffer(index)

        override fun releaseOutputBuffer(index: Int) {
            encoder.releaseOutputBuffer(index, false)
        }

        override fun addTrack(format: Any): Int = muxer.addTrack(format as MediaFormat)

        override fun startMuxer() {
            muxer.start()
        }

        override fun writeSampleData(trackIndex: Int, data: ByteBuffer, info: SampleInfo) {
            bufferInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
            muxer.writeSampleData(trackIndex, data, bufferInfo)
        }

        override fun stopEncoder() {
            encoder.stop()
        }

        override fun releaseEncoder() {
            encoder.release()
        }

        override fun releaseSurface() {
            inputSurface.release()
        }

        override fun stopMuxer() {
            muxer.stop()
        }

        override fun releaseMuxer() {
            muxer.release()
        }
    }

    private val lock = Any()
    private val backend: Backend
    private val sampleInfo = SampleInfo()
    private var trackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private var closed: Boolean = false
    private var submittedFrames: Int = 0
    private var writtenSamples: Int = 0
    private var startNanoTime: Long = 0L
    private var lastPresentationTimeUs: Long = 0L

    init {
        backend = try {
            backendFactory()
        } catch (t: Throwable) {
            runCatching { outputFile.delete() }
            throw t
        }
    }

    fun submitFrame(bitmap: Bitmap) = submitFrameInternal {
        backend.drawFrame(bitmap)
    }

    // If draw() or drainEncoder() throws, submittedFrames is not incremented so finish()/close()
    // still releases resources and discards any unfinalized output file.
    internal fun submitFrameInternal(draw: () -> Unit) = synchronized(lock) {
        if (closed) return
        drainEncoder(endOfStream = false)
        draw()
        submittedFrames++
        drainEncoder(endOfStream = false)
    }

    fun finish(): File = synchronized(lock) {
        if (!closed) {
            closed = true
            var drainFailed = false
            var muxerStopFailed = false
            try {
                if (submittedFrames > 0) {
                    drainEncoder(endOfStream = true)
                }
            } catch (t: Throwable) {
                drainFailed = true
                throw t
            } finally {
                runCatching { backend.stopEncoder() }
                runCatching { backend.releaseEncoder() }
                runCatching { backend.releaseSurface() }
                if (muxerStarted && writtenSamples > 0) {
                    val stopRes = runCatching { backend.stopMuxer() }
                    if (stopRes.isFailure) {
                        muxerStopFailed = true
                    }
                }
                runCatching { backend.releaseMuxer() }
                if (drainFailed || muxerStopFailed || !muxerStarted || writtenSamples == 0) {
                    runCatching { outputFile.delete() }
                }
            }
        }
        return outputFile
    }

    override fun close() {
        finish()
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            backend.signalEndOfInputStream()
        }
        var emptyPollAttempts = 0
        while (true) {
            val status = backend.dequeueOutputBuffer(sampleInfo, if (endOfStream) EOS_POLL_TIMEOUT_US else 0L)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    emptyPollAttempts++
                    if (emptyPollAttempts >= MAX_EOS_POLL_ATTEMPTS) break
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        emptyPollAttempts = 0
                        trackIndex = backend.addTrack(backend.getOutputFormat())
                        backend.startMuxer()
                        muxerStarted = true
                    } else if (endOfStream) {
                        emptyPollAttempts++
                        if (emptyPollAttempts >= MAX_EOS_POLL_ATTEMPTS) break
                    }
                }
                status < 0 -> {
                    if (!endOfStream) break
                    emptyPollAttempts++
                    if (emptyPollAttempts >= MAX_EOS_POLL_ATTEMPTS) break
                }
                else -> {
                    emptyPollAttempts = 0
                    val encodedData = backend.getOutputBuffer(status)
                    if (encodedData != null && sampleInfo.size > 0) {
                        if ((sampleInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            sampleInfo.size = 0
                        }
                        if (sampleInfo.size > 0 && muxerStarted) {
                            val nowNanos = System.nanoTime()
                            if (startNanoTime == 0L) {
                                startNanoTime = nowNanos
                            }
                            val candidatePtsUs = (nowNanos - startNanoTime) / 1_000L
                            val ptsUs = maxOf(candidatePtsUs, lastPresentationTimeUs + 1_000L)
                            lastPresentationTimeUs = ptsUs
                            sampleInfo.presentationTimeUs = ptsUs
                            encodedData.position(sampleInfo.offset)
                            encodedData.limit(sampleInfo.offset + sampleInfo.size)
                            backend.writeSampleData(trackIndex, encodedData, sampleInfo)
                            writtenSamples++
                        }
                    }
                    backend.releaseOutputBuffer(status)
                    if ((sampleInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    companion object {
        internal const val EOS_POLL_TIMEOUT_US = 10_000L
        internal const val MAX_EOS_POLL_ATTEMPTS = 50

        private fun createAndroidBackend(
            outputFile: File,
            width: Int,
            height: Int,
            bitRate: Int,
            frameRate: Int,
        ): Backend {
            val alignedWidth = (width / 2) * 2
            val alignedHeight = (height / 2) * 2
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                alignedWidth,
                alignedHeight,
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            var encoder: MediaCodec? = null
            var encoderStarted = false
            var inputSurface: Surface? = null
            var muxer: MediaMuxer? = null
            try {
                val createdEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder = createdEncoder
                createdEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val createdSurface = createdEncoder.createInputSurface()
                inputSurface = createdSurface
                createdEncoder.start()
                encoderStarted = true
                val createdMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = createdMuxer
                return AndroidBackend(createdEncoder, createdSurface, createdMuxer)
            } catch (t: Throwable) {
                if (encoderStarted) {
                    runCatching { encoder?.stop() }
                }
                runCatching { encoder?.release() }
                runCatching { inputSurface?.release() }
                runCatching { muxer?.release() }
                runCatching { outputFile.delete() }
                throw t
            }
        }
    }
}
