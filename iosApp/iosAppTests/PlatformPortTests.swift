import AVFoundation
import Photos
import SharedLogic
import UIKit
import XCTest
@testable import Smartmirrorapp

final class PermissionCheckerTests: XCTestCase {
    func testStatusMapping() {
        XCTAssertEqual(CameraPermissionChecker.map(.authorized), .granted)
        XCTAssertEqual(CameraPermissionChecker.map(.notDetermined), .notDetermined)
        XCTAssertEqual(CameraPermissionChecker.map(.denied), .blocked)
        XCTAssertEqual(CameraPermissionChecker.map(.restricted), .blocked)
    }

    func testRequestShowsDialogOnlyWhenUndetermined() {
        var asked = 0
        var status: AVAuthorizationStatus = .notDetermined
        let checker = CameraPermissionChecker(
            authorizationStatus: { status },
            requestAccess: { completion in asked += 1; completion(true) },
            openURL: { _ in }
        )
        var result: PermissionStatus?
        checker.requestCameraPermission { result = $0 }
        XCTAssertEqual(asked, 1)
        XCTAssertEqual(result, .granted)

        status = .denied
        checker.requestCameraPermission { result = $0 }
        XCTAssertEqual(asked, 1, "iOS can't re-prompt; report BLOCKED without asking")
        XCTAssertEqual(result, .blocked)
    }

    func testDeclinedDialogIsBlocked() {
        let checker = CameraPermissionChecker(authorizationStatus: { .notDetermined }, requestAccess: { $0(false) }, openURL: { _ in })
        var result: PermissionStatus?
        checker.requestCameraPermission { result = $0 }
        XCTAssertEqual(result, .blocked)
    }

    func testOpenAppSettingsOpensTheSettingsURL() {
        var opened: URL?
        let checker = CameraPermissionChecker(authorizationStatus: { .denied }, requestAccess: { _ in }, openURL: { opened = $0 })
        checker.openAppSettings()
        XCTAssertEqual(opened?.absoluteString, UIApplication.openSettingsURLString)
    }
}

final class BrightnessControllerTests: XCTestCase {
    private let center = NotificationCenter()

    func testBoostRemembersUserLevelAndRestoresIt() {
        let screen = FakeScreen(brightness: 0.3)
        let controller = ScreenBrightnessController(screen: { screen }, idleTimer: { nil }, notificationCenter: center)
        controller.applyBrightness(level: 1)
        XCTAssertEqual(screen.brightness, 1)
        controller.applyBrightness(level: 0.8) // thermal throttle must not overwrite the saved level
        XCTAssertEqual(screen.brightness, 0.8, accuracy: 0.001)
        controller.restoreBrightness()
        XCTAssertEqual(screen.brightness, 0.3, accuracy: 0.001)
        controller.restoreBrightness()
        XCTAssertEqual(screen.brightness, 0.3, accuracy: 0.001, "restore is idempotent")
    }

    func testResignActiveRestoresAndBecomeActiveReapplies() {
        let screen = FakeScreen(brightness: 0.4)
        let controller = ScreenBrightnessController(screen: { screen }, idleTimer: { nil }, notificationCenter: center)
        controller.applyBrightness(level: 1)
        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(screen.brightness, 0.4, accuracy: 0.001)
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(screen.brightness, 1)
        controller.restoreBrightness()
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(screen.brightness, 0.4, accuracy: 0.001, "no re-boost after the ViewModel restored")
    }

    func testKeepScreenOnDrivesIdleTimer() {
        let timer = FakeIdleTimer()
        let controller = ScreenBrightnessController(screen: { nil }, idleTimer: { timer }, notificationCenter: center)
        controller.setKeepScreenOn(enabled: true)
        XCTAssertTrue(timer.isIdleTimerDisabled)
        controller.setKeepScreenOn(enabled: false)
        XCTAssertFalse(timer.isIdleTimerDisabled)
    }
}

final class PhotoLibrarySaverTests: XCTestCase {
    private func save(_ library: FakePhotoLibrary, file: URL) -> SaveOutcome? {
        var outcomes: [SaveOutcome] = []
        PhotoLibrarySaver(library: library).save(
            photo: CapturedPhoto(localUri: file.absoluteString, width: 10, height: 10, mirrored: true)
        ) { outcomes.append($0) }
        XCTAssertLessThanOrEqual(outcomes.count, 1, "onResult must fire exactly once")
        return outcomes.first
    }

    func testAuthorizedSaveReturnsAssetAndRemovesTempFile() throws {
        let file = try makeTempFile()
        let library = FakePhotoLibrary(status: .authorized)
        let outcome = save(library, file: file)
        XCTAssertEqual((outcome as? SaveOutcomeSaved)?.uri, "asset-1")
        XCTAssertEqual(library.addedURLs, [file])
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testFirstSaveAsksOnceAndReportsDenial() throws {
        let file = try makeTempFile()
        let library = FakePhotoLibrary(status: .notDetermined)
        library.statusAfterRequest = .denied
        let outcome = save(library, file: file)
        XCTAssertEqual((outcome as? SaveOutcomeFailed)?.reason, .permissionDenied)
        XCTAssertEqual(library.requestCount, 1)
        XCTAssertTrue(library.addedURLs.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "no app-private copy remains (MVP-08.AC7)")
    }

    func testDeniedDoesNotPromptAgain() throws {
        let library = FakePhotoLibrary(status: .denied)
        let outcome = save(library, file: try makeTempFile())
        XCTAssertEqual((outcome as? SaveOutcomeFailed)?.reason, .permissionDenied)
        XCTAssertEqual(library.requestCount, 0)
    }

    func testOutOfSpaceMapsToStorageFull() throws {
        let library = FakePhotoLibrary(status: .authorized)
        library.addResult = .failure(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError))
        let outcome = save(library, file: try makeTempFile())
        XCTAssertEqual((outcome as? SaveOutcomeFailed)?.reason, .storageFull)
        XCTAssertEqual(PhotoLibrarySaver.failure(for: NSError(domain: "x", code: 1)), .unknown)
    }
}

final class PhotoFilesTests: XCTestCase {
    func testLocalUriParsingAcceptsUrlsAndPaths() {
        XCTAssertEqual(PhotoFiles.url(fromLocalUri: "file:///tmp/a.jpg").path, "/tmp/a.jpg")
        XCTAssertEqual(PhotoFiles.url(fromLocalUri: "/tmp/b.jpg").path, "/tmp/b.jpg")
    }

    func testMirroredJpegIsFlippedHorizontally() throws {
        let frame = try XCTUnwrap(SimulatedCameraController.syntheticFrame())
        let url = PhotoFiles.newTempURL()
        defer { PhotoFiles.removeQuietly(url) }
        try PhotoFiles.writeJPEG(frame, mirrored: true, to: url)
        let image = try XCTUnwrap(UIImage(contentsOfFile: url.path)?.cgImage)
        XCTAssertEqual(image.width, frame.width)
        // The synthetic frame has a yellow marker at the top-LEFT; mirrored it must be top-RIGHT.
        XCTAssertTrue(isYellow(image, x: image.width - 160, y: 200))
        XCTAssertFalse(isYellow(image, x: 160, y: 200))
    }

    private func isYellow(_ image: CGImage, x: Int, y: Int) -> Bool {
        var pixel = [UInt8](repeating: 0, count: 4)
        let context = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.draw(image, in: CGRect(x: -x, y: -(image.height - 1 - y), width: image.width, height: image.height))
        return pixel[0] > 200 && pixel[1] > 150 && pixel[2] < 100
    }
}

final class SystemEventsTests: XCTestCase {
    func testThermalMapping() {
        XCTAssertEqual(SystemEventsObserver.map(.nominal), .nominal)
        XCTAssertEqual(SystemEventsObserver.map(.fair), .fair)
        XCTAssertEqual(SystemEventsObserver.map(.serious), .serious)
        XCTAssertEqual(SystemEventsObserver.map(.critical), .critical)
    }
}
