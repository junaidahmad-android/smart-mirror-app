package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureStatus
import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.RingLightSettings
import com.smart.mirror.app.domain.model.ThermalState
import com.smart.mirror.app.fakes.TEST_CAPS
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * QA edge cases for the pure reducer (docs/TEST_PLAN.md). Complements [MirrorReducerTest].
 * Tests marked @Ignore("QA-BUG-xx") assert the ROADMAP behaviour and currently FAIL; see docs/QA_REPORT.md.
 */
class MirrorReducerEdgeCaseTest {
    private val initial = MirrorUiState(settings = MirrorSettings.DEFAULT)
    private val granted = MirrorReducer.reduce(initial.copy(isForeground = true), MirrorResult.PermissionResolved(PermissionStatus.GRANTED))
    private val running = MirrorReducer.reduce(granted, MirrorResult.CameraStarted(TEST_CAPS))

    private fun MirrorUiState.after(vararg intents: MirrorIntent) = intents.fold(this) { s, i -> MirrorReducer.reduce(s, i) }
    private fun MirrorUiState.afterResults(vararg results: MirrorResult) = results.fold(this) { s, r -> MirrorReducer.reduce(s, r) }

    // --- Zoom bounds (MVP-03) ---------------------------------------------------------------------

    @Test
    fun zoomWithoutCapabilitiesIsClampedToGlobalRangeAndIgnoresNonFiniteValues() {
        assertEquals(MirrorSettings.MIN_ZOOM_RATIO, initial.after(MirrorIntent.SetZoom(0.01f)).zoomRatio)
        assertEquals(MirrorSettings.MAX_ZOOM_RATIO, initial.after(MirrorIntent.SetZoom(1000f)).zoomRatio)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, initial.after(MirrorIntent.SetZoom(bad)).zoomRatio, "SetZoom($bad) must be ignored")
            assertEquals(1f, initial.after(MirrorIntent.PinchZoom(bad)).zoomRatio, "PinchZoom($bad) must be ignored")
        }
        for (bad in listOf(0f, -1f, -0.5f)) {
            assertSame(running, running.after(MirrorIntent.PinchZoom(bad)), "PinchZoom($bad) must be a no-op")
        }
    }

    @Test
    fun zoomWithCapabilitiesStaysInsideTheCameraRange() {
        assertEquals(1f, running.after(MirrorIntent.SetZoom(0.5f)).zoomRatio)
        assertEquals(4f, running.after(MirrorIntent.SetZoom(4.0001f)).zoomRatio)
        val pinchedIn = (1..20).fold(running) { s, _ -> s.after(MirrorIntent.PinchZoom(1.5f)) }
        assertEquals(4f, pinchedIn.zoomRatio, "repeated pinch never exceeds max")
        val pinchedOut = (1..20).fold(pinchedIn) { s, _ -> s.after(MirrorIntent.PinchZoom(0.5f)) }
        assertEquals(1f, pinchedOut.zoomRatio, "repeated pinch never goes below min")
    }

    @Test
    fun zoomPresetsOnlyContainValuesTheCameraSupports() {
        assertEquals(listOf(1f, 2f, 3f), running.zoomPresets)
        val wide = CameraCapabilities(0.5f, 10f, -2f, 2f, 1f / 3f)
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 5f), granted.afterResults(MirrorResult.CameraStarted(wide)).zoomPresets)
        assertTrue(initial.zoomPresets.isEmpty(), "no presets before the camera reports capabilities")
    }

    @Test
    fun rapidDoubleTapsAlternateDeterministically() {
        var s = running
        repeat(21) { s = s.after(MirrorIntent.ToggleZoom) }
        assertEquals(2f, s.zoomRatio, "odd number of double taps from 1x ends at 2x")
        s = s.after(MirrorIntent.ToggleZoom)
        assertEquals(1f, s.zoomRatio)
    }

    @Test
    fun doubleTapRemembersZoomReachedByDoubleTapFromNonDefault() {
        val s = running.after(MirrorIntent.SetZoom(3f), MirrorIntent.ToggleZoom)
        assertEquals(1f, s.zoomRatio)
        assertEquals(3f, s.after(MirrorIntent.ToggleZoom).zoomRatio)
    }

    /** QA-BUG-03: MVP-03.AC3 "toggles between 1.0x and the LAST non-1.0x zoom". */

    @Test
    fun doubleTapReturnsToLastNonDefaultZoomSetBySliderPresetOrPinch() {
        val viaPreset = running.after(MirrorIntent.SetZoom(3f), MirrorIntent.SetZoom(1f), MirrorIntent.ToggleZoom)
        assertEquals(3f, viaPreset.zoomRatio, "slider/preset 3x -> 1x, then double tap should return to 3x")
        val viaPinch = running.after(MirrorIntent.PinchZoom(2.5f), MirrorIntent.PinchZoom(0.4f), MirrorIntent.ToggleZoom)
        assertEquals(2.5f, viaPinch.zoomRatio, 0.001f, "pinch 2.5x -> 1x, then double tap should return to 2.5x")
    }

    // --- Exposure (MVP-04) -------------------------------------------------------------------------

    @Test
    fun exposureIsClampedSnappedAndResetAndHiddenWhenUnsupported() {
        assertEquals(2f, running.after(MirrorIntent.SetExposure(9f)).exposureEv)
        assertEquals(-2f, running.after(MirrorIntent.SetExposure(-9f)).exposureEv)
        assertEquals(0.5f, running.after(MirrorIntent.SetExposure(0.6f)).exposureEv)
        assertEquals(0f, running.after(MirrorIntent.SetExposure(0.2f)).exposureEv, "snaps to the 0 detent")
        assertEquals(0f, running.after(MirrorIntent.SetExposure(1.5f), MirrorIntent.ResetExposure).exposureEv)
        assertEquals(0f, running.after(MirrorIntent.SetExposure(Float.NaN)).exposureEv)
        val noEv = granted.afterResults(MirrorResult.CameraStarted(CameraCapabilities(1f, 4f, 0f, 0f, 0f)))
        assertFalse(noEv.showExposureControl)
        assertEquals(0f, noEv.after(MirrorIntent.SetExposure(1f)).exposureEv)
    }

    @Test
    fun exposureIsReclampedWhenTheCameraRestartsWithANarrowerRange() {
        val s = running.after(MirrorIntent.SetExposure(2f))
            .afterResults(MirrorResult.CameraStopped, MirrorResult.CameraStarted(CameraCapabilities(1f, 4f, -1f, 1f, 0.5f)))
        assertEquals(1f, s.exposureEv)
    }

    // --- Ring light (MVP-05) -----------------------------------------------------------------------

    @Test
    fun ringLightIntensityIsBoundedAndMeetsTheTwelvePercentEdgeAtMax() {
        assertEquals(RingLightSettings.MIN_INTENSITY, running.after(MirrorIntent.SetRingLightIntensity(-3f)).settings.ringLight.intensity)
        val max = running.after(MirrorIntent.SetRingLightIntensity(7f))
        assertEquals(1f, max.settings.ringLight.intensity)
        assertTrue(max.ringLight.edgeWidthFraction >= 0.12f, "MVP-05.AC2: >= 12% of short edge at 100%")
        assertTrue(max.ringLight.visible)
        val off = max.after(MirrorIntent.ToggleRingLight)
        assertFalse(off.ringLight.visible)
        assertEquals(1f, off.settings.ringLight.intensity, "toggling off keeps the chosen intensity")
        for (w in LightWarmth.entries) assertEquals(w, off.after(MirrorIntent.SetLightWarmth(w)).settings.ringLight.warmth)
    }

    /** QA-BUG-09: a NaN intensity (e.g. from a buggy slider or accessibility adjust) leaks into state. */

    @Test
    fun nonFiniteRingLightIntensityIsIgnored() {
        val s = running.after(MirrorIntent.SetRingLightIntensity(Float.NaN))
        assertTrue(s.settings.ringLight.intensity.isFinite())
        assertTrue(s.ringLight.edgeWidthFraction.isFinite() && s.ringLight.opacity.isFinite())
    }

    // --- Freeze + capture interplay (MVP-07, MVP-08) ----------------------------------------------

    @Test
    fun freezeRequiresARunningIdleCamera() {
        assertFalse(granted.after(MirrorIntent.ToggleFreeze).isFrozen, "camera STARTING")
        val capturing = running.afterResults(MirrorResult.CaptureStarted)
        assertFalse(capturing.canFreeze)
        assertFalse(capturing.after(MirrorIntent.ToggleFreeze).isFrozen, "cannot freeze while a capture is in flight")
        val errored = running.afterResults(MirrorResult.CameraFailed(CameraError.DISCONNECTED))
        assertFalse(errored.after(MirrorIntent.ToggleFreeze).isFrozen)
    }

    @Test
    fun frozenZoomGesturesScaleTheImageOnlyAndResumeRestoresPreFreezeZoom() {
        val frozen = running.after(MirrorIntent.SetZoom(2.5f), MirrorIntent.ToggleFreeze)
        assertTrue(frozen.isFrozen)
        assertTrue(frozen.controlsVisible, "freeze reveals the controls (Resume reachable)")
        val zoomed = frozen.after(MirrorIntent.PinchZoom(3f), MirrorIntent.PinchZoom(3f), MirrorIntent.SetZoom(20f))
        assertEquals(2.5f, zoomed.zoomRatio, "live zoom untouched while frozen")
        assertEquals(MirrorUiState.MAX_FROZEN_IMAGE_SCALE, zoomed.frozenImageScale, "MVP-07.AC3: up to 8x, no more")
        assertEquals(1f, zoomed.after(MirrorIntent.PinchZoom(0.01f)).frozenImageScale, "never below 1x")
        val resumed = zoomed.after(MirrorIntent.ToggleFreeze)
        assertFalse(resumed.isFrozen)
        assertEquals(2.5f, resumed.zoomRatio, "MVP-07.AC4: back at the pre-freeze zoom")
        assertEquals(1f, resumed.frozenImageScale)
    }

    @Test
    fun captureIsAllowedWhileFrozenAndBlocksFreezeTogglingOnlyInOneDirection() {
        val frozen = running.after(MirrorIntent.ToggleFreeze)
        assertTrue(frozen.canCapture, "MVP-08.AC1: freeze mode saves the frozen frame")
        val capturing = frozen.afterResults(MirrorResult.CaptureStarted)
        assertFalse(capturing.canCapture)
        // Resuming during a capture is allowed (the adapter captured the frozen bitmap already).
        assertFalse(capturing.after(MirrorIntent.ToggleFreeze).isFrozen)
        val saved = capturing.afterResults(MirrorResult.SavingStarted, MirrorResult.CaptureFinished(com.smart.mirror.app.domain.model.CaptureResult.Saved("x")))
        assertTrue(saved.isFrozen, "saving does not un-freeze")
        assertEquals(CaptureStatus.IDLE, saved.captureStatus)
    }

    @Test
    fun freezeIsDiscardedOnBackgroundCameraErrorAndStop() {
        val frozen = running.after(MirrorIntent.ToggleFreeze, MirrorIntent.PinchZoom(4f))
        for (s in listOf(
            frozen.after(MirrorIntent.ScreenStopped),
            frozen.afterResults(MirrorResult.CameraFailed(CameraError.IN_USE_BY_ANOTHER_APP)),
            frozen.afterResults(MirrorResult.CameraStopped),
        )) {
            assertFalse(s.isFrozen, "MVP-07.AC7")
            assertEquals(1f, s.frozenImageScale)
        }
    }

    @Test
    fun mirrorModeAppliesToFrozenAndLiveAlike() {
        val s = running.after(MirrorIntent.ToggleFreeze, MirrorIntent.ToggleMirrorMode)
        assertFalse(s.previewMirrored)
        assertTrue(s.isFrozen)
        assertEquals(MirrorMode.MIRROR, s.after(MirrorIntent.SetMirrorMode(MirrorMode.MIRROR)).settings.mirrorMode)
    }

    // --- Thermal throttling (NFR-06, MVP-06.AC5) --------------------------------------------------

    @Test
    fun thermalPolicyAcrossAllLevels() {
        val nominal = running.after(MirrorIntent.ThermalStateChanged(ThermalState.NOMINAL))
        assertEquals(60, nominal.maxFrameRate)
        assertEquals(1f, nominal.brightnessLevel)
        val fair = running.after(MirrorIntent.ThermalStateChanged(ThermalState.FAIR))
        assertEquals(60, fair.maxFrameRate)
        assertNull(fair.message)

        val serious = running.after(MirrorIntent.ThermalStateChanged(ThermalState.SERIOUS))
        assertEquals(30, serious.maxFrameRate)
        assertEquals(0.8f, serious.brightnessLevel)
        assertEquals(UserMessage.THERMAL_THROTTLED, serious.message)

        val againSerious = serious.after(MirrorIntent.DismissMessage, MirrorIntent.ThermalStateChanged(ThermalState.NOMINAL), MirrorIntent.ThermalStateChanged(ThermalState.SERIOUS))
        assertNull(againSerious.message, "the throttling notice is one-time")

        val critical = serious.after(MirrorIntent.ThermalStateChanged(ThermalState.CRITICAL))
        assertEquals(MirrorScreen.OVERHEATED, critical.screen)
        assertNull(critical.brightnessLevel, "no forced brightness on the hot screen")
        assertFalse(critical.keepScreenOn)
        assertEquals(MirrorScreen.MIRROR, critical.after(MirrorIntent.ThermalStateChanged(ThermalState.FAIR)).screen)
    }

    @Test
    fun throttlingNeverRaisesBrightnessWhenTheUserOptedOut() {
        val s = running.after(MirrorIntent.SetAutoMaxBrightness(false), MirrorIntent.ThermalStateChanged(ThermalState.SERIOUS))
        assertNull(s.brightnessLevel)
        assertFalse(s.after(MirrorIntent.ScreenStopped).keepScreenOn)
    }

    // --- Reset / settings (MVP-11, MVP-15) --------------------------------------------------------

    @Test
    fun resetRestoresEveryDocumentedDefault() {
        val custom = running.afterResults(MirrorResult.TapHintShown).after(
            MirrorIntent.SetMirrorMode(MirrorMode.TRUE_VIEW),
            MirrorIntent.SetRingLightIntensity(0.9f),
            MirrorIntent.SetLightWarmth(LightWarmth.WARM),
            MirrorIntent.SetAutoMaxBrightness(false),
            MirrorIntent.SetZoom(3f),
            MirrorIntent.SetExposure(1.5f),
        )
        val reset = custom.after(MirrorIntent.ResetSettingsToDefaults)
        // MVP-11.AC3: light off, 50%, Daylight, 1.0x, Mirror, auto max brightness ON (+ exposure 0).
        assertFalse(reset.settings.ringLight.enabled)
        assertEquals(0.5f, reset.settings.ringLight.intensity)
        assertEquals(LightWarmth.DAYLIGHT, reset.settings.ringLight.warmth)
        assertEquals(1f, reset.zoomRatio)
        assertEquals(MirrorMode.MIRROR, reset.settings.mirrorMode)
        assertTrue(reset.settings.autoMaxBrightness)
        assertEquals(0f, reset.exposureEv)
        assertTrue(reset.settings.tapHintShown, "one-time hint does not come back after reset")
        assertEquals(2f, reset.after(MirrorIntent.ToggleZoom).zoomRatio, "double-tap target reset to 2x")
    }

    @Test
    fun resetClampsDefaultZoomToCameraMinimum() {
        val caps = CameraCapabilities(1.2f, 5f, -2f, 2f, 0.5f)
        val s = granted.afterResults(MirrorResult.CameraStarted(caps)).after(MirrorIntent.SetZoom(4f), MirrorIntent.ResetSettingsToDefaults)
        assertEquals(1.2f, s.zoomRatio)
    }

    @Test
    fun settingsSheetPausesAutoHideAndScreenReaderDisablesIt() {
        val visible = running.after(MirrorIntent.ScreenTapped)
        assertTrue(visible.shouldAutoHideControls)
        assertFalse(visible.after(MirrorIntent.SetSettingsOpen(true)).shouldAutoHideControls)
        assertTrue(running.after(MirrorIntent.SetSettingsOpen(true)).controlsVisible, "opening settings shows controls")
        assertTrue(visible.after(MirrorIntent.SetSettingsOpen(true), MirrorIntent.SetSettingsOpen(false)).controlsVisible, "closing settings returns to mirror with controls intact")
        assertFalse(visible.after(MirrorIntent.SetScreenReaderActive(true)).shouldAutoHideControls)
        assertTrue(visible.after(MirrorIntent.SetScreenReaderActive(true)).afterResults(MirrorResult.ControlsTimedOut).controlsVisible)
    }

    // --- Permission (MVP-12) -----------------------------------------------------------------------

    @Test
    fun permissionRevokedWhileRunningSwitchesScreenAndDropsHardwareDemands() {
        for ((status, screen) in listOf(PermissionStatus.DENIED to MirrorScreen.PERMISSION_DENIED, PermissionStatus.BLOCKED to MirrorScreen.PERMISSION_BLOCKED)) {
            val s = running.afterResults(MirrorResult.PermissionResolved(status))
            assertEquals(screen, s.screen)
            assertNull(s.brightnessLevel)
            assertFalse(s.keepScreenOn)
        }
    }

    // --- Rapid random intent sequences: invariants never break ------------------------------------

    @Test
    fun randomIntentAndResultSequencesKeepStateInvariants() {
        val rnd = Random(20260925)
        val intents: List<() -> MirrorIntent> = listOf(
            { MirrorIntent.ScreenStarted }, { MirrorIntent.ScreenStopped }, { MirrorIntent.ScreenTapped },
            { MirrorIntent.ToggleZoom }, { MirrorIntent.SetZoom(rnd.nextFloat() * 20f - 5f) },
            { MirrorIntent.PinchZoom(rnd.nextFloat() * 3f) }, { MirrorIntent.SetExposure(rnd.nextFloat() * 10f - 5f) },
            { MirrorIntent.ResetExposure }, { MirrorIntent.ToggleRingLight },
            { MirrorIntent.SetRingLightIntensity(rnd.nextFloat() * 3f - 1f) },
            { MirrorIntent.SetLightWarmth(LightWarmth.entries.random(rnd)) }, { MirrorIntent.ToggleFreeze },
            { MirrorIntent.ToggleMirrorMode }, { MirrorIntent.DismissMessage }, { MirrorIntent.SetSettingsOpen(rnd.nextBoolean()) },
            { MirrorIntent.SetAutoMaxBrightness(rnd.nextBoolean()) }, { MirrorIntent.ResetSettingsToDefaults },
            { MirrorIntent.ThermalStateChanged(ThermalState.entries.random(rnd)) }, { MirrorIntent.SetScreenReaderActive(rnd.nextBoolean()) },
        )
        val results: List<() -> MirrorResult> = listOf(
            { MirrorResult.CameraStarted(TEST_CAPS) }, { MirrorResult.CameraStopped },
            { MirrorResult.CameraFailed(CameraError.entries.random(rnd)) }, { MirrorResult.CaptureStarted },
            { MirrorResult.CaptureFinished(com.smart.mirror.app.domain.model.CaptureResult.Saved("u")) },
            { MirrorResult.ControlsTimedOut }, { MirrorResult.MessageTimedOut },
            { MirrorResult.PermissionResolved(PermissionStatus.GRANTED) },
        )
        var s = running
        repeat(5_000) { step ->
            s = if (rnd.nextInt(4) == 0) MirrorReducer.reduce(s, results.random(rnd)()) else MirrorReducer.reduce(s, intents.random(rnd)())
            val ctx = "step $step: $s"
            assertTrue(s.zoomRatio in 1f..4f, "zoom within camera range; $ctx")
            assertTrue(s.frozenImageScale in 1f..MirrorUiState.MAX_FROZEN_IMAGE_SCALE, ctx)
            assertTrue(s.exposureEv in -2f..2f, ctx)
            val steps = s.exposureEv / 0.5f
            assertTrue(abs(steps - steps.roundToInt()) < 1e-3f, "exposure on the EV step; $ctx")
            assertTrue(s.settings.ringLight.intensity in RingLightSettings.MIN_INTENSITY..RingLightSettings.MAX_INTENSITY, ctx)
            if (s.isFrozen) assertEquals(CameraStatus.RUNNING, s.cameraStatus, "frozen implies a running camera; $ctx")
            if (s.screen == MirrorScreen.OVERHEATED) assertNull(s.brightnessLevel, ctx)
            if (!s.isForeground) {
                assertNull(s.brightnessLevel, "never boost brightness in background; $ctx")
                assertFalse(s.keepScreenOn, ctx)
            }
        }
    }
}
