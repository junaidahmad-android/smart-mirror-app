import AVFoundation
import SharedLogic
import UIKit

/// AVFoundation implementation of the shared `CameraController` port (ARCHITECTURE §8.2).
///
/// - All session work runs on a dedicated serial `sessionQueue`; port methods return immediately.
/// - Preview: `AVCaptureVideoPreviewLayer` (hosted by `CameraPreviewView` through `surface`).
/// - Capture: `AVCapturePhotoOutput` (JPEG, mirrored per request, no location metadata).
/// - Freeze: the preview layer connection is disabled instantly, one frame is grabbed from the
///   `AVCaptureVideoDataOutput` for pinch/pan and capture, then the session is paused (NFR-05).
/// - The video data output doubles as the future `ProcessingPipeline` hook (`FrameTap.processor`).
final class AVCameraController: NSObject, CameraController {
    /// ROADMAP MVP-04.AC1 asks for at least ±2 EV; we don't expose more than that.
    static let maxExposureMagnitude: Float = 2
    /// AVFoundation's target bias is continuous; the UI snaps to third stops.
    static let exposureStep: Float = 1.0 / 3.0

    let surface: PreviewSurface

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.smart.mirror.camera.session", qos: .userInitiated)
    private let videoQueue = DispatchQueue(label: "com.smart.mirror.camera.video", qos: .userInteractive)
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let frameTap = FrameTap()

    // Session-queue confined.
    private var device: AVCaptureDevice?
    private var isConfigured = false
    private var capabilities: CameraCapabilities?
    private var pausedForFreeze = false
    private var isFrozen = false
    private var maxFrameRate: Int32 = 60
    private var inFlightCaptures: [Int64: PhotoCaptureProcessor] = [:]

    // Shared across threads, guarded by `lock`.
    private let lock = NSLock()
    private var _listener: CameraListener?
    /// Bumped on every start/stop so late results of an older start are dropped.
    private var _generation = 0
    private var _wantsRunning = false
    private var _previewDegrees: CGFloat = 90
    private var _captureDegrees: CGFloat = 90

    private var observers: [NSObjectProtocol] = []

    override init() {
        surface = PreviewSurface(session: session)
        super.init()
        surface.onRotationChanged = { [weak self] preview, capture in self?.updateRotation(preview: preview, capture: capture) }
        observeSession()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    /// Hook for the future native frame-processing pipeline (Core ML / Vision). No-op in v1.0.
    var frameProcessor: VideoFrameProcessing? {
        get { frameTap.processor }
        set { frameTap.processor = newValue }
    }

    // MARK: - CameraController

    func setListener(listener: CameraListener?) {
        lock.withLock { _listener = listener }
    }

    func start(config: CameraConfig) {
        let generation = lock.withLock { () -> Int in
            _generation += 1
            _wantsRunning = true
            return _generation
        }
        let initialZoom = config.initialZoomRatio
        sessionQueue.async { [self] in
            guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
                return notifyError(.permissionDenied, generation: generation)
            }
            if !isConfigured {
                if let error = configureSession() { return notifyError(error, generation: generation) }
                applyZoom(initialZoom)
            }
            pausedForFreeze = false
            if !session.isRunning { session.startRunning() }
            // If the session could not start, an interruption or runtime-error notification follows.
            if session.isRunning { notifyStarted(generation: generation) }
        }
    }

    func stop() {
        lock.withLock {
            _generation += 1
            _wantsRunning = false
        }
        frameTap.cancelSnapshot()
        sessionQueue.async { [self] in
            pausedForFreeze = false
            isFrozen = false
            if session.isRunning { session.stopRunning() }
        }
        // onCameraStopped is optional; the ViewModel already moved to IDLE, and a late callback
        // could race with a quick restart, so we deliberately don't send it.
    }

    func setZoomRatio(ratio: Float) {
        sessionQueue.async { [self] in applyZoom(ratio) }
    }

    func setExposureCompensation(ev: Float) {
        sessionQueue.async { [self] in
            guard let device else { return }
            let bias = min(max(ev, device.minExposureTargetBias), device.maxExposureTargetBias)
            withLockedDevice(device) { $0.setExposureTargetBias(bias, completionHandler: nil) }
        }
    }

    func setPreviewMirrored(mirrored: Bool) {
        onMain { self.surface.setMirrored(mirrored) }
    }

    func setPreviewFrozen(frozen: Bool) {
        if frozen {
            onMain { self.surface.setLayerFrozen(true) }
            sessionQueue.async { [self] in
                isFrozen = true
                frameTap.requestSnapshot { [weak self] image in
                    DispatchQueue.main.async { self?.didReceiveFrozenFrame(image) }
                }
            }
        } else {
            frameTap.cancelSnapshot()
            onMain {
                self.surface.frozenImage = nil
                self.surface.setLayerFrozen(false)
            }
            sessionQueue.async { [self] in
                isFrozen = false
                if pausedForFreeze {
                    pausedForFreeze = false
                    if wantsRunning && !session.isRunning { session.startRunning() }
                }
            }
        }
    }

    func setMaxFrameRate(fps: Int32) {
        sessionQueue.async { [self] in
            maxFrameRate = fps
            applyFrameRate()
        }
    }

    func capturePhoto(request: CaptureRequest, onResult: @escaping (CameraCaptureOutcome) -> Void) {
        let once = OnceCallback(onResult)
        if request.fromFrozenFrame {
            onMain { self.captureFrozenFrame(mirrored: request.mirrored, onResult: once) }
            return
        }
        sessionQueue.async { [self] in
            guard session.isRunning, let connection = photoOutput.connection(with: .video) else {
                return once.call(CameraCaptureOutcomeFailure(error: .unknown))
            }
            CaptureRotation.apply(degrees: captureDegrees, to: connection)
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = request.mirrored
            }
            let settings: AVCapturePhotoSettings
            if photoOutput.availablePhotoCodecTypes.contains(.jpeg) {
                settings = AVCapturePhotoSettings(format: [
                    AVVideoCodecKey: AVVideoCodecType.jpeg,
                    AVVideoCompressionPropertiesKey: [AVVideoQualityKey: PhotoFiles.jpegQuality],
                ])
            } else {
                settings = AVCapturePhotoSettings()
            }
            settings.flashMode = .off
            settings.maxPhotoDimensions = photoOutput.maxPhotoDimensions
            let id = settings.uniqueID
            let processor = PhotoCaptureProcessor(mirrored: request.mirrored, cropAspect: surface.viewportAspect) { [weak self] outcome in
                self?.sessionQueue.async { self?.inFlightCaptures[id] = nil }
                once.call(outcome)
            }
            inFlightCaptures[id] = processor
            photoOutput.capturePhoto(with: settings, delegate: processor)
        }
    }

    // MARK: - Session configuration (session queue)

    private func configureSession() -> CameraError? {
        guard let device = Self.frontCamera() else { return .noFrontCamera }
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        if session.canSetSessionPreset(.high) { session.sessionPreset = .high }
        // iPadOS can run the app in a resizable window next to others (QA-BUG-07): keep the mirror
        // live there when the device allows it instead of showing "Camera unavailable".
        if session.isMultitaskingCameraAccessSupported { session.isMultitaskingCameraAccessEnabled = true }
        do {
            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input) else { return .inUseByAnotherApp }
            session.addInput(input)
        } catch {
            return .unknown
        }
        guard session.canAddOutput(photoOutput) else { return .unknown }
        session.addOutput(photoOutput)
        photoOutput.maxPhotoQualityPrioritization = .balanced

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(frameTap, queue: videoQueue)
        if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }

        selectFormat(for: device)
        if let largest = device.activeFormat.supportedMaxPhotoDimensions.max(by: { $0.width * $0.height < $1.width * $1.height }) {
            photoOutput.maxPhotoDimensions = largest
        }
        if let connection = videoOutput.connection(with: .video) {
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false // frozen frames are stored un-mirrored
            }
            CaptureRotation.apply(degrees: previewDegrees, to: connection)
        }

        self.device = device
        capabilities = Self.capabilities(for: device)
        isConfigured = true
        applyFrameRate()
        DispatchQueue.main.async { self.surface.cameraDidConfigure(device: device) }
        return nil
    }

    static func frontCamera() -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInTrueDepthCamera, for: .video, position: .front)
            ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
    }

    static func capabilities(for device: AVCaptureDevice) -> CameraCapabilities {
        capabilities(
            minZoom: device.minAvailableVideoZoomFactor,
            maxZoom: device.maxAvailableVideoZoomFactor,
            minBias: device.minExposureTargetBias,
            maxBias: device.maxExposureTargetBias
        )
    }

    /// Pure mapping from device limits to the shared capability model (unit tested).
    static func capabilities(minZoom: CGFloat, maxZoom: CGFloat, minBias: Float, maxBias: Float) -> CameraCapabilities {
        let lowerZoom = Float(max(minZoom, 1))
        let upperZoom = max(lowerZoom, min(Float(maxZoom), MirrorSettings.companion.MAX_ZOOM_RATIO))
        let minEv = max(minBias, -maxExposureMagnitude)
        let maxEv = min(maxBias, maxExposureMagnitude)
        let supportsExposure = maxEv > minEv
        return CameraCapabilities(
            minZoomRatio: lowerZoom,
            maxZoomRatio: upperZoom,
            minExposureEv: supportsExposure ? minEv : 0,
            maxExposureEv: supportsExposure ? maxEv : 0,
            exposureStepEv: supportsExposure ? exposureStep : 0
        )
    }

    /// Prefers a 1080p format that can do 60 fps (NFR-03), keeping the preset otherwise.
    private func selectFormat(for device: AVCaptureDevice) {
        let candidates = device.formats.filter { format in
            let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            return dims.width == 1920 && dims.height == 1080
                && format.videoSupportedFrameRateRanges.contains { $0.maxFrameRate >= 60 }
        }
        guard let best = candidates.max(by: { a, b in
            let pa = a.supportedMaxPhotoDimensions.map { Int($0.width) * Int($0.height) }.max() ?? 0
            let pb = b.supportedMaxPhotoDimensions.map { Int($0.width) * Int($0.height) }.max() ?? 0
            return pa < pb
        }) else { return }
        withLockedDevice(device) { $0.activeFormat = best }
    }

    private func applyFrameRate() {
        guard let device else { return }
        let supported = device.activeFormat.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30
        let fps = Int32(min(Double(maxFrameRate), supported).rounded(.down))
        guard fps > 0 else { return }
        withLockedDevice(device) { $0.activeVideoMinFrameDuration = CMTime(value: 1, timescale: fps) }
    }

    private func applyZoom(_ ratio: Float) {
        guard let device else { return }
        let upper = min(device.maxAvailableVideoZoomFactor, CGFloat(MirrorSettings.companion.MAX_ZOOM_RATIO))
        let factor = min(max(CGFloat(ratio), device.minAvailableVideoZoomFactor), upper)
        withLockedDevice(device) { $0.videoZoomFactor = factor }
    }

    private func withLockedDevice(_ device: AVCaptureDevice, _ body: (AVCaptureDevice) -> Void) {
        do {
            try device.lockForConfiguration()
            body(device)
            device.unlockForConfiguration()
        } catch {
            // Another client holds the lock; the next command retries.
        }
    }

    // MARK: - Rotation

    private var previewDegrees: CGFloat { lock.withLock { _previewDegrees } }
    private var captureDegrees: CGFloat { lock.withLock { _captureDegrees } }
    private var wantsRunning: Bool { lock.withLock { _wantsRunning } }

    private func updateRotation(preview: CGFloat, capture: CGFloat) {
        lock.withLock {
            _previewDegrees = preview
            _captureDegrees = capture
        }
        sessionQueue.async { [self] in
            if let connection = videoOutput.connection(with: .video) {
                CaptureRotation.apply(degrees: preview, to: connection)
            }
        }
    }

    // MARK: - Freeze (main thread)

    private func didReceiveFrozenFrame(_ image: CGImage?) {
        guard let image else { return }
        surface.frozenImage = image
        sessionQueue.async { [self] in
            // Pause the camera while frozen to save battery (MVP-07.AC5, NFR-05).
            guard isFrozen, session.isRunning else { return }
            pausedForFreeze = true
            session.stopRunning()
        }
    }

    private func captureFrozenFrame(mirrored: Bool, onResult: OnceCallback<CameraCaptureOutcome>) {
        guard let frozen = surface.frozenImage else {
            return onResult.call(CameraCaptureOutcomeFailure(error: .unknown))
        }
        let image = PhotoFiles.centerCropped(frozen, aspect: surface.viewportAspect)
        DispatchQueue.global(qos: .userInitiated).async {
            let url = PhotoFiles.newTempURL()
            do {
                try PhotoFiles.writeJPEG(image, mirrored: mirrored, to: url)
                let photo = CapturedPhoto(localUri: url.absoluteString, width: Int32(image.width), height: Int32(image.height), mirrored: mirrored)
                onResult.call(CameraCaptureOutcomeSuccess(photo: photo))
            } catch {
                PhotoFiles.removeQuietly(url)
                onResult.call(CameraCaptureOutcomeFailure(error: .unknown))
            }
        }
    }

    // MARK: - Listener

    private func notifyStarted(generation: Int) {
        guard let caps = capabilities else { return }
        guard let listener = lock.withLock({ _generation == generation ? _listener : nil }) else { return }
        listener.onCameraStarted(capabilities: caps)
    }

    private func notifyError(_ error: CameraError, generation: Int? = nil) {
        let listener = lock.withLock { () -> CameraListener? in
            if let generation, generation != _generation { return nil }
            return _wantsRunning ? _listener : nil
        }
        listener?.onCameraError(error: error)
    }

    // MARK: - Interruptions & runtime errors

    private func observeSession() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: nil) { [weak self] note in
            guard let self else { return }
            let raw = note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int
            if let error = Self.error(forInterruptionReason: raw.flatMap(AVCaptureSession.InterruptionReason.init(rawValue:))) {
                notifyError(error)
            }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: nil) { [weak self] _ in
            self?.resumeAfterInterruption()
        })
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) { [weak self] note in
            guard let self else { return }
            let error = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
            if error?.code == .mediaServicesWereReset {
                resumeAfterInterruption()
            } else {
                notifyError(Self.error(forRuntimeError: error?.code))
            }
        })
    }

    private func resumeAfterInterruption() {
        let generation = lock.withLock { _generation }
        sessionQueue.async { [self] in
            guard wantsRunning, isConfigured, !pausedForFreeze else { return }
            if !session.isRunning { session.startRunning() }
            if session.isRunning { notifyStarted(generation: generation) }
        }
    }

    /// Nil = ignore (e.g. backgrounding, which the lifecycle already handles).
    static func error(forInterruptionReason reason: AVCaptureSession.InterruptionReason?) -> CameraError? {
        switch reason {
        case .videoDeviceNotAvailableInBackground, .audioDeviceInUseByAnotherClient:
            return nil
        case .videoDeviceInUseByAnotherClient, .videoDeviceNotAvailableWithMultipleForegroundApps:
            return .inUseByAnotherApp
        case .videoDeviceNotAvailableDueToSystemPressure:
            return .unknown
        default:
            return .inUseByAnotherApp
        }
    }

    static func error(forRuntimeError code: AVError.Code?) -> CameraError {
        switch code {
        case .deviceNotConnected?, .deviceWasDisconnected?: return .disconnected
        case .deviceInUseByAnotherApplication?, .sessionWasInterrupted?: return .inUseByAnotherApp
        default: return .unknown
        }
    }
}

// MARK: - Photo capture delegate

/// One per capture; converts the AVCapturePhoto into a temp JPEG for `PhotoSaver`.
private final class PhotoCaptureProcessor: NSObject, AVCapturePhotoCaptureDelegate {
    private let mirrored: Bool
    private let cropAspect: CGFloat?
    private let completion: (CameraCaptureOutcome) -> Void
    private var outcome: CameraCaptureOutcome?

    init(mirrored: Bool, cropAspect: CGFloat?, completion: @escaping (CameraCaptureOutcome) -> Void) {
        self.mirrored = mirrored
        self.cropAspect = cropAspect
        self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else {
            outcome = CameraCaptureOutcomeFailure(error: .unknown)
            return
        }
        let url = PhotoFiles.newTempURL()
        do {
            // Crop to the on-screen aspect-fill framing so the photo matches the mirror (QA-BUG-14).
            // Re-encoding also drops all capture metadata (no location, MVP-08.AC6).
            guard let image = PhotoFiles.uprightCropped(photoData: data, aspect: cropAspect) else {
                throw CocoaError(.fileWriteUnknown)
            }
            try PhotoFiles.writeJPEG(image, mirrored: false, to: url)
            outcome = CameraCaptureOutcomeSuccess(photo: CapturedPhoto(
                localUri: url.absoluteString, width: Int32(image.width), height: Int32(image.height), mirrored: mirrored
            ))
        } catch {
            PhotoFiles.removeQuietly(url)
            outcome = CameraCaptureOutcomeFailure(error: .unknown)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        completion(outcome ?? CameraCaptureOutcomeFailure(error: .unknown))
    }
}

// MARK: - Helpers

/// Guarantees a port callback is invoked exactly once (ARCHITECTURE §8.2).
final class OnceCallback<T> {
    private let lock = NSLock()
    private var callback: ((T) -> Void)?

    init(_ callback: @escaping (T) -> Void) { self.callback = callback }

    func call(_ value: T) {
        let cb = lock.withLock { () -> ((T) -> Void)? in
            defer { callback = nil }
            return callback
        }
        cb?(value)
    }
}

func onMain(_ work: @escaping () -> Void) {
    if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
}
