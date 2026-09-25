import Foundation
import SharedLogic

/// Composition root: builds the platform adapters, bundles them into `MirrorPorts` and asks the
/// app-scoped `SharedGraph` for the screen's ViewModel (ARCHITECTURE §5, §8.1).
///
/// Launch arguments (for the simulator, screenshots and UI tests):
/// - `-SMCamera real|simulated`: pick the camera adapter. Default: `real` on devices,
///   `simulated` in the Simulator (which has no camera). `real` in the Simulator shows the
///   "no front camera" error state.
/// - `-SMDemoControls YES` (DEBUG only): keep the controls visible once the camera runs.
/// - `-SMDemoLight 0.5` (DEBUG only): turn the ring light on at that intensity.
/// - `-SMOrientation landscape|portrait` (DEBUG only): request that interface orientation.
@MainActor
enum AppEnvironment {
    static func makeStore(graph: SharedGraph, arguments: UserDefaults = .standard) -> MirrorStore {
        let (camera, surface) = makeCamera(arguments: arguments)
        let ports = MirrorPorts(
            camera: camera,
            brightness: ScreenBrightnessController(),
            photoSaver: PhotoLibrarySaver(),
            permissions: CameraPermissionChecker()
        )
        let store = MirrorStore(viewModel: graph.createMirrorViewModel(ports: ports), surface: surface)
        #if DEBUG
        store.demoControls = arguments.bool(forKey: "SMDemoControls")
        if arguments.object(forKey: "SMDemoLight") != nil { store.demoLightIntensity = arguments.float(forKey: "SMDemoLight") }
        #endif
        return store
    }

    private static func makeCamera(arguments: UserDefaults) -> (CameraController, PreviewSurface) {
        let useSimulated: Bool
        switch arguments.string(forKey: "SMCamera") {
        case "real": useSimulated = false
        case "simulated": useSimulated = true
        default:
            #if targetEnvironment(simulator)
            useSimulated = true
            #else
            useSimulated = false
            #endif
        }
        if useSimulated {
            let camera = SimulatedCameraController()
            return (camera, camera.surface)
        }
        let camera = AVCameraController()
        return (camera, camera.surface)
    }
}
