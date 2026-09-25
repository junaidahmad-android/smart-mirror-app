package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureFailure
import com.smart.mirror.app.domain.model.CaptureResult
import com.smart.mirror.app.domain.model.CaptureStatus
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.RingLightSettings
import kotlin.math.abs

/** Results of side effects, fed back into the reducer. */
internal sealed interface MirrorResult {
    data class PermissionResolved(val status: PermissionStatus) : MirrorResult
    data object CameraStarting : MirrorResult
    data class CameraStarted(val capabilities: CameraCapabilities) : MirrorResult
    data class CameraFailed(val error: CameraError) : MirrorResult
    data object CameraStopped : MirrorResult
    data object CaptureStarted : MirrorResult
    data object SavingStarted : MirrorResult
    data class CaptureFinished(val result: CaptureResult) : MirrorResult
    data object ControlsTimedOut : MirrorResult
    data object MessageTimedOut : MirrorResult
    data object TapHintShown : MirrorResult
    data object TapHintTimedOut : MirrorResult
}

/** Pure state transitions. No I/O, no coroutines: trivially unit-testable. */
internal object MirrorReducer {

    fun reduce(state: MirrorUiState, intent: MirrorIntent): MirrorUiState = when (intent) {
        MirrorIntent.ScreenStarted -> state.copy(isForeground = true)
        MirrorIntent.ScreenStopped -> state.copy(isForeground = false, isFrozen = false, frozenImageScale = 1f)
        is MirrorIntent.ThermalStateChanged -> {
            val throttledNow = state.copy(thermalState = intent.state).isThrottled
            val notify = throttledNow && !state.thermalNoticeShown
            state.copy(
                thermalState = intent.state,
                thermalNoticeShown = state.thermalNoticeShown || notify,
            ).let { if (notify) it.withMessage(UserMessage.THERMAL_THROTTLED) else it }
        }
        is MirrorIntent.SetScreenReaderActive -> state.copy(screenReaderActive = intent.active)
        MirrorIntent.RequestCameraPermission, MirrorIntent.OpenAppSettings, MirrorIntent.RetryCamera,
        MirrorIntent.CapturePhoto, MirrorIntent.ControlsInteracted,
        -> state
        MirrorIntent.ScreenTapped -> state.copy(controlsVisible = !state.controlsVisible, showTapHint = false)
        MirrorIntent.ToggleZoom -> when {
            state.isFrozen -> state.copy(frozenImageScale = if (state.frozenImageScale > 1f) 1f else MirrorUiState.DEFAULT_DOUBLE_TAP_ZOOM)
            abs(state.zoomRatio - 1f) > ZOOM_EPSILON -> state.copy(lastNonDefaultZoom = state.zoomRatio).withZoom(1f)
            else -> state.withZoom(state.lastNonDefaultZoom)
        }
        is MirrorIntent.SetZoom ->
            if (state.isFrozen) state.withFrozenScale(intent.ratio) else state.withZoom(intent.ratio)
        is MirrorIntent.PinchZoom -> when {
            !intent.scaleFactor.isFinite() || intent.scaleFactor <= 0f -> state
            state.isFrozen -> state.withFrozenScale(state.frozenImageScale * intent.scaleFactor)
            else -> state.withZoom(state.zoomRatio * intent.scaleFactor)
        }
        is MirrorIntent.SetExposure -> state.withExposure(intent.ev)
        MirrorIntent.ResetExposure -> state.copy(exposureEv = 0f)
        MirrorIntent.ToggleRingLight -> state.withRingLight { it.copy(enabled = !it.enabled) }
        is MirrorIntent.SetRingLightIntensity -> if (!intent.intensity.isFinite()) state else state.withRingLight {
            it.copy(
                enabled = true,
                intensity = intent.intensity.coerceIn(RingLightSettings.MIN_INTENSITY, RingLightSettings.MAX_INTENSITY),
            )
        }
        is MirrorIntent.SetLightWarmth -> state.withRingLight { it.copy(enabled = true, warmth = intent.warmth) }
        MirrorIntent.ToggleFreeze -> when {
            state.isFrozen -> state.copy(isFrozen = false, frozenImageScale = 1f)
            state.canFreeze -> state.copy(isFrozen = true, frozenImageScale = 1f, controlsVisible = true)
            else -> state
        }
        MirrorIntent.ToggleMirrorMode -> state.withSettings {
            it.copy(mirrorMode = if (it.mirrorMode == MirrorMode.MIRROR) MirrorMode.TRUE_VIEW else MirrorMode.MIRROR)
        }
        is MirrorIntent.SetMirrorMode -> state.withSettings { it.copy(mirrorMode = intent.mode) }
        MirrorIntent.DismissMessage -> state.copy(message = null)
        is MirrorIntent.SetSettingsOpen -> state.copy(settingsOpen = intent.open, controlsVisible = state.controlsVisible || intent.open)
        is MirrorIntent.SetAutoMaxBrightness -> state.withSettings { it.copy(autoMaxBrightness = intent.enabled) }
        MirrorIntent.ResetSettingsToDefaults -> state.copy(
            settings = MirrorSettings.DEFAULT.copy(tapHintShown = state.settings.tapHintShown),
            exposureEv = 0f,
            lastNonDefaultZoom = MirrorUiState.DEFAULT_DOUBLE_TAP_ZOOM,
        ).withZoom(MirrorSettings.DEFAULT.zoomRatio)
    }

    fun reduce(state: MirrorUiState, result: MirrorResult): MirrorUiState = when (result) {
        is MirrorResult.PermissionResolved -> state.copy(permission = result.status, permissionChecked = true)
        MirrorResult.CameraStarting -> state.copy(cameraStatus = CameraStatus.STARTING, cameraError = null)
        is MirrorResult.CameraStarted -> {
            val caps = result.capabilities
            state.copy(
                cameraStatus = CameraStatus.RUNNING,
                cameraError = null,
                capabilities = caps,
                exposureEv = caps.clampExposure(state.exposureEv),
            ).withSettings { it.copy(zoomRatio = caps.clampZoom(it.zoomRatio)) }
        }
        is MirrorResult.CameraFailed -> state.copy(
            cameraStatus = CameraStatus.ERROR,
            cameraError = result.error,
            isFrozen = false,
            frozenImageScale = 1f,
            permission = if (result.error == CameraError.PERMISSION_DENIED) PermissionStatus.DENIED else state.permission,
        )
        MirrorResult.CameraStopped ->
            if (state.cameraStatus == CameraStatus.ERROR) state.copy(isFrozen = false, frozenImageScale = 1f)
            else state.copy(cameraStatus = CameraStatus.IDLE, isFrozen = false, frozenImageScale = 1f)
        MirrorResult.CaptureStarted -> state.copy(captureStatus = CaptureStatus.CAPTURING)
        MirrorResult.SavingStarted -> state.copy(captureStatus = CaptureStatus.SAVING)
        is MirrorResult.CaptureFinished -> when (val r = result.result) {
            is CaptureResult.Saved -> state.copy(
                captureStatus = CaptureStatus.IDLE,
                lastSavedPhotoUri = r.uri,
            ).withMessage(UserMessage.PHOTO_SAVED)
            is CaptureResult.Failed -> state.copy(captureStatus = CaptureStatus.IDLE).withMessage(
                when (r.reason) {
                    CaptureFailure.SAVE_PERMISSION_DENIED -> UserMessage.SAVE_PERMISSION_DENIED
                    CaptureFailure.STORAGE_FULL -> UserMessage.STORAGE_FULL
                    CaptureFailure.CAMERA_ERROR, CaptureFailure.UNKNOWN -> UserMessage.CAPTURE_FAILED
                },
            )
        }
        MirrorResult.ControlsTimedOut ->
            if (state.shouldAutoHideControls) state.copy(controlsVisible = false) else state
        MirrorResult.MessageTimedOut -> state.copy(message = null)
        MirrorResult.TapHintShown -> state.copy(showTapHint = true).withSettings { it.copy(tapHintShown = true) }
        MirrorResult.TapHintTimedOut -> state.copy(showTapHint = false)
    }

    private const val ZOOM_EPSILON = 0.01f
    private const val REMEMBER_ZOOM_EPSILON = 0.05f

    /** A single zoom change at least this big is a deliberate jump (preset/slider tap), not a gesture step. */
    private const val DISCRETE_ZOOM_JUMP = 0.5f

    private fun MirrorUiState.withZoom(ratio: Float): MirrorUiState {
        if (!ratio.isFinite()) return this
        val clamped = capabilities?.clampZoom(ratio)
            ?: ratio.coerceIn(MirrorSettings.MIN_ZOOM_RATIO, MirrorSettings.MAX_ZOOM_RATIO)
        // QA-BUG-03: remember the zoom the user moved TO (zooming in, or a discrete jump such as a
        // preset) as the double-tap target. Continuous steps back toward 1x (pinching/sliding out)
        // don't overwrite it, so pinch 2.5x -> 1x then double tap returns to 2.5x (MVP-03.AC3).
        val old = zoomRatio
        val towardOne = abs(clamped - 1f) < abs(old - 1f)
        val jump = abs(clamped - old) >= DISCRETE_ZOOM_JUMP
        val remember = abs(clamped - 1f) > REMEMBER_ZOOM_EPSILON && (!towardOne || jump)
        return withSettings { it.copy(zoomRatio = clamped) }
            .let { if (remember) it.copy(lastNonDefaultZoom = clamped) else it }
    }

    /** Sets a transient message and bumps [MirrorUiState.messageId] so repeats are distinct events. */
    fun MirrorUiState.withMessage(message: UserMessage): MirrorUiState =
        copy(message = message, messageId = messageId + 1)

    private fun MirrorUiState.withFrozenScale(scale: Float): MirrorUiState =
        if (!scale.isFinite()) this else copy(frozenImageScale = scale.coerceIn(1f, MirrorUiState.MAX_FROZEN_IMAGE_SCALE))

    private fun MirrorUiState.withExposure(ev: Float): MirrorUiState {
        val caps = capabilities ?: return this
        if (!ev.isFinite() || !caps.supportsExposureCompensation) return this
        return copy(exposureEv = caps.clampExposure(ev))
    }

    private inline fun MirrorUiState.withSettings(block: (MirrorSettings) -> MirrorSettings) =
        copy(settings = block(settings))

    private inline fun MirrorUiState.withRingLight(block: (RingLightSettings) -> RingLightSettings) =
        withSettings { it.copy(ringLight = block(it.ringLight)) }
}

/** Controls auto-hide only over a live, unfrozen preview with nothing in flight (MVP-02, NFR-10, MVP-15). */
internal val MirrorUiState.shouldAutoHideControls: Boolean
    get() = controlsVisible && isCameraRunning && !isFrozen && captureStatus == CaptureStatus.IDLE &&
        !screenReaderActive && !settingsOpen

/** QA-BUG-10: the camera reports "started" but we are not trying to run it. */
internal val MirrorUiState.isStaleCameraStart: Boolean
    get() = !isForeground || cameraStatus == CameraStatus.IDLE
