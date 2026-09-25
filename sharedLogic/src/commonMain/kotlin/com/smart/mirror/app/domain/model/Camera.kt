package com.smart.mirror.app.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

enum class CameraLens { FRONT, BACK }

/** What the shared layer asks the platform camera to open. */
data class CameraConfig(
    val lens: CameraLens,
    val initialZoomRatio: Float,
)

/** Reported by the platform camera once the session is running. */
data class CameraCapabilities(
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val minExposureEv: Float,
    val maxExposureEv: Float,
    /** EV increment between supported exposure values (e.g. 1/3 or 1/6). 0 when unsupported. */
    val exposureStepEv: Float,
) {
    val supportsZoom: Boolean get() = maxZoomRatio > minZoomRatio
    val supportsExposureCompensation: Boolean get() = exposureStepEv > 0f && maxExposureEv > minExposureEv

    fun clampZoom(ratio: Float): Float = ratio.coerceIn(minZoomRatio, maxZoomRatio)

    /** Clamps to the supported range and snaps to the nearest supported step. */
    fun clampExposure(ev: Float): Float {
        if (!supportsExposureCompensation) return 0f
        val clamped = ev.coerceIn(minExposureEv, maxExposureEv)
        val snapped = (clamped / exposureStepEv).roundToInt() * exposureStepEv
        return snapped.coerceIn(minExposureEv, maxExposureEv).let { if (abs(it) < 1e-4f) 0f else it }
    }

    /** Quick-select zoom buttons (e.g. 0.5x, 1x, 2x, 3x) that fall inside the supported range. */
    val zoomPresets: List<Float>
        get() = STANDARD_ZOOM_PRESETS.filter { it in minZoomRatio..maxZoomRatio }

    companion object {
        val STANDARD_ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 3f, 5f)
    }
}

enum class CameraStatus { IDLE, STARTING, RUNNING, ERROR }

/** OS thermal pressure (Android PowerManager thermal status / iOS ProcessInfo.thermalState). */
enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL }

enum class CameraError {
    PERMISSION_DENIED,
    NO_FRONT_CAMERA,
    IN_USE_BY_ANOTHER_APP,
    DISCONNECTED,
    UNKNOWN,
}
