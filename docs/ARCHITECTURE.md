# Smart Mirror: Architecture

Status: v1 (MVP baseline), owner: KMP Architect. Companion to [ROADMAP.md](./ROADMAP.md): story IDs such as `MVP-02` and `NFR-06` refer to that document.

## 1. Principles

1. **All business logic is shared Kotlin** (`:sharedLogic`). This covers state machines, settings, zoom/exposure math, the ring-light colour, the thermal policy, permission flow, capture orchestration and frame-pipeline policy.
2. **The UI is 100% native**: Jetpack Compose in `:androidApp` and SwiftUI in `iosApp`. The UI layers render `MirrorUiState` and send `MirrorIntent`s. They contain no decisions beyond layout, animation and gesture recognition.
3. **Hardware sits behind ports.** The shared code talks to the camera, screen brightness, the photo library and permissions only through small interfaces that each platform implements natively (CameraX / AVFoundation, and so on).
4. **Pixels never cross into Kotlin.** Kotlin is the *control plane*. The *data plane* (frames, GPU, NPU) stays native. See section 7.
5. **Unidirectional data flow** (MVI). There is one immutable state per screen and one entry point for input, and hardware effects are derived from state diffs.

## 2. Module map

### Now

```
:androidApp   Android app. Jetpack Compose UI + Android port adapters (CameraX, window brightness, MediaStore, permissions)
    └── depends on :sharedLogic
iosApp/       Xcode project. SwiftUI UI + iOS port adapters (AVFoundation, UIScreen, Photos, AVCaptureDevice auth)
    └── links SharedLogic.framework (static) via the "Compile Kotlin Framework" build phase:
        ./gradlew :sharedLogic:embedAndSignAppleFrameworkForXcode
:sharedLogic  KMP library: android + iosArm64 + iosSimulatorArm64, framework baseName "SharedLogic"
```

The template's Compose Multiplatform `:sharedUI` module was **removed** (ADR-001). `:androidApp` depends on `:sharedLogic` directly and uses the AndroidX Compose BOM.

### `:sharedLogic` package structure (`com.smart.mirror.app.*`)

| Package | Layer | Contents |
|---|---|---|
| `domain.model` | Domain | `MirrorSettings`, `MirrorMode`, `RingLightSettings`, `LightWarmth`, `LightIntensityPreset`, `RingLightAppearance`, `RgbColor`, `CameraCapabilities`, `CameraConfig`, `CameraStatus`, `CameraError`, `ThermalState`, `CaptureRequest`, `CapturedPhoto`, `CameraCaptureOutcome`, `SaveOutcome`, `CaptureResult`, `CaptureStatus`, `PermissionStatus` |
| `domain.port` | Domain | Platform ports: `CameraController`, `CameraListener`, `BrightnessController`, `PhotoSaver`, `PermissionChecker` |
| `domain.usecase` | Domain | `CapturePhotoUseCase` (capture, then save, then a mapped result) |
| `data.settings` | Data | `SettingsRepository`, `KeyValueSettingsRepository`, `KeyValueStore`, `InMemoryKeyValueStore`. Platform stores: `SharedPreferencesKeyValueStore` (androidMain), `UserDefaultsKeyValueStore` (iosMain) |
| `processing` | Domain/Data | Frame-pipeline control plane: `ProcessingPipeline` (port), `FrameProcessor`, `FrameProcessorDescriptor`, `ProcessorCapability`, `ProcessingStage`, `PipelineConfig`, `PipelineCoordinator`, `NoOpProcessingPipeline`, `ProcessorIds` |
| `presentation.mirror` | Presentation state | `MirrorViewModel`, `MirrorUiState`, `MirrorIntent`, `MirrorScreen`, `UserMessage`, `MirrorPorts`, internal `MirrorReducer` / `MirrorResult` |
| `di` | Wiring | `SharedGraph` (manual DI container) |
| `core` | Infra | `FlowSubscription` + the `subscribeIn` helper (Swift flow bridge) |
| `preview` | Tooling | `PreviewPorts`: hardware-free ports for previews, placeholders and the simulator |

Dependency rule: `presentation` → `domain` ← `data`. `domain` depends on nothing platform-specific. Only `data.settings` has `expect`-free platform source sets (plain classes in androidMain and iosMain).

### Future (split when a second screen or feature team appears; package names already match)

```
:core:model        domain.model + ports shared by all features
:core:data         settings, media store (compare-with-previous history), feature flags
:core:processing   processing control plane + PerformanceGovernor
:feature:mirror    presentation.mirror (today's MVP)
:feature:enhance   AI enhancement policies (which processors, parameters, presets)
:feature:tryon     AR hair/makeup: catalog, selection state, overlay params
:feature:compare   photo history, split view, delayed-mirror controls
:shared-umbrella   the ONLY module that produces the iOS framework (exports the others)
```

**iOS rule:** Kotlin/Native produces one framework per module. If several modules each produced a framework, each would carry its own copy of the Kotlin runtime and stdlib, and their types would be incompatible with each other. When modules are split, add a single umbrella module that `export(...)`s the feature modules into one `SharedLogic` framework, and keep the Xcode build phase pointed at that module.

## 3. Layers and data flow

```
 ┌──────────────── Platform UI (Compose / SwiftUI) ────────────────┐
 │  renders MirrorUiState          sends MirrorIntent              │
 └───────────▲──────────────────────────────┬──────────────────────┘
             │ StateFlow / observeState     │ onIntent(intent)
 ┌───────────┴──────────────────────────────▼──────────────────────┐
 │ MirrorViewModel (shared)                                        │
 │   1. state' = MirrorReducer.reduce(state, intent)   (pure)      │
 │   2. syncSideEffects(old, new)  → ports (zoom, EV, brightness…) │
 │   3. intent-specific effects    → start/stop camera, capture    │
 │   4. port callbacks → MirrorResult → reducer → (repeat 2)       │
 └───────┬─────────────────────┬──────────────────────┬────────────┘
         │ ports               │ repository           │ coordinator
 ┌───────▼─────────┐   ┌───────▼────────────┐  ┌──────▼──────────────┐
 │ CameraController│   │ SettingsRepository │  │ PipelineCoordinator │
 │ Brightness…     │   │  → KeyValueStore   │  │  → ProcessingPipeline│
 │ PhotoSaver      │   └────────────────────┘  └─────────────────────┘
 │ PermissionCheck │     (SharedPreferences /      (native GPU/NPU)
 └─────────────────┘      NSUserDefaults)
   (CameraX / AVFoundation, …)
```

* **Reducer** (`MirrorReducer`): pure `(state, intent|result) -> state`. It holds all the rules: clamping zoom to the camera's range, snapping exposure to the EV step, 1x/2x double-tap, frozen-image scale up to 8x, ring-light presets, the thermal policy, auto-hide eligibility and message mapping.
* **State-diff effects** (`syncSideEffects`): the ViewModel compares old and new state and issues port commands for what actually changed: zoom, exposure, mirroring, freeze, max fps, brightness level, keep-screen-on, debounced persistence, and the auto-hide and message timers. Nothing is sent twice, and the reducer never touches hardware.
* **Results** (`MirrorResult`, internal): asynchronous outcomes such as camera started or failed, permission resolved, capture finished and timers expiring. They go back through the reducer.
* **Threading**: the ViewModel runs on `Dispatchers.Main.immediate`. Ports may call back on any thread, and the ViewModel moves the work to main. Call `onIntent` on the main thread.
* **Persistence**: settings are written through `SettingsRepository` with a 300 ms debounce, so continuous gestures don't hammer storage. Pending writes are flushed on `close()`. Settings load lazily on a background dispatcher, which `SharedGraph.init` starts. That keeps storage I/O off the main thread before the camera opens (NFR-01). Exposure is deliberately session-only (MVP-11.AC1).

### Lifecycle contract

| Event | Android (Compose) | iOS (SwiftUI) | Intent |
|---|---|---|---|
| Visible / foreground | `ON_START` | `scenePhase == .active` | `ScreenStarted` (re-checks permission, so it also covers returning from Settings) |
| Hidden / background | `ON_STOP` | `scenePhase != .active` | `ScreenStopped` (stops the camera, restores brightness, discards the frozen frame) |
| Owner destroyed | `ViewModel.onCleared` | `deinit` of the ObservableObject | `viewModel.close()` |

On Android the shared `MirrorViewModel` is wrapped in a thin `androidx.lifecycle.ViewModel` (`MirrorHostViewModel`), so it survives rotation (MVP-10.AC2).

## 4. Consuming state from Swift

**Decision (ADR-003): a hand-written bridge, with no extra Gradle plugin.**

* `MirrorViewModel.observeState(onEach: (MirrorUiState) -> Unit): FlowSubscription` delivers the current state and then every change, on the main thread. Call `cancel()` in `deinit`. (It is not called `Cancellable`, to avoid a name clash with Combine.)
* `MirrorViewModel.currentState` gives a synchronous snapshot for the initial `@Published` value.
* Ports use plain callbacks (`onResult: (X) -> Unit`) instead of `suspend`. Swift implements them as ordinary closures, and no Kotlin coroutine is ever driven from Swift.

```swift
@MainActor final class MirrorStore: ObservableObject {
    @Published private(set) var state: MirrorUiState
    private let viewModel: MirrorViewModel
    private var subscription: FlowSubscription?
    init(viewModel: MirrorViewModel) {
        self.viewModel = viewModel
        state = viewModel.currentState
        subscription = viewModel.observeState { [weak self] s in MainActor.assumeIsolated { self?.state = s } }
    }
    func send(_ i: MirrorIntent) { viewModel.onIntent(intent: i) }
    deinit { subscription?.cancel(); viewModel.close() }
}
```

Why not SKIE or KMP-NativeCoroutines right now:

* **SKIE** gives the nicest Swift (real `enum`s for sealed and enum classes, `AsyncSequence` for flows, `async` for suspend). But it hooks into the Kotlin compiler and ships a release per Kotlin version. We are on Kotlin 2.4.20, and SKIE (0.10.x) has to explicitly support each Kotlin release. Pinning our Kotlin upgrades to a third-party plugin is a risk that a 20-line bridge avoids.
* **KMP-NativeCoroutines** needs KSP plus annotations on every exposed flow, and it also follows Kotlin versions closely.
* **Upgrade path:** as soon as SKIE officially supports our Kotlin version, adding it is non-breaking. `observeState` keeps working, and Swift can switch to `for await state in viewModel.state`. Revisit when we add the second screen.

Swift ergonomics to know about without SKIE:

* Kotlin `enum class` values are class instances: `state.screen == .mirror` works, but a `switch` needs a `default:`.
* Sealed intents: `MirrorIntentScreenTapped.shared` for objects and `MirrorIntentSetZoom(ratio: 2)` for data classes.
* Nullable primitives come across as `KotlinFloat?` (for example `brightnessLevel`). The UI normally does not need them.

## 5. Dependency injection

**Decision (ADR-004): manual DI through `SharedGraph`.** The graph is small: one screen, four ports, one repository and one pipeline. A hand-written container is compile-time safe, has zero startup cost (NFR-01) and is trivial to call from Swift.

* **App-scoped**: `SharedGraph(keyValueStore:[processingPipeline:])` is created once. On Android that's in `SmartMirrorApplication`. On iOS it's in the `App` struct.
* **Screen-scoped**: `graph.createMirrorViewModel(ports: MirrorPorts)`. Ports are passed per screen because they depend on UI objects: Android's brightness needs the `Window`, and CameraX binds to a `LifecycleOwner`.
* **Android port lifetime rule:** the shared ViewModel outlives the Activity (rotation). So Android port adapters must **not** hold an Activity strongly. Create them in the ViewModel factory, and `attach(activity/lifecycleOwner)` in `onCreate` / `detach()` in `onDestroy`. Weak references are also acceptable.
* Move to **Koin** (it has KMP support) when there are more than about 3 screens or feature modules. `SharedGraph` then becomes a Koin module, and the platform call sites stay the same.

## 6. `expect/actual` vs interface injection

| Use | When | Examples here |
|---|---|---|
| **Interface in commonMain, implemented by platform app code** (default) | Needs UI objects, lifecycle, platform SDK objects, or is implemented in Swift | `CameraController`, `BrightnessController`, `PhotoSaver`, `PermissionChecker`, `ProcessingPipeline` |
| **Interface in commonMain, implementation in androidMain / iosMain of sharedLogic** | Pure platform API with no UI, where the same implementation is useful everywhere | `KeyValueStore` → `SharedPreferencesKeyValueStore` / `UserDefaultsKeyValueStore` |
| **`expect/actual`** | Only for tiny, stateless platform primitives with no need for fakes (for example a monotonic clock or UUID) | None yet; avoid it for anything you'll want to fake in tests |

Interfaces are testable with fakes in `commonTest`, can be implemented from Swift, and avoid `expect class` limitations. We did not add a multiplatform-settings library (ADR-005): our needs are covered by about 40 lines of code, and field-per-key storage makes schema evolution safe (a `schemaVersion` key is reserved for migrations).

## 7. Frame-processing pipeline (future AI)

### Why pixel buffers must NOT cross into Kotlin per frame

* **Bandwidth and latency.** 1080p at 30–60 fps is about 250–500 MB/s of pixels. Copying a `CVPixelBuffer` or `HardwareBuffer` into a Kotlin `ByteArray` costs milliseconds per frame, and NFR-04 allows a motion-to-photon budget of ≤ 100 ms end to end.
* **Wrong processor.** The heavy work belongs on the GPU/ANE/NPU (Metal / Core ML / Vision; GL/Vulkan / LiteRT / MediaPipe / NNAPI). The zero-copy paths (`IOSurface`-backed `CVPixelBuffer` → `MTLTexture`; `HardwareBuffer` → GL/Vulkan texture) exist only in native APIs.
* **Memory management.** Kotlin/Native's GC plus ObjC interop, and JVM GC churn from per-frame allocations, cause jank. Native frame pools avoid both.
* **Privacy (NFR-11).** Keeping frames in native, bounded, in-memory pools makes it easy to prove that no frame is persisted.

### Split: control plane (Kotlin) vs data plane (native)

```
                 Kotlin (shared)                          Native (per platform)
 ┌──────────────────────────────────────┐   apply(PipelineConfig)   ┌──────────────────────────────────────────┐
 │ Feature state (enhance level, try-on │ ─────────────────────────▶│ ProcessingPipeline impl                  │
 │ selection, delay seconds …)          │                            │  camera → [CORRECTION] → [ANALYSIS] →    │
 │           │                          │                            │  [ENHANCEMENT] → [OVERLAY] → [TEMPORAL]  │
 │           ▼                          │                            │  → renderer (preview) / capture          │
 │ PipelineCoordinator                  │  onStats / onInsights      │  each stage = native FrameProcessor      │
 │  filter by availableProcessors()     │ ◀───────────────────────── │  (Metal/CoreML/Vision | GL/LiteRT/MP)    │
 │  order by ProcessingStage            │   (~1–2 Hz, metadata only) │  latest-frame-wins, drop when busy       │
 │  add deps (face landmarks)           │                            └──────────────────────────────────────────┘
 │  degrade DEGRADABLE on fps/thermal   │
 └──────────────────────────────────────┘
```

Kotlin types (`processing` package):

* `FrameProcessorDescriptor(id, stage, capabilities, estimatedCostMs)`: what a native processor can do. Capability flags: `RUNS_ON_GPU`, `RUNS_ON_NEURAL_ENGINE`, `HAS_CPU_FALLBACK`, `MODIFIES_PIXELS`, `PRODUCES_METADATA`, `REQUIRES_FACE_LANDMARKS`, `KEEPS_TEMPORAL_STATE`, `DEGRADABLE`, `AFFECTS_CAPTURE`.
* `FrameProcessor`: the common part of a native processor (`descriptor`, `updateParameters`, `release`). Each platform **extends** it with its per-frame method using native types, for example Swift `func process(_ input: CVPixelBuffer, into output: MTLTexture, context: FrameContext)` or an Android `fun process(input: HardwareBuffer, …)`. Kotlin never calls a per-frame method.
* `ProcessingPipeline` (platform port): `availableProcessors()`, `apply(PipelineConfig)`, `setListener(PipelineListener)`. Listener callbacks arrive on main at low frequency: `PipelineStats` (fps, latency, thermal) and `FrameInsights` (luminance, face detected). These are derived metadata, never pixels.
* `PipelineCoordinator` (shared policy, unit tested): builds the chain from requested `ProcessorConfig`s, drops processors the device lacks, orders by stage, auto-adds `face_landmarks` when a processor `REQUIRES_FACE_LANDMARKS`, and removes the most expensive `DEGRADABLE` processor when fps drops below the target or thermal state is `SERIOUS` or worse. The ViewModel activates it when the camera runs and deactivates it when the camera stops.
* `NoOpProcessingPipeline`: the MVP implementation. `ProcessorIds` holds shared ids so both platforms name processors identically.

How the roadmap features map onto the chain (no rewrites needed):

| Feature | Native processor(s) | Kotlin side |
|---|---|---|
| Low-light denoise, skin/light correction | `CORRECTION` / `ENHANCEMENT`, `DEGRADABLE`, optional `AFFECTS_CAPTURE` | `:feature:enhance` picks processors + params; suggests ring light from `FrameInsights.sceneLuminance` |
| Face landmarks / overlays | `ANALYSIS`, `PRODUCES_METADATA`; overlay drawn natively in normalized, mirror-aware coordinates | Overlay toggles, which guides to show |
| AR hair/makeup try-on | `OVERLAY`, `REQUIRES_FACE_LANDMARKS` | Catalog, selection, colour params |
| Delayed mirror (N-second buffer) | `TEMPORAL`, `KEEPS_TEMPORAL_STATE`: a bounded in-memory GPU ring buffer | Delay seconds, on/off, memory budget |
| Compare with previous / split view | none (the renderer composes two sources) | Photo history repository (future `:core:data` media store), split layout state |

Rules for native pipeline implementers: process on a dedicated queue with **latest-frame-wins** (never queue frames); the raw preview must never wait for AI (fall back to passthrough on a miss); apply `AFFECTS_CAPTURE` processors to the still capture too; use the camera's native format end to end.

## 8. Contracts for platform engineers

### 8.1 Shared classes each UI uses

| Shared type | Used for |
|---|---|
| `SharedGraph` | Create once per app. Android: `SharedGraph(SharedPreferencesKeyValueStore(context))`. iOS: `SharedGraph(keyValueStore: UserDefaultsKeyValueStore(defaults: .standard))` |
| `MirrorPorts(camera, brightness, photoSaver, permissions)` | Bundle your adapters, then call `graph.createMirrorViewModel(ports:)` |
| `MirrorViewModel` | `state: StateFlow<MirrorUiState>` (Android: `collectAsStateWithLifecycle()`), `observeState(onEach:)` + `currentState` (iOS), `onIntent(intent)`, `close()` |
| `MirrorUiState` | Everything to render. `screen` (LOADING / PERMISSION_ONBOARDING / PERMISSION_DENIED / PERMISSION_BLOCKED / OVERHEATED / MIRROR), `cameraStatus` + `cameraError`, `controlsVisible`, `showTapHint`, `zoomRatio`, `zoomPresets`, `capabilities`, `exposureEv`, `showExposureControl`, `ringLight: RingLightAppearance` (color, opacity, `edgeWidthFraction` of the short side), `previewMirrored`, `isFrozen`, `frozenImageScale`, `captureStatus`, `canCapture`, `lastSavedPhotoUri`, `message: UserMessage?`, `settings` (for the settings sheet) |
| `MirrorIntent` | Lifecycle: `ScreenStarted`, `ScreenStopped`. System: `ThermalStateChanged(state)`, `SetScreenReaderActive(active)`. Permission and errors: `RequestCameraPermission`, `OpenAppSettings`, `RetryCamera`. Controls: `ScreenTapped` (single tap), `ToggleZoom` (double tap), `SetZoom` (slider or preset), `PinchZoom(scaleFactor)` (incremental per gesture event), `SetExposure`, `ResetExposure`, `ToggleRingLight`, `SetRingLightIntensity`, `SetLightWarmth`, `ToggleFreeze`, `CapturePhoto`, `ToggleMirrorMode` / `SetMirrorMode`, `DismissMessage`. Settings sheet: `SetSettingsOpen`, `SetAutoMaxBrightness`, `ResetSettingsToDefaults` |
| `LightWarmth`, `LightIntensityPreset`, `MirrorMode`, `UserMessage` | Preset buttons and localized strings (the UI owns all copy and localization) |
| `PreviewPorts.create()` | Compose `@Preview`s, SwiftUI previews, and the simulator (no camera) |

UI-owned (not shared): layout per orientation and size class, animations, gesture recognition (tap vs pinch vs double tap), pan of the frozen image, transient "2.5x" / "Mirror" labels, haptics, and the saved flash. When the frozen image is showing, zoom intents scale `frozenImageScale`, not the camera.

### 8.2 Interfaces each platform must implement

All methods are non-suspending and must return quickly. Callbacks may fire on any thread, and each `onResult` must be called **exactly once**.

**`CameraController`** (Android: CameraX `Preview` + `ImageCapture` bound to the Activity lifecycle, rendering into `PreviewView`. iOS: `AVCaptureSession` + `AVCapturePhotoOutput`, rendering into `AVCaptureVideoPreviewLayer`, using a session queue)

| Method | Contract |
|---|---|
| `setListener(listener)` | Store it (the ViewModel sets it on init and clears it on close). **Kotlin tip:** don't name a backing property `listener` with a public setter, because it clashes on the JVM with `setListener` |
| `start(config)` | Open `config.lens` (FRONT). Then call `onCameraStarted(capabilities)` with the zoom range, EV range and EV step, or `onCameraError(error)` (`NO_FRONT_CAMERA`, `PERMISSION_DENIED`, `IN_USE_BY_ANOTHER_APP`, `DISCONNECTED`, `UNKNOWN`). After an interruption ends (a phone call, or the camera being evicted), call `onCameraStarted` again. |
| `stop()` | Release the camera within 500 ms (MVP-14). `onCameraStopped()` is optional. |
| `setZoomRatio(ratio)` | Already clamped to your reported range |
| `setExposureCompensation(ev)` | EV value already snapped to your step (CameraX: `ev / step` gives the index. iOS: `setExposureTargetBias`) |
| `setPreviewMirrored(mirrored)` | Mirror the preview (`PreviewView` scaleX / `isVideoMirrored`) |
| `setPreviewFrozen(frozen)` | Keep the last frame on screen, and pause the session if possible (NFR-05) |
| `setMaxFrameRate(fps)` | 60 normally, 30 under thermal pressure |
| `capturePhoto(request, onResult)` | Capture, or use the frozen frame if `request.fromFrozenFrame`. Flip according to `request.mirrored`. JPEG ≥ 90, no location EXIF. No ring-light border. Return `CameraCaptureOutcome.Success(CapturedPhoto(localUri, w, h, mirrored))`, where `localUri` is a platform-owned temp reference, or `Failure(error)` |

**`BrightnessController`**: `applyBrightness(level)` (window `screenBrightness`, or `UIScreen.main.brightness`; remember the user's level first), `restoreBrightness()`, `setKeepScreenOn(enabled)` (`FLAG_KEEP_SCREEN_ON`, or `isIdleTimerDisabled`). Never change the system setting.

**`PhotoSaver`**: `save(photo, onResult)` writes to MediaStore (API 29+ needs no permission; ≤ 28 needs `WRITE_EXTERNAL_STORAGE`) or to Photos with add-only authorization. Delete the temp file afterwards (MVP-08.AC7). Return `SaveOutcome.Saved(uri)` or `Failed(PERMISSION_DENIED | STORAGE_FULL | UNKNOWN)`.

**`PermissionChecker`**: `cameraPermissionStatus()` returns `NOT_DETERMINED` / `GRANTED` / `DENIED` (can ask again) / `BLOCKED` (Settings only: Android "don't ask again", iOS `.denied` / `.restricted`). `requestCameraPermission(onResult)` shows the system dialog (Android: `ActivityResultLauncher` registered by the attached Activity). `openAppSettings()` opens the app's system settings page.

**`ProcessingPipeline`** (optional in v1): pass `NoOpProcessingPipeline()` for now.

Also forward to the ViewModel: thermal changes (`PowerManager.addThermalStatusListener` / `ProcessInfo.thermalStateDidChangeNotification`) as `ThermalStateChanged`, and screen-reader state (`AccessibilityManager` touch exploration / `UIAccessibility.isVoiceOverRunning`) as `SetScreenReaderActive`.

**Already provided by the shared code (do not implement):** `KeyValueStore` (`SharedPreferencesKeyValueStore`, `UserDefaultsKeyValueStore`) and `SettingsRepository`.

## 9. Testing strategy

| Level | Where | What |
|---|---|---|
| Reducer unit tests | `commonTest` (runs on the JVM and the iOS simulator) | Pure transitions: clamping, snapping, double tap, freeze scale, thermal policy, reset, message mapping |
| ViewModel tests | `commonTest` with fakes (`FakeCameraController`, `FakeBrightness`, `FakePhotoSaver`, `FakePermissions`, `FakeProcessingPipeline`) plus `kotlinx-coroutines-test` virtual time | Permission flows, camera start/stop/retry, port commands from state diffs, auto-hide timer, tap hint, debounced persistence, capture success/failure, thermal stop/restart, `observeState` |
| Repository tests | `commonTest` (`InMemoryKeyValueStore`), `iosTest` (real `NSUserDefaults`) | Defaults, round trip, corrupt values, sanitizing |
| Pipeline policy | `commonTest` | Ordering, dependency injection, filtering, degradation |
| Platform adapters | Android: instrumented tests / Robolectric for adapters. iOS: XCTest | CameraX/AVFoundation adapters behind the port contracts; brightness restore |
| UI | Compose UI tests, XCUITest, screenshot tests (phone/tablet × portrait/landscape) | Render `MirrorUiState` fixtures, using `PreviewPorts` |
| Performance | Macrobenchmark + baseline profiles; Instruments | NFR-01..07 |

Target: ≥ 80% line coverage for `sharedLogic` (NFR-14). Commands:

```
./gradlew :sharedLogic:allTests                     # JVM host tests + iOS simulator tests
./gradlew :sharedLogic:testAndroidHostTest          # JVM only (fast loop)
./gradlew :sharedLogic:iosSimulatorArm64Test
./gradlew :sharedLogic:linkDebugFrameworkIosSimulatorArm64
./gradlew :androidApp:assembleDebug
```

The Gradle daemon JVM is pinned to JDK 21 (`gradle/gradle-daemon-jvm.properties`). To launch the wrapper, set `JAVA_HOME` to a JDK 17+ (for example Android Studio's JBR).

## 10. Architecture decision records

| ADR | Decision | Rationale / consequences |
|---|---|---|
| **001** | Remove the Compose Multiplatform `:sharedUI` template module; Android uses Jetpack Compose (AndroidX BOM) and iOS uses SwiftUI | Hard product requirement: native UI per platform. This drops the CMP runtime from the iOS binary (NFR-09 size) and removes the `org.jetbrains.compose` plugin. |
| **002** | A single `:sharedLogic` module for now, organized by clean-architecture packages; split into `:core:*` / `:feature:*` + an iOS umbrella later | Avoids early multi-module/framework overhead; the package boundaries already match the future modules. |
| **003** | Hand-written Swift flow bridge (`observeState` → `FlowSubscription`) and callback-style ports | Zero compiler plugins, so Kotlin can be upgraded freely. Adopt SKIE once it supports the current Kotlin version (non-breaking). |
| **004** | Manual DI (`SharedGraph`); migrate to Koin once there are more than ~3 screens | Compile-time safety, no startup cost, simple from Swift. |
| **005** | Own `KeyValueStore` + field-per-key `KeyValueSettingsRepository`; no DataStore / multiplatform-settings | Tiny needs, no dependency, safe schema evolution, sync reads warmed off the main thread. Revisit if we need encrypted or structured storage. |
| **006** | MVI with a pure reducer plus side effects derived from state diffs | Deterministic and testable, and idempotent hardware commands (e.g. brightness is restored exactly once). |
| **007** | Frame pipeline = Kotlin control plane plus a native data plane; no per-frame Kotlin calls | Latency, bandwidth, GPU/NPU zero-copy paths, privacy. See section 7. |
| **008** | Minimum OS: Android API 26, iOS 16.0 | Matches ROADMAP NFR-13 (the template had 24 / 18.2). |
| **009** | Ports are non-suspending and report asynchronous results via callbacks/listeners | Swift can implement them naturally, with no Kotlin coroutine machinery on the platform side. The shared use cases wrap them with `suspendCancellableCoroutine`. |
