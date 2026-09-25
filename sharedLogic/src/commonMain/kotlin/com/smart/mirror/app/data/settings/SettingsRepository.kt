package com.smart.mirror.app.data.settings

import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.RingLightSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SettingsRepository {
    val settings: StateFlow<MirrorSettings>
    fun update(transform: (MirrorSettings) -> MirrorSettings)
}

/**
 * [SettingsRepository] backed by a [KeyValueStore]. Each field is stored under its own key so
 * adding fields never invalidates old data; unknown/corrupt values fall back to defaults.
 */
class KeyValueSettingsRepository(private val store: KeyValueStore) : SettingsRepository {

    // Loaded lazily (thread-safe) so app start never does storage I/O on the main thread:
    // SharedGraph calls preload() on a background dispatcher at construction.
    private val state: MutableStateFlow<MirrorSettings> by lazy { MutableStateFlow(load()) }
    private val readOnly: StateFlow<MirrorSettings> by lazy { state.asStateFlow() }
    override val settings: StateFlow<MirrorSettings> get() = readOnly

    /** Forces the (blocking) load; call off the main thread. */
    fun preload() {
        state
    }

    override fun update(transform: (MirrorSettings) -> MirrorSettings) {
        val next = sanitize(transform(state.value))
        if (next == state.value) return
        state.value = next
        save(next)
    }

    private fun load(): MirrorSettings {
        migrateIfNeeded()
        val d = MirrorSettings.DEFAULT
        return sanitize(
            MirrorSettings(
                mirrorMode = store.getString(KEY_MIRROR_MODE).toEnumOrNull<MirrorMode>() ?: d.mirrorMode,
                ringLight = RingLightSettings(
                    enabled = store.getBoolean(KEY_RING_ENABLED) ?: d.ringLight.enabled,
                    intensity = store.getFloat(KEY_RING_INTENSITY) ?: d.ringLight.intensity,
                    warmth = store.getString(KEY_RING_WARMTH).toEnumOrNull<LightWarmth>() ?: d.ringLight.warmth,
                ),
                autoMaxBrightness = store.getBoolean(KEY_AUTO_BRIGHTNESS) ?: d.autoMaxBrightness,
                zoomRatio = store.getFloat(KEY_ZOOM) ?: d.zoomRatio,
                tapHintShown = store.getBoolean(KEY_TAP_HINT_SHOWN) ?: d.tapHintShown,
            ),
        )
    }

    private fun save(s: MirrorSettings) {
        store.putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        store.putString(KEY_MIRROR_MODE, s.mirrorMode.name)
        store.putBoolean(KEY_RING_ENABLED, s.ringLight.enabled)
        store.putFloat(KEY_RING_INTENSITY, s.ringLight.intensity)
        store.putString(KEY_RING_WARMTH, s.ringLight.warmth.name)
        store.putBoolean(KEY_AUTO_BRIGHTNESS, s.autoMaxBrightness)
        store.putFloat(KEY_ZOOM, s.zoomRatio)
        store.putBoolean(KEY_TAP_HINT_SHOWN, s.tapHintShown)
    }

    /** Hook for future schema migrations (rename keys, convert units...). */
    private fun migrateIfNeeded() {
        val stored = store.getInt(KEY_SCHEMA_VERSION) ?: return
        if (stored > SCHEMA_VERSION) return // downgrade: read what we understand
    }

    private fun sanitize(s: MirrorSettings): MirrorSettings = s.copy(
        ringLight = s.ringLight.copy(
            intensity = s.ringLight.intensity.finiteOr(RingLightSettings.DEFAULT.intensity)
                .coerceIn(RingLightSettings.MIN_INTENSITY, RingLightSettings.MAX_INTENSITY),
        ),
        zoomRatio = s.zoomRatio.finiteOr(1f).coerceIn(MirrorSettings.MIN_ZOOM_RATIO, MirrorSettings.MAX_ZOOM_RATIO),
    )

    private fun Float.finiteOr(fallback: Float) = if (isNaN() || isInfinite()) fallback else this

    private inline fun <reified E : Enum<E>> String?.toEnumOrNull(): E? =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val PREFIX = "mirror."
        internal const val KEY_SCHEMA_VERSION = PREFIX + "schemaVersion"
        internal const val KEY_MIRROR_MODE = PREFIX + "mirrorMode"
        internal const val KEY_RING_ENABLED = PREFIX + "ringLight.enabled"
        internal const val KEY_RING_INTENSITY = PREFIX + "ringLight.intensity"
        internal const val KEY_RING_WARMTH = PREFIX + "ringLight.warmth"
        internal const val KEY_AUTO_BRIGHTNESS = PREFIX + "autoMaxBrightness"
        internal const val KEY_ZOOM = PREFIX + "zoomRatio"
        internal const val KEY_TAP_HINT_SHOWN = PREFIX + "tapHintShown"
    }
}
