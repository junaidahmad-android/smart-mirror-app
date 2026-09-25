package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.core.FlowSubscription
import com.smart.mirror.app.core.subscribeIn
import com.smart.mirror.app.data.settings.SettingsRepository
import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraConfig
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraLens
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureRequest
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.port.BrightnessController
import com.smart.mirror.app.domain.port.CameraController
import com.smart.mirror.app.domain.port.CameraListener
import com.smart.mirror.app.domain.port.PermissionChecker
import com.smart.mirror.app.domain.port.PhotoSaver
import com.smart.mirror.app.domain.usecase.CapturePhotoUseCase
import com.smart.mirror.app.processing.PipelineCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Screen-scoped platform ports the mirror screen needs. */
class MirrorPorts(
    val camera: CameraController,
    val brightness: BrightnessController,
    val photoSaver: PhotoSaver,
    val permissions: PermissionChecker,
)

internal data class MirrorTiming(
    val controlsAutoHideMillis: Long = 4_000,
    val messageMillis: Long = 2_500,
    val persistDebounceMillis: Long = 300,
    val tapHintMillis: Long = 3_000,
)

/**
 * Shared MVI state holder for the mirror screen.
 *
 * - Input: [onIntent] (call on the main thread).
 * - Output: [state] (`StateFlow<MirrorUiState>`), or [observeState] from Swift.
 * - Hardware side effects are derived from state diffs in [syncSideEffects], so the reducer stays pure.
 *
 * Lifetime: create once per screen (Android: inside an androidx ViewModel; iOS: inside an
 * ObservableObject) and call [close] when that owner is destroyed.
 */
class MirrorViewModel internal constructor(
    private val ports: MirrorPorts,
    private val settingsRepository: SettingsRepository,
    private val pipeline: PipelineCoordinator,
    private val scope: CoroutineScope,
    private val timing: MirrorTiming = MirrorTiming(),
) {
    private val camera = ports.camera
    private val capturePhoto = CapturePhotoUseCase(ports.camera, ports.photoSaver)

    private val _state = MutableStateFlow(MirrorUiState(settings = settingsRepository.settings.value))
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()
    val currentState: MirrorUiState get() = _state.value

    private var hideControlsJob: Job? = null
    private var messageJob: Job? = null
    private var persistJob: Job? = null
    private var tapHintJob: Job? = null

    private val cameraListener = object : CameraListener {
        override fun onCameraStarted(capabilities: CameraCapabilities) = post(MirrorResult.CameraStarted(capabilities))
        override fun onCameraError(error: CameraError) = post(MirrorResult.CameraFailed(error))
        override fun onCameraStopped() = post(MirrorResult.CameraStopped)
    }

    init {
        camera.setListener(cameraListener)
    }

    fun onIntent(intent: MirrorIntent) {
        setState(MirrorReducer.reduce(currentState, intent))
        when (intent) {
            MirrorIntent.ScreenStarted -> refreshPermission()
            MirrorIntent.ScreenStopped -> stopCamera()
            MirrorIntent.RequestCameraPermission ->
                ports.permissions.requestCameraPermission { post(MirrorResult.PermissionResolved(it)) }
            MirrorIntent.OpenAppSettings -> ports.permissions.openAppSettings()
            MirrorIntent.RetryCamera -> startCameraIfNeeded()
            MirrorIntent.CapturePhoto -> startCapture()
            is MirrorIntent.ThermalStateChanged ->
                if (currentState.isOverheated) stopCamera() else startCameraIfNeeded()
            is MirrorIntent.SetScreenReaderActive, is MirrorIntent.SetSettingsOpen -> scheduleControlsHide()
            else -> Unit
        }
        if (intent.isControlInteraction) scheduleControlsHide()
    }

    /** Swift-friendly subscription; [onEach] runs on the main thread, first with the current state. */
    fun observeState(onEach: (MirrorUiState) -> Unit): FlowSubscription = state.subscribeIn(scope, onEach)

    /** Releases hardware (camera, brightness, pipeline) and cancels all work. */
    fun close() {
        persistJob?.let { if (it.isActive) settingsRepository.update { currentState.settings } }
        camera.setListener(null)
        if (currentState.cameraStatus != CameraStatus.IDLE) camera.stop()
        if (currentState.brightnessLevel != null) ports.brightness.restoreBrightness()
        if (currentState.keepScreenOn) ports.brightness.setKeepScreenOn(false)
        pipeline.deactivate()
        scope.cancel()
    }

    // --- results & side effects -------------------------------------------------------------

    private fun post(result: MirrorResult) {
        scope.launch { handle(result) }
    }

    private fun handle(result: MirrorResult) {
        if (result is MirrorResult.CameraStarted && currentState.isStaleCameraStart) {
            // QA-BUG-10: a session we already stopped came up late; release it and ignore it so
            // the next ScreenStarted still restarts the camera.
            camera.stop()
            return
        }
        val next = MirrorReducer.reduce(currentState, result)
        if (next.messageId != currentState.messageId && next.message != null && next.message == currentState.message) {
            // QA-BUG-06: the same message again (e.g. a second "Saved" within the toast window).
            // Clear it first and re-issue in a separate dispatch, so observers keyed on `message`
            // (not just `messageId`) see a fresh change instead of a conflated no-op.
            setState(currentState.copy(message = null))
            scope.launch { applyResult(result) }
            return
        }
        applyResult(result)
    }

    private fun applyResult(result: MirrorResult) {
        setState(MirrorReducer.reduce(currentState, result))
        when (result) {
            is MirrorResult.PermissionResolved -> startCameraIfNeeded()
            is MirrorResult.CameraStarted -> {
                scheduleControlsHide()
                if (!currentState.settings.tapHintShown) handle(MirrorResult.TapHintShown)
            }
            MirrorResult.TapHintShown -> {
                tapHintJob?.cancel()
                tapHintJob = scope.launch {
                    delay(timing.tapHintMillis)
                    handle(MirrorResult.TapHintTimedOut)
                }
            }
            is MirrorResult.CaptureFinished -> scheduleControlsHide()
            else -> Unit
        }
    }

    private fun setState(new: MirrorUiState) {
        val old = _state.value
        if (old == new) return
        _state.value = new
        syncSideEffects(old, new)
    }

    /** Pushes state changes to hardware/persistence. Only acts on what actually changed. */
    private fun syncSideEffects(old: MirrorUiState, new: MirrorUiState) {
        if (old.settings != new.settings) schedulePersist()

        val justStarted = new.isCameraRunning && !old.isCameraRunning
        if (new.isCameraRunning) {
            if (justStarted || old.zoomRatio != new.zoomRatio) camera.setZoomRatio(new.zoomRatio)
            if (justStarted || old.exposureEv != new.exposureEv) camera.setExposureCompensation(new.exposureEv)
            if (justStarted || old.previewMirrored != new.previewMirrored) camera.setPreviewMirrored(new.previewMirrored)
            if (old.isFrozen != new.isFrozen) camera.setPreviewFrozen(new.isFrozen)
            if (justStarted || old.maxFrameRate != new.maxFrameRate) camera.setMaxFrameRate(new.maxFrameRate)
        }
        if (justStarted) pipeline.activate()
        if (old.isCameraRunning && !new.isCameraRunning) pipeline.deactivate()

        if (old.brightnessLevel != new.brightnessLevel) {
            val level = new.brightnessLevel
            if (level != null) ports.brightness.applyBrightness(level) else ports.brightness.restoreBrightness()
        }
        if (old.keepScreenOn != new.keepScreenOn) ports.brightness.setKeepScreenOn(new.keepScreenOn)

        if (new.message != null && (new.message != old.message || new.messageId != old.messageId)) scheduleMessageDismiss()
        if (new.controlsVisible && !old.controlsVisible) scheduleControlsHide()
        if (!new.controlsVisible) hideControlsJob?.cancel()
    }

    private fun refreshPermission() {
        handle(MirrorResult.PermissionResolved(ports.permissions.cameraPermissionStatus()))
    }

    private fun startCameraIfNeeded() {
        val s = currentState
        val canStart = s.isForeground && s.permission == PermissionStatus.GRANTED && !s.isOverheated &&
            (s.cameraStatus == CameraStatus.IDLE || s.cameraStatus == CameraStatus.ERROR)
        if (!canStart) return
        handle(MirrorResult.CameraStarting)
        camera.start(CameraConfig(lens = CameraLens.FRONT, initialZoomRatio = s.zoomRatio))
    }

    private fun stopCamera() {
        hideControlsJob?.cancel()
        if (currentState.cameraStatus == CameraStatus.IDLE) return
        camera.stop()
        handle(MirrorResult.CameraStopped)
    }

    private fun startCapture() {
        val s = currentState
        if (!s.canCapture) return
        handle(MirrorResult.CaptureStarted)
        val request = CaptureRequest(mirrored = s.previewMirrored, fromFrozenFrame = s.isFrozen)
        scope.launch {
            val result = capturePhoto(request, onSaving = { handle(MirrorResult.SavingStarted) })
            handle(MirrorResult.CaptureFinished(result))
        }
    }

    private fun scheduleControlsHide() {
        hideControlsJob?.cancel()
        if (!currentState.shouldAutoHideControls) return
        hideControlsJob = scope.launch {
            delay(timing.controlsAutoHideMillis)
            handle(MirrorResult.ControlsTimedOut)
        }
    }

    private fun scheduleMessageDismiss() {
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(timing.messageMillis)
            handle(MirrorResult.MessageTimedOut)
        }
    }

    /** Debounced so continuous gestures (pinch, sliders) don't hammer storage. */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(timing.persistDebounceMillis)
            val settings: MirrorSettings = currentState.settings
            settingsRepository.update { settings }
        }
    }

    private val MirrorIntent.isControlInteraction: Boolean
        get() = when (this) {
            MirrorIntent.ScreenStarted, MirrorIntent.ScreenStopped, MirrorIntent.ScreenTapped,
            MirrorIntent.DismissMessage, MirrorIntent.OpenAppSettings, MirrorIntent.RequestCameraPermission,
            MirrorIntent.RetryCamera, is MirrorIntent.ThermalStateChanged, is MirrorIntent.SetScreenReaderActive,
            is MirrorIntent.SetSettingsOpen,
            -> false
            else -> true
        }
}
