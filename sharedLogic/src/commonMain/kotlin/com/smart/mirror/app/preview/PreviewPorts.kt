package com.smart.mirror.app.preview

import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraCaptureOutcome
import com.smart.mirror.app.domain.model.CameraConfig
import com.smart.mirror.app.domain.model.CaptureRequest
import com.smart.mirror.app.domain.model.CapturedPhoto
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.SaveOutcome
import com.smart.mirror.app.domain.port.BrightnessController
import com.smart.mirror.app.domain.port.CameraController
import com.smart.mirror.app.domain.port.CameraListener
import com.smart.mirror.app.domain.port.PermissionChecker
import com.smart.mirror.app.domain.port.PhotoSaver
import com.smart.mirror.app.presentation.mirror.MirrorPorts

/**
 * Hardware-free port implementations for Compose/SwiftUI previews, placeholder screens and
 * simulator runs. The "camera" reports typical front-camera capabilities and "saves" nothing.
 * Replace with real adapters in the apps.
 */
object PreviewPorts {
    val capabilities = CameraCapabilities(
        minZoomRatio = 1f,
        maxZoomRatio = 5f,
        minExposureEv = -2f,
        maxExposureEv = 2f,
        exposureStepEv = 1f / 3f,
    )

    fun create(): MirrorPorts = MirrorPorts(
        camera = PreviewCameraController(),
        brightness = object : BrightnessController {
            override fun applyBrightness(level: Float) = Unit
            override fun restoreBrightness() = Unit
            override fun setKeepScreenOn(enabled: Boolean) = Unit
        },
        photoSaver = object : PhotoSaver {
            override fun save(photo: CapturedPhoto, onResult: (SaveOutcome) -> Unit) =
                onResult(SaveOutcome.Saved("preview://photo"))
        },
        permissions = object : PermissionChecker {
            override fun cameraPermissionStatus() = PermissionStatus.GRANTED
            override fun requestCameraPermission(onResult: (PermissionStatus) -> Unit) = onResult(PermissionStatus.GRANTED)
            override fun openAppSettings() = Unit
        },
    )

    private class PreviewCameraController : CameraController {
        private var listener: CameraListener? = null
        override fun setListener(listener: CameraListener?) { this.listener = listener }
        override fun start(config: CameraConfig) { listener?.onCameraStarted(capabilities) }
        override fun stop() { listener?.onCameraStopped() }
        override fun setZoomRatio(ratio: Float) = Unit
        override fun setExposureCompensation(ev: Float) = Unit
        override fun setPreviewMirrored(mirrored: Boolean) = Unit
        override fun setPreviewFrozen(frozen: Boolean) = Unit
        override fun setMaxFrameRate(fps: Int) = Unit
        override fun capturePhoto(request: CaptureRequest, onResult: (CameraCaptureOutcome) -> Unit) =
            onResult(CameraCaptureOutcome.Success(CapturedPhoto("preview://capture", 1080, 1440, request.mirrored)))
    }
}
