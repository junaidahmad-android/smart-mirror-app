import SharedLogic
import SwiftUI

/// The on-screen ring light (MVP-05): the whole screen outside a rounded "window" is lit, and the
/// window sits inside the safe area inset by the band width, so notches/cutouts never cover the
/// visible preview (AC1). Width = `edgeWidthFraction` × the screen's short side (≥ 12% at 100%).
struct RingLightOverlay: View {
    let appearance: RingLightAppearance
    /// Safe-area insets of the screen (the overlay itself ignores the safe area).
    let safeArea: EdgeInsets
    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        GeometryReader { geo in
            let band = CGFloat(appearance.edgeWidthFraction) * min(geo.size.width, geo.size.height)
            let left = layoutDirection == .rightToLeft ? safeArea.trailing : safeArea.leading
            let right = layoutDirection == .rightToLeft ? safeArea.leading : safeArea.trailing
            let window = CGRect(
                x: left + band,
                y: safeArea.top + band,
                width: max(0, geo.size.width - left - right - 2 * band),
                height: max(0, geo.size.height - safeArea.top - safeArea.bottom - 2 * band)
            )
            RingShape(window: window, cornerRadius: min(band, 32))
                .fill(appearance.color.swiftUIColor.opacity(Double(appearance.opacity)), style: FillStyle(eoFill: true))
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct RingShape: Shape {
    let window: CGRect
    let cornerRadius: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        path.addRoundedRect(in: window, cornerSize: CGSize(width: cornerRadius, height: cornerRadius), style: .continuous)
        return path
    }
}
