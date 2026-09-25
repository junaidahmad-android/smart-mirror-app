import SharedLogic
import SwiftUI

enum ControlTool: String, CaseIterable, Identifiable {
    case zoom, exposure, light
    var id: String { rawValue }

    var title: String {
        switch self {
        case .zoom: return String(localized: "Zoom")
        case .exposure: return String(localized: "Exposure")
        case .light: return String(localized: "Light")
        }
    }

    var systemImage: String {
        switch self {
        case .zoom: return "plus.magnifyingglass"
        case .exposure: return "sun.max"
        case .light: return "lightbulb"
        }
    }
}

/// The tap-revealed controls (MVP-02). Two layouts:
/// - `.bottomBar`: compact width + regular height (iPhone portrait): tool card above an action row.
/// - `.sideRail`: everything else (iPhone landscape, iPad any orientation): a fixed-width card and
///   an action column on the trailing edge, so tablet controls never stretch edge to edge.
struct ControlsOverlay: View {
    enum Layout { case bottomBar, sideRail }

    let state: MirrorUiState
    let layout: Layout
    let isLarge: Bool
    @Binding var tool: ControlTool
    let send: (MirrorIntent) -> Void

    private var tools: [ControlTool] {
        ControlTool.allCases.filter { $0 != .exposure || state.showExposureControl }
    }

    var body: some View {
        ZStack {
            VStack {
                HStack {
                    Button { send(MirrorIntentSetSettingsOpen(open: true)) } label: {
                        Image(systemName: "gearshape.fill")
                    }
                    .buttonStyle(OverlayIconButtonStyle())
                    .accessibilityLabel(Text("Settings"))
                    Spacer()
                }
                Spacer()
            }
            .padding(isLarge ? 24 : 12)

            switch layout {
            case .bottomBar:
                VStack(spacing: 12) {
                    Spacer()
                    toolCard
                    actionRow
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            case .sideRail:
                HStack(spacing: isLarge ? 20 : 12) {
                    Spacer()
                    toolCard.frame(width: isLarge ? 360 : 300)
                    actionColumn
                }
                .padding(.trailing, isLarge ? 24 : 12)
                .padding(.vertical, 12)
            }
        }
    }

    // MARK: - Tool card

    private var toolCard: some View {
        let selected = tools.contains(tool) ? tool : .zoom
        return VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .horizontal) {
                toolPicker(showTitles: true)
                toolPicker(showTitles: false)
            }
            if layout == .bottomBar {
                // Hugs its content; the bottom bar only ever shows one short tool section.
                // Falls back to scrolling in short windows (iPadOS 26 resizable windows, QA-BUG-07).
                ViewThatFits(in: .vertical) {
                    section(selected)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                    ScrollView(showsIndicators: false) {
                        section(selected).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } else {
                // Scrolls when it doesn't fit (iPhone landscape, large Dynamic Type).
                ViewThatFits(in: .vertical) {
                    section(selected).frame(maxWidth: .infinity, alignment: .leading)
                    ScrollView(showsIndicators: false) {
                        section(selected).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .padding(isLarge ? 20 : 14)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color.black.opacity(0.62)))
        .foregroundStyle(.white)
        .frame(maxWidth: 520)
    }

    private func toolPicker(showTitles: Bool) -> some View {
        let selected = tools.contains(tool) ? tool : .zoom
        return HStack(spacing: 8) {
            ForEach(tools) { item in
                Button { tool = item } label: {
                    if showTitles {
                        Label(item.title, systemImage: item.systemImage).labelStyle(.titleAndIcon)
                    } else {
                        Image(systemName: item.systemImage)
                    }
                }
                .buttonStyle(ChipButtonStyle(isSelected: item == selected))
                .accessibilityLabel(Text(item.title))
                .accessibilityAddTraits(item == selected ? .isSelected : [])
            }
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    @ViewBuilder
    private func section(_ tool: ControlTool) -> some View {
        switch tool {
        case .zoom: zoomSection
        case .exposure: exposureSection
        case .light: lightSection
        }
    }

    private var zoomSection: some View {
        let frozen = state.isFrozen
        let value = frozen ? state.frozenImageScale : state.zoomRatio
        let caps = state.capabilities
        let range: ClosedRange<Float> = frozen
            ? 1...MirrorUiState.companion.MAX_FROZEN_IMAGE_SCALE
            : (caps.map { $0.minZoomRatio...max($0.maxZoomRatio, $0.minZoomRatio + 0.01) } ?? 1...5)
        return VStack(alignment: .leading, spacing: 10) {
            valueHeader(frozen ? String(localized: "Zoom (frozen image)") : String(localized: "Zoom"), value: Format.zoom(value))
            Slider(value: Binding(get: { value }, set: { send(MirrorIntentSetZoom(ratio: $0)) }), in: range)
                .tint(.white)
                .accessibilityLabel(Text("Zoom"))
                .accessibilityValue(Text(Format.zoom(value)))
            if !frozen, !state.zoomPresetValues.isEmpty {
                HStack(spacing: 8) {
                    ForEach(state.zoomPresetValues, id: \.self) { preset in
                        let selected = abs(state.zoomRatio - preset) < 0.05
                        Button(Format.zoom(preset)) { send(MirrorIntentSetZoom(ratio: preset)) }
                            .buttonStyle(ChipButtonStyle(isSelected: selected))
                            .accessibilityLabel(Text("Zoom \(Format.zoom(preset))"))
                            .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var exposureSection: some View {
        if let caps = state.capabilities, state.showExposureControl {
            let step = caps.exposureStepEv
            VStack(alignment: .leading, spacing: 10) {
                valueHeader(String(localized: "Exposure"), value: Format.ev(state.exposureEv))
                HStack(spacing: 8) {
                    Slider(
                        value: Binding(get: { state.exposureEv }, set: { value in
                            // Detent at 0 (MVP-04.AC1).
                            send(MirrorIntentSetExposure(ev: abs(value) < step * 0.75 ? 0 : value))
                        }),
                        in: caps.minExposureEv...caps.maxExposureEv,
                        step: step
                    )
                    .tint(.white)
                    .accessibilityLabel(Text("Exposure"))
                    .accessibilityValue(Text(Format.ev(state.exposureEv)))
                    Button { send(MirrorIntentResetExposure.shared) } label: {
                        Image(systemName: "arrow.counterclockwise")
                    }
                    .buttonStyle(OverlayIconButtonStyle(diameter: 44))
                    .disabled(abs(state.exposureEv) < 0.01)
                    .accessibilityLabel(Text("Reset exposure"))
                }
            }
        }
    }

    private var lightSection: some View {
        let light = state.settings.ringLight
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Toggle(isOn: Binding(get: { light.enabled }, set: { _ in send(MirrorIntentToggleRingLight.shared) })) {
                    Text("Ring light").font(.subheadline.weight(.semibold))
                }
                .tint(.yellow)
            }
            HStack(spacing: 8) {
                Image(systemName: "sun.min").accessibilityHidden(true)
                Slider(value: Binding(get: { light.intensity }, set: { send(MirrorIntentSetRingLightIntensity(intensity: $0)) }),
                       in: RingLightSettings.companion.MIN_INTENSITY...RingLightSettings.companion.MAX_INTENSITY)
                    .tint(.white)
                    .accessibilityLabel(Text("Light intensity"))
                    .accessibilityValue(Text("\(Int((light.intensity * 100).rounded())) percent"))
                Image(systemName: "sun.max.fill").accessibilityHidden(true)
            }
            HStack(spacing: 8) {
                ForEach(LightIntensityPreset.presets, id: \.self) { preset in
                    let selected = light.enabled && abs(light.intensity - preset.intensity) < 0.01
                    Button(preset.title) { send(MirrorIntentSetRingLightIntensity(intensity: preset.intensity)) }
                        .buttonStyle(ChipButtonStyle(isSelected: selected))
                        .accessibilityLabel(Text("Intensity \(preset.title)"))
                        .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            HStack(spacing: 8) {
                ForEach(LightWarmth.presets, id: \.self) { warmth in
                    let selected = light.enabled && light.warmth == warmth
                    Button { send(MirrorIntentSetLightWarmth(warmth: warmth)) } label: {
                        HStack(spacing: 6) {
                            Circle().fill(warmth.swatch).frame(width: 14, height: 14)
                                .overlay(Circle().strokeBorder(Color.black.opacity(0.3)))
                            Text(warmth.title)
                        }
                    }
                    .buttonStyle(ChipButtonStyle(isSelected: selected))
                    .accessibilityLabel(Text("\(warmth.title) light"))
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
    }

    private func valueHeader(_ title: String, value: String) -> some View {
        HStack {
            Text(title).font(.subheadline.weight(.semibold))
            Spacer()
            Text(value).font(.subheadline.monospacedDigit()).accessibilityHidden(true)
        }
    }

    // MARK: - Actions

    private var actionRow: some View {
        HStack {
            freezeButton
            Spacer()
            captureButton
            Spacer()
            mirrorButton
        }
        .padding(.horizontal, 20)
    }

    private var actionColumn: some View {
        ViewThatFits(in: .vertical) {
            actionColumnContent
            ScrollView(showsIndicators: false) { actionColumnContent }
        }
    }

    private var actionColumnContent: some View {
        VStack(spacing: isLarge ? 24 : 14) {
            mirrorButton
            captureButton
            freezeButton
        }
        .padding(.vertical, 4)
    }

    private var freezeButton: some View {
        Button { send(MirrorIntentToggleFreeze.shared) } label: {
            Image(systemName: state.isFrozen ? "play.fill" : "pause.fill")
        }
        .buttonStyle(OverlayIconButtonStyle(isOn: state.isFrozen, diameter: isLarge ? 60 : 52))
        .disabled(!state.isFrozen && !state.canFreeze)
        .accessibilityLabel(state.isFrozen ? Text("Resume live mirror") : Text("Freeze frame"))
    }

    private var mirrorButton: some View {
        Button { send(MirrorIntentToggleMirrorMode.shared) } label: {
            Image(systemName: "arrow.left.and.right.righttriangle.left.righttriangle.right")
        }
        .buttonStyle(OverlayIconButtonStyle(isOn: !state.isMirrorMode, diameter: isLarge ? 60 : 52))
        .accessibilityLabel(Text("True view"))
        .accessibilityValue(state.isMirrorMode ? Text("Off, showing mirror image") : Text("On, showing how others see you"))
        .accessibilityHint(Text("Switches between mirror and true view"))
    }

    private var captureButton: some View {
        let diameter: CGFloat = isLarge ? 84 : 72
        return Button { send(MirrorIntentCapturePhoto.shared) } label: {
            ZStack {
                Circle().fill(Color.white).padding(6)
                Circle().strokeBorder(Color.white, lineWidth: 4)
                if state.isBusyCapturing {
                    ProgressView().tint(.black)
                }
            }
            .frame(width: diameter, height: diameter)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!state.canCapture)
        .opacity(state.canCapture || state.isBusyCapturing ? 1 : 0.5)
        .accessibilityLabel(state.isFrozen ? Text("Save frozen photo") : Text("Take photo"))
    }
}
