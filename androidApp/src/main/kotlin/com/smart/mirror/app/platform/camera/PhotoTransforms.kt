package com.smart.mirror.app.platform.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/** Bitmap operations for turning a camera still / frozen frame into exactly what the user saw. */
internal object PhotoTransforms {
    /** JPEG quality for saved photos (MVP-08.AC6 requires >= 90). */
    const val JPEG_QUALITY = 95

    /**
     * Rotates [source] upright by [rotationDegrees], then crops it like the viewfinder
     * ([viewWidth] x [viewHeight], FILL_CENTER, plus [digitalZoom]) and mirrors it if [mirror].
     * Recycles intermediate bitmaps. Returns a new bitmap (or [source] if nothing changed).
     */
    fun toDisplayedImage(
        source: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        digitalZoom: Float,
        mirror: Boolean,
    ): Bitmap {
        val rotated = rotationDegrees % 180 != 0
        val uprightW = if (rotated) source.height else source.width
        val uprightH = if (rotated) source.width else source.height
        val crop = centerCropFor(uprightW, uprightH, viewWidth, viewHeight, digitalZoom)

        // Map the crop (upright space) back to source space, then build one matrix:
        // rotate -> (optional) mirror. Bitmap.createBitmap applies it in a single pass.
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val srcRect = uprightCropToSource(crop, source.width, source.height, rotationDegrees)
        if (srcRect.left == 0 && srcRect.top == 0 && srcRect.width == source.width &&
            srcRect.height == source.height && matrix.isIdentity
        ) {
            return source
        }
        return Bitmap.createBitmap(source, srcRect.left, srcRect.top, srcRect.width, srcRect.height, matrix, true)
    }

    /** Crop the centre of an already display-oriented bitmap by [digitalZoom] and mirror it if asked. */
    fun cropAndMirror(source: Bitmap, digitalZoom: Float, mirror: Boolean): Bitmap =
        toDisplayedImage(source, 0, source.width, source.height, digitalZoom, mirror)

    fun encodeJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray =
        // Bitmap.compress writes no EXIF at all: no location, no device data (MVP-08.AC6).
        ByteArrayOutputStream(bitmap.byteCount / 8).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" }
            out.toByteArray()
        }

    /**
     * The crop is centred, so rotating it back to the source orientation just swaps the
     * dimensions for 90/270 degrees; it stays centred.
     */
    private fun uprightCropToSource(crop: CropRect, srcW: Int, srcH: Int, rotationDegrees: Int): CropRect {
        val swap = rotationDegrees % 180 != 0
        val w = (if (swap) crop.height else crop.width).coerceAtMost(srcW)
        val h = (if (swap) crop.width else crop.height).coerceAtMost(srcH)
        val left = (srcW - w) / 2
        val top = (srcH - h) / 2
        return CropRect(left, top, left + w, top + h)
    }
}
