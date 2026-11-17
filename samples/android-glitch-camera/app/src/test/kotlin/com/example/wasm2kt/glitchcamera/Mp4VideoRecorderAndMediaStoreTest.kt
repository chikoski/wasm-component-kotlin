package com.example.wasm2kt.glitchcamera

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class Mp4VideoRecorderAndMediaStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeBackend(
        private val dequeueHandler: (info: Mp4VideoRecorder.SampleInfo, endOfStream: Boolean, pollIndex: Int) -> Int,
        private val onStopMuxer: (() -> Unit)? = null,
    ) : Mp4VideoRecorder.Backend {
        var drawnFrames = 0
        var eosSignals = 0
        var dequeueCalls = 0
        var eosPollCalls = 0
        var writtenSamples = 0
        var stoppedEncoder = false
        var releasedEncoder = false
        var releasedSurface = false
        var startedMuxer = false
        var stoppedMuxer = false
        var releasedMuxer = false
        private val outputBuf = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65))

        override fun drawFrame(bitmap: android.graphics.Bitmap) {
            drawnFrames++
        }

        override fun signalEndOfInputStream() {
            eosSignals++
        }

        override fun dequeueOutputBuffer(info: Mp4VideoRecorder.SampleInfo, timeoutUs: Long): Int {
            dequeueCalls++
            val isEos = timeoutUs > 0L
            val pollIdx = if (isEos) eosPollCalls++ else 0
            return dequeueHandler(info, isEos, pollIdx)
        }

        override fun getOutputFormat(): Any = "fake-avc-format"

        override fun getOutputBuffer(index: Int): ByteBuffer = outputBuf.duplicate()

        override fun releaseOutputBuffer(index: Int) = Unit

        override fun addTrack(format: Any): Int = 0

        override fun startMuxer() {
            startedMuxer = true
        }

        override fun writeSampleData(trackIndex: Int, data: ByteBuffer, info: Mp4VideoRecorder.SampleInfo) {
            writtenSamples++
        }

        override fun stopEncoder() {
            stoppedEncoder = true
        }

        override fun releaseEncoder() {
            releasedEncoder = true
        }

        override fun releaseSurface() {
            releasedSurface = true
        }

        override fun stopMuxer() {
            stoppedMuxer = true
            onStopMuxer?.invoke()
        }

        override fun releaseMuxer() {
            releasedMuxer = true
        }
    }

    @Test
    fun zeroFrameFinishSkipsSignalEndOfInputStreamReleasesAndDeletesTempFile() {
        val tempFile = tempFolder.newFile("zero_frames.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val backend = FakeBackend(
            dequeueHandler = { _, _, _ -> MediaCodec.INFO_TRY_AGAIN_LATER },
        )
        val recorder = Mp4VideoRecorder(tempFile, 640, 480) { backend }

        val returned = recorder.finish()
        assertEquals(tempFile, returned)
        assertEquals(0, backend.eosSignals)
        assertEquals(0, backend.dequeueCalls)
        assertTrue(backend.stoppedEncoder)
        assertTrue(backend.releasedEncoder)
        assertTrue(backend.releasedSurface)
        assertFalse(backend.startedMuxer)
        assertFalse(backend.stoppedMuxer)
        assertTrue(backend.releasedMuxer)
        assertFalse(tempFile.exists())
    }

    @Test
    fun missingEndOfStreamTerminatesAtAttemptLimitAndDeletesEmptyFile() {
        val tempFile = tempFolder.newFile("missing_eos.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val backend = FakeBackend(
            dequeueHandler = { _, _, _ -> MediaCodec.INFO_TRY_AGAIN_LATER },
        )
        val recorder = Mp4VideoRecorder(tempFile, 640, 480) { backend }
        recorder.submitFrameInternal { }

        recorder.finish()
        assertEquals(1, backend.eosSignals)
        assertEquals(Mp4VideoRecorder.MAX_EOS_POLL_ATTEMPTS, backend.eosPollCalls)
        assertTrue(backend.stoppedEncoder)
        assertTrue(backend.releasedEncoder)
        assertTrue(backend.releasedSurface)
        assertFalse(backend.stoppedMuxer)
        assertTrue(backend.releasedMuxer)
        assertFalse(tempFile.exists())
    }

    @Test
    fun missingEndOfStreamWithWrittenSampleStopsMuxerAndKeepsOutputFile() {
        val tempFile = tempFolder.newFile("partial_missing_eos.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        var step = 0
        val singleSampleBackend = FakeBackend(
            dequeueHandler = { info, endOfStream, _ ->
                if (!endOfStream) {
                    MediaCodec.INFO_TRY_AGAIN_LATER
                } else {
                    when (step++) {
                        0 -> MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                        1 -> {
                            info.offset = 0
                            info.size = 5
                            info.flags = 0
                            0
                        }
                        else -> MediaCodec.INFO_TRY_AGAIN_LATER
                    }
                }
            },
        )
        val recorder = Mp4VideoRecorder(tempFile, 640, 480) { singleSampleBackend }
        recorder.submitFrameInternal { }
        recorder.finish()

        assertEquals(1, singleSampleBackend.writtenSamples)
        assertTrue(singleSampleBackend.startedMuxer)
        assertTrue(singleSampleBackend.stoppedMuxer)
        assertTrue(singleSampleBackend.releasedMuxer)
        // After writing format + sample (poll 0, 1), 50 consecutive TRY_AGAIN_LATER terminate the loop.
        assertEquals(2 + Mp4VideoRecorder.MAX_EOS_POLL_ATTEMPTS, singleSampleBackend.eosPollCalls)
        assertTrue(tempFile.exists())
    }

    @Test
    fun normalFrameFollowedByEosWritesSampleAndPreservesFile() {
        val tempFile = tempFolder.newFile("valid.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        var phase = 0
        val backend = FakeBackend(
            dequeueHandler = { info, endOfStream, _ ->
                if (!endOfStream) {
                    MediaCodec.INFO_TRY_AGAIN_LATER
                } else {
                    when (phase++) {
                        0 -> MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                        1 -> {
                            info.offset = 0
                            info.size = 5
                            info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            0
                        }
                        else -> MediaCodec.INFO_TRY_AGAIN_LATER
                    }
                }
            },
        )
        val recorder = Mp4VideoRecorder(tempFile, 640, 480) { backend }
        recorder.submitFrameInternal { }
        val resultFile = recorder.finish()

        assertEquals(tempFile, resultFile)
        assertEquals(1, backend.writtenSamples)
        assertTrue(backend.startedMuxer)
        assertTrue(backend.stoppedMuxer)
        assertTrue(backend.releasedMuxer)
        assertTrue(tempFile.exists())
    }

    @Test
    fun constructorFailureDeletesOutputFile() {
        val tempFile = tempFolder.newFile("init_fail.mp4").apply {
            writeBytes(byteArrayOf(9, 9))
        }
        val err = runCatching {
            Mp4VideoRecorder(tempFile, 640, 480) {
                error("codec unavailable")
            }
        }.exceptionOrNull()

        assertTrue(err is IllegalStateException)
        assertFalse(tempFile.exists())
    }

    @Test
    fun mediaStoreHelperCleansUpPendingRowAndTempFileOnWriteFailure() {
        val tempFile = tempFolder.newFile("failed_upload.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val deleted = mutableListOf<String>()

        val res = MediaStoreHelper.writePendingMediaEntry(
            insert = { "content://media/video/42" },
            writeAndPublish = { error("disk full") },
            deleteEntry = { uri -> deleted += uri },
            cleanupTempFile = { tempFile.delete() },
        )

        assertTrue(res.isFailure)
        assertEquals(listOf("content://media/video/42"), deleted)
        assertFalse(tempFile.exists())
    }

    @Test
    fun mediaStoreHelperCleansUpTempFileOnInsertFailureAndSuccess() {
        val insertFailTemp = tempFolder.newFile("insert_fail.mp4").apply {
            writeBytes(byteArrayOf(1, 2))
        }
        val deletedOnInsertFail = mutableListOf<String>()
        val failRes = MediaStoreHelper.writePendingMediaEntry<String>(
            insert = { null },
            writeAndPublish = { },
            deleteEntry = { uri -> deletedOnInsertFail.add(uri) },
            cleanupTempFile = { insertFailTemp.delete() },
        )
        assertTrue(failRes.isFailure)
        assertTrue(deletedOnInsertFail.isEmpty())
        assertFalse(insertFailTemp.exists())

        val okTemp = tempFolder.newFile("ok.mp4").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val okRes = MediaStoreHelper.writePendingMediaEntry<String>(
            insert = { "content://media/video/99" },
            writeAndPublish = { },
            deleteEntry = { error("must not delete on success") },
            cleanupTempFile = { okTemp.delete() },
        )
        assertEquals("content://media/video/99", okRes.getOrThrow())
        assertFalse(okTemp.exists())
    }

    @Test
    fun muxerStopFailureDeletesUnfinalizedOutputFile() {
        val tempFile = tempFolder.newFile("stop_fail.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        var phase = 0
        val backend = FakeBackend(
            dequeueHandler = { info, endOfStream, _ ->
                if (!endOfStream) {
                    MediaCodec.INFO_TRY_AGAIN_LATER
                } else {
                    when (phase++) {
                        0 -> MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                        1 -> {
                            info.offset = 0
                            info.size = 5
                            info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            0
                        }
                        else -> MediaCodec.INFO_TRY_AGAIN_LATER
                    }
                }
            },
            onStopMuxer = { throw IllegalStateException("Failed to stop muxer") },
        )
        val recorder = Mp4VideoRecorder(tempFile, 640, 480) { backend }
        recorder.submitFrameInternal { }
        recorder.finish()

        assertTrue(backend.stoppedMuxer)
        assertTrue(backend.releasedMuxer)
        assertFalse(tempFile.exists())
    }
}
