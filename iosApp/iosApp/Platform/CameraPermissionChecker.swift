import AVFoundation
import SharedLogic
import UIKit

/// `PermissionChecker` port backed by `AVCaptureDevice` authorization.
///
/// iOS never re-shows the camera prompt after a "Don't Allow", so `.denied` and `.restricted`
/// both map to `BLOCKED` (Settings is the only fix, MVP-12.AC3).
final class CameraPermissionChecker: NSObject, PermissionChecker {
    private let authorizationStatus: () -> AVAuthorizationStatus
    private let requestAccess: (@escaping (Bool) -> Void) -> Void
    private let openURL: (URL) -> Void

    init(
        authorizationStatus: @escaping () -> AVAuthorizationStatus = { AVCaptureDevice.authorizationStatus(for: .video) },
        requestAccess: @escaping (@escaping (Bool) -> Void) -> Void = { AVCaptureDevice.requestAccess(for: .video, completionHandler: $0) },
        openURL: @escaping (URL) -> Void = { UIApplication.shared.open($0) }
    ) {
        self.authorizationStatus = authorizationStatus
        self.requestAccess = requestAccess
        self.openURL = openURL
    }

    static func map(_ status: AVAuthorizationStatus) -> PermissionStatus {
        switch status {
        case .authorized: return .granted
        case .notDetermined: return .notDetermined
        case .denied, .restricted: return .blocked
        @unknown default: return .blocked
        }
    }

    func cameraPermissionStatus() -> PermissionStatus {
        Self.map(authorizationStatus())
    }

    func requestCameraPermission(onResult: @escaping (PermissionStatus) -> Void) {
        let current = authorizationStatus()
        guard current == .notDetermined else {
            // The system dialog can't be shown again; report the current state.
            return onResult(Self.map(current))
        }
        requestAccess { granted in onResult(granted ? .granted : .blocked) }
    }

    func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        onMain { self.openURL(url) }
    }
}
