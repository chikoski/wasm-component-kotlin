package com.example.wasm2kt.glitchcamera

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

class Mp4VideoRecorder(
    private val outputFile: File,
    val width: Int,
    val height: Int,
    private val bitRate: Int = 6_000_000,
    private val frameRate: Int = 30,
) : AutoCloseable {
    private val lock = Any()
    private val encoder: MediaCodec
    private val inputSurface: Surface
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private var closed: Boolean = false
    private var startNanoTime: Long = 0L
    private var lastPresentationTimeUs: Long = 0L

    init {
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
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun submitFrame(bitmap: Bitmap) = synchronized(lock) {
        if (closed) return
        drainEncoder(endOfStream = false)
        val canvas = inputSurface.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            inputSurface.unlockCanvasAndPost(canvas)
        }
        drainEncoder(endOfStream = false)
    }

    fun finish(): File = synchronized(lock) {
        if (!closed) {
            closed = true
            try {
                drainEncoder(endOfStream = true)
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                runCatching { inputSurface.release() }
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                }
                runCatching { muxer.release() }
            }
        }
        return outputFile
    }

    override fun close() {
        finish()
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                status >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(status)
                    if (encodedData != null && bufferInfo.size > 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            val nowNanos = System.nanoTime()
                            if (startNanoTime == 0L) {
                                startNanoTime = nowNanos
                            }
                            val candidatePtsUs = (nowNanos - startNanoTime) / 1_000L
                            val ptsUs = maxOf(candidatePtsUs, lastPresentationTimeUs + 1_000L)
                            lastPresentationTimeUs = ptsUs
                            bufferInfo.presentationTimeUs = ptsUs
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(status, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }
}
