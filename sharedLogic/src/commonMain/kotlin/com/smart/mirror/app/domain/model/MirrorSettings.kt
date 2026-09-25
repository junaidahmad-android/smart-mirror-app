package com.smart.mirror.app.domain.model

/** How the live preview (and the saved photo) is oriented. */
enum class MirrorMode {
    /** Horizontally flipped, like a real mirror (default). */
    MIRROR,

    /** Un-flipped "true view": how other people see you. */
    TRUE_VIEW,
}

/** Colour temperature presets for the on-screen ring light (ROADMAP MVP-05.AC3). */
enum class LightWarmth(val kelvin: Int) {
    COOL(7000),
    DAYLIGHT(5500),
    WARM(3200),
}

/** Quick intensity presets for the ring light; the value is a 0..1 fraction. */
enum class LightIntensityPreset(val intensity: Float) {
    LOW(0.25f),
    MEDIUM(0.5f),
    HIGH(1.0f),
}

data class RingLightSettings(
    val enabled: Boolean,
    /** 0.1..1.0, see [MIN_INTENSITY]. */
    val intensity: Float,
    val warmth: LightWarmth,
) {
    companion object {
        const val MIN_INTENSITY = 0.1f
        const val MAX_INTENSITY = 1.0f
        val DEFAULT = RingLightSettings(
            enabled = false,
            intensity = LightIntensityPreset.MEDIUM.intensity,
            warmth = LightWarmth.DAYLIGHT,
        )
    }
}

/** User preferences persisted across launches by [com.smart.mirror.app.data.settings.SettingsRepository]. */
data class MirrorSettings(
    val mirrorMode: MirrorMode,
    val ringLight: RingLightSettings,
    /** Boost screen brightness to max while the mirror is on screen. */
    val autoMaxBrightness: Boolean,
    /** Last used zoom ratio; clamped to the camera's range once it is known. */
    val zoomRatio: Float,
    /** One-time "Tap for controls" hint already shown (kept across "reset to defaults"). */
    val tapHintShown: Boolean,
) {
    companion object {
        const val MIN_ZOOM_RATIO = 0.5f
        const val MAX_ZOOM_RATIO = 10f
        val DEFAULT = MirrorSettings(
            mirrorMode = MirrorMode.MIRROR,
            ringLight = RingLightSettings.DEFAULT,
            autoMaxBrightness = true,
            zoomRatio = 1f,
            tapHintShown = false,
        )
    }
}
