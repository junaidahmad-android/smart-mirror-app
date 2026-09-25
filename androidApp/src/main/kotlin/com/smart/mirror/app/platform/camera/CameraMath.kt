package com.smart.mirror.app.platform.camera

import com.smart.mirror.app.domain.model.CameraError
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Pure camera helpers (no Android types) so they can be unit tested on the JVM.
 * The CameraX adapter converts to/from Android/CameraX types at the edges.
 */

/** Integer rectangle: [left, top) .. [right, bottom). */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Largest rectangle with the viewfinder's aspect ratio centred in a `imageWidth x imageHeight`
 * image, then shrunk by [extraZoom] around the centre. This reproduces what `PreviewView`'s
 * FILL_CENTER shows (plus our digital zoom top-up), so the saved photo matches the screen.
 */
fun centerCropFor(
    imageWidth: Int,
    imageHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    extraZoom: Float = 1f,
): CropRect {
    require(imageWidth > 0 && imageHeight > 0) { "empty image" }
    var cropW = imageWidth.toFloat()
    var cropH = imageHeight.toFloat()
    if (viewWidth > 0 && viewHeight > 0) {
        val viewAspect = viewWidth.toFloat() / viewHeight
        val imageAspect = imageWidth.toFloat() / imageHeight
        if (imageAspect > viewAspect) cropW = imageHeight * viewAspect else cropH = imageWidth / viewAspect
    }
    val zoom = if (extraZoom.isFinite() && extraZoom > 1f) extraZoom else 1f
    val w = (cropW / zoom).roundToInt().coerceIn(1, imageWidth)
    val h = (cropH / zoom).roundToInt().coerceIn(1, imageHeight)
    val left = (imageWidth - w) / 2
    val top = (imageHeight - h) / 2
    return CropRect(left, top, left + w, top + h)
}

/**
 * Splits a requested zoom into what the camera hardware does ([hardware]) and the remaining
 * factor we apply as a view/crop scale ([digital]) when the camera's own maximum is below
 * [CameraZoom.ADVERTISED_MIN_MAX_ZOOM] (MVP-03.AC1 promises at least 5x).
 */
data class ZoomSplit(val hardware: Float, val digital: Float)

object CameraZoom {
    const val ADVERTISED_MIN_MAX_ZOOM = 5f

    fun advertisedMax(hardwareMax: Float): Float = max(hardwareMax, ADVERTISED_MIN_MAX_ZOOM)

    fun split(requested: Float, hardwareMin: Float, hardwareMax: Float): ZoomSplit {
        val hw = requested.coerceIn(hardwareMin, max(hardwareMin, hardwareMax))
        val digital = if (hw > 0f) (requested / hw).coerceAtLeast(1f) else 1f
        return ZoomSplit(hw, digital)
    }
}

/** EV value -> CameraX exposure compensation index, clamped to the supported index range. */
fun exposureIndexFor(ev: Float, stepEv: Float, minIndex: Int, maxIndex: Int): Int {
    if (stepEv <= 0f || !ev.isFinite()) return 0
    return (ev / stepEv).roundToInt().coerceIn(minIndex, maxIndex)
}

/**
 * Picks the AE target fps range for a max frame rate: the highest upper bound not above [maxFps],
 * and among those the lowest lower bound (variable frame rate lets AE brighten dim scenes, which
 * matters for a mirror used in bad light). Null when nothing fits (keep the camera default).
 */
fun chooseFpsRange(available: List<IntRange>, maxFps: Int): IntRange? =
    available
        .filter { it.last in 1..maxFps }
        .sortedWith(compareByDescending<IntRange> { it.last }.thenBy { it.first })
        .firstOrNull()

/** CameraX `CameraState.ERROR_*` codes (mirrored here so the mapping is JVM-testable). */
object CameraStateErrorCodes {
    const val MAX_CAMERAS_IN_USE = 1
    const val CAMERA_IN_USE = 2
    const val OTHER_RECOVERABLE_ERROR = 3
    const val STREAM_CONFIG = 4
    const val CAMERA_DISABLED = 5
    const val CAMERA_FATAL_ERROR = 6
    const val DO_NOT_DISTURB_MODE_ENABLED = 7
    const val CAMERA_REMOVED = 8
}

/** Maps a CameraX camera-state error code to the shared [CameraError]. */
fun cameraErrorFor(code: Int): CameraError = when (code) {
    CameraStateErrorCodes.MAX_CAMERAS_IN_USE,
    CameraStateErrorCodes.CAMERA_IN_USE,
    CameraStateErrorCodes.CAMERA_DISABLED, // device policy / privacy toggle: "unavailable" state
    CameraStateErrorCodes.DO_NOT_DISTURB_MODE_ENABLED,
    -> CameraError.IN_USE_BY_ANOTHER_APP
    CameraStateErrorCodes.OTHER_RECOVERABLE_ERROR,
    CameraStateErrorCodes.CAMERA_REMOVED,
    -> CameraError.DISCONNECTED
    else -> CameraError.UNKNOWN
}

/** True when two zoom ratios are practically the same (avoids redundant camera commands). */
internal fun sameZoom(a: Float, b: Float): Boolean = abs(a - b) < 1e-3f

internal fun Float.atMost(limit: Float) = min(this, limit)
