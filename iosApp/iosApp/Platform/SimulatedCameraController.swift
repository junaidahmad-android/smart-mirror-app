import CoreGraphics
import SharedLogic
import UIKit

/// Camera stand-in for the iOS Simulator (which has no camera) and for UI screenshots.
///
/// Honors the same port contract as `AVCameraController`: it reports typical front-camera
/// capabilities, "freezes" a synthetic frame, and "captures" a real JPEG so the Photos saving
/// path can be exercised end to end. The UI draws `SimulatedFeedView` for `surface.isSimulated`.
final class SimulatedCameraController: NSObject, CameraController {
    let surface = PreviewSurface(session: nil)

    private let lock = NSLock()
    private var listener: CameraListener?
    private var generation = 0
    private let startDelay: TimeInterval

    init(startDelay: TimeInterval = 0.15) {
        self.startDelay = startDelay
    }

    static let capabilities = CameraCapabilities(
        minZoomRatio: 1, maxZoomRatio: 10, minExposureEv: -2, maxExposureEv: 2, exposureStepEv: 1.0 / 3.0
    )

    func setListener(listener: CameraListener?) {
        lock.withLock { self.listener = listener }
    }

    func start(config: CameraConfig) {
        let current = lock.withLock { () -> Int in
            generation += 1
            return generation
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + startDelay) { [weak self] in
            guard let self else { return }
            let listener = self.lock.withLock { self.generation == current ? self.listener : nil }
            listener?.onCameraStarted(capabilities: Self.capabilities)
        }
    }

    func stop() {
        lock.withLock { generation += 1 }
    }

    func setZoomRatio(ratio: Float) {}
    func setExposureCompensation(ev: Float) {}
    func setMaxFrameRate(fps: Int32) {}

    func setPreviewMirrored(mirrored: Bool) {
        onMain { self.surface.setMirrored(mirrored) }
    }

    func setPreviewFrozen(frozen: Bool) {
        onMain { self.surface.frozenImage = frozen ? Self.syntheticFrame() : nil }
    }

    func capturePhoto(request: CaptureRequest, onResult: @escaping (CameraCaptureOutcome) -> Void) {
        let once = OnceCallback(onResult)
        let aspect = surface.viewportAspect
        DispatchQueue.global(qos: .userInitiated).async {
            guard let frame = Self.syntheticFrame() else {
                return once.call(CameraCaptureOutcomeFailure(error: .unknown))
            }
            let image = PhotoFiles.centerCropped(frame, aspect: aspect)
            let url = PhotoFiles.newTempURL()
            do {
                try PhotoFiles.writeJPEG(image, mirrored: request.mirrored, to: url)
                once.call(CameraCaptureOutcomeSuccess(photo: CapturedPhoto(
                    localUri: url.absoluteString, width: Int32(image.width), height: Int32(image.height), mirrored: request.mirrored
                )))
            } catch {
                once.call(CameraCaptureOutcomeFailure(error: .unknown))
            }
        }
    }

    /// A 1080×1920 gradient with an asymmetric marker, so mirroring is visible in saved photos.
    static func syntheticFrame() -> CGImage? {
        let size = CGSize(width: 1080, height: 1920)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let colors = [UIColor(red: 0.20, green: 0.24, blue: 0.32, alpha: 1).cgColor,
                          UIColor(red: 0.52, green: 0.44, blue: 0.40, alpha: 1).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                ctx.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            }
            UIColor(white: 1, alpha: 0.25).setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: 290, y: 520, width: 500, height: 640))
            UIColor.systemYellow.setFill()
            ctx.cgContext.fill(CGRect(x: 80, y: 120, width: 160, height: 160)) // top-left marker
        }
        return image.cgImage
    }
}
