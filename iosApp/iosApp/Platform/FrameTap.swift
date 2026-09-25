import AVFoundation
import CoreImage

/// Native per-frame processor hook (the iOS data plane of ARCHITECTURE §7).
///
/// A future Core ML / Vision / Metal `ProcessingPipeline` implementation registers one of these on
/// `FrameTap`. Rules: runs on the video queue, must never block (latest frame wins, the output
/// drops late frames), and pixel buffers never cross into Kotlin. Nothing is registered in v1.0.
protocol VideoFrameProcessing: AnyObject {
    func process(_ sampleBuffer: CMSampleBuffer, connection: AVCaptureConnection)
}

/// Delegate of the `AVCaptureVideoDataOutput`. In v1.0 it only grabs a single frame on demand for
/// freeze; otherwise every callback returns immediately.
final class FrameTap: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let lock = NSLock()
    private var pendingSnapshot: ((CGImage?) -> Void)?
    private var _processor: VideoFrameProcessing?
    private lazy var ciContext = CIContext(options: [.cacheIntermediates: false])

    /// Future AI hook. Nil (no-op) in the MVP.
    var processor: VideoFrameProcessing? {
        get { lock.withLock { _processor } }
        set { lock.withLock { _processor = newValue } }
    }

    /// Delivers the next frame as an upright CGImage (on the video queue). Replaces any pending request.
    func requestSnapshot(_ completion: @escaping (CGImage?) -> Void) {
        let previous = lock.withLock { () -> ((CGImage?) -> Void)? in
            let old = pendingSnapshot
            pendingSnapshot = completion
            return old
        }
        previous?(nil)
    }

    func cancelSnapshot() {
        let pending = lock.withLock { () -> ((CGImage?) -> Void)? in
            defer { pendingSnapshot = nil }
            return pendingSnapshot
        }
        pending?(nil)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let (processor, snapshot) = lock.withLock { () -> (VideoFrameProcessing?, ((CGImage?) -> Void)?) in
            defer { pendingSnapshot = nil }
            return (_processor, pendingSnapshot)
        }
        processor?.process(sampleBuffer, connection: connection)
        guard let snapshot else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return snapshot(nil) }
        let image = CIImage(cvPixelBuffer: pixelBuffer)
        snapshot(ciContext.createCGImage(image, from: image.extent))
    }
}
