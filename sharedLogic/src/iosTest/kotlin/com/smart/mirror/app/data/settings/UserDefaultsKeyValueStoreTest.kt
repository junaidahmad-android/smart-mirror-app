package com.smart.mirror.app.data.settings

import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserDefaultsKeyValueStoreTest {
    // Unique suite per test instance: simulator preferences can outlive a test process.
    private val suite = "com.smart.mirror.app.test." + NSUUID().UUIDString
    private val defaults = NSUserDefaults(suiteName = suite)

    @AfterTest
    fun tearDown() = defaults.removePersistentDomainForName(suite)

    @Test
    fun roundTripsPrimitivesAndReportsMissingKeysAsNull() {
        val store = UserDefaultsKeyValueStore(defaults)
        assertNull(store.getBoolean("b"))
        assertNull(store.getFloat("f"))
        store.putBoolean("b", true)
        store.putFloat("f", 1.5f)
        store.putInt("i", 7)
        store.putString("s", "x")
        assertEquals(true, store.getBoolean("b"))
        assertEquals(1.5f, store.getFloat("f"))
        assertEquals(7, store.getInt("i"))
        assertEquals("x", store.getString("s"))
        store.remove("s")
        assertNull(store.getString("s"))
    }

    @Test
    fun settingsSurviveRepositoryRecreation() {
        KeyValueSettingsRepository(UserDefaultsKeyValueStore(defaults)).update {
            it.copy(mirrorMode = MirrorMode.TRUE_VIEW, ringLight = it.ringLight.copy(warmth = LightWarmth.WARM))
        }
        val reloaded = KeyValueSettingsRepository(UserDefaultsKeyValueStore(defaults)).settings.value
        assertEquals(MirrorMode.TRUE_VIEW, reloaded.mirrorMode)
        assertEquals(LightWarmth.WARM, reloaded.ringLight.warmth)
    }
}
