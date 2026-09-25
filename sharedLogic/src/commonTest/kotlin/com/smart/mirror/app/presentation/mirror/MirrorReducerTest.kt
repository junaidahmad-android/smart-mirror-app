package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureFailure
import com.smart.mirror.app.domain.model.CaptureResult
import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.ThermalState
import com.smart.mirror.app.fakes.TEST_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MirrorReducerTest {
    private val initial = MirrorUiState(settings = MirrorSettings.DEFAULT)
    private val running = MirrorReducer.reduce(
        MirrorReducer.reduce(initial, MirrorResult.PermissionResolved(PermissionStatus.GRANTED)),
        MirrorResult.CameraStarted(TEST_CAPS),
    )

    private fun MirrorUiState.after(vararg intents: MirrorIntent) =
        intents.fold(this) { s, i -> MirrorReducer.reduce(s, i) }

    @Test
    fun screenFollowsPermission() {
        assertEquals(MirrorScreen.LOADING, initial.screen)
        fun screenFor(p: PermissionStatus) = MirrorReducer.reduce(initial, MirrorResult.PermissionResolved(p)).screen
        assertEquals(MirrorScreen.PERMISSION_ONBOARDING, screenFor(PermissionStatus.NOT_DETERMINED))
        assertEquals(MirrorScreen.PERMISSION_DENIED, screenFor(PermissionStatus.DENIED))
        assertEquals(MirrorScreen.PERMISSION_BLOCKED, screenFor(PermissionStatus.BLOCKED))
        assertEquals(MirrorScreen.MIRROR, screenFor(PermissionStatus.GRANTED))
    }

    @Test
    fun zoomIsClampedToCapabilitiesAndPinchMultiplies() {
        assertEquals(4f, running.after(MirrorIntent.SetZoom(9f)).zoomRatio)
        assertEquals(1f, running.after(MirrorIntent.SetZoom(0.2f)).zoomRatio)
        assertEquals(3f, running.after(MirrorIntent.SetZoom(2f), MirrorIntent.PinchZoom(1.5f)).zoomRatio)
        assertEquals(running, running.after(MirrorIntent.PinchZoom(Float.NaN)))
        assertEquals(listOf(1f, 2f, 3f), running.zoomPresets)
    }

    @Test
    fun savedZoomIsClampedWhenCameraStarts() {
        val s = initial.copy(settings = MirrorSettings.DEFAULT.copy(zoomRatio = 8f))
        assertEquals(4f, MirrorReducer.reduce(s, MirrorResult.CameraStarted(TEST_CAPS)).zoomRatio)
    }

    @Test
    fun exposureIsSnappedToStepAndIgnoredWithoutCapabilities() {
        assertEquals(0.5f, running.after(MirrorIntent.SetExposure(0.6f)).exposureEv)
        assertEquals(2f, running.after(MirrorIntent.SetExposure(5f)).exposureEv)
        assertEquals(0f, running.after(MirrorIntent.SetExposure(-1f), MirrorIntent.ResetExposure).exposureEv)
        assertEquals(0f, initial.after(MirrorIntent.SetExposure(1f)).exposureEv)
    }

    @Test
    fun ringLightPresetsEnableTheLight() {
        val s = initial.after(MirrorIntent.SetLightWarmth(LightWarmth.WARM))
        assertTrue(s.settings.ringLight.enabled)
        assertEquals(LightWarmth.WARM, s.settings.ringLight.warmth)
        assertEquals(1f, initial.after(MirrorIntent.SetRingLightIntensity(3f)).settings.ringLight.intensity)
        assertFalse(s.after(MirrorIntent.ToggleRingLight).settings.ringLight.enabled)
        assertTrue(s.ringLight.visible)
    }

    @Test
    fun mirrorModeToggles() {
        val s = initial.after(MirrorIntent.ToggleMirrorMode)
        assertEquals(MirrorMode.TRUE_VIEW, s.settings.mirrorMode)
        assertFalse(s.previewMirrored)
        assertEquals(MirrorMode.MIRROR, s.after(MirrorIntent.ToggleMirrorMode).settings.mirrorMode)
    }

    @Test
    fun freezeOnlyWhileRunning() {
        assertFalse(initial.after(MirrorIntent.ToggleFreeze).isFrozen)
        val frozen = running.after(MirrorIntent.ToggleFreeze)
        assertTrue(frozen.isFrozen)
        assertFalse(frozen.after(MirrorIntent.ToggleFreeze).isFrozen)
        assertFalse(MirrorReducer.reduce(frozen, MirrorResult.CameraStopped).isFrozen)
        assertFalse(frozen.after(MirrorIntent.ScreenStopped).isFrozen)
    }

    @Test
    fun controlsStartHiddenTapTogglesAndTimeoutHidesOnlyLivePreview() {
        assertFalse(running.controlsVisible)
        val shown = running.after(MirrorIntent.ScreenTapped)
        assertTrue(shown.controlsVisible)
        assertFalse(shown.after(MirrorIntent.ScreenTapped).controlsVisible)
        assertFalse(MirrorReducer.reduce(shown, MirrorResult.ControlsTimedOut).controlsVisible)
        fun staysVisible(s: MirrorUiState) = assertTrue(MirrorReducer.reduce(s, MirrorResult.ControlsTimedOut).controlsVisible)
        staysVisible(shown.after(MirrorIntent.ToggleFreeze))
        staysVisible(shown.after(MirrorIntent.SetScreenReaderActive(true)))
        staysVisible(shown.after(MirrorIntent.SetSettingsOpen(true)))
    }

    @Test
    fun doubleTapTogglesBetweenOneXAndLastZoom() {
        assertEquals(2f, running.after(MirrorIntent.ToggleZoom).zoomRatio)
        val at3 = running.after(MirrorIntent.SetZoom(3f))
        val back = at3.after(MirrorIntent.ToggleZoom)
        assertEquals(1f, back.zoomRatio)
        assertEquals(3f, back.after(MirrorIntent.ToggleZoom).zoomRatio)
    }

    @Test
    fun zoomWhileFrozenScalesFrozenImageOnlyUpTo8x() {
        val frozen = running.after(MirrorIntent.SetZoom(2f), MirrorIntent.ToggleFreeze)
        val zoomed = frozen.after(MirrorIntent.PinchZoom(20f))
        assertEquals(8f, zoomed.frozenImageScale)
        assertEquals(2f, zoomed.zoomRatio, "camera zoom is untouched while frozen")
        val resumed = zoomed.after(MirrorIntent.ToggleFreeze)
        assertEquals(1f, resumed.frozenImageScale)
        assertEquals(2f, resumed.zoomRatio)
    }

    @Test
    fun thermalPolicy() {
        val serious = running.after(MirrorIntent.ScreenStarted, MirrorIntent.ThermalStateChanged(ThermalState.SERIOUS))
        assertEquals(UserMessage.THERMAL_THROTTLED, serious.message)
        assertEquals(MirrorUiState.THROTTLED_BRIGHTNESS, serious.brightnessLevel)
        assertEquals(30, serious.maxFrameRate)
        // notice is shown once per session
        val again = serious.after(
            MirrorIntent.DismissMessage,
            MirrorIntent.ThermalStateChanged(ThermalState.NOMINAL),
            MirrorIntent.ThermalStateChanged(ThermalState.SERIOUS),
        )
        assertNull(again.message)
        val critical = serious.after(MirrorIntent.ThermalStateChanged(ThermalState.CRITICAL))
        assertEquals(MirrorScreen.OVERHEATED, critical.screen)
        assertNull(critical.brightnessLevel)
    }

    @Test
    fun resetToDefaultsKeepsHintFlagAndClampsZoom() {
        val custom = running.after(
            MirrorIntent.SetZoom(3f),
            MirrorIntent.ToggleMirrorMode,
            MirrorIntent.SetLightWarmth(LightWarmth.COOL),
            MirrorIntent.SetExposure(1f),
        )
        val withHint = MirrorReducer.reduce(custom, MirrorResult.TapHintShown)
        val reset = withHint.after(MirrorIntent.ResetSettingsToDefaults)
        assertEquals(MirrorSettings.DEFAULT.copy(tapHintShown = true), reset.settings)
        assertEquals(0f, reset.exposureEv)
    }

    @Test
    fun captureResultsMapToMessages() {
        val saved = MirrorReducer.reduce(running, MirrorResult.CaptureFinished(CaptureResult.Saved("uri")))
        assertEquals(UserMessage.PHOTO_SAVED, saved.message)
        assertEquals("uri", saved.lastSavedPhotoUri)
        val denied = MirrorReducer.reduce(running, MirrorResult.CaptureFinished(CaptureResult.Failed(CaptureFailure.SAVE_PERMISSION_DENIED)))
        assertEquals(UserMessage.SAVE_PERMISSION_DENIED, denied.message)
        assertNull(denied.after(MirrorIntent.DismissMessage).message)
    }

    @Test
    fun cameraPermissionErrorFlipsToDeniedScreen() {
        val s = MirrorReducer.reduce(running, MirrorResult.CameraFailed(CameraError.PERMISSION_DENIED))
        assertEquals(CameraStatus.ERROR, s.cameraStatus)
        assertEquals(MirrorScreen.PERMISSION_DENIED, s.screen)
        // a trailing "stopped" callback must not hide the error
        assertEquals(CameraStatus.ERROR, MirrorReducer.reduce(s, MirrorResult.CameraStopped).cameraStatus)
    }

    @Test
    fun noOpIntentReturnsSameInstance() {
        assertSame(running, MirrorReducer.reduce(running, MirrorIntent.CapturePhoto))
    }
}
