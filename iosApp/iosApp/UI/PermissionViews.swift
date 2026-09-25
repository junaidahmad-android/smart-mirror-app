import SwiftUI

/// Shared layout for full-screen informational states. Scrolls at large Dynamic Type sizes.
struct InfoScreen<Actions: View>: View {
    let systemImage: String
    let title: String
    let message: String
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        GeometryReader { geo in
        ScrollView {
            VStack(spacing: 24) {
                Image(systemName: systemImage)
                    .font(.system(size: 64, weight: .light))
                    .foregroundStyle(.white.opacity(0.9))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.title.bold())
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(message)
                    .font(.body)
                    .foregroundStyle(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                VStack(spacing: 12) { actions() }
                    .padding(.top, 8)
            }
            .frame(maxWidth: 480)
            .padding(32)
            .frame(maxWidth: .infinity, minHeight: geo.size.height)
        }
        .scrollBounceBehaviorIfAvailable()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .foregroundStyle(.white)
        .background(Color.black.ignoresSafeArea())
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 50)
            .padding(.horizontal, 16)
            .foregroundStyle(.black)
            .background(Capsule().fill(Color.white))
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 50)
            .padding(.horizontal, 16)
            .foregroundStyle(.white)
            .background(Capsule().strokeBorder(Color.white.opacity(0.6), lineWidth: 1.5))
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}

/// MVP-12.AC1: one friendly screen, one primary button that triggers the OS prompt.
struct PermissionOnboardingView: View {
    let onContinue: () -> Void

    var body: some View {
        InfoScreen(
            systemImage: "person.crop.square",
            title: String(localized: "Your mirror, anywhere"),
            message: String(localized: "Smart Mirror uses your front camera. Nothing is recorded or sent anywhere.")
        ) {
            Button(String(localized: "Continue"), action: onContinue)
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityHint(Text("Asks for camera access"))
        }
    }
}

/// MVP-12.AC3: explains the denial and offers Settings (iOS can't re-prompt after "Don't Allow").
struct PermissionDeniedView: View {
    let canAskAgain: Bool
    let onTryAgain: () -> Void
    let onOpenSettings: () -> Void

    var body: some View {
        InfoScreen(
            systemImage: "video.slash",
            title: String(localized: "Camera access is off"),
            message: canAskAgain
                ? String(localized: "Smart Mirror needs the front camera to show your reflection. Nothing is recorded or sent anywhere.")
                : String(localized: "To use Smart Mirror, turn on Camera for Smart Mirror in Settings. Your mirror starts automatically when you come back.")
        ) {
            Button(String(localized: "Open Settings"), action: onOpenSettings)
                .buttonStyle(PrimaryButtonStyle())
            if canAskAgain {
                Button(String(localized: "Try again"), action: onTryAgain)
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
    }
}

/// NFR-06: thermal state CRITICAL pauses the preview.
struct OverheatedView: View {
    var body: some View {
        InfoScreen(
            systemImage: "thermometer.sun",
            title: String(localized: "Device is hot"),
            message: String(localized: "The mirror is paused to let your device cool down. It resumes automatically.")
        ) { EmptyView() }
    }
}

private extension View {
    @ViewBuilder
    func scrollBounceBehaviorIfAvailable() -> some View {
        if #available(iOS 16.4, *) {
            scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }
}
