package com.smart.mirror.app.fakes

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
import com.smart.mirror.app.processing.FrameProcessorDescriptor
import com.smart.mirror.app.processing.PipelineConfig
import com.smart.mirror.app.processing.PipelineListener
import com.smart.mirror.app.processing.ProcessingPipeline

val TEST_CAPS = CameraCapabilities(
    minZoomRatio = 1f,
    maxZoomRatio = 4f,
    minExposureEv = -2f,
    maxExposureEv = 2f,
    exposureStepEv = 0.5f,
)

/** Records calls; the test drives async callbacks explicitly. */
class FakeCameraController : CameraController {
    var currentListener: CameraListener? = null
    val calls = mutableListOf<String>()
    var startConfig: CameraConfig? = null
    var zoom: Float? = null
    var exposure: Float? = null
    var mirrored: Boolean? = null
    var frozen: Boolean? = null
    var maxFps: Int? = null
    var pendingCapture: Pair<CaptureRequest, (CameraCaptureOutcome) -> Unit>? = null

    override fun setListener(listener: CameraListener?) { currentListener = listener }
    override fun start(config: CameraConfig) { calls += "start"; startConfig = config }
    override fun stop() { calls += "stop" }
    override fun setZoomRatio(ratio: Float) { zoom = ratio }
    override fun setExposureCompensation(ev: Float) { exposure = ev }
    override fun setPreviewMirrored(mirrored: Boolean) { this.mirrored = mirrored }
    override fun setPreviewFrozen(frozen: Boolean) { this.frozen = frozen }
    override fun setMaxFrameRate(fps: Int) { maxFps = fps }
    override fun capturePhoto(request: CaptureRequest, onResult: (CameraCaptureOutcome) -> Unit) {
        calls += "capture"
        pendingCapture = request to onResult
    }

    fun completeCapture(outcome: CameraCaptureOutcome = CameraCaptureOutcome.Success(photo())) {
        val (_, cb) = pendingCapture ?: error("no capture pending")
        pendingCapture = null
        cb(outcome)
    }

    fun photo() = CapturedPhoto("file:///tmp/p.jpg", 100, 100, mirrored = true)
}

class FakeBrightness : BrightnessController {
    /** null = user's own brightness. */
    var level: Float? = null
    var screenKeptOn = false
    val boosted get() = level != null
    override fun applyBrightness(level: Float) { this.level = level }
    override fun restoreBrightness() { level = null }
    override fun setKeepScreenOn(enabled: Boolean) { screenKeptOn = enabled }
}

class FakePhotoSaver : PhotoSaver {
    var saved = mutableListOf<CapturedPhoto>()
    var outcome: SaveOutcome = SaveOutcome.Saved("content://saved/1")
    override fun save(photo: CapturedPhoto, onResult: (SaveOutcome) -> Unit) {
        saved += photo
        onResult(outcome)
    }
}

class FakePermissions(var status: PermissionStatus = PermissionStatus.GRANTED) : PermissionChecker {
    var requestResult: PermissionStatus = PermissionStatus.GRANTED
    var requests = 0
    var settingsOpened = 0
    override fun cameraPermissionStatus() = status
    override fun requestCameraPermission(onResult: (PermissionStatus) -> Unit) {
        requests++
        status = requestResult
        onResult(requestResult)
    }
    override fun openAppSettings() { settingsOpened++ }
}

class FakeProcessingPipeline(private val available: List<FrameProcessorDescriptor> = emptyList()) : ProcessingPipeline {
    val applied = mutableListOf<PipelineConfig>()
    var currentListener: PipelineListener? = null
    override fun availableProcessors() = available
    override fun apply(config: PipelineConfig) { applied += config }
    override fun setListener(listener: PipelineListener?) { currentListener = listener }
}
