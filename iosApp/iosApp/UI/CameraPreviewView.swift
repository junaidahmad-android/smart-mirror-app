import AVFoundation
import SwiftUI

/// Hosts the `AVCaptureVideoPreviewLayer` (aspect fill, MVP-01.AC4) and keeps its connection's
/// rotation in sync with the UI: `AVCaptureDevice.RotationCoordinator` on iOS 17+,
/// `videoOrientation` from the window scene's interface orientation on iOS 16.
struct CameraPreviewView: UIViewRepresentable {
    let surface: PreviewSurface

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.attach(surface)
        return view
    }

    func updateUIView(_ view: PreviewUIView, context: Context) {
        view.refreshRotation()
    }
}

final class PreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    private var previewLayer: AVCaptureVideoPreviewLayer {
        // swiftlint:disable:next force_cast
        layer as! AVCaptureVideoPreviewLayer
    }

    private weak var surface: PreviewSurface?
    private var rotationCoordinator: AnyObject?
    private var rotationObservations: [NSKeyValueObservation] = []
    private var deviceObservation: Any?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        isAccessibilityElement = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func attach(_ surface: PreviewSurface) {
        self.surface = surface
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.session = surface.session
        surface.previewLayer = previewLayer
        deviceObservation = surface.$device.sink { [weak self] device in
            guard device != nil else { return }
            DispatchQueue.main.async { self?.setUpRotation() }
        }
        setUpRotation()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        refreshRotation()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        refreshRotation()
    }

    /// iOS 16 path; on iOS 17+ the rotation coordinator drives everything.
    func refreshRotation() {
        if #available(iOS 17.0, *) { return }
        guard let orientation = window?.windowScene?.interfaceOrientation else { return }
        let degrees = CaptureRotation.degrees(for: orientation)
        if let connection = previewLayer.connection {
            CaptureRotation.apply(degrees: degrees, to: connection)
        }
        surface?.reportRotation(previewDegrees: degrees, captureDegrees: degrees)
    }

    private func setUpRotation() {
        guard #available(iOS 17.0, *) else { return refreshRotation() }
        guard let device = surface?.device, rotationCoordinator == nil else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        rotationCoordinator = coordinator
        let update: (AVCaptureDevice.RotationCoordinator) -> Void = { [weak self] coordinator in
            guard let self else { return }
            let preview = coordinator.videoRotationAngleForHorizonLevelPreview
            if let connection = self.previewLayer.connection {
                CaptureRotation.apply(degrees: preview, to: connection)
            }
            self.surface?.reportRotation(previewDegrees: preview, captureDegrees: coordinator.videoRotationAngleForHorizonLevelCapture)
        }
        rotationObservations = [
            coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]) { c, _ in
                DispatchQueue.main.async { update(c) }
            },
            coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.new]) { c, _ in
                DispatchQueue.main.async { update(c) }
            },
        ]
    }
}

/// Simulator stand-in for the live camera: a soft gradient "face" that reacts to zoom and mirroring.
struct SimulatedFeedView: View {
    let zoom: CGFloat
    let mirrored: Bool

    var body: some View {
        ZStack {
            ZStack {
                LinearGradient(colors: [Color(red: 0.20, green: 0.24, blue: 0.32), Color(red: 0.52, green: 0.44, blue: 0.40)],
                               startPoint: .top, endPoint: .bottom)
                HStack(alignment: .bottom, spacing: 12) {
                    Image(systemName: "person.crop.circle.fill")
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 180)
                    // Asymmetric marker so mirror vs true view is visible in the simulator.
                    Image(systemName: "hand.raised.fill")
                        .font(.system(size: 44))
                }
                .foregroundStyle(.white.opacity(0.4))
                .scaleEffect(zoom)
            }
            .scaleEffect(x: mirrored ? -1 : 1, y: 1)

            VStack {
                Label("Simulated camera", systemImage: "video.slash")
                    .font(.caption.weight(.semibold))
                    .padding(8)
                    .background(Color.yellow.opacity(0.85), in: Capsule())
                    .foregroundStyle(.black)
                Spacer()
            }
            .padding(.top, 60)
        }
        .accessibilityHidden(true)
    }
}
