package com.smart.mirror.app.domain.model

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** sRGB colour, components 0..255. */
data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    /** 0xAARRGGBB with full alpha; handy for `Color(argb)` on Android. */
    val argb: Long get() = (0xFFL shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
}

/**
 * Everything both UIs need to draw the screen-edge ring light identically.
 * Computed in shared code so Android and iOS render the same colour and thickness.
 */
data class RingLightAppearance(
    val visible: Boolean,
    val color: RgbColor,
    /** Opacity of the light band, 0..1. */
    val opacity: Float,
    /** Band thickness as a fraction of the screen's shorter side. */
    val edgeWidthFraction: Float,
) {
    companion object {
        private const val MIN_EDGE = 0.06f
        private const val MAX_EDGE = 0.16f

        fun from(settings: RingLightSettings): RingLightAppearance {
            val i = settings.intensity.coerceIn(RingLightSettings.MIN_INTENSITY, RingLightSettings.MAX_INTENSITY)
            return RingLightAppearance(
                visible = settings.enabled,
                color = kelvinToRgb(settings.warmth.kelvin),
                opacity = 0.55f + 0.45f * i,
                edgeWidthFraction = MIN_EDGE + (MAX_EDGE - MIN_EDGE) * i,
            )
        }

        /** Tanner Helland's black-body approximation (1000K..40000K). */
        fun kelvinToRgb(kelvin: Int): RgbColor {
            val t = kelvin.coerceIn(1000, 40000) / 100.0
            val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
            val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
            val b = when {
                t >= 66 -> 255.0
                t <= 19 -> 0.0
                else -> 138.5177312231 * ln(t - 10) - 305.0447927307
            }
            fun c(v: Double) = v.coerceIn(0.0, 255.0).roundToInt()
            return RgbColor(c(r), c(g), c(b))
        }
    }
}
