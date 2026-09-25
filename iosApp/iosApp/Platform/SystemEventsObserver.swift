import Foundation
import SharedLogic
import UIKit

/// Forwards OS signals the shared ViewModel needs (ARCHITECTURE §8.2): thermal pressure
/// (`ProcessInfo.thermalState`, NFR-06) and VoiceOver (NFR-10: no auto-hide while it runs).
final class SystemEventsObserver {
    private var observers: [NSObjectProtocol] = []

    init(send: @escaping (MirrorIntent) -> Void) {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main) { _ in
            send(MirrorIntentThermalStateChanged(state: Self.map(ProcessInfo.processInfo.thermalState)))
        })
        observers.append(center.addObserver(forName: UIAccessibility.voiceOverStatusDidChangeNotification, object: nil, queue: .main) { _ in
            send(MirrorIntentSetScreenReaderActive(active: UIAccessibility.isVoiceOverRunning))
        })
        send(MirrorIntentThermalStateChanged(state: Self.map(ProcessInfo.processInfo.thermalState)))
        send(MirrorIntentSetScreenReaderActive(active: UIAccessibility.isVoiceOverRunning))
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    static func map(_ state: ProcessInfo.ThermalState) -> ThermalState {
        switch state {
        case .nominal: return .nominal
        case .fair: return .fair
        case .serious: return .serious
        case .critical: return .critical
        @unknown default: return .serious
        }
    }
}
