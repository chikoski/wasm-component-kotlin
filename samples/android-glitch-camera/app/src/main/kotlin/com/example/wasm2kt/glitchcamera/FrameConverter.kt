package com.example.wasm2kt.glitchcamera

import androidx.camera.core.ImageProxy

object FrameConverter {
    /**
     * Extracts tightly packed upright RGBA_8888 bytes from a CameraX [ImageProxy] produced with
     * `OUTPUT_IMAGE_FORMAT_RGBA_8888`, applying the frame's rotation, optional horizontal
     * mirroring for the front camera, and a downscale to fit within
     * [maxLongSide] x [maxShortSide] (see [RgbaFrameTransform.convert]).
     */
    fun extractRgba(
        image: ImageProxy,
        mirrorHorizontally: Boolean,
        maxLongSide: Int,
        maxShortSide: Int,
        scratchHolder: RgbaBufferHolder,
    ): ExtractedFrame {
        val plane = image.planes[0]
        return RgbaFrameTransform.convert(
            source = plane.buffer,
            srcWidth = image.width,
            srcHeight = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            rotationDegrees = image.imageInfo.rotationDegrees,
            mirrorHorizontally = mirrorHorizontally,
            maxLongSide = maxLongSide,
            maxShortSide = maxShortSide,
            scratch = scratchHolder,
        )
    }
}
