import SharedLogic
import SwiftUI

/// MVP-15: the few options plus app info. No network requests; the support link opens Mail.
struct SettingsSheet: View {
    let state: MirrorUiState
    let send: (MirrorIntent) -> Void
    let onDone: () -> Void
    @State private var confirmReset = false

    // TODO(product): confirm the real support address before release.
    static let supportMailto = "mailto:support@smartmirror.app?subject=Smart%20Mirror%20feedback"

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "\(short) (\(build))"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle(isOn: Binding(
                        get: { state.settings.autoMaxBrightness },
                        set: { send(MirrorIntentSetAutoMaxBrightness(enabled: $0)) }
                    )) {
                        Label("Auto max brightness", systemImage: "sun.max")
                    }
                } footer: {
                    Text("Turns the screen to full brightness while the mirror is open, and restores your level when you leave.")
                }

                Section {
                    Button(role: .destructive) { confirmReset = true } label: {
                        Label("Reset to defaults", systemImage: "arrow.counterclockwise")
                    }
                    .confirmationDialog(Text("Reset all settings?"), isPresented: $confirmReset, titleVisibility: .visible) {
                        Button("Reset", role: .destructive) { send(MirrorIntentResetSettingsToDefaults.shared) }
                    } message: {
                        Text("Light off, 50% intensity, Daylight, 1× zoom, Mirror mode, auto max brightness on.")
                    }
                }

                Section("Privacy") {
                    Text("Smart Mirror works entirely on your device. There is no account, and nothing you see is recorded or sent anywhere. Photos are saved only when you tap the shutter, and only to your photo library.")
                        .font(.callout)
                }

                Section("About") {
                    LabeledContent("Version", value: version)
                    NavigationLink {
                        LicensesView()
                    } label: {
                        Text("Open-source licenses")
                    }
                    if let mail = URL(string: Self.supportMailto) {
                        Link(destination: mail) {
                            Label("Contact support", systemImage: "envelope")
                        }
                    }
                }
            }
            .navigationTitle(Text("Settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDone)
                }
            }
        }
    }
}

private struct LicensesView: View {
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Kotlin, kotlinx.coroutines").font(.headline)
                    Text("Copyright JetBrains s.r.o. and contributors. Licensed under the Apache License, Version 2.0.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(Text("Licenses"))
    }
}
