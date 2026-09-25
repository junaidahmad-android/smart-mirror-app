import Foundation
import SharedLogic

/// Bridges the shared Kotlin `MirrorViewModel` (StateFlow + onIntent) into SwiftUI (ARCHITECTURE §4).
///
/// `ObservableObject` rather than `@Observable` because the deployment target is iOS 16.
/// Kotlin delivers state on the main thread (Dispatchers.Main), so updates are main-actor safe.
@MainActor
final class MirrorStore: ObservableObject {
    @Published private(set) var state: MirrorUiState

    /// One value per user-facing message *occurrence* (QA-BUG-06). The shared state keeps
    /// `message == PHOTO_SAVED` across back-to-back saves, so SwiftUI's `onChange(of: message)`
    /// would stay silent for the second one. Every finished capture (and every new message value)
    /// produces a fresh event, which drives the toast, haptic, flash and VoiceOver announcement.
    @Published private(set) var feedback: FeedbackEvent?
    private var feedbackCount = 0

    /// What the camera adapter renders into (preview layer or simulated feed, frozen frame).
    let surface: PreviewSurface

    private let viewModel: MirrorViewModel
    private var subscription: FlowSubscription?
    private var systemEvents: SystemEventsObserver?

    init(viewModel: MirrorViewModel, surface: PreviewSurface, observeSystemEvents: Bool = true) {
        self.viewModel = viewModel
        self.surface = surface
        self.state = viewModel.currentState
        subscription = viewModel.observeState { [weak self] newState in
            MainActor.assumeIsolated { self?.apply(newState) }
        }
        if observeSystemEvents {
            systemEvents = SystemEventsObserver { [weak self] intent in
                MainActor.assumeIsolated { self?.send(intent) }
            }
        }
    }

    func send(_ intent: MirrorIntent) {
        viewModel.onIntent(intent: intent)
    }

    #if DEBUG
    /// Screenshot/UI-test aid (`-SMDemoControls YES`): pin the controls open once the camera runs.
    var demoControls = false
    /// `-SMDemoLight <0.1...1>`: turn the ring light on at that intensity once the camera runs.
    var demoLightIntensity: Float?
    private var demoApplied = false
    #endif

    private func apply(_ newState: MirrorUiState) {
        let old = state
        state = newState
        let captureFinished = old.captureStatus != CaptureStatus.idle && newState.captureStatus == CaptureStatus.idle
        if let message = newState.message, captureFinished || message != old.message {
            feedbackCount += 1
            feedback = FeedbackEvent(id: feedbackCount, message: message)
        }
        #if DEBUG
        if (demoControls || demoLightIntensity != nil), !demoApplied, newState.isCameraRunning {
            demoApplied = true
            if let intensity = demoLightIntensity { send(MirrorIntentSetRingLightIntensity(intensity: intensity)) }
            if demoControls {
                send(MirrorIntentSetScreenReaderActive(active: true))
                if !newState.controlsVisible { send(MirrorIntentScreenTapped.shared) }
            }
        }
        #endif
    }

    deinit {
        subscription?.cancel()
        viewModel.close()
    }
}

struct FeedbackEvent: Equatable {
    let id: Int
    let message: UserMessage
}
