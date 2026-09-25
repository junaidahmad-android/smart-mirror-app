import SharedLogic
import SwiftUI
import UIKit

/// The live mirror (MVP-01..MVP-10). Renders `MirrorUiState`; every decision lives in the shared
/// reducer. UI-owned bits (ARCHITECTURE §8.1): layout per size class, gesture recognition,
/// frozen-image pan, transient labels, haptics and the saved flash.
struct MirrorScreenView: View {
    @ObservedObject var store: MirrorStore
    @ObservedObject private var surface: PreviewSurface

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var tool: ControlTool = .zoom
    @State private var lastMagnification: CGFloat = 1
    @State private var panOffset: CGSize = .zero
    @State private var panStart: CGSize = .zero
    @State private var zoomLabelVisible = false
    @State private var zoomLabelToken = 0
    @State private var modeLabel: String?
    @State private var modeLabelToken = 0
    @State private var flashOpacity: Double = 0
    @State private var taps = TapClassifier()
    /// True for the double-tap window after a tap revealed the controls, so a second tap lands on
    /// the mirror (double tap) even where the freshly shown controls would be.
    @State private var controlsPassThrough = false
    @State private var passThroughToken = 0
    @State private var toast: FeedbackEvent?
    @State private var viewport: CGSize = .zero

    init(store: MirrorStore) {
        self.store = store
        _surface = ObservedObject(wrappedValue: store.surface)
    }

    private var state: MirrorUiState { store.state }

    /// Layout comes from the actual window size, not only size classes: on iPadOS 26 the app can run
    /// in a freely resizable window (UIRequiresFullScreen is ignored, QA-BUG-07), so every size
    /// between a phone and a full iPad must work. The side rail needs room for a 300 pt card plus
    /// the action column; otherwise, or in portrait-like shapes on compact width, use the bottom bar.
    static func layout(for size: CGSize, horizontalSizeClass: UserInterfaceSizeClass?) -> ControlsOverlay.Layout {
        let railFits = size.width >= 480 && size.height >= 320
        let portraitLike = size.height > size.width
        if !railFits { return .bottomBar }
        if portraitLike && horizontalSizeClass != .regular { return .bottomBar }
        return .sideRail
    }

    /// Roomy spacing and bigger targets only when the window itself is tablet-sized.
    static func isLarge(_ size: CGSize) -> Bool {
        min(size.width, size.height) >= 600
    }

    var body: some View {
        GeometryReader { geo in
            let fullSize = CGSize(
                width: geo.size.width + geo.safeAreaInsets.leading + geo.safeAreaInsets.trailing,
                height: geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom
            )
            let windowSize = geo.size
            ZStack {
                preview
                    .ignoresSafeArea()
                frozenImage(fullSize: fullSize)
                    .ignoresSafeArea()
                if state.ringLight.visible {
                    RingLightOverlay(appearance: state.ringLight, safeArea: geo.safeAreaInsets)
                }
                gestureLayer(fullSize: fullSize)
                    .ignoresSafeArea()
                cameraStatusOverlay
                hud
                if state.controlsVisible {
                    ControlsOverlay(
                        state: state,
                        layout: Self.layout(for: windowSize, horizontalSizeClass: horizontalSizeClass),
                        isLarge: Self.isLarge(windowSize),
                        tool: $tool,
                        send: store.send
                    )
                        .allowsHitTesting(!controlsPassThrough)
                        .transition(.opacity)
                }
                messageToast
                Color.white
                    .opacity(flashOpacity)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: state.controlsVisible)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: toast)
            .onAppear { updateViewport(fullSize) }
            .onChange(of: fullSize) { updateViewport($0) }
        }
        .background(Color.black.ignoresSafeArea())
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .sheet(isPresented: Binding(
            get: { store.state.settingsOpen },
            set: { if !$0 { store.send(MirrorIntentSetSettingsOpen(open: false)) } }
        )) {
            SettingsSheet(state: store.state, send: store.send) {
                store.send(MirrorIntentSetSettingsOpen(open: false))
            }
        }
        .onChange(of: state.zoomRatio) { _ in showZoomLabel() }
        .onChange(of: state.frozenImageScale) { _ in
            showZoomLabel()
            panOffset = clampedPan(panOffset, scale: CGFloat(state.frozenImageScale), size: viewport)
            panStart = panOffset
        }
        .onChange(of: state.isFrozen) { _ in
            panOffset = .zero
            panStart = .zero
        }
        .onChange(of: state.previewMirrored) { mirrored in
            showModeLabel(mirrored ? String(localized: "Mirror") : String(localized: "True view"))
        }
        .onChange(of: store.feedback) { event in
            guard let event else { return }
            present(event)
        }
    }

    // MARK: - Preview layers

    @ViewBuilder
    private var preview: some View {
        if surface.isSimulated {
            SimulatedFeedView(zoom: CGFloat(state.zoomRatio), mirrored: state.previewMirrored)
        } else {
            CameraPreviewView(surface: surface)
        }
    }

    @ViewBuilder
    private func frozenImage(fullSize: CGSize) -> some View {
        if state.isFrozen, let image = surface.frozenImage {
            Image(decorative: image, scale: 1)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: fullSize.width, height: fullSize.height)
                .scaleEffect(x: state.previewMirrored ? -1 : 1, y: 1)
                .scaleEffect(CGFloat(state.frozenImageScale))
                .offset(panOffset)
                .frame(width: fullSize.width, height: fullSize.height)
                .clipped()
                .accessibilityHidden(true)
        }
    }

    // MARK: - Gestures

    private func gestureLayer(fullSize: CGSize) -> some View {
        let pinch = MagnificationGesture()
            .onChanged { value in
                let factor = value / lastMagnification
                lastMagnification = value
                if factor.isFinite, factor > 0 { store.send(MirrorIntentPinchZoom(scaleFactor: Float(factor))) }
            }
            .onEnded { _ in lastMagnification = 1 }

        let pan = DragGesture(minimumDistance: 10)
            .onChanged { value in
                let proposed = CGSize(width: panStart.width + value.translation.width, height: panStart.height + value.translation.height)
                panOffset = clampedPan(proposed, scale: CGFloat(state.frozenImageScale), size: fullSize)
            }
            .onEnded { _ in panStart = panOffset }

        return Color.clear
            .contentShape(Rectangle())
            .gesture(pinch)
            .simultaneousGesture(pan, including: state.isFrozen ? .all : .subviews)
            // No `.onTapGesture(count: 2)`: SwiftUI would hold every single tap until the double-tap
            // window expires (~300 ms, QA-BUG-13, MVP-02.AC1 wants 150 ms). Single taps act at once;
            // a second tap in the window undoes the controls toggle and toggles zoom instead.
            .gesture(SpatialTapGesture().onEnded { value in handleTap(at: value.location) })
            .accessibilityElement()
            .accessibilityLabel(Text("Mirror"))
            .accessibilityValue(Text("Zoom \(Format.zoom(state.isFrozen ? state.frozenImageScale : state.zoomRatio))"))
            .accessibilityHint(state.controlsVisible ? Text("Hides the controls") : Text("Shows the controls"))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { store.send(MirrorIntentScreenTapped.shared) }
    }

    private func handleTap(at location: CGPoint) {
        switch taps.register(at: location, time: ProcessInfo.processInfo.systemUptime) {
        case .single:
            if !state.controlsVisible { passThroughControls() }
            store.send(MirrorIntentScreenTapped.shared)
        case .double:
            controlsPassThrough = false
            store.send(MirrorIntentScreenTapped.shared) // revert the first tap's controls toggle
            store.send(MirrorIntentToggleZoom.shared)
        }
    }

    private func passThroughControls() {
        controlsPassThrough = true
        passThroughToken += 1
        let token = passThroughToken
        DispatchQueue.main.asyncAfter(deadline: .now() + TapClassifier.doubleTapInterval) {
            if token == passThroughToken { controlsPassThrough = false }
        }
    }

    private func updateViewport(_ size: CGSize) {
        viewport = size
        surface.viewportSize = size
    }

    /// Bounded pan: the frozen image can never be dragged off screen (MVP-07.AC3).
    private func clampedPan(_ offset: CGSize, scale: CGFloat, size: CGSize) -> CGSize {
        let maxX = max(0, (scale - 1) * size.width / 2)
        let maxY = max(0, (scale - 1) * size.height / 2)
        return CGSize(width: min(max(offset.width, -maxX), maxX), height: min(max(offset.height, -maxY), maxY))
    }

    // MARK: - Overlays

    @ViewBuilder
    private var cameraStatusOverlay: some View {
        if state.cameraStatus == CameraStatus.error {
            let error = state.cameraError ?? CameraError.unknown
            VStack(spacing: 16) {
                Image(systemName: error == CameraError.noFrontCamera ? "camera.badge.ellipsis" : "video.slash")
                    .font(.system(size: 44))
                    .accessibilityHidden(true)
                Text(Self.errorTitle(error)).font(.title3.bold()).multilineTextAlignment(.center)
                Text(Self.errorMessage(error)).font(.body).multilineTextAlignment(.center).foregroundStyle(.white.opacity(0.8))
                Button(String(localized: "Try again")) { store.send(MirrorIntentRetryCamera.shared) }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 260)
            }
            .foregroundStyle(.white)
            .padding(28)
            .frame(maxWidth: 420)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color.black.opacity(0.75)))
            .padding(24)
            .accessibilityElement(children: .contain)
        }
    }

    static func errorTitle(_ error: CameraError) -> String {
        switch error {
        case CameraError.noFrontCamera: return String(localized: "No front camera found")
        case CameraError.inUseByAnotherApp, CameraError.disconnected: return String(localized: "Camera unavailable")
        default: return String(localized: "Camera problem")
        }
    }

    static func errorMessage(_ error: CameraError) -> String {
        switch error {
        case CameraError.noFrontCamera:
            return String(localized: "Smart Mirror needs a front camera, and none is available on this device.")
        case CameraError.inUseByAnotherApp, CameraError.disconnected:
            return String(localized: "Another app or a call is using the camera. Your mirror comes back automatically when it's free.")
        default:
            return String(localized: "Something went wrong with the camera.")
        }
    }

    private var hud: some View {
        VStack(spacing: 12) {
            if state.isFrozen {
                Button { store.send(MirrorIntentToggleFreeze.shared) } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "pause.circle.fill")
                        Text("Frozen")
                        Text("·").accessibilityHidden(true)
                        Text("Resume").underline()
                    }
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .background(Capsule().fill(Color.black.opacity(0.65)))
                    .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Frozen"))
                .accessibilityHint(Text("Resumes the live mirror"))
            }
            if zoomLabelVisible || modeLabel != nil {
                Text(modeLabel ?? Format.zoom(state.isFrozen ? state.frozenImageScale : state.zoomRatio))
                    .font(.title2.weight(.semibold).monospacedDigit())
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(Color.black.opacity(0.55)))
                    .foregroundStyle(.white)
                    .accessibilityHidden(true)
                    .transition(.opacity)
            }
            Spacer()
            if state.showTapHint && !state.controlsVisible {
                Text("Tap for controls")
                    .font(.callout.weight(.semibold))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(Color.black.opacity(0.55)))
                    .foregroundStyle(.white)
                    .padding(.bottom, 40)
                    .allowsHitTesting(false)
            }
        }
        .padding(.top, 12)
        .frame(maxWidth: .infinity)
    }

    /// Driven by `store.feedback` (one event per occurrence) with its own timer, so a second save
    /// within the shared 2.5 s message window still gets a full-length toast (QA-BUG-06).
    @ViewBuilder
    private var messageToast: some View {
        if let event = toast {
            let message = event.message
            VStack {
                HStack(spacing: 12) {
                    Image(systemName: message.isError ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                        .foregroundStyle(message.isError ? Color.yellow : Color.green)
                        .accessibilityHidden(true)
                    Text(message.text).font(.callout).fixedSize(horizontal: false, vertical: true)
                    if message == UserMessage.savePermissionDenied {
                        Button(String(localized: "Settings")) { store.send(MirrorIntentOpenAppSettings.shared) }
                            .font(.callout.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.black.opacity(0.8)))
                .frame(maxWidth: 520)
                .padding(.horizontal, 24)
                .padding(.top, state.isFrozen ? 64 : 12)
                .onTapGesture {
                    toast = nil
                    store.send(MirrorIntentDismissMessage.shared)
                }
                Spacer()
            }
            .transition(.opacity)
        }
    }

    // MARK: - Transient feedback

    private func showZoomLabel() {
        zoomLabelVisible = true
        zoomLabelToken += 1
        let token = zoomLabelToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            if token == zoomLabelToken { zoomLabelVisible = false }
        }
    }

    private func showModeLabel(_ text: String) {
        modeLabel = text
        modeLabelToken += 1
        let token = modeLabelToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            if token == modeLabelToken { modeLabel = nil }
        }
    }

    static let toastDuration: TimeInterval = 2.5

    private func present(_ event: FeedbackEvent) {
        toast = event
        UIAccessibility.post(notification: .announcement, argument: event.message.text)
        if event.message == UserMessage.photoSaved { celebrateSave() }
        if event.message.isError { UINotificationFeedbackGenerator().notificationOccurred(.error) }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.toastDuration) {
            if toast == event { toast = nil }
        }
    }

    /// MVP-08.AC4: flash + haptic within 1 s of saving; no flash under Reduce Motion (NFR-10).
    private func celebrateSave() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        guard !reduceMotion else { return }
        flashOpacity = 0.7
        withAnimation(.easeOut(duration: 0.35)) { flashOpacity = 0 }
    }
}

/// Immediate single taps plus double-tap detection without delaying the single tap (QA-BUG-13).
struct TapClassifier {
    enum Kind: Equatable { case single, double }

    static let doubleTapInterval: TimeInterval = 0.3
    static let doubleTapRadius: CGFloat = 44

    private var last: (time: TimeInterval, location: CGPoint)?

    mutating func register(at location: CGPoint, time: TimeInterval) -> Kind {
        if let last, time - last.time <= Self.doubleTapInterval,
           hypot(location.x - last.location.x, location.y - last.location.y) <= Self.doubleTapRadius {
            self.last = nil // a third tap starts a new sequence
            return .double
        }
        last = (time, location)
        return .single
    }
}
