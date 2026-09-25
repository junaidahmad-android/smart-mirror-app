import SharedLogic
import XCTest
@testable import Smartmirrorapp

/// End-to-end bridge test: Swift adapters → SharedGraph → Kotlin ViewModel → `observeState` → store.
@MainActor
final class MirrorStoreTests: XCTestCase {
    private func makeStore(permission: PermissionStatus = .granted) -> (MirrorStore, SimulatedCameraController, RecordingBrightness) {
        let camera = SimulatedCameraController(startDelay: 0.01)
        let brightness = RecordingBrightness()
        let ports = MirrorPorts(
            camera: camera,
            brightness: brightness,
            photoSaver: PhotoLibrarySaver(library: FakePhotoLibrary(status: .authorized)),
            permissions: FakePermissions(status: permission)
        )
        let graph = SharedGraph(keyValueStore: InMemoryKeyValueStore())
        let store = MirrorStore(viewModel: graph.createMirrorViewModel(ports: ports), surface: camera.surface, observeSystemEvents: false)
        return (store, camera, brightness)
    }

    func testStartsInLoadingThenShowsOnboardingWithoutPermission() {
        let (store, _, _) = makeStore(permission: .notDetermined)
        XCTAssertEqual(store.state.screen, .loading)
        store.send(MirrorIntentScreenStarted.shared)
        waitUntil { store.state.screen == .permissionOnboarding }
    }

    func testBlockedPermissionShowsSettingsOnlyScreen() {
        let (store, _, _) = makeStore(permission: .blocked)
        store.send(MirrorIntentScreenStarted.shared)
        waitUntil { store.state.screen == .permissionBlocked }
    }

    func testGrantedStartsCameraBoostsBrightnessAndKeepsScreenOn() {
        let (store, _, brightness) = makeStore()
        store.send(MirrorIntentScreenStarted.shared)
        waitUntil { store.state.isCameraRunning }
        XCTAssertEqual(store.state.screen, .mirror)
        XCTAssertFalse(store.state.controlsVisible, "no chrome on first display (MVP-01.AC3)")
        XCTAssertEqual(brightness.applied.last, 1)
        XCTAssertTrue(brightness.keepScreenOn)

        store.send(MirrorIntentScreenStopped.shared)
        waitUntil { !store.state.isCameraRunning }
        XCTAssertEqual(brightness.restoreCount, 1)
        XCTAssertFalse(brightness.keepScreenOn)
    }

    func testMirrorToggleReachesThePreviewSurface() {
        let (store, camera, _) = makeStore()
        store.send(MirrorIntentScreenStarted.shared)
        waitUntil { store.state.isCameraRunning }
        XCTAssertTrue(camera.surface.isMirrored)
        store.send(MirrorIntentToggleMirrorMode.shared)
        waitUntil { !store.state.previewMirrored }
        XCTAssertFalse(camera.surface.isMirrored)
    }

    func testTapPinchAndFreezeFlowThroughTheBridge() {
        let (store, camera, _) = makeStore()
        store.send(MirrorIntentScreenStarted.shared)
        waitUntil { store.state.isCameraRunning }

        store.send(MirrorIntentScreenTapped.shared)
        waitUntil { store.state.controlsVisible }

        store.send(MirrorIntentPinchZoom(scaleFactor: 2.5))
        waitUntil { abs(store.state.zoomRatio - 2.5) < 0.01 }

        store.send(MirrorIntentToggleFreeze.shared)
        waitUntil { store.state.isFrozen && camera.surface.frozenImage != nil }
        store.send(MirrorIntentPinchZoom(scaleFactor: 4))
        waitUntil { store.state.frozenImageScale == 4 }
        XCTAssertEqual(store.state.zoomRatio, 2.5, accuracy: 0.01, "pinch while frozen scales the image, not the camera")

        store.send(MirrorIntentToggleFreeze.shared)
        waitUntil { !store.state.isFrozen && camera.surface.frozenImage == nil }
    }

    func testCaptureSavesAndReportsMessage() {
        let (store, _, _) = makeStore()
        store.send(MirrorIntentScreenStarted.shared)
        waitUntil { store.state.canCapture }
        store.send(MirrorIntentCapturePhoto.shared)
        waitUntil { store.state.message == .photoSaved }
        XCTAssertEqual(store.state.lastSavedPhotoUri, "asset-1")
    }
}
