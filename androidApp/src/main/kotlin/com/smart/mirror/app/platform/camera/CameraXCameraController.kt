package com.smart.mirror.app.platform.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest as Camera2CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraCaptureOutcome
import com.smart.mirror.app.domain.model.CameraConfig
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CaptureRequest
import com.smart.mirror.app.domain.model.CapturedPhoto
import com.smart.mirror.app.domain.port.CameraController
import com.smart.mirror.app.domain.port.CameraListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX implementation of the shared [CameraController] port (front camera, Preview +
 * ImageCapture, optional ImageAnalysis for the future frame pipeline).
 *
 * ## Preview surface choice
 * We render into a [PreviewView] (hosted in Compose through `AndroidView`) in
 * [PreviewView.ImplementationMode.COMPATIBLE] (TextureView) mode rather than `CameraXViewfinder`
 * or a SurfaceView, because:
 *  - Mirror/true view must switch in <= 100 ms without rebinding (MVP-09.AC2): a TextureView
 *    honours `View.scaleX = -1` instantly, while a SurfaceView ignores view transforms and
 *    changing `Preview` mirror mode would force a rebind (black frame).
 *  - Freeze (MVP-07.AC1) needs "exactly what is on screen" within 100 ms: [PreviewView.getBitmap]
 *    returns the displayed, FILL_CENTER-cropped, correctly rotated frame from the TextureView.
 *  - The digital zoom top-up above the sensor's max zoom is also a plain view scale.
 *  - PreviewView handles display rotation (incl. 180 degree flips) and front-camera mirroring.
 *
 * ## Lifecycle
 * Use cases are bound to [sessionOwner], a private lifecycle driven by the shared ViewModel's
 * [start]/[stop] (which follow the Activity's ON_START/ON_STOP via `ScreenStarted/Stopped`),
 * not to the Activity itself. The adapter therefore survives Activity recreation while the
 * ViewModel does; the PreviewView is attached/detached separately ([attachPreviewView]).
 *
 * All [CameraController] methods must be called on the main thread (the shared ViewModel does).
 */
class CameraXCameraController(
    context: Context,
    private val photoBuffer: CapturedPhotoBuffer,
    private val frameHook: FrameAnalysisHook? = null,
) : CameraController {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "photo-capture") }

    // Kick off CameraX initialisation immediately (NFR-01: camera opens in parallel with UI).
    private val providerFuture = ProcessCameraProvider.getInstance(appContext)

    private val sessionOwner = SessionLifecycleOwner()
    private var cameraListener: CameraListener? = null

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var startRequested = false
    private var reportedOpen = false
    private var sawActiveState = false
    private var lastReportedError: CameraError? = null
    private var observedCamera: Camera? = null

    private var previewView: PreviewView? = null
    private var mirrored = true
    private var frozen = false
    private var requestedZoom = 1f
    private var hardwareZoomMin = 1f
    private var hardwareZoomMax = 1f
    private var digitalZoom = 1f
    private var maxFps = 60

    private val _frozenFrame = MutableStateFlow<Bitmap?>(null)

    /**
     * The frozen frame in the camera's natural (mirrored, display-oriented) form, or null.
     * The UI shows it with the same `scaleX` as the live preview. Pixels stay in the platform
     * layer; the shared state only knows `isFrozen`.
     */
    val frozenFrame: StateFlow<Bitmap?> = _frozenFrame.asStateFlow()

    private val clearFrozenFrame = Runnable { if (!frozen) _frozenFrame.value = null }

    /** Fails a start that never reaches OPEN (e.g. a HAL open timeout), so the UI can offer Retry. */
    private val openWatchdog = Runnable { onOpenWatchdog() }
    private var watchdogExtensions = 0

    /** A bind waiting for the PreviewView's surface provider (so the session is built once). */
    private var bindWaitingForView = false
    private val bindWithoutView = Runnable {
        if (bindWaitingForView && startRequested) {
            bindWaitingForView = false
            rebind()
        }
    }

    private val cameraStateObserver = Observer<CameraState> { state -> onCameraState(state) }

    init {
        frameHook?.setOnAnalysisEnabledChanged { mainHandler.post { if (camera != null) rebind() } }
    }

    // --- PreviewView attachment (UI side) --------------------------------------------------

    @MainThread
    fun attachPreviewView(view: PreviewView) {
        previewView = view
        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        view.scaleType = PreviewView.ScaleType.FILL_CENTER
        applyViewTransform()
        if (bindWaitingForView && startRequested) {
            mainHandler.removeCallbacks(bindWithoutView)
            bindWaitingForView = false
            rebind()
            return
        }
        if (!frozen) preview?.setSurfaceProvider(mainExecutor, view.surfaceProvider)
    }

    @MainThread
    fun detachPreviewView(view: PreviewView) {
        if (previewView !== view) return
        preview?.setSurfaceProvider(null)
        previewView = null
    }

    // --- CameraController -------------------------------------------------------------------

    override fun setListener(listener: CameraListener?) {
        cameraListener = listener
    }

    override fun start(config: CameraConfig) {
        startRequested = true
        reportedOpen = false
        lastReportedError = null
        requestedZoom = config.initialZoomRatio
        // QA-BUG-11: the provider is pre-warmed in Application.onCreate, so it is usually ready:
        // bind right now instead of posting another main-thread hop before the camera opens.
        if (providerFuture.isDone) bind() else providerFuture.addListener({ if (startRequested) bind() }, mainExecutor)
    }

    override fun stop() {
        startRequested = false
        bindWaitingForView = false
        mainHandler.removeCallbacks(bindWithoutView)
        mainHandler.removeCallbacks(openWatchdog)
        observedCamera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
        observedCamera = null
        // unbindAll closes the camera immediately (MVP-14.AC1: released within 500 ms).
        provider?.unbindAll()
        sessionOwner.setState(Lifecycle.State.CREATED)
        camera = null
        preview = null
        imageCapture = null
        frozen = false
        mainHandler.removeCallbacks(clearFrozenFrame)
        _frozenFrame.value = null // MVP-07.AC7: backgrounding discards the frozen frame.
    }

    override fun setZoomRatio(ratio: Float) {
        requestedZoom = ratio
        applyZoom()
    }

    override fun setExposureCompensation(ev: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val index = exposureIndexFor(ev, state.exposureCompensationStep.toFloat(), range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    override fun setPreviewMirrored(mirrored: Boolean) {
        this.mirrored = mirrored
        applyViewTransform()
    }

    override fun setPreviewFrozen(frozen: Boolean) {
        if (this.frozen == frozen) return
        this.frozen = frozen
        mainHandler.removeCallbacks(clearFrozenFrame)
        if (frozen) {
            // Grab exactly what is on screen (rotation, FILL_CENTER crop, sensor zoom, front
            // mirroring), then apply our digital top-up crop, which is a view scale.
            val shown = previewView?.bitmap
            _frozenFrame.value = shown?.let { PhotoTransforms.cropAndMirror(it, digitalZoom, mirror = false) }
            // Pause the preview stream to save battery (NFR-05); ImageCapture stays bound.
            preview?.setSurfaceProvider(null)
        } else {
            previewView?.let { preview?.setSurfaceProvider(mainExecutor, it.surfaceProvider) }
            // Keep the frozen image over the preview until the stream is back (MVP-07.AC5, no black flash).
            mainHandler.postDelayed(clearFrozenFrame, RESUME_OVERLAY_MILLIS)
        }
    }

    override fun setMaxFrameRate(fps: Int) {
        maxFps = fps
        applyFrameRate()
    }

    override fun capturePhoto(request: CaptureRequest, onResult: (CameraCaptureOutcome) -> Unit) {
        val done = AtomicBoolean(false)
        val finish: (CameraCaptureOutcome) -> Unit = { if (done.compareAndSet(false, true)) onResult(it) }

        val frozenFrame = _frozenFrame.value
        if (request.fromFrozenFrame && frozenFrame != null) {
            // The frozen bitmap is in natural (mirrored) orientation; un-flip it for true view.
            captureExecutor.execute {
                finish(encodeOutcome(request) { PhotoTransforms.cropAndMirror(frozenFrame, 1f, mirror = !request.mirrored) })
            }
            return
        }

        val capture = imageCapture
        if (capture == null) {
            finish(CameraCaptureOutcome.Failure(CameraError.UNKNOWN))
            return
        }
        val view = previewView
        val viewW = view?.width ?: 0
        val viewH = view?.height ?: 0
        val zoomTopUp = digitalZoom
        capture.targetRotation = view?.display?.rotation ?: Surface.ROTATION_0
        try {
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val outcome = try {
                            val rotation = image.imageInfo.rotationDegrees
                            val raw = image.toBitmap()
                            image.close()
                            // A raw front-camera still is un-mirrored ("true view").
                            encodeOutcome(request) {
                                PhotoTransforms.toDisplayedImage(raw, rotation, viewW, viewH, zoomTopUp, mirror = request.mirrored)
                            }
                        } catch (t: Throwable) {
                            image.close()
                            Log.w(TAG, "Processing the capture failed", t)
                            CameraCaptureOutcome.Failure(CameraError.UNKNOWN)
                        }
                        finish(outcome)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "takePicture failed (${exception.imageCaptureError})", exception)
                        finish(
                            CameraCaptureOutcome.Failure(
                                if (exception.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                                    CameraError.DISCONNECTED
                                } else {
                                    CameraError.UNKNOWN
                                },
                            ),
                        )
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "takePicture threw", t)
            finish(CameraCaptureOutcome.Failure(CameraError.UNKNOWN))
        }
    }

    /** Frees threads; call when the owner (host ViewModel) is cleared, after [stop]. */
    fun release() {
        frameHook?.setOnAnalysisEnabledChanged(null)
        mainHandler.removeCallbacksAndMessages(null)
        captureExecutor.shutdown()
        sessionOwner.setState(Lifecycle.State.DESTROYED)
    }

    // --- internals --------------------------------------------------------------------------

    private inline fun encodeOutcome(request: CaptureRequest, produce: () -> Bitmap): CameraCaptureOutcome =
        try {
            val bitmap = produce()
            val jpeg = PhotoTransforms.encodeJpeg(bitmap)
            val key = photoBuffer.put(jpeg)
            CameraCaptureOutcome.Success(CapturedPhoto(key, bitmap.width, bitmap.height, request.mirrored))
        } catch (t: Throwable) {
            Log.w(TAG, "Encoding the photo failed", t)
            CameraCaptureOutcome.Failure(CameraError.UNKNOWN)
        }

    private fun bind() {
        val cameraProvider = try {
            providerFuture.get()
        } catch (t: Throwable) {
            Log.e(TAG, "CameraX init failed", t)
            reportError(CameraError.UNKNOWN)
            return
        }
        provider = cameraProvider
        val hasFront = try {
            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        } catch (t: Throwable) {
            false
        }
        if (!hasFront) {
            reportError(CameraError.NO_FRONT_CAMERA)
            return
        }
        rebind()
    }

    private fun rebind() {
        val cameraProvider = provider ?: return
        if (!startRequested) return
        if (previewView == null && !frozen && camera == null) {
            // QA-BUG-11: binding before the viewfinder exists makes CameraX build the capture
            // session twice (without, then with the preview surface). The PreviewView attaches in
            // the first composition, so wait for it briefly; bind anyway after a short grace.
            if (!bindWaitingForView) {
                bindWaitingForView = true
                mainHandler.postDelayed(bindWithoutView, VIEW_ATTACH_GRACE_MILLIS)
            }
            return
        }
        observedCamera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
        observedCamera = null

        // Same 16:9 aspect for preview and still: the still has the preview's field of view,
        // so the FILL_CENTER crop we apply to it matches the screen. 16:9 also crops less on phones.
        val aspect = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        val newPreview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
            .build()
        val newCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(PhotoTransforms.JPEG_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    // A mirror snapshot doesn't need 50 MP; bounded size keeps memory in NFR-07.
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(2560, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .build()
        val useCases = mutableListOf<UseCase>(newPreview, newCapture)
        val hook = frameHook
        if (hook != null && hook.isAnalysisEnabled) {
            useCases += ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // latest frame wins
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build()
                .apply { setAnalyzer(hook.analysisExecutor, hook.analyzer) }
        }

        try {
            // Surface provider first, then bind: the first session already includes the preview
            // surface, so there is no reconfiguration when it arrives (QA-BUG-11).
            val view = previewView
            if (view != null && !frozen) newPreview.setSurfaceProvider(mainExecutor, view.surfaceProvider)
            cameraProvider.unbindAll()
            sessionOwner.setState(Lifecycle.State.RESUMED)
            val cam = cameraProvider.bindToLifecycle(sessionOwner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases.toTypedArray())
            camera = cam
            preview = newPreview
            imageCapture = newCapture
            readHardwareZoom(cam)
            applyZoom()
            applyFrameRate()
            observedCamera = cam
            sawActiveState = false
            watchdogExtensions = 0
            mainHandler.removeCallbacks(openWatchdog)
            mainHandler.postDelayed(openWatchdog, OPEN_TIMEOUT_MILLIS)
            cam.cameraInfo.cameraState.observeForever(cameraStateObserver)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing", e)
            reportError(CameraError.PERMISSION_DENIED)
        } catch (t: Throwable) {
            Log.e(TAG, "Binding the camera failed", t)
            reportError(CameraError.UNKNOWN)
        }
    }

    private fun onCameraState(state: CameraState) {
        if (!startRequested) return
        val error = state.error
        if (state.type != CameraState.Type.CLOSED) sawActiveState = true
        when {
            state.type == CameraState.Type.OPEN && error == null -> {
                if (!reportedOpen) {
                    reportedOpen = true
                    lastReportedError = null
                    mainHandler.removeCallbacks(openWatchdog)
                    val cam = camera ?: return
                    readHardwareZoom(cam)
                    cameraListener?.onCameraStarted(capabilities(cam))
                }
            }
            error != null -> {
                // CameraX keeps retrying recoverable errors (e.g. another app released the camera);
                // when it reopens we report onCameraStarted again (MVP-12.AC5 auto-resume).
                reportedOpen = false
                reportError(cameraErrorFor(error.code))
            }
            state.type == CameraState.Type.CLOSED -> {
                reportedOpen = false
                // CameraX publishes states with LiveData.postValue, so a CLOSING(error) can be
                // coalesced into a plain CLOSED. We only get here while we still want the camera
                // (stop() removes the observer first), so an unexpected close is a failure.
                if (sawActiveState) reportError(CameraError.DISCONNECTED)
            }
        }
    }

    /**
     * QA-BUG-12: LiveData.postValue can coalesce OPEN away under main-thread load, so before
     * failing, look at the camera's CURRENT state: already open -> report started; still
     * (re)opening without an error -> give CameraX more time (bounded); otherwise fail.
     */
    private fun onOpenWatchdog() {
        if (!startRequested || reportedOpen || lastReportedError != null) return
        val current = camera?.cameraInfo?.cameraState?.value
        when {
            current != null && current.type == CameraState.Type.OPEN && current.error == null -> onCameraState(current)
            current != null && current.error == null && watchdogExtensions < MAX_WATCHDOG_EXTENSIONS &&
                (current.type == CameraState.Type.OPENING || current.type == CameraState.Type.PENDING_OPEN) -> {
                watchdogExtensions++
                mainHandler.postDelayed(openWatchdog, OPEN_TIMEOUT_MILLIS / 2)
            }
            else -> {
                Log.w(TAG, "Camera did not open (state=${current?.type})")
                reportError(CameraError.UNKNOWN)
            }
        }
    }

    private fun reportError(error: CameraError) {
        if (lastReportedError == error) return
        lastReportedError = error
        cameraListener?.onCameraError(error)
    }

    private fun capabilities(cam: Camera): CameraCapabilities {
        val exposure = cam.cameraInfo.exposureState
        val step = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationStep.toFloat() else 0f
        val range = exposure.exposureCompensationRange
        return CameraCapabilities(
            minZoomRatio = hardwareZoomMin,
            maxZoomRatio = CameraZoom.advertisedMax(hardwareZoomMax),
            minExposureEv = if (step > 0f) range.lower * step else 0f,
            maxExposureEv = if (step > 0f) range.upper * step else 0f,
            exposureStepEv = step,
        )
    }

    private fun readHardwareZoom(cam: Camera) {
        val zoom = cam.cameraInfo.zoomState.value
        hardwareZoomMin = zoom?.minZoomRatio ?: 1f
        hardwareZoomMax = zoom?.maxZoomRatio ?: 1f
    }

    private fun applyZoom() {
        val split = CameraZoom.split(requestedZoom, hardwareZoomMin, hardwareZoomMax)
        camera?.cameraControl?.setZoomRatio(split.hardware)
        if (!sameZoom(split.digital, digitalZoom)) {
            digitalZoom = split.digital
            applyViewTransform()
        }
    }

    /**
     * PreviewView already mirrors the front camera ("selfie" orientation = mirror mode), so
     * true view is `scaleX = -1`. The digital zoom top-up scales around the view's centre.
     */
    private fun applyViewTransform() {
        val view = previewView ?: return
        view.scaleX = digitalZoom * if (mirrored) 1f else -1f
        view.scaleY = digitalZoom
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyFrameRate() {
        val cam = camera ?: return
        val available = cam.cameraInfo.supportedFrameRateRanges.map { it.lower..it.upper }
        val range = chooseFpsRange(available, maxFps) ?: return
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(Camera2CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(range.first, range.last))
                    .build(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Setting fps range failed", t)
        }
    }

    /** Lifecycle that CameraX binds to; moved by [start]/[stop] only. */
    private class SessionLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry

        fun setState(state: Lifecycle.State) {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            registry.currentState = state
        }
    }

    companion object {
        private const val TAG = "CameraXController"
        private const val RESUME_OVERLAY_MILLIS = 350L
        private const val OPEN_TIMEOUT_MILLIS = 8_000L
        private const val MAX_WATCHDOG_EXTENSIONS = 2
        private const val VIEW_ATTACH_GRACE_MILLIS = 500L
    }
}
