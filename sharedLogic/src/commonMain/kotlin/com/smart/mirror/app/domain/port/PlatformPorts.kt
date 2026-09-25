package com.smart.mirror.app.domain.port

import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraCaptureOutcome
import com.smart.mirror.app.domain.model.CameraConfig
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CaptureRequest
import com.smart.mirror.app.domain.model.CapturedPhoto
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.SaveOutcome

/*
 * Platform ports: interfaces the shared layer depends on and each platform implements
 * (Kotlin on Android, Swift on iOS). Rules for implementers:
 *  - Methods are non-suspending and return quickly; long work happens on the platform's own queues.
 *  - Callbacks/listeners may be invoked from any thread; the shared layer hops to main itself.
 *  - Pixel data never crosses this boundary; only commands, metadata and opaque references do.
 */

/** Receives asynchronous camera session events. Implemented by the shared ViewModel. */
interface CameraListener {
    fun onCameraStarted(capabilities: CameraCapabilities)
    fun onCameraError(error: CameraError)
    fun onCameraStopped()
}

/**
 * Wraps CameraX (Android) / AVFoundation (iOS). Owns the preview surface binding, which is a
 * platform-UI concern (PreviewView / AVCaptureVideoPreviewLayer) and never exposed to Kotlin.
 */
interface CameraController {
    fun setListener(listener: CameraListener?)
    fun start(config: CameraConfig)
    fun stop()
    fun setZoomRatio(ratio: Float)
    fun setExposureCompensation(ev: Float)
    fun setPreviewMirrored(mirrored: Boolean)
    /** Frozen: keep showing the last frame (the session may be paused to save battery). */
    fun setPreviewFrozen(frozen: Boolean)
    /** Upper bound for the preview frame rate (60 normally, 30 under thermal pressure). */
    fun setMaxFrameRate(fps: Int)
    /** [onResult] must be invoked exactly once. */
    fun capturePhoto(request: CaptureRequest, onResult: (CameraCaptureOutcome) -> Unit)
}

/**
 * Window/screen brightness and keep-awake. Android: `WindowManager.LayoutParams.screenBrightness`
 * + `FLAG_KEEP_SCREEN_ON`; iOS: `UIScreen.brightness` + `isIdleTimerDisabled`. Never changes the
 * system setting; the implementation remembers the user's level and puts it back on restore.
 */
interface BrightnessController {
    /** [level] 0..1. */
    fun applyBrightness(level: Float)
    fun restoreBrightness()
    fun setKeepScreenOn(enabled: Boolean)
}

/** Saves a captured photo to the gallery (MediaStore / Photos). Handles its own add-only permission. */
interface PhotoSaver {
    /** [onResult] must be invoked exactly once. */
    fun save(photo: CapturedPhoto, onResult: (SaveOutcome) -> Unit)
}

interface PermissionChecker {
    fun cameraPermissionStatus(): PermissionStatus
    /** Shows the system dialog if possible. [onResult] must be invoked exactly once. */
    fun requestCameraPermission(onResult: (PermissionStatus) -> Unit)
    fun openAppSettings()
}
