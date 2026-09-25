# Smart Mirror: Product Roadmap

| | |
|---|---|
| Owner | Product Owner |
| Status | v1.0 scope agreed. This document refines it. |
| Last updated | 2026-09-23 |
| Platforms | Android (Jetpack Compose) and iOS (SwiftUI), with shared KMP core (`sharedLogic`) |

Stable IDs: user stories are `MVP-xx`, non-functional requirements are `NFR-xx`, and post-MVP items are `R11-xx`, `R12-xx`, `R20-xx` and so on. Acceptance criteria are `MVP-xx.ACn`. Once an ID is assigned, it is never renumbered or reused. A dropped item is marked **Withdrawn**.

---

## 1. Product Vision

> **The best mirror you always carry: instant, flattering, honest, and completely private.**

Smart Mirror turns the front camera into a real mirror. It opens straight to your reflection in under a second, with no chrome and good lighting. It adds what a glass mirror cannot do: zoom in, freeze the frame, see yourself as others see you, and (later) get help from on-device AI. No account, no network, and no frames ever leave the device.

### Target users / personas

| Persona | Who | Core need | Typical context |
|---|---|---|---|
| **P1 "On-the-go Olivia"** | Commuter / student, 18-35 | Quick check of face, teeth and hair before a meeting or date | Bus, office restroom, car. Often poor lighting. One hand. |
| **P2 "Makeup Maya"** | Does daily makeup, beauty enthusiast | Close-up detail (eyeliner, brows, lashes), good even light, warm/cool light to match the venue | Home, vanity desk, tablet on a stand |
| **P3 "Groomer Greg"** | Shaves or trims facial hair, styles hair | Zoom on jawline and sideburns, check symmetry, see the back of the head | Bathroom, phone propped up |
| **P4 "Accessible Ada"** | Low vision or limited mobility | Large magnification, high brightness, big touch targets, screen reader support | Home. Tablet or phone. |
| **P5 "Contact-lens Chris"** | Wears contacts, removes splinters or eyelashes | Extreme zoom plus freeze frame to inspect the eye | Home. Needs steady light. |

### Key use cases
1. **Quick check** (under 5 s session): open, look, close. The dominant use case.
2. **Detailed grooming** (1-10 min session): makeup, shaving, eyebrows, with zoom, light and freeze.
3. **Low-light check**: the ring light compensates for a dark room or car at night.
4. **"How do others see me?"**: toggle true view (non-mirrored).
5. **Inspect a hard-to-see spot**: freeze, then pinch-zoom into the frozen frame.
6. **Before/after snapshot**: capture a photo to the gallery.

### Differentiators
- **Speed**: cold start to live mirror in under 1 s on reference devices. Most camera apps take 2-3 s and open in photo mode.
- **Real mirror feel**: mirrored by default, zero UI clutter, full bleed, auto brightness.
- **Built-in ring light** with warmth presets, so you don't need a separate light app.
- **Freeze plus zoom on the frozen frame**. Inspecting without holding still is not something stock cameras do well.
- **Privacy by architecture**: no INTERNET permission in v1.x (Android) and no network entitlement use (iOS), so there is nothing to leak. This is also a marketing message.
- **AI-ready**: an on-device frame-processing pipeline designed from day one for enhancement, landmarks and AR try-on.

---

## 2. MVP (v1.0)

### 2.1 Definitions used in acceptance criteria
- **Reference devices (RD)**
  - Android low tier: Pixel 6a or Galaxy A54
  - Android high tier: Pixel 8 or Galaxy S23
  - iOS low tier: iPhone 12
  - iOS high tier: iPhone 15
  - Tablets: iPad (10th gen) and Galaxy Tab S8
- **Cold start**: the process is not running, and the timer runs from the launcher icon tap to the first camera frame rendered on screen.
- **Warm start**: the process is alive in the background, and the timer runs from bringing the app to the foreground to the first new frame.
- **Controls overlay**: the translucent control layer revealed by a tap.
- Unless a criterion names a platform, it applies to both platforms, phone and tablet, and portrait and landscape.

### 2.2 User stories

#### MVP-01: Instant full-screen mirror preview
*As a user, I want the app to open directly into a live mirror, so that I can check myself instantly.*
- **AC1**: With camera permission already granted, launching the app shows the live front-camera preview full-screen (edge to edge, including under system bars and cutouts), with no splash beyond the OS-mandated launch screen.
- **AC2**: The preview is horizontally mirrored by default: raising your right hand moves the on-screen hand on the right side.
- **AC3**: On first display, no controls are visible (no chrome). At most, a one-time hint "Tap for controls" appears for ≤3 s on first launch only.
- **AC4**: The preview fills the screen with center-crop (aspect fill). There is no letterboxing and no distortion (circles stay circular).
- **AC5**: Cold start meets NFR-01 and warm start meets NFR-02.
- **AC6**: The device does not auto-lock or dim while the preview is showing (keep-screen-on).

#### MVP-02: Tap to reveal controls, auto-hide
*As a user, I want controls only when I need them, so that the mirror stays uncluttered.*
- **AC1**: A single tap on the preview shows the controls overlay within 150 ms (animated fade).
- **AC2**: The controls auto-hide after 4 s without interaction. Any interaction with a control restarts the timer.
- **AC3**: Tapping the preview again while controls are visible hides them immediately.
- **AC4**: While TalkBack or VoiceOver is on, the controls do **not** auto-hide (see NFR-10).
- **AC5**: A tap is distinguished from pinch or drag: a pinch never toggles the controls.

#### MVP-03: Zoom (pinch and slider)
*As a user, I want to zoom in on details, so that I can see my eyes, brows or skin up close.*
- **AC1**: Pinch on the live preview zooms smoothly from 1.0x to at least 5.0x (digital). The zoom is centered between the fingers or at screen center, as the platform allows.
- **AC2**: The controls overlay has a zoom slider that is kept in sync with pinch (the two never disagree).
- **AC3**: Double-tap toggles between 1.0x and the last non-1.0x zoom (default 2.0x).
- **AC4**: The current zoom level shows as a transient label (for example "2.5x") while zooming, and disappears 1 s after zooming stops.
- **AC5**: Zoom never drops the preview below NFR-03 FPS.
- **AC6**: The zoom level persists (MVP-11).

#### MVP-04: Exposure / brightness compensation
*As a user, I want to brighten or darken my image, so that I look correctly exposed in any light.*
- **AC1**: The controls overlay has an exposure slider covering at least −2 EV to +2 EV (or the device's supported range, if narrower). 0 is the center and has a detent or snap.
- **AC2**: Changes are visible in the preview within 200 ms.
- **AC3**: A "reset" action returns exposure to 0.
- **AC4**: On devices without exposure compensation support, the control is hidden. It is never shown in a broken state.

#### MVP-05: Mirror light (ring light)
*As a user, I want the screen to act as a ring light, so that my face is evenly lit in dim places.*
- **AC1**: A toggle turns on a light border around the preview edges. The border stays inside safe areas, so the preview is never covered by cutouts.
- **AC2**: An intensity control (0-100%) changes the border width and/or opacity. At 100%, the border is at least 12% of the short screen edge.
- **AC3**: Warmth presets: **Daylight** (~5500K, neutral white), **Warm** (~3200K, amber), **Cool** (~7000K, bluish). The selected preset is visually indicated. An optional fine warmth slider is a stretch goal (R11-02).
- **AC4**: Switching light on/off, intensity or warmth takes effect within 100 ms and without preview stutter.
- **AC5**: In a dark room (<10 lux), turning the ring light on at 100% Daylight produces a visibly lit face on all RDs (QA to capture comparison screenshots).
- **AC6**: The light settings persist (MVP-11).

#### MVP-06: Auto-max screen brightness
*As a user, I want the screen at full brightness while mirroring, so that the preview and ring light are as bright as possible.*
- **AC1**: When the app enters the foreground with the preview active, window or screen brightness is set to maximum.
- **AC2**: On background, lock, or leaving the app, brightness is restored to the user's previous level or setting within 500 ms. It never stays stuck at max.
- **AC3**: This uses per-window brightness where available (Android `screenBrightness`) and does not change the system setting. On iOS, `UIScreen.brightness` is restored on resign-active.
- **AC4**: A setting "Auto max brightness" (default ON) lets the user disable this behavior.
- **AC5**: Under thermal throttling, NFR-06 rules take precedence.

#### MVP-07: Freeze frame
*As a user, I want to pause the mirror, so that I can inspect myself without holding still.*
- **AC1**: A freeze button in the controls overlay freezes the current frame within 100 ms. The frozen image is exactly what was on screen (same mirroring and exposure).
- **AC2**: A clear "Frozen" indicator is visible while frozen (it is not auto-hidden).
- **AC3**: On the frozen image, pinch zooms up to at least 8x and one-finger drag pans. Panning is bounded, so the user cannot pan off the image.
- **AC4**: Tapping "Resume" (or the freeze button again) returns to the live preview at the zoom level that was set before freezing.
- **AC5**: While frozen, the camera may be paused to save battery. Resuming shows a live frame within 300 ms.
- **AC6**: The frozen frame is kept in memory only. It is never written to disk unless the user captures it (MVP-08).
- **AC7**: Backgrounding the app while frozen discards the frozen frame. The app returns to live preview.

#### MVP-08: Capture photo to gallery
*As a user, I want to save a photo, so that I can keep a before/after or share it later.*
- **AC1**: A capture button in the controls overlay saves the current image. In live mode, it captures a new photo. In freeze mode, it saves the frozen frame.
- **AC2**: The saved photo matches the current mode: mirrored in mirror mode and non-mirrored in true view. It includes the current zoom crop and exposure. **The ring-light border is not included.**
- **AC3**: The first save requests gallery or photos write permission (iOS "Add Photos Only"; Android: none needed on API 29+ via MediaStore, `WRITE_EXTERNAL_STORAGE` on ≤28). Capture is optional: the app works fully without this permission.
- **AC4**: A successful save gives feedback within 1 s: a brief flash or animation, a haptic, and a "Saved" toast. The toast is announced by the screen reader.
- **AC5**: If permission is denied, a non-blocking message explains how to enable it and offers a link to Settings. No crash, and no repeated prompts in the same session.
- **AC6**: Photos save as JPEG (quality ≥ 90) with no location EXIF data.
- **AC7**: No photo is stored anywhere else (no app-private copies or caches remain after the save completes).

#### MVP-09: Mirror mode / true view toggle
*As a user, I want to see myself as others see me.*
- **AC1**: A toggle in the controls overlay switches between **Mirror** (flipped) and **True view** (unflipped).
- **AC2**: The switch is instant (≤100 ms), keeps zoom and exposure, and shows a transient label ("Mirror" / "True view").
- **AC3**: The mode applies to live preview, freeze, and capture (MVP-08.AC2).
- **AC4**: The mode persists (MVP-11).

#### MVP-10: Orientation and form factor support
*As a user on a phone or tablet, I want the mirror to work in any orientation.*
- **AC1**: Portrait and landscape (both directions) are supported on phones and tablets. The preview is upright and correctly mirrored after rotation.
- **AC2**: Rotation does not restart the camera visibly: no black frame >300 ms, and zoom, exposure, freeze and settings state are retained.
- **AC3**: The controls re-layout for each orientation. Tablet layouts use larger spacing and do not stretch controls edge to edge.
- **AC4**: Android split-screen, multi-window and foldables: the preview adapts to the window size without distortion. When the app loses the camera to another app, MVP-12.AC5 applies.
- **AC5**: iPad Split View and Slide Over are supported, or explicitly opted out via `UIRequiresFullScreen`. **Decision for v1.0: support full-screen only on iPad. Split View moves to R12-04.**

#### MVP-11: Persisted settings
*As a user, I want my preferences remembered.*
- **AC1**: These settings persist across app restarts and device reboots: ring light on/off, intensity, warmth preset, zoom level, mirror/true view mode, auto max brightness, and exposure. **Exposure resets to 0 each launch by default.** Persisting exposure is a toggle in R11.
- **AC2**: Settings are stored locally only (shared `sharedLogic` settings repository: DataStore on Android, UserDefaults on iOS, or a multiplatform-settings equivalent).
- **AC3**: A "Reset to defaults" action restores defaults: light off, 50% intensity, Daylight, 1.0x zoom, Mirror mode, auto max brightness ON.
- **AC4**: A corrupted or missing settings store falls back to defaults without crashing.
- **AC5**: Reinstalling the app starts from defaults. Settings are not required to survive a reinstall, and auto-backup is allowed.

#### MVP-12: Camera permission onboarding and error states
*As a new user, I want to understand why the camera is needed and recover if I said no.*
- **AC1**: On first launch, a single friendly screen explains "Smart Mirror uses your front camera. Nothing is recorded or sent anywhere." It has one primary button, which triggers the OS permission prompt. Nothing else is required.
- **AC2**: If permission is granted, the preview appears immediately without an app restart.
- **AC3**: If denied, a denied-state screen explains the situation and offers **Open Settings**. On return from Settings with permission granted, the preview starts automatically. On Android, if "don't ask again" is set, only the Settings path is shown.
- **AC4**: No front camera, or camera hardware failure: a clear error screen appears with a retry. No crash.
- **AC5**: If the camera is in use by another app or is interrupted (for example a phone call, iOS multitasking camera restriction, or Android camera eviction), the app shows a "Camera unavailable" state and resumes automatically when the camera becomes available.
- **AC6**: If the permission is revoked while the app is backgrounded, the app shows the denied state on return and does not crash.
- **AC7**: Subsequent launches with permission already granted skip onboarding entirely (MVP-01).

#### MVP-13: Privacy: fully on-device, no account
*As a privacy-conscious user, I want certainty that my face never leaves my phone.*
- **AC1**: The app has no login, account or sign-up anywhere.
- **AC2**: Android: the merged manifest does **not** declare `android.permission.INTERNET`. This is verified in CI by inspecting the manifest.
- **AC3**: iOS: no networking code paths and no third-party SDKs that phone home. The App Privacy "Data Not Collected" label is accurate.
- **AC4**: No analytics or crash SDK that transmits data in v1.0. Crash insight comes from the stores' built-in opt-in reporting (Play Vitals, Xcode Organizer) only.
- **AC5**: Camera frames are never persisted except via explicit capture (MVP-08). There are no frame caches on disk (verified by inspecting the app sandbox after a 5-min session).
- **AC6**: The Settings/About screen states the privacy promise in plain language.

#### MVP-14: Lifecycle correctness
*As a user, I want the camera to behave correctly when I leave or return.*
- **AC1**: On background or screen-off, the camera is released within 500 ms and the OS camera indicator (green dot) turns off.
- **AC2**: On return to the foreground, the preview resumes per NFR-02 with settings intact.
- **AC3**: No camera, memory or brightness leak after 50 background/foreground cycles (QA stress test): memory growth under 10 MB, and no crash or ANR.

#### MVP-15: Minimal settings / about screen
*As a user, I want a place for the few options and app info.*
- **AC1**: The controls overlay includes a settings entry that shows: auto max brightness, reset to defaults, privacy statement, app version, open-source licenses, and contact/support (a mailto link opens the mail app. The app itself makes no network request).
- **AC2**: Opening settings pauses the auto-hide timer. Closing settings returns to the mirror with state intact.

### 2.3 Non-functional requirements (v1.0)

| ID | Area | Requirement (measured on RDs, release build) |
|---|---|---|
| **NFR-01** | Cold start | Launcher tap to first preview frame: **P50 ≤ 800 ms, P90 ≤ 1200 ms** on high tier. **P90 ≤ 1500 ms** on low tier. The camera opens in parallel with UI inflation, and nothing blocks the main thread before the camera opens (for example no settings I/O). Android baseline profiles are required. |
| **NFR-02** | Warm start | Foreground to live frame: **P90 ≤ 400 ms**. |
| **NFR-03** | Preview FPS | **≥ 30 fps sustained** (target 60 fps where the sensor supports it) with ring light on and zoom at 5x. Jank: < 1% frames dropped over a 60 s session. |
| **NFR-04** | Latency | Motion-to-photon **≤ 100 ms** (target ≤ 80 ms), measured with a high-speed camera filming a moving object and the screen. No post-processing on the preview path in v1.0 beyond GPU transforms (mirror, crop, zoom). |
| **NFR-05** | Battery | A 10-minute continuous session at max brightness with ring light on consumes **≤ 5% battery** on high-tier phones (≤ 7% low tier). The camera is paused when frozen, backgrounded or screen-off. No wakelocks survive backgrounding. |
| **NFR-06** | Thermal | Under OS thermal status "severe"/"serious" (Android `PowerManager` thermal status ≥ SEVERE, iOS `thermalState` ≥ `.serious`): drop to 30 fps and reduce auto-brightness to 80%, with a subtle one-time notice. At "critical": pause the preview with a "Device is hot" screen. **No thermal shutdown in a 30-minute session at room temperature (22-25 °C).** |
| **NFR-07** | Memory | Peak RSS ≤ 250 MB (phone), ≤ 350 MB (tablet). No leak across MVP-14.AC3. |
| **NFR-08** | Stability | Crash-free sessions ≥ 99.8%, ANR rate < 0.2% (Play Vitals), and zero crashes in the QA regression suite. |
| **NFR-09** | App size | Download size ≤ 15 MB (Android AAB split) and ≤ 25 MB (iOS thinned). |
| **NFR-10** | Accessibility | All controls have accessibility labels (TalkBack / VoiceOver). Touch targets ≥ 48 dp / 44 pt. Text scales with system font size up to 200% without clipping. Controls contrast ratio ≥ 4.5:1 over the preview (use scrims). Auto-hide is disabled while a screen reader is active. Reduce Motion is respected (no zoom or flash animations). All actions are reachable without multi-finger gestures (slider alternative to pinch, button alternative to double-tap). |
| **NFR-11** | Privacy | No frame, image or derived data (including landmarks, histograms or embeddings in later releases) leaves the device. No network permission in v1.x. Frames are held in memory only for rendering and freeze. Photos save only on explicit user action. The Privacy Policy states this and it is enforced by MVP-13 checks. |
| **NFR-12** | Localization | All strings externalized in shared resources. v1.0 ships English. RTL layout verified (mirror semantics are unchanged in RTL). |
| **NFR-13** | OS support | Android 8.0 (API 26)+, iOS 16+. |
| **NFR-14** | Architecture | Camera state machine, settings, zoom/exposure math and light config live in `sharedLogic`, unit tested (≥ 80% line coverage for shared logic). Platform camera adapters (CameraX / AVFoundation) sit behind a shared interface, with a **frame-processor hook** (no-op in v1.0) reserved for AI features (section 3). |

### 2.4 v1.0 out of scope (explicit)
Video recording, filters and beauty effects, AI features, rear camera, cloud and sharing flows (the OS share sheet from the gallery is enough), widgets and shortcuts, and monetization/IAP.

### 2.5 v1.0 release criteria (definition of done)
- All MVP-01 to MVP-15 ACs pass on all RDs, both orientations.
- All NFRs are met or have a signed-off waiver with a fix version.
- The store listing and privacy labels are accurate, and screenshots are made for phone and tablet.

---

## 3. Post-MVP Releases

Guiding principle: **everything stays on-device.** AI uses on-device models (Android: MediaPipe / LiteRT with GPU/NNAPI delegate. iOS: Vision / Core ML / ARKit face tracking where available). Any model download (if models are too big to bundle) goes through Play Asset Delivery / Apple On-Demand Resources, **never a custom network call**. This keeps the no-INTERNET-permission promise where feasible. Any future network need requires a PO-approved privacy review and an opt-in.

### Architectural prerequisites the KMP core must support (plan now, build later)
1. **Frame pipeline with pluggable processors**: camera, then an optional processor chain, then the renderer. Processors run on the GPU or in a background thread with a frame-drop policy (the latest frame wins) so that AI never adds latency to the raw preview. Each processor can be turned off at runtime.
2. **Face analysis service interface** in `sharedLogic` (landmarks, face box, head pose) with platform implementations.
3. **Overlay layer** above the preview for landmarks, guides and AR, with coordinates in normalized preview space and mirror-aware transforms.
4. **Frame ring buffer** (in-memory, bounded) for delayed mirror and compare.
5. **Local media store** (app-private, encrypted at rest where the OS supports it) for the compare-with-previous history. The user controls it and can delete it.
6. **Feature flags / entitlements** module in shared code (local only) for tiers and premium gating.
7. **Performance governor**: shared policy that degrades AI quality or FPS based on thermal and battery state.

### v1.1: Polish and retention (MVP + ~6 weeks)
Theme: make the daily habit stickier and cover the top feedback.
- **R11-01 Quick-launch shortcuts**: Android app shortcuts / Quick Settings tile. iOS Home Screen quick actions, Lock Screen widget / Control Center control (iOS 18+) that opens straight to the mirror.
- **R11-02 Custom warmth slider** (2700K-7500K) plus a custom-color ring light.
- **R11-03 Grid and symmetry guides**: center line and thirds overlay for eyebrows and beard alignment.
- **R11-04 Volume-button actions**: volume up = freeze, volume down = capture (configurable).
- **R11-05 Persist exposure option** and per-orientation zoom memory.
- **R11-06 Localization**: ES, FR, DE, PT-BR, JA, KO, AR (RTL), HI.
- **R11-07 Privacy-preserving opt-in analytics** (on-device aggregation, opt-in only). **Requires privacy review. Default: deferred unless the product needs it.**

### v1.2: Smart mirror basics (MVP + ~3 months)
Theme: capabilities a glass mirror can't provide, without heavy AI.
- **R12-01 3-second delayed mirror**: shows what happened N seconds ago (N = 1-10 s, default 3 s). Turn around to see the back of your head. Uses the in-memory ring buffer, and memory is capped (for example downscaled frames ≤ 150 MB).
- **R12-02 Compare with previous**: side-by-side or slider between the live view and a saved snapshot (for example a haircut or makeup before/after). Snapshots go in the app-private store, and users can delete them.
- **R12-03 Split view**: live mirror plus true view side by side, or live plus frozen.
- **R12-04 iPad Split View / Slide Over and Android freeform** support.
- **R12-05 Home-screen widgets**: small launch widget and "last snapshot" widget.
- **R12-06 Low-light boost (non-AI)**: longer exposure / night-mode frame averaging where the camera API allows.

### v2.0: On-device AI mirror (MVP + ~6-9 months)
Theme: an intelligent, flattering but honest mirror. All inference is on-device, and every AI effect has a visible on/off indicator (honesty principle: the user always knows whether they're seeing a processed image).
- **R20-01 Real-time AI enhancement**: skin-tone-aware lighting correction and auto white balance for indoor or mixed light. Target: ≥ 30 fps on high tier, graceful fallback on low tier.
- **R20-02 Low-light denoise**: temporal/ML denoise for dark environments.
- **R20-03 Face landmark overlays**: brow/eye/lip landmarks, symmetry guides, and brow mapping for P2 and P3.
- **R20-04 Makeup try-on (AR)**: lipstick, blush, eyeshadow color try-on anchored to landmarks.
- **R20-05 Hair color try-on (AR)**: hair segmentation plus recoloring.
- **R20-06 Guided grooming**: beard line and brow shape templates overlaid on the face.
- **R20-07 Performance governor for AI**: auto-downgrade model resolution or frequency under thermal or battery pressure (extends NFR-06).

### v2.x+ (exploratory)
- **R2X-01 Hairstyle try-on** (generative, heavier models, may need NPU-class devices).
- **R2X-02 Skin journal**: private, local, time-lapse of saved snapshots (sensitive, so strict opt-in and local encryption).
- **R2X-03 Wearables**: Apple Watch / Wear OS remote shutter and freeze.
- **R2X-04 Multi-camera** (rear camera + remote mirror via a second device on the local network, which requires a privacy review).

### Monetization options (decision due before v1.2)
| Option | Description | Pros | Cons | PO view |
|---|---|---|---|---|
| **M1 Freemium + "Pro" one-time purchase** | Core mirror free forever. Pro unlocks delayed mirror, compare, split view, custom light colors, and widgets. | Fits privacy positioning, no ads SDK, simple | Lower LTV | **Recommended for v1.2** |
| **M2 Subscription "Pro AI"** | AI enhancement, AR try-on and guided grooming (v2.0). Monthly/annual. | Funds ongoing model work | Subscription fatigue | **Recommended for v2.0**, layered on M1 |
| M3 Ads | Banner or interstitial | Revenue from free users | Breaks the mirror feel. Ad SDKs need network and tracking, which contradicts the privacy promise | **Rejected** |
| M4 Brand partnerships | Sponsored makeup/hair color catalogs in try-on, with catalogs shipped via store asset delivery | High value for R20-04/05 | Needs a content pipeline | Evaluate in v2.x |

Store billing (Play Billing / StoreKit 2) needs network access through the OS store app, not the app's own network permission on iOS. Android Play Billing Library does require the INTERNET permission, so **adding IAP on Android triggers a privacy review**. We will communicate "no data collected" rather than "no network".

---

## 4. Success Metrics, Risks, Backlog

### 4.1 KPIs
Because v1.0 has no analytics, metrics come from store consoles (Play Console, App Store Connect: installs, retention cohorts where available, vitals, ratings) plus QA benchmarks. Richer metrics depend on R11-07.

| KPI | Target (90 days post-launch) | Source |
|---|---|---|
| Cold start to preview (P90) | ≤ 1.2 s high tier | QA benchmark, Android vitals startup |
| Crash-free sessions | ≥ 99.8% | Play Vitals / Xcode Organizer |
| ANR rate | < 0.2% | Play Vitals |
| Store rating | ≥ 4.5★ | Stores |
| Day-1 / Day-7 / Day-30 retention | ≥ 40% / 20% / 10% | Store console cohorts |
| Share of reviews mentioning "fast", "light" or "privacy" positively | ≥ 25% of text reviews | Review mining (manual) |
| Uninstall rate within 24 h | < 15% | Play Console |
| Permission grant rate on onboarding | ≥ 90% | Inferred from store data / R11-07 |
| (v1.2+) Pro conversion | ≥ 3% of MAU | Store billing |
| (v2.0) AI feature engagement | ≥ 30% of weekly users enable an AI feature | Opt-in local analytics |

### 4.2 Risks and mitigations
| ID | Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|---|
| RK-01 | Cold start > 1 s on low-tier Android (CameraX init cost) | High | Med | Start camera open first. Baseline profiles. Defer non-critical init. Measure in CI with Macrobenchmark. |
| RK-02 | Thermal throttling from max brightness plus camera plus ring light | High | High | NFR-06 governor. Pause camera when frozen. Test 30-min soak on every RD. |
| RK-03 | Device fragmentation (front camera FOV, unsupported exposure/zoom ranges, OEM quirks) | Med | High | Capability detection in the adapter. Hide unsupported controls (MVP-04.AC4). Device lab with ≥ 10 Android models. |
| RK-04 | Brightness not restored (user annoyance, bad reviews) | Med | Med | Per-window brightness on Android. Explicit restore on every lifecycle exit. MVP-06.AC2 stress test. |
| RK-05 | Mirror/true-view inconsistency between preview, freeze and saved photo | Med | Med | A single shared transform model in `sharedLogic` used by all three paths. Golden-image tests. |
| RK-06 | Store review rejects "privacy" claims or photo permission usage | Med | Low | Accurate privacy labels. Minimal permissions. Clear purpose strings. |
| RK-07 | AI features (v2.0) too heavy for low-tier devices and add latency | High | Med | Frame-processor hook designed in v1.0 (NFR-14). Async processors with latest-frame policy. Device-tier gating. |
| RK-08 | AI "beautification" perceived as dishonest or harmful to body image | Med | Med | Honesty principle: AI off by default, visible indicator, one-tap compare to raw. Focus on lighting correction over reshaping. **No face-reshaping features.** |
| RK-09 | Commoditization: stock camera or OS "mirror" apps | Med | Med | Differentiate on speed, light, freeze and privacy now, AI later. Quick-launch surfaces (R11-01). |
| RK-10 | Monetization (Android IAP) forces the INTERNET permission, which weakens the privacy message | Low | High | Message "no data collected". Isolate billing. Privacy review before R-release with IAP. |
| RK-11 | KMP/iOS interop overhead slows the camera path | Med | Low | Keep per-frame work native. Shared code owns state and decisions, not pixel loops. |

### 4.3 Prioritized backlog
Priority: **P0** = release blocker, **P1** = should ship in the release, **P2** = nice to have, **P3** = exploratory.
Effort (per platform pair, including shared logic and QA): **XS** ≤ 1 d, **S** 2-3 d, **M** ~1 wk, **L** 2-3 wk, **XL** > 1 mo.

| ID | Feature | Release | Priority | Effort |
|---|---|---|---|---|
| MVP-01 | Instant full-screen mirrored preview | v1.0 | P0 | L |
| MVP-12 | Camera permission onboarding and error states | v1.0 | P0 | M |
| MVP-14 | Lifecycle correctness (release/resume camera) | v1.0 | P0 | M |
| MVP-13 | Privacy: on-device, no account, no INTERNET | v1.0 | P0 | S |
| MVP-02 | Tap to reveal controls, auto-hide | v1.0 | P0 | S |
| MVP-10 | Orientation and phone/tablet support | v1.0 | P0 | M |
| MVP-05 | Mirror light (intensity + warmth presets) | v1.0 | P0 | M |
| MVP-06 | Auto-max brightness with restore | v1.0 | P0 | S |
| MVP-03 | Zoom (pinch, slider, double-tap) | v1.0 | P0 | M |
| MVP-09 | Mirror / true view toggle | v1.0 | P0 | S |
| MVP-11 | Persisted settings | v1.0 | P0 | S |
| MVP-07 | Freeze frame + zoom/pan on frozen image | v1.0 | P1 | M |
| MVP-04 | Exposure compensation | v1.0 | P1 | S |
| MVP-08 | Capture photo to gallery | v1.0 | P1 | M |
| MVP-15 | Minimal settings / about screen | v1.0 | P1 | S |
| NFR-14 | Frame-processor hook + shared camera state machine | v1.0 | P0 | M |
| R11-01 | Quick-launch shortcuts / QS tile / Control Center | v1.1 | P1 | M |
| R11-04 | Volume-button freeze/capture | v1.1 | P1 | S |
| R11-02 | Custom warmth slider + custom ring color | v1.1 | P2 | S |
| R11-03 | Grid / symmetry guides | v1.1 | P2 | S |
| R11-05 | Persist exposure + per-orientation zoom | v1.1 | P2 | XS |
| R11-06 | Localization (8 languages + RTL) | v1.1 | P1 | M |
| R11-07 | Opt-in privacy-preserving analytics | v1.1 | P3 | M |
| R12-01 | 3-second delayed mirror (configurable) | v1.2 | P1 | L |
| R12-02 | Compare with previous (side-by-side/slider) | v1.2 | P1 | L |
| R12-03 | Split view (mirror + true / live + frozen) | v1.2 | P2 | M |
| R12-04 | iPad Split View / Android freeform | v1.2 | P2 | M |
| R12-05 | Home-screen widgets | v1.2 | P2 | M |
| R12-06 | Non-AI low-light boost | v1.2 | P2 | M |
| M1 | Pro one-time purchase (entitlements, paywall) | v1.2 | P1 | L |
| R20-07 | AI performance governor | v2.0 | P0 | M |
| R20-01 | Real-time AI lighting/skin-tone correction | v2.0 | P1 | XL |
| R20-02 | Low-light AI denoise | v2.0 | P1 | L |
| R20-03 | Face landmark overlays / symmetry | v2.0 | P1 | L |
| R20-04 | AR makeup try-on | v2.0 | P1 | XL |
| R20-05 | AR hair color try-on | v2.0 | P2 | XL |
| R20-06 | Guided grooming templates | v2.0 | P2 | L |
| M2 | "Pro AI" subscription | v2.0 | P1 | M |
| R2X-01 | Generative hairstyle try-on | v2.x | P3 | XL |
| R2X-02 | Private skin journal (encrypted, local) | v2.x | P3 | L |
| R2X-03 | Watch remote shutter/freeze | v2.x | P3 | M |
| R2X-04 | Second-device remote mirror (LAN) | v2.x | P3 | L |

### 4.4 Open decisions (owner: PO, with Architect)
1. **Ring light rendering**: overlay border (shrinks the visible preview) vs. inset-scaled preview. Default: overlay border within safe areas. Confirm with UX on tablets.
2. **Exposure persistence default** (currently resets each launch, MVP-11.AC1).
3. **iPad multitasking in v1.0** (currently full-screen only, MVP-10.AC5).
4. **Model delivery mechanism for v2.0** (bundled vs. store asset delivery), which must not require the app's own network calls.
