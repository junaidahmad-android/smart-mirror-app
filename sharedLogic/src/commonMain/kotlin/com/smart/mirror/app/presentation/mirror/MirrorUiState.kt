package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureStatus
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.RingLightAppearance
import com.smart.mirror.app.domain.model.ThermalState

/** Which top-level screen the platform UI should show. */
enum class MirrorScreen {
    /** Permission status not checked yet: show a black screen, never flash onboarding. */
    LOADING,

    /** First run: explain why the camera is needed, then send [MirrorIntent.RequestCameraPermission]. */
    PERMISSION_ONBOARDING,

    /** Denied but can ask again: explain and offer "Try again" + "Open Settings". */
    PERMISSION_DENIED,

    /** Only Settings can fix it: offer [MirrorIntent.OpenAppSettings]. */
    PERMISSION_BLOCKED,

    /** Thermal state CRITICAL: preview paused, show "Device is hot" (NFR-06). */
    OVERHEATED,

    /** Live mirror (inspect [MirrorUiState.cameraStatus] / [MirrorUiState.cameraError] for overlays). */
    MIRROR,
}

/** Transient, auto-dismissed message (toast/snackbar). Localised by the platform UI. */
enum class UserMessage {
    PHOTO_SAVED,
    CAPTURE_FAILED,
    SAVE_PERMISSION_DENIED,
    STORAGE_FULL,
    THERMAL_THROTTLED,
}

/** Single immutable snapshot of everything the mirror screen renders. */
data class MirrorUiState(
    val settings: MirrorSettings,
    val isForeground: Boolean = false,
    val permission: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val permissionChecked: Boolean = false,
    val cameraStatus: CameraStatus = CameraStatus.IDLE,
    val cameraError: CameraError? = null,
    /** Null until the camera reports its capabilities. */
    val capabilities: CameraCapabilities? = null,
    /** Session-only exposure compensation in EV (resets each launch, MVP-11.AC1). */
    val exposureEv: Float = 0f,
    /** Controls start hidden (MVP-01.AC3). */
    val controlsVisible: Boolean = false,
    /** One-time "Tap for controls" hint (MVP-01.AC3). */
    val showTapHint: Boolean = false,
    val settingsOpen: Boolean = false,
    val screenReaderActive: Boolean = false,
    val isFrozen: Boolean = false,
    /** Extra digital scale applied by the UI to the frozen image, 1..[MAX_FROZEN_IMAGE_SCALE]. Pan stays UI-local. */
    val frozenImageScale: Float = 1f,
    /** Double-tap target when currently at 1x (MVP-03.AC3). */
    val lastNonDefaultZoom: Float = DEFAULT_DOUBLE_TAP_ZOOM,
    val captureStatus: CaptureStatus = CaptureStatus.IDLE,
    /** Last saved photo (thumbnail now, "compare with previous" later). */
    val lastSavedPhotoUri: String? = null,
    val message: UserMessage? = null,
    val thermalState: ThermalState = ThermalState.NOMINAL,
    val thermalNoticeShown: Boolean = false,
    /**
     * Increments every time a [message] is (re)issued, even if it is the same value as before
     * (e.g. two "Saved" in a row). Key toast/haptic/announcement effects on this (QA-BUG-06).
     */
    val messageId: Int = 0,
) {
    val screen: MirrorScreen
        get() = when {
            !permissionChecked -> MirrorScreen.LOADING
            permission == PermissionStatus.GRANTED ->
                if (isOverheated) MirrorScreen.OVERHEATED else MirrorScreen.MIRROR
            permission == PermissionStatus.BLOCKED -> MirrorScreen.PERMISSION_BLOCKED
            permission == PermissionStatus.DENIED -> MirrorScreen.PERMISSION_DENIED
            else -> MirrorScreen.PERMISSION_ONBOARDING
        }

    val zoomRatio: Float get() = settings.zoomRatio
    val previewMirrored: Boolean get() = settings.mirrorMode == MirrorMode.MIRROR
    val ringLight: RingLightAppearance get() = RingLightAppearance.from(settings.ringLight)
    val isCameraRunning: Boolean get() = cameraStatus == CameraStatus.RUNNING
    val canCapture: Boolean get() = isCameraRunning && captureStatus == CaptureStatus.IDLE
    val canFreeze: Boolean get() = isCameraRunning && captureStatus == CaptureStatus.IDLE
    val zoomPresets: List<Float> get() = capabilities?.zoomPresets ?: emptyList()
    val showExposureControl: Boolean get() = capabilities?.supportsExposureCompensation == true
    val isOverheated: Boolean get() = thermalState == ThermalState.CRITICAL
    val isThrottled: Boolean get() = thermalState >= ThermalState.SERIOUS

    /** Screen brightness the platform should force, or null to restore the user's own level. */
    val brightnessLevel: Float?
        get() = when {
            !isForeground || screen != MirrorScreen.MIRROR -> null
            !(settings.autoMaxBrightness || settings.ringLight.enabled) -> null
            isThrottled -> THROTTLED_BRIGHTNESS
            else -> 1f
        }

    val keepScreenOn: Boolean get() = isForeground && screen == MirrorScreen.MIRROR
    val maxFrameRate: Int get() = if (isThrottled) THROTTLED_FPS else NORMAL_FPS

    companion object {
        const val DEFAULT_DOUBLE_TAP_ZOOM = 2f
        const val MAX_FROZEN_IMAGE_SCALE = 8f
        const val THROTTLED_BRIGHTNESS = 0.8f
        const val NORMAL_FPS = 60
        const val THROTTLED_FPS = 30
    }
}
