package com.smart.mirror.app.ui.mirror

import kotlin.math.max

/** Where the control surface goes (MVP-10.AC3). */
enum class ControlsPlacement {
    /** Compact/medium portrait: a bottom bar with the capture button centred. */
    BOTTOM_BAR,

    /** Landscape or expanded width (tablets, unfolded foldables): a vertical rail on the end edge. */
    SIDE_RAIL,
}

/**
 * Layout decision for the mirror controls, derived from the window size class breakpoints
 * (WindowSizeClass: medium >= 600dp wide, expanded >= 840dp wide) and the window's aspect.
 *
 * @property spacious medium+ windows (tablets, foldables): larger spacing, and the adjustment
 *   panel is width-capped instead of stretching edge to edge.
 */
data class MirrorLayoutSpec(
    val placement: ControlsPlacement,
    val spacious: Boolean,
) {
    companion object {
        const val MEDIUM_WIDTH_DP = 600
        const val EXPANDED_WIDTH_DP = 840
        const val MEDIUM_HEIGHT_DP = 480

        fun from(widthAtLeastMedium: Boolean, widthAtLeastExpanded: Boolean, heightAtLeastMedium: Boolean, isLandscape: Boolean) =
            MirrorLayoutSpec(
                placement = if (isLandscape || widthAtLeastExpanded) ControlsPlacement.SIDE_RAIL else ControlsPlacement.BOTTOM_BAR,
                spacious = widthAtLeastMedium && heightAtLeastMedium,
            )

        /** Convenience for tests / previews from raw window dp. */
        fun forWindow(widthDp: Int, heightDp: Int) = from(
            widthAtLeastMedium = widthDp >= MEDIUM_WIDTH_DP,
            widthAtLeastExpanded = widthDp >= EXPANDED_WIDTH_DP,
            heightAtLeastMedium = heightDp >= MEDIUM_HEIGHT_DP,
            isLandscape = widthDp > heightDp,
        )
    }
}

/**
 * Bounds the frozen-image pan so the image always covers the viewport (MVP-07.AC3). The image
 * fills a `width x height` box and is scaled by [scale] around its centre; the translation may
 * move at most half the overflow in each direction.
 */
fun clampPan(offsetX: Float, offsetY: Float, scale: Float, width: Float, height: Float): Pair<Float, Float> {
    val maxX = max(0f, (scale - 1f) * width / 2f)
    val maxY = max(0f, (scale - 1f) * height / 2f)
    // "+ 0f" normalises -0f so a centred image reports exactly zero.
    return (offsetX.coerceIn(-maxX, maxX) + 0f) to (offsetY.coerceIn(-maxY, maxY) + 0f)
}
