package com.smart.mirror.app.data.settings

import com.smart.mirror.app.data.settings.KeyValueSettingsRepository.Companion.KEY_MIRROR_MODE
import com.smart.mirror.app.data.settings.KeyValueSettingsRepository.Companion.KEY_RING_INTENSITY
import com.smart.mirror.app.data.settings.KeyValueSettingsRepository.Companion.KEY_RING_WARMTH
import com.smart.mirror.app.data.settings.KeyValueSettingsRepository.Companion.KEY_SCHEMA_VERSION
import com.smart.mirror.app.data.settings.KeyValueSettingsRepository.Companion.KEY_ZOOM
import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.RingLightSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyValueSettingsRepositoryTest {
    private val store = InMemoryKeyValueStore()

    @Test
    fun emptyStoreYieldsDefaultsWithoutWriting() {
        assertEquals(MirrorSettings.DEFAULT, KeyValueSettingsRepository(store).settings.value)
        assertNull(store.getInt(KEY_SCHEMA_VERSION))
    }

    @Test
    fun updatesArePublishedAndSurviveRecreation() {
        val repo = KeyValueSettingsRepository(store)
        val expected = MirrorSettings(
            mirrorMode = MirrorMode.TRUE_VIEW,
            ringLight = RingLightSettings(enabled = true, intensity = 0.35f, warmth = LightWarmth.WARM),
            autoMaxBrightness = false,
            zoomRatio = 2f,
            tapHintShown = true,
        )
        repo.update { expected }
        assertEquals(expected, repo.settings.value)
        assertEquals(expected, KeyValueSettingsRepository(store).settings.value)
        assertEquals(KeyValueSettingsRepository.SCHEMA_VERSION, store.getInt(KEY_SCHEMA_VERSION))
    }

    @Test
    fun corruptOrOutOfRangeValuesFallBackSafely() {
        store.putString(KEY_MIRROR_MODE, "SIDEWAYS")
        store.putString(KEY_RING_WARMTH, "PLASMA")
        store.putFloat(KEY_RING_INTENSITY, 42f)
        store.putFloat(KEY_ZOOM, Float.NaN)
        val s = KeyValueSettingsRepository(store).settings.value
        assertEquals(MirrorMode.MIRROR, s.mirrorMode)
        assertEquals(LightWarmth.DAYLIGHT, s.ringLight.warmth)
        assertEquals(RingLightSettings.MAX_INTENSITY, s.ringLight.intensity)
        assertEquals(1f, s.zoomRatio)
    }

    @Test
    fun updatesAreSanitized() {
        val repo = KeyValueSettingsRepository(store)
        repo.update { it.copy(zoomRatio = 100f, ringLight = it.ringLight.copy(intensity = -1f)) }
        assertEquals(MirrorSettings.MAX_ZOOM_RATIO, repo.settings.value.zoomRatio)
        assertEquals(RingLightSettings.MIN_INTENSITY, repo.settings.value.ringLight.intensity)
    }
}
