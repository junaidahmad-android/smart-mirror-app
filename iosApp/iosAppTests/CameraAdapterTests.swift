import AVFoundation
import SharedLogic
import XCTest
@testable import Smartmirrorapp

final class CameraAdapterTests: XCTestCase {
    func testCapabilitiesClampZoomToAppMaximumAndExposureToTwoEv() {
        let caps = AVCameraController.capabilities(minZoom: 1, maxZoom: 16, minBias: -8, maxBias: 8)
        XCTAssertEqual(caps.minZoomRatio, 1)
        XCTAssertEqual(caps.maxZoomRatio, 10)
        XCTAssertEqual(caps.minExposureEv, -2)
        XCTAssertEqual(caps.maxExposureEv, 2)
        XCTAssertEqual(caps.exposureStepEv, 1.0 / 3.0, accuracy: 0.0001)
        XCTAssertTrue(caps.supportsExposureCompensation)
    }

    func testCapabilitiesKeepNarrowerDeviceRanges() {
        let caps = AVCameraController.capabilities(minZoom: 1, maxZoom: 4, minBias: -1, maxBias: 1.5)
        XCTAssertEqual(caps.maxZoomRatio, 4)
        XCTAssertEqual(caps.minExposureEv, -1)
        XCTAssertEqual(caps.maxExposureEv, 1.5)
    }

    func testCapabilitiesWithoutExposureSupportHideTheControl() {
        let caps = AVCameraController.capabilities(minZoom: 1, maxZoom: 1, minBias: 0, maxBias: 0)
        XCTAssertFalse(caps.supportsExposureCompensation)
        XCTAssertEqual(caps.exposureStepEv, 0)
        XCTAssertFalse(caps.supportsZoom)
    }

    func testInterruptionReasonsMapToCameraErrors() {
        XCTAssertNil(AVCameraController.error(forInterruptionReason: .videoDeviceNotAvailableInBackground))
        XCTAssertEqual(AVCameraController.error(forInterruptionReason: .videoDeviceInUseByAnotherClient), .inUseByAnotherApp)
        XCTAssertEqual(AVCameraController.error(forInterruptionReason: .videoDeviceNotAvailableWithMultipleForegroundApps), .inUseByAnotherApp)
        XCTAssertEqual(AVCameraController.error(forInterruptionReason: .videoDeviceNotAvailableDueToSystemPressure), .unknown)
    }

    func testRuntimeErrorsMapToCameraErrors() {
        XCTAssertEqual(AVCameraController.error(forRuntimeError: .deviceWasDisconnected), .disconnected)
        XCTAssertEqual(AVCameraController.error(forRuntimeError: .deviceInUseByAnotherApplication), .inUseByAnotherApp)
        XCTAssertEqual(AVCameraController.error(forRuntimeError: nil), .unknown)
    }

    func testRotationMapping() {
        XCTAssertEqual(CaptureRotation.degrees(for: .portrait), 90)
        XCTAssertEqual(CaptureRotation.degrees(for: .landscapeRight), 0)
        XCTAssertEqual(CaptureRotation.degrees(for: .landscapeLeft), 180)
        XCTAssertEqual(CaptureRotation.degrees(for: .portraitUpsideDown), 270)
        XCTAssertEqual(CaptureRotation.videoOrientation(forDegrees: 90), .portrait)
        XCTAssertEqual(CaptureRotation.videoOrientation(forDegrees: 0), .landscapeRight)
        XCTAssertEqual(CaptureRotation.videoOrientation(forDegrees: 180), .landscapeLeft)
        XCTAssertEqual(CaptureRotation.videoOrientation(forDegrees: -90), .portraitUpsideDown)
        XCTAssertEqual(CaptureRotation.normalized(359), 0)
    }

    func testOnceCallbackFiresExactlyOnce() {
        var calls = 0
        let once = OnceCallback<Int> { _ in calls += 1 }
        once.call(1)
        once.call(2)
        XCTAssertEqual(calls, 1)
    }

    func testRealAdapterReportsAnErrorInsteadOfCrashingWithoutACamera() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Only meaningful where no camera exists")
        #else
        let camera = AVCameraController()
        let listener = RecordingCameraListener()
        camera.setListener(listener: listener)
        camera.start(config: CameraConfig(lens: .front, initialZoomRatio: 1))
        waitUntil { !listener.errors.isEmpty }
        XCTAssertTrue(listener.started.isEmpty)
        // Unauthorized → PERMISSION_DENIED, authorized but no device → NO_FRONT_CAMERA.
        XCTAssertTrue([CameraError.permissionDenied, CameraError.noFrontCamera].contains(listener.errors.first!))
        #endif
    }

    func testSimulatedCameraStartsAndStopCancelsLateStart() {
        let camera = SimulatedCameraController(startDelay: 0.05)
        let listener = RecordingCameraListener()
        camera.setListener(listener: listener)

        camera.start(config: CameraConfig(lens: .front, initialZoomRatio: 1))
        camera.stop()
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        XCTAssertTrue(listener.started.isEmpty, "a start superseded by stop must not report started")

        camera.start(config: CameraConfig(lens: .front, initialZoomRatio: 1))
        waitUntil { listener.started.count == 1 }
    }

    func testSimulatedCaptureWritesAMirroredJpeg() throws {
        let camera = SimulatedCameraController()
        var outcome: CameraCaptureOutcome?
        camera.capturePhoto(request: CaptureRequest(mirrored: true, fromFrozenFrame: false)) { outcome = $0 }
        waitUntil { outcome != nil }
        let success = try XCTUnwrap(outcome as? CameraCaptureOutcomeSuccess)
        XCTAssertTrue(success.photo.mirrored)
        let url = PhotoFiles.url(fromLocalUri: success.photo.localUri)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        PhotoFiles.removeQuietly(url)
    }
}
