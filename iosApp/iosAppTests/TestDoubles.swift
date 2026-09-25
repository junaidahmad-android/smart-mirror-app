import Foundation
import Photos
import SharedLogic
import XCTest
@testable import Smartmirrorapp

final class FakeScreen: ScreenBrightnessProviding {
    var brightness: CGFloat
    init(brightness: CGFloat) { self.brightness = brightness }
}

final class FakeIdleTimer: IdleTimerControlling {
    var isIdleTimerDisabled = false
}

final class FakePhotoLibrary: PhotoLibraryClient {
    var status: PHAuthorizationStatus
    var statusAfterRequest: PHAuthorizationStatus = .denied
    var addResult: Result<String, Error> = .success("asset-1")
    private(set) var requestCount = 0
    private(set) var addedURLs: [URL] = []

    init(status: PHAuthorizationStatus) { self.status = status }

    func authorizationStatus() -> PHAuthorizationStatus { status }

    func requestAuthorization(_ completion: @escaping (PHAuthorizationStatus) -> Void) {
        requestCount += 1
        status = statusAfterRequest
        completion(statusAfterRequest)
    }

    func addPhoto(fileURL: URL, completion: @escaping (Result<String, Error>) -> Void) {
        addedURLs.append(fileURL)
        completion(addResult)
    }
}

final class RecordingCameraListener: NSObject, CameraListener {
    var started: [CameraCapabilities] = []
    var errors: [CameraError] = []
    var onStarted: (() -> Void)?

    func onCameraStarted(capabilities: CameraCapabilities) {
        started.append(capabilities)
        onStarted?()
    }

    func onCameraError(error: CameraError) { errors.append(error) }
    func onCameraStopped() {}
}

final class FakePermissions: NSObject, PermissionChecker {
    var status: PermissionStatus
    init(status: PermissionStatus) { self.status = status }
    func cameraPermissionStatus() -> PermissionStatus { status }
    func requestCameraPermission(onResult: @escaping (PermissionStatus) -> Void) { onResult(status) }
    func openAppSettings() {}
}

final class RecordingBrightness: NSObject, BrightnessController {
    var applied: [Float] = []
    var restoreCount = 0
    var keepScreenOn = false
    func applyBrightness(level: Float) { applied.append(level) }
    func restoreBrightness() { restoreCount += 1 }
    func setKeepScreenOn(enabled: Bool) { keepScreenOn = enabled }
}

func makeTempFile(_ contents: String = "jpeg") throws -> URL {
    let url = PhotoFiles.newTempURL()
    try Data(contents.utf8).write(to: url)
    return url
}

extension XCTestCase {
    /// Spins the main run loop until [condition] holds (Kotlin's Dispatchers.Main posts to it).
    func waitUntil(timeout: TimeInterval = 3, file: StaticString = #filePath, line: UInt = #line, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        }
        XCTAssertTrue(condition(), "Timed out waiting for condition", file: file, line: line)
    }
}
