import AVFoundation
import Combine
import CoreGraphics
import Foundation

/// The UI-facing half of a camera adapter: what the SwiftUI preview renders.
///
/// The Kotlin `CameraController` port never sees any of this (pixels and layers stay native,
/// ARCHITECTURE §1.4). The adapter owns the surface; `CameraPreviewView` binds to it.
/// All members are main-thread only.
final class PreviewSurface: ObservableObject {
    /// Nil for the simulated feed (the simulator has no camera).
    let session: AVCaptureSession?

    /// Last live frame while frozen: upright and **un-mirrored**. The UI flips it for mirror
    /// mode, and a capture from the frozen frame flips it according to the capture request.
    @Published var frozenImage: CGImage?

    /// Set once the capture session is configured; used for the iOS 17 rotation coordinator.
    @Published private(set) var device: AVCaptureDevice?

    /// Whether the preview is currently mirrored (mirror mode) or not (true view).
    private(set) var isMirrored = true

    /// The preview layer hosted by `CameraPreviewView`, once it exists.
    weak var previewLayer: AVCaptureVideoPreviewLayer? {
        didSet { applyMirroring() }
    }

    /// Rotation angles (degrees, AVFoundation convention) reported by the preview view, forwarded
    /// to the adapter so the video-data and photo connections match the preview.
    var onRotationChanged: ((_ previewDegrees: CGFloat, _ captureDegrees: CGFloat) -> Void)?

    private let viewportLock = NSLock()
    private var _viewportSize: CGSize = .zero

    /// Size of the on-screen (aspect-fill) viewport. Captures are center-cropped to its aspect ratio
    /// so saved photos show the same field of view as the mirror (QA-BUG-14, MVP-08.AC2).
    /// Written on main, read from any thread.
    var viewportSize: CGSize {
        get { viewportLock.withLock { _viewportSize } }
        set { viewportLock.withLock { _viewportSize = newValue } }
    }

    /// Width / height of the viewport, or nil before the first layout.
    var viewportAspect: CGFloat? {
        let size = viewportSize
        guard size.width > 0, size.height > 0 else { return nil }
        return size.width / size.height
    }

    init(session: AVCaptureSession?) {
        self.session = session
    }

    var isSimulated: Bool { session == nil }

    func setMirrored(_ mirrored: Bool) {
        isMirrored = mirrored
        applyMirroring()
    }

    /// Freezes/unfreezes the preview layer itself; this is instant, before the frozen image arrives.
    func setLayerFrozen(_ frozen: Bool) {
        previewLayer?.connection?.isEnabled = !frozen
    }

    func cameraDidConfigure(device: AVCaptureDevice) {
        self.device = device
        applyMirroring()
    }

    func reportRotation(previewDegrees: CGFloat, captureDegrees: CGFloat) {
        onRotationChanged?(previewDegrees, captureDegrees)
    }

    private func applyMirroring() {
        guard let connection = previewLayer?.connection, connection.isVideoMirroringSupported else { return }
        connection.automaticallyAdjustsVideoMirroring = false
        connection.isVideoMirrored = isMirrored
    }
}
