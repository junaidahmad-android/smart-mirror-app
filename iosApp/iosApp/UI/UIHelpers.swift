import SharedLogic
import SwiftUI

// Kotlin enums are exported as classes, so we compare against the class constants explicitly.

extension RgbColor {
    var swiftUIColor: Color {
        Color(red: Double(red) / 255, green: Double(green) / 255, blue: Double(blue) / 255)
    }
}

extension LightWarmth {
    static var presets: [LightWarmth] { [.warm, .daylight, .cool] }

    var title: String {
        switch self {
        case LightWarmth.warm: return String(localized: "Warm")
        case LightWarmth.cool: return String(localized: "Cool")
        default: return String(localized: "Daylight")
        }
    }

    var swatch: Color { RingLightAppearance.companion.kelvinToRgb(kelvin: kelvin).swiftUIColor }
}

extension LightIntensityPreset {
    static var presets: [LightIntensityPreset] { [.low, .medium, .high] }

    var title: String {
        switch self {
        case LightIntensityPreset.low: return String(localized: "Low")
        case LightIntensityPreset.high: return String(localized: "High")
        default: return String(localized: "Medium")
        }
    }
}

extension UserMessage {
    var text: String {
        switch self {
        case UserMessage.photoSaved: return String(localized: "Saved to Photos")
        case UserMessage.savePermissionDenied: return String(localized: "Can't save: allow “Add Photos Only” for Smart Mirror in Settings.")
        case UserMessage.storageFull: return String(localized: "Can't save: your device storage is full.")
        case UserMessage.thermalThrottled: return String(localized: "Device is warm. Brightness and frame rate are reduced.")
        default: return String(localized: "Couldn't take the photo. Please try again.")
        }
    }

    var isError: Bool { self != UserMessage.photoSaved && self != UserMessage.thermalThrottled }
}

extension MirrorUiState {
    var isMirrorMode: Bool { settings.mirrorMode == MirrorMode.mirror }
    var zoomPresetValues: [Float] { zoomPresets.map(\.floatValue) }
    var ringLightEnabled: Bool { settings.ringLight.enabled }
    var isBusyCapturing: Bool { captureStatus != CaptureStatus.idle }
}

enum Format {
    static func zoom(_ value: Float) -> String {
        let rounded = (value * 10).rounded() / 10
        return rounded == rounded.rounded() ? String(format: "%.0f×", rounded) : String(format: "%.1f×", rounded)
    }

    static func ev(_ value: Float) -> String {
        if abs(value) < 0.01 { return "0 EV" }
        return String(format: "%+.1f EV", value)
    }
}

/// Minimum 44 pt hit area (NFR-10) plus a readable scrim for buttons over the live preview.
struct OverlayIconButtonStyle: ButtonStyle {
    var isOn = false
    var diameter: CGFloat = 48

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: diameter * 0.4, weight: .semibold))
            .foregroundStyle(isOn ? Color.black : Color.white)
            .frame(width: diameter, height: diameter)
            .background(Circle().fill(isOn ? Color.white : Color.black.opacity(0.55)))
            .overlay(Circle().strokeBorder(Color.white.opacity(0.25), lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Circle())
    }
}

struct ChipButtonStyle: ButtonStyle {
    var isSelected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, 12)
            .frame(minWidth: 44, minHeight: 44)
            .foregroundStyle(isSelected ? Color.black : Color.white)
            .background(Capsule().fill(isSelected ? Color.white : Color.white.opacity(0.14)))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Capsule())
    }
}
