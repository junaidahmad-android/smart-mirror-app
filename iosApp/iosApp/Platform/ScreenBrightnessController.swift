import SharedLogic
import UIKit

protocol ScreenBrightnessProviding: AnyObject {
    var brightness: CGFloat { get set }
}

extension UIScreen: ScreenBrightnessProviding {}

protocol IdleTimerControlling: AnyObject {
    var isIdleTimerDisabled: Bool { get set }
}

extension UIApplication: IdleTimerControlling {}

/// `BrightnessController` port: boosts `UIScreen.brightness` while the mirror is on screen and
/// puts the user's own level back afterwards (MVP-06). Never touches a system setting.
///
/// Safety net for MVP-06.AC2/AC3: on resign-active the user's level is restored immediately,
/// even before the ViewModel reacts to the scene phase, and re-applied on becoming active.
final class ScreenBrightnessController: NSObject, BrightnessController {
    private let screen: () -> ScreenBrightnessProviding?
    private let idleTimer: () -> IdleTimerControlling?
    private var observers: [NSObjectProtocol] = []
    private let notificationCenter: NotificationCenter

    /// User's level before we boosted it; nil while not boosted.
    private(set) var savedBrightness: CGFloat?
    /// The level the ViewModel currently wants; nil = user's level.
    private(set) var requestedLevel: CGFloat?

    init(
        screen: @escaping () -> ScreenBrightnessProviding? = { ScreenBrightnessController.activeScreen() },
        idleTimer: @escaping () -> IdleTimerControlling? = { UIApplication.shared },
        notificationCenter: NotificationCenter = .default
    ) {
        self.screen = screen
        self.idleTimer = idleTimer
        self.notificationCenter = notificationCenter
        super.init()
        observers.append(notificationCenter.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.restorePhysicalBrightness()
        })
        observers.append(notificationCenter.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, let level = self.requestedLevel else { return }
            self.boost(to: level)
        })
    }

    deinit {
        observers.forEach(notificationCenter.removeObserver)
    }

    func applyBrightness(level: Float) {
        onMain {
            let clamped = CGFloat(min(max(level, 0), 1))
            self.requestedLevel = clamped
            self.boost(to: clamped)
        }
    }

    func restoreBrightness() {
        onMain {
            self.requestedLevel = nil
            self.restorePhysicalBrightness()
        }
    }

    func setKeepScreenOn(enabled: Bool) {
        onMain { self.idleTimer()?.isIdleTimerDisabled = enabled }
    }

    private func boost(to level: CGFloat) {
        guard let screen = screen() else { return }
        if savedBrightness == nil { savedBrightness = screen.brightness }
        screen.brightness = level
    }

    private func restorePhysicalBrightness() {
        guard let saved = savedBrightness else { return }
        screen()?.brightness = saved
        savedBrightness = nil
    }

    static func activeScreen() -> UIScreen? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return (scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)?.screen
    }
}
