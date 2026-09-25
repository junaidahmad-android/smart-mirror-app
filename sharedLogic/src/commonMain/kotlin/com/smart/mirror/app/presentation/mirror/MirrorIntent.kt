package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.ThermalState

/** Everything the user, the screen lifecycle or the OS can tell the mirror screen. */
sealed interface MirrorIntent {
    // --- lifecycle & system -------------------------------------------------------------------
    /** Screen visible & app in foreground (Android ON_START, iOS scenePhase == .active). */
    data object ScreenStarted : MirrorIntent

    /** Screen hidden / app backgrounded (Android ON_STOP, iOS scenePhase != .active). */
    data object ScreenStopped : MirrorIntent

    data class ThermalStateChanged(val state: ThermalState) : MirrorIntent

    /** TalkBack / VoiceOver on: controls never auto-hide (NFR-10). */
    data class SetScreenReaderActive(val active: Boolean) : MirrorIntent

    // --- permission & errors ------------------------------------------------------------------
    data object RequestCameraPermission : MirrorIntent
    data object OpenAppSettings : MirrorIntent
    data object RetryCamera : MirrorIntent

    // --- controls -----------------------------------------------------------------------------
    /** Single tap on the preview: toggles control visibility. */
    data object ScreenTapped : MirrorIntent

    /** Double tap: toggles 1x <-> last non-1x zoom. */
    data object ToggleZoom : MirrorIntent

    /** Absolute zoom (slider / preset buttons). While frozen it scales the frozen image instead. */
    data class SetZoom(val ratio: Float) : MirrorIntent

    /** Incremental pinch: multiply the current zoom by [scaleFactor]. While frozen: frozen image. */
    data class PinchZoom(val scaleFactor: Float) : MirrorIntent

    data class SetExposure(val ev: Float) : MirrorIntent
    data object ResetExposure : MirrorIntent

    data object ToggleRingLight : MirrorIntent

    /** Also turns the ring light on. */
    data class SetRingLightIntensity(val intensity: Float) : MirrorIntent

    /** Also turns the ring light on. */
    data class SetLightWarmth(val warmth: LightWarmth) : MirrorIntent

    data object ToggleFreeze : MirrorIntent
    data object CapturePhoto : MirrorIntent
    data object ToggleMirrorMode : MirrorIntent
    data class SetMirrorMode(val mode: MirrorMode) : MirrorIntent
    data object DismissMessage : MirrorIntent

    /**
     * The user touched the controls without changing a value (e.g. switched a UI-local tab or
     * started dragging a panel). No state change; only restarts the auto-hide timer (MVP-02.AC2).
     */
    data object ControlsInteracted : MirrorIntent

    // --- settings sheet -----------------------------------------------------------------------
    /** Settings sheet shown/hidden: pauses control auto-hide (MVP-15.AC2). */
    data class SetSettingsOpen(val open: Boolean) : MirrorIntent
    data class SetAutoMaxBrightness(val enabled: Boolean) : MirrorIntent
    data object ResetSettingsToDefaults : MirrorIntent
}
