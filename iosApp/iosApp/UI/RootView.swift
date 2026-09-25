import SharedLogic
import SwiftUI

/// Top-level screen switch driven by `MirrorUiState.screen`, plus the lifecycle contract
/// (ARCHITECTURE §3): `scenePhase == .active` → `ScreenStarted`, anything else → `ScreenStopped`.
struct RootView: View {
    @ObservedObject var store: MirrorStore
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .preferredColorScheme(.dark)
            .onAppear {
                sendLifecycle(scenePhase)
                #if DEBUG
                applyDebugOrientation()
                #endif
            }
            .onChange(of: scenePhase) { sendLifecycle($0) }
    }

    @ViewBuilder
    private var content: some View {
        let screen = store.state.screen
        switch screen {
        case MirrorScreen.mirror:
            MirrorScreenView(store: store)
        case MirrorScreen.permissionOnboarding:
            PermissionOnboardingView { store.send(MirrorIntentRequestCameraPermission.shared) }
        case MirrorScreen.permissionDenied:
            PermissionDeniedView(
                canAskAgain: true,
                onTryAgain: { store.send(MirrorIntentRequestCameraPermission.shared) },
                onOpenSettings: { store.send(MirrorIntentOpenAppSettings.shared) }
            )
        case MirrorScreen.permissionBlocked:
            PermissionDeniedView(
                canAskAgain: false,
                onTryAgain: {},
                onOpenSettings: { store.send(MirrorIntentOpenAppSettings.shared) }
            )
        case MirrorScreen.overheated:
            OverheatedView()
        default:
            // LOADING: plain black, never flash onboarding before the permission is known.
            Color.black.ignoresSafeArea().accessibilityHidden(true)
        }
    }

    #if DEBUG
    private func applyDebugOrientation() {
        guard let value = UserDefaults.standard.string(forKey: "SMOrientation"),
              let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        let mask: UIInterfaceOrientationMask = value == "landscape" ? .landscapeRight : .portrait
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
    }
    #endif

    private func sendLifecycle(_ phase: ScenePhase) {
        store.send(phase == .active ? MirrorIntentScreenStarted.shared : MirrorIntentScreenStopped.shared)
    }
}
