package com.smart.mirror.app.domain.model

import com.smart.mirror.app.fakes.TEST_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelTest {
    @Test
    fun warmLightIsRedderThanCoolLight() {
        val warm = RingLightAppearance.kelvinToRgb(LightWarmth.WARM.kelvin)
        val cool = RingLightAppearance.kelvinToRgb(LightWarmth.COOL.kelvin)
        assertEquals(255, warm.red)
        assertTrue(warm.blue < cool.blue)
        assertEquals(0xFFL, (warm.argb shr 24) and 0xFF)
    }

    @Test
    fun ringLightGetsThickerWithIntensity() {
        val low = RingLightAppearance.from(RingLightSettings.DEFAULT.copy(intensity = 0.1f))
        val high = RingLightAppearance.from(RingLightSettings.DEFAULT.copy(intensity = 1f))
        assertTrue(high.edgeWidthFraction > low.edgeWidthFraction)
        assertTrue(high.opacity > low.opacity)
        assertFalse(low.visible)
    }

    @Test
    fun exposureWithoutSupportIsZero() {
        val noEv = TEST_CAPS.copy(exposureStepEv = 0f)
        assertFalse(noEv.supportsExposureCompensation)
        assertEquals(0f, noEv.clampExposure(1f))
        assertEquals(-2f, TEST_CAPS.clampExposure(-9f))
    }
}
