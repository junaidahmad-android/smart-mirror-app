import AVFoundation
import UIKit

/// Rotation helpers that work on iOS 16 (`videoOrientation`) and iOS 17+ (`videoRotationAngle`).
///
/// Angles use AVFoundation's convention: 0° = landscape right (the sensor's native orientation),
/// 90° = portrait, 180° = landscape left, 270° = portrait upside down.
enum CaptureRotation {
    static func degrees(for orientation: UIInterfaceOrientation) -> CGFloat {
        switch orientation {
        case .landscapeRight: return 0
        case .landscapeLeft: return 180
        case .portraitUpsideDown: return 270
        default: return 90
        }
    }

    static func videoOrientation(forDegrees degrees: CGFloat) -> AVCaptureVideoOrientation {
        switch normalized(degrees) {
        case 0: return .landscapeRight
        case 180: return .landscapeLeft
        case 270: return .portraitUpsideDown
        default: return .portrait
        }
    }

    /// Rounds to the nearest quarter turn in 0..<360.
    static func normalized(_ degrees: CGFloat) -> CGFloat {
        let quarter = (degrees / 90).rounded()
        let value = (quarter * 90).truncatingRemainder(dividingBy: 360)
        return value < 0 ? value + 360 : value
    }

    static func apply(degrees: CGFloat, to connection: AVCaptureConnection) {
        if #available(iOS 17.0, *) {
            if connection.isVideoRotationAngleSupported(degrees) {
                connection.videoRotationAngle = degrees
            }
        } else if connection.isVideoOrientationSupported {
            connection.videoOrientation = videoOrientation(forDegrees: degrees)
        }
    }
}
