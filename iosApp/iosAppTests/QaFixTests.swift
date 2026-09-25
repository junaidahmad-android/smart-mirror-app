import SharedLogic
import SwiftUI
import XCTest
@testable import Smartmirrorapp

/// Regression tests for the iOS bugs in docs/QA_REPORT.md.

/// QA-BUG-04: the privacy manifest ships in the app bundle with the required-reason declarations.
final class PrivacyManifestTests: XCTestCase {
    func testPrivacyManifestIsBundledAndDeclaresUserDefaults() throws {
        // Hosted tests run inside the app, so Bundle.main is the app bundle.
        let url = try XCTUnwrap(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"),
                                "PrivacyInfo.xcprivacy missing from the app bundle")
        let plist = try XCTUnwrap(PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: Any])

        XCTAssertEqual(plist["NSPrivacyTracking"] as? Bool, false)
        XCTAssertEqual((plist["NSPrivacyTrackingDomains"] as? [Any])?.count, 0)
        XCTAssertEqual((plist["NSPrivacyCollectedDataTypes"] as? [Any])?.count, 0)

        let apis = try XCTUnwrap(plist["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let userDefaults = try XCTUnwrap(apis.first { $0["NSPrivacyAccessedAPIType"] as? String == "NSPrivacyAccessedAPICategoryUserDefaults" })
        XCTAssertEqual(userDefaults["NSPrivacyAccessedAPITypeReasons"] as? [String], ["CA92.1"])
    }
}

/// QA-BUG-13: single taps are delivered immediately; a quick second tap nearby is a double tap.
final class TapClassifierTests: XCTestCase {
    func testSingleTapIsImmediateAndQuickSecondTapIsDouble() {
        var taps = TapClassifier()
        XCTAssertEqual(taps.register(at: CGPoint(x: 100, y: 100), time: 10.0), .single)
        XCTAssertEqual(taps.register(at: CGPoint(x: 110, y: 105), time: 10.2), .double)
        XCTAssertEqual(taps.register(at: CGPoint(x: 110, y: 105), time: 10.3), .single, "third tap starts over")
    }

    func testSlowOrDistantSecondTapIsSingle() {
        var taps = TapClassifier()
        _ = taps.register(at: .zero, time: 1.0)
        XCTAssertEqual(taps.register(at: .zero, time: 1.5), .single)
        XCTAssertEqual(taps.register(at: CGPoint(x: 200, y: 0), time: 1.6), .single)
    }
}

/// QA-BUG-07: layout follows the real window size (iPadOS 26 resizable windows).
final class AdaptiveLayoutTests: XCTestCase {
    func testLayoutForWindowSizes() {
        // iPhone portrait / landscape.
        XCTAssertEqual(MirrorScreenView.layout(for: CGSize(width: 402, height: 874), horizontalSizeClass: .compact), .bottomBar)
        XCTAssertEqual(MirrorScreenView.layout(for: CGSize(width: 874, height: 402), horizontalSizeClass: .regular), .sideRail)
        // Full-screen iPad.
        XCTAssertEqual(MirrorScreenView.layout(for: CGSize(width: 820, height: 1180), horizontalSizeClass: .regular), .sideRail)
        // Small or squat iPad windows.
        XCTAssertEqual(MirrorScreenView.layout(for: CGSize(width: 420, height: 700), horizontalSizeClass: .compact), .bottomBar)
        XCTAssertEqual(MirrorScreenView.layout(for: CGSize(width: 700, height: 300), horizontalSizeClass: .regular), .bottomBar)
        XCTAssertEqual(MirrorScreenView.layout(for: CGSize(width: 520, height: 700), horizontalSizeClass: .compact), .bottomBar)
        XCTAssertEqual(MirrorScreenView.layout(for: CGSize(width: 700, height: 500), horizontalSizeClass: .compact), .sideRail)
    }

    func testLargeSpacingOnlyForTabletSizedWindows() {
        XCTAssertTrue(MirrorScreenView.isLarge(CGSize(width: 820, height: 1180)))
        XCTAssertFalse(MirrorScreenView.isLarge(CGSize(width: 900, height: 500)))
        XCTAssertFalse(MirrorScreenView.isLarge(CGSize(width: 402, height: 874)))
    }
}

/// QA-BUG-14: saved photos use the same aspect-fill crop as the preview.
final class CaptureFramingTests: XCTestCase {
    func testCenterCropMatchesAspectFill() {
        // 16:9 landscape sensor frame shown in a portrait phone viewport.
        let rect = PhotoFiles.centerCropRect(imageSize: CGSize(width: 1920, height: 1080), aspect: 402.0 / 874.0)
        XCTAssertEqual(rect.height, 1080)
        XCTAssertEqual(rect.width, (1080 * 402.0 / 874.0).rounded())
        XCTAssertEqual(rect.midX, 960, accuracy: 1)

        let tall = PhotoFiles.centerCropRect(imageSize: CGSize(width: 1080, height: 1920), aspect: 4.0 / 3.0)
        XCTAssertEqual(tall.width, 1080)
        XCTAssertEqual(tall.height, 810)
        XCTAssertEqual(tall.midY, 960, accuracy: 1)

        XCTAssertEqual(PhotoFiles.centerCropRect(imageSize: CGSize(width: 100, height: 50), aspect: nil),
                       CGRect(x: 0, y: 0, width: 100, height: 50))
    }

    func testUprightCropOfEncodedPhoto() throws {
        let frame = try XCTUnwrap(SimulatedCameraController.syntheticFrame()) // 1080 × 1920
        let url = PhotoFiles.newTempURL()
        defer { PhotoFiles.removeQuietly(url) }
        try PhotoFiles.writeJPEG(frame, mirrored: false, to: url)
        let cropped = try XCTUnwrap(PhotoFiles.uprightCropped(photoData: Data(contentsOf: url), aspect: 1))
        XCTAssertEqual(cropped.width, 1080)
        XCTAssertEqual(cropped.height, 1080)
    }

    func testSimulatedCaptureIsCroppedToTheViewport() throws {
        let camera = SimulatedCameraController()
        camera.surface.viewportSize = CGSize(width: 800, height: 600) // 4:3 landscape window
        var outcome: CameraCaptureOutcome?
        camera.capturePhoto(request: CaptureRequest(mirrored: false, fromFrozenFrame: false)) { outcome = $0 }
        waitUntil { outcome != nil }
        let photo = try XCTUnwrap(outcome as? CameraCaptureOutcomeSuccess).photo
        defer { PhotoFiles.removeQuietly(PhotoFiles.url(fromLocalUri: photo.localUri)) }
        XCTAssertEqual(Double(photo.width) / Double(photo.height), 4.0 / 3.0, accuracy: 0.01)
    }
}

/// QA-BUG-06 (iOS side): every finished save produces a fresh feedback event, even when the shared
/// `message` value is unchanged because the previous "Saved" toast is still showing.
@MainActor
final class RepeatedSaveFeedbackTests: XCTestCase {
    func testSecondSaveWithinMessageWindowGetsFreshFeedback() {
        let camera = SimulatedCameraController(startDelay: 0.01)
        let ports = MirrorPorts(
            camera: camera,
            brightness: RecordingBrightness(),
            photoSaver: PhotoLibrarySaver(library: FakePhotoLibrary(status: .authorized)),
            permissions: FakePermissions(status: .granted)
        )
        let store = MirrorStore(
            viewModel: SharedGraph(keyValueStore: InMemoryKeyValueStore()).createMirrorViewModel(ports: ports),
            surface: camera.surface,
            observeSystemEvents: false
        )
        store.send(MirrorIntentScreenStarted.shared)
        waitUntil { store.state.canCapture }

        store.send(MirrorIntentCapturePhoto.shared)
        waitUntil { store.feedback?.message == .photoSaved && store.state.canCapture }
        let first = store.feedback

        store.send(MirrorIntentCapturePhoto.shared)
        waitUntil { store.feedback != first && store.state.canCapture }
        XCTAssertEqual(store.state.message, .photoSaved, "shared message unchanged (same value)")
        XCTAssertEqual(store.feedback?.message, .photoSaved)
        XCTAssertNotEqual(store.feedback?.id, first?.id, "second save must get its own feedback event")
    }
}
