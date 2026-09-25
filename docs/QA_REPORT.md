# Smart Mirror v1.0: QA Report

| | |
|---|---|
| Owner | QA |
| Build under test | `main` working tree, 2026-09-25 (debug builds; release APK built for the size check) |
| Test plan | [TEST_PLAN.md](./TEST_PLAN.md) (traceability matrix, RD manual cases) |
| Evidence | `docs/qa-screenshots/`, test sources listed in section 8 |

## 1. Summary and recommendation

**Recommendation: NO-GO for the v1.0 store release. GO for an internal / closed beta on reference devices once QA-BUG-01 and QA-BUG-04 are fixed.**

* All automated suites are green: 0 unexpected failures. Six QA tests are deliberately skipped because they reproduce open bugs.
* No crashes or ANRs were seen in any suite or in exploratory testing, including 50 background/foreground cycles.
* The shared state machine is in good shape. A 5,000-step random intent fuzz keeps all invariants: zoom, exposure, freeze and brightness never leave their allowed ranges.
* **2 High, 8 Medium and 4 Low issues are open (section 4).** The Highs are:
  * Android: an "Only this time" permission lands users on a Settings-only dead end.
  * iOS: the privacy manifest is missing, so App Store submission will be rejected.
* ROADMAP release criteria (section 2.5) are **not met yet**. Every performance, thermal, battery and latency NFR, and every camera-optics AC, still needs reference-device runs (section 6). None of them could be measured on the emulator or simulator.

## 2. Environment

| Item | Value |
|---|---|
| Host | macOS (Darwin 25.4), 16 GB RAM, ~5 GB free disk at start |
| JDK / Gradle | Android Studio JBR 21, Gradle 9.5.1, AGP 9.1.1, Kotlin 2.4.20 |
| Android | AVD `Medium_Phone`, API 37 arm64, 1080x2400 @420 dpi, emulated front camera. Tablet simulated with `wm size 1600x2560` / `wm density 240` |
| iOS | Xcode 26.6. iPhone 17 Pro (iOS 26.4) and iPad Pro 11-inch M5 (iOS 26.4). Simulated camera (`-SMCamera simulated`) |
| Constraint | One emulator/simulator at a time, always shut down after use. No new AVDs or system images. iOS 16 runtimes are not installed, so the minimum OS (NFR-13) was not run |

**Environment incident.** The first connected run happened while Gradle/Kotlin daemons and Android Studio were resident. With 5.7 GB of host swap in use, the 2 GB AVD thrashed: guest load average was about 24, Activity display took 38 s, and ART verification ran at around 50 bytecodes/s. The CameraX watchdog then fired and 3 E2E tests failed on camera timeouts. They pass consistently after stopping the daemons and cold-booting the same AVD with `-memory 3072` (a runtime flag; the AVD is unchanged). The run did expose QA-BUG-12.

## 3. Test execution summary

| Suite | Command / runner | Baseline (before QA) | Final (with QA tests) |
|---|---|---|---|
| Shared tests, JVM (`testAndroidHostTest`) | `./gradlew :sharedLogic:allTests` | 43 / 43 pass | **83 run: 79 pass, 0 fail, 4 skipped (known bugs)** |
| Shared tests, iOS simulator (`iosSimulatorArm64Test`) | same | 45 / 45 pass | **85 run: 81 pass, 0 fail, 4 skipped** |
| Android JVM unit (`testDebugUnitTest`) | `./gradlew :androidApp:testDebugUnitTest` | 20 / 20 pass | 20 / 20 pass |
| Android build (`assembleDebug`, `assembleRelease`) | Gradle | OK | OK. Release APK 2.2 MB |
| Android instrumented (`connectedDebugAndroidTest`) | Gradle, then `am instrument` on the same APKs | 39 run, 36 pass, **3 fail** (E2E camera timeouts caused by the environment; see section 2). Reruns: 37/39, then 39/39 | **59 run: 57 pass, 0 fail, 2 skipped (known bugs)** on a fresh emulator |
| iOS XCTest `iosAppTests` (iPhone 17 Pro) | `xcodebuild test -scheme iosApp` | 30 / 30 pass | 30 / 30 pass |
| iOS XCUITest `iosAppUITests` (new target), iPhone 17 Pro | `xcodebuild test -scheme iosApp` | n/a | **13 / 13 pass** |
| iOS XCTest + XCUITest, iPad Pro 11-inch | same | n/a | **43 / 43 pass** |

New tests added by QA: 40 shared (4 skipped), 20 Android instrumented (2 skipped), 13 iOS UI. That is 73 in total, 6 of them skipped as bug reproducers.

Skipped tests, all of which fail when enabled (verified before annotating):

| Test | Bug |
|---|---|
| `MirrorReducerEdgeCaseTest.doubleTapReturnsToLastNonDefaultZoomSetBySliderPresetOrPinch` | QA-BUG-03 |
| `MirrorReducerEdgeCaseTest.nonFiniteRingLightIntensityIsIgnored` | QA-BUG-09 |
| `MirrorViewModelQaTest.secondSaveWithinMessageWindowGetsFreshFeedback` | QA-BUG-06 |
| `MirrorViewModelQaTest.lateCameraStartedAfterStopDoesNotWedgeTheCamera` | QA-BUG-10 |
| `QaMirrorUiTest.installedPackageRequestsNoNetworkPermissionsAtAll` | QA-BUG-05 |
| `QaMirrorUiTest.supportAddressIsNotAPlaceholder` | QA-BUG-08 |

## 4. Bug list

Severity: **Critical** = data loss, crash, or privacy breach. **High** = blocks a core flow or a store release. **Medium** = AC violated with a workaround, or a likely store/UX problem. **Low** = edge case or hardening.

| ID | Sev | Platform | Title |
|---|---|---|---|
| QA-BUG-01 | High | Android | Expired "Only this time" (or auto-revoked) camera permission shows the Settings-only "blocked" screen |
| QA-BUG-04 | High | iOS | No `PrivacyInfo.xcprivacy`, although the app uses UserDefaults (a required-reason API) |
| QA-BUG-02 | Medium | Android | Frozen image is stretched (non-uniform scale) after rotating while frozen |
| QA-BUG-03 | Medium | Shared (both) | Double tap does not return to the last non-1x zoom when that zoom came from the slider, a preset or a pinch |
| QA-BUG-05 | Medium | Android | Merged manifest (debug and release) declares `ACCESS_NETWORK_STATE`; camera-video/media3 pulled in transitively |
| QA-BUG-06 | Medium | Shared (both) | A second photo saved within 2.5 s gets no fresh feedback (no re-announcement or haptic, iOS flash missing, toast cut short) |
| QA-BUG-07 | Medium | iOS (iPad) | On iPadOS 26 the app runs as a resizable window; the "full-screen only" decision (MVP-10.AC5) is not enforced |
| QA-BUG-08 | Medium | Both | Support contact is a placeholder and differs by platform |
| QA-BUG-11 | Medium | Android | Cold start opens the camera only after the first UI frame, sometimes builds the capture session twice, and has no app baseline profile (NFR-01 risk) |
| QA-BUG-13 | Medium (suspected) | iOS | Single tap waits for double-tap failure, so controls may appear later than 150 ms (MVP-02.AC1) |
| QA-BUG-14 | Medium (suspected) | iOS | Live photo is not cropped to the on-screen (aspect-fill) framing, unlike Android (MVP-08.AC2, RK-05) |
| QA-BUG-09 | Low | Shared | `SetRingLightIntensity(NaN)` stores NaN; the ring-light opacity and width become NaN |
| QA-BUG-10 | Low | Shared | A late `onCameraStarted` after `stop()` leaves the state RUNNING in the background, and the camera is never restarted |
| QA-BUG-12 | Low | Android | Open watchdog can raise a spurious "Camera problem" when CameraX LiveData coalesces the OPEN state under main-thread load |

### QA-BUG-01 (High, Android): expired one-time permission is treated as "don't ask again"
* **Steps:**
  1. Fresh install. Tap **Turn on camera** and choose **Only this time**. The mirror works.
  2. Press Home and wait about 1 minute; Android revokes the one-time grant. Dumpsys shows `granted=false, flags=[... ONE_TIME]`, without USER_FIXED.
  3. Open the app.
* **Expected:** Onboarding or denied state with a button that shows the system prompt again. Android allows re-prompting here, and MVP-12.AC3 limits the Settings-only path to "don't ask again".
* **Actual:** The "Camera access is turned off. Open Settings..." blocked screen, with no way to re-prompt. The same happens after `pm revoke` and after unused-app auto-revoke. Screenshot: `docs/qa-screenshots/BUG_android_onetime_blocked.png`.
* **Suspected cause:** `AndroidPermissionChecker.kt:21-27` (`resolvePermissionStatus`: `askedBefore && !shouldShowRationale -> BLOCKED`) with `:43-47`. The heuristic can't tell "never shown since revoke" from "permanently denied". Suggested approach: attempt the request and classify BLOCKED only if the dialog returns denied immediately without rationale.

### QA-BUG-04 (High, iOS): privacy manifest missing
* **Steps:** Search the project for `*.xcprivacy`. Result: none. The app reads and writes `UserDefaults` (`iOSApp.swift:12`, `UserDefaultsKeyValueStore.kt`).
* **Expected:** `PrivacyInfo.xcprivacy` declaring `NSPrivacyAccessedAPICategoryUserDefaults` (reason CA92.1), `NSPrivacyTracking = false`, and no collected data types. App Store Connect rejects uploads that lack required-reason declarations (ITMS-91053). RK-06 and MVP-13.AC3 depend on accurate privacy labels.
* **Actual:** No manifest. Review also whether the Kotlin/Native runtime uses other required-reason APIs, such as file timestamps or system boot time.

### QA-BUG-02 (Medium, Android): frozen frame distorted after rotation
* **Steps:** Landscape, reveal controls, tap **Freeze**, rotate to portrait while frozen.
* **Expected:** The frozen image keeps its aspect ratio (aspect fill, "circles stay circular", MVP-01.AC4) and freeze state is retained (MVP-10.AC2).
* **Actual:** The 2400x1080 frozen bitmap is stretched into the 1080x2400 viewport. Screenshots: `a_frozen_landscape.png` and `BUG_android_frozen_after_rotation.png`.
* **Suspected cause:** `MirrorScreen.kt:151-166`, `contentScale = ContentScale.FillBounds` at line 154 (should be `Crop`, with pan clamping recomputed for the new viewport). iOS uses `.aspectRatio(contentMode: .fill)` and is not affected.

### QA-BUG-03 (Medium, shared): double tap ignores zoom set by slider, preset or pinch
* **Steps:** Tap zoom preset **3x**, then **1x** (or pinch to 2.5x and back to 1x), then double-tap the preview.
* **Expected:** MVP-03.AC3: toggle to the last non-1.0x zoom, which is 3x (or 2.5x).
* **Actual:** 2x, the default. `lastNonDefaultZoom` is only updated inside the `ToggleZoom` branch.
* **Suspected cause:** `MirrorReducer.kt:51-55` (and `withZoom` at 140-145, which should record any non-1x zoom). Reproducer: `RE.doubleTapReturnsToLastNonDefaultZoomSetBySliderPresetOrPinch`.

### QA-BUG-05 (Medium, Android): network permission merged from CameraX dependencies
* **Steps:** `aapt2 dump permissions` on the debug or release APK, or `QaMirrorUiTest.installedPackageRequestsNoNetworkPermissionsAtAll`.
* **Expected:** NFR-11: "no network permission in v1.x". MVP-13.AC2 (no INTERNET) itself **passes**.
* **Actual:** `android.permission.ACCESS_NETWORK_STATE` is declared. It comes from `camera-view:1.6.2 -> camera-video:1.6.2 -> media3-container -> media3-common` (manifest-merger blame report). The dependency also pulls a video stack into a stills-only app.
* **Suspected fix location:** `androidApp/src/main/AndroidManifest.xml` (add `<uses-permission ... tools:node="remove">`), and exclude `camera-video` if unused. The CI manifest check required by MVP-13.AC2 does not exist yet; `QaMirrorUiTest.installedPackageDoesNotRequestInternet` now covers INTERNET.

### QA-BUG-06 (Medium, shared): repeated saves lose feedback
* **Steps:** Take a photo, then take another within 2.5 s.
* **Expected:** MVP-08.AC4: every save gets a "Saved" toast, haptic and screen-reader announcement, and iOS also flashes.
* **Actual:** `message` stays `PHOTO_SAVED`, so the state holds the same value:
  * the dismiss timer is not restarted, and the second toast disappears early;
  * iOS `onChange(of: state.message)` does not fire, so there is no flash, haptic or VoiceOver announcement;
  * Android `LaunchedEffect(state.message)` does not re-run (no haptic), and the live region does not re-announce the same text.
* **Suspected cause:** `MirrorViewModel.kt:170` (`new.message != old.message`), `MirrorReducer.kt:117-121`, `MirrorScreenView.swift:98-103`, `MirrorScreen.kt:378-380`. A fix needs a per-event token such as a message id or counter. Reproducer: `VQ.secondSaveWithinMessageWindowGetsFreshFeedback`.

### QA-BUG-07 (Medium, iOS iPad): windowed mode on iPadOS 26
* **Steps:** iPad Pro 11 (iOS 26.4), launch with `-SMOrientation landscape` (DEBUG), which requests a landscape geometry.
* **Expected:** MVP-10.AC5 decision for v1.0: full screen only on iPad (`UIRequiresFullScreen = true`).
* **Actual:** The system resized the app into a floating landscape window over the home screen, with the device still in portrait. Screenshot: `BUG_ios_ipad_windowed.png`. `UIRequiresFullScreen` is deprecated in iPadOS 26, where apps are expected to support arbitrary window sizes, so users can run Smart Mirror in window sizes that have never been tested. Layouts inside the window looked correct.
* **Action:** PO/architect decision needed: pull R12-04 (resizable window support and testing) into v1.0, or document the limitation. `Info.plist:13-14`, `RootView.swift:51-56`.

### QA-BUG-08 (Medium, both): support contact is a placeholder
* Android `strings.xml:86`: `support@example.com`, marked TODO. iOS `SettingsSheet.swift:11-12`: `support@smartmirror.app`, also TODO. MVP-15.AC1 requires a working contact. The platforms disagree and neither address is confirmed. Reproducer: `AQ.supportAddressIsNotAPlaceholder`.

### QA-BUG-11 (Medium, Android): cold-start camera ordering and missing baseline profile (NFR-01 risk)
* **Observed on the emulator (EX-A11, 3 cold starts):** CameraX `OPENING` begins 150-225 ms after the Activity is displayed, so the camera does not open in parallel with UI inflation. In 1 of 3 starts the session went OPEN, then CLOSING/CLOSED, then OPENING and OPEN again (about +270 ms). Earlier runs showed two back-to-back camera graphs on most launches.
* **Likely cause:**
  * `start()` is only called from `ON_START`, then `ScreenStarted`, then a permission check, and binding is posted to the main executor (`MainActivity.kt:57`, `CameraXCameraController.kt:159-165`).
  * The use cases are bound before `attachPreviewView` supplies the surface provider (`:137-144`, `:376-377`). CameraX then reconfigures the session when the surface arrives.
  * No app-specific baseline profile or Macrobenchmark module exists, although NFR-01 requires one. Only library profiles (`.dm`) are packaged.
* **Needs:** RD-10 measurement on reference devices before sign-off.

### QA-BUG-13 (Medium, suspected, iOS): single-tap latency
* `MirrorScreenView.swift:155-156` stacks `.onTapGesture(count: 2)` before `.onTapGesture`. SwiftUI then delays the single tap until the double-tap window expires (about 250-300 ms), above the 150 ms target in MVP-02.AC1. Android deliberately avoids this (`MirrorScreen.kt:389-407`). Confirm with RD-11 (high-speed video).

### QA-BUG-14 (Medium, suspected, iOS): capture framing differs from preview
* The preview is `AVCaptureVideoPreviewLayer` `.resizeAspectFill` of the 16:9 1080p video format. Live capture uses `photoOutput.maxPhotoDimensions` from the same format (`AVCameraController.swift:181`, `:216-218`), and the saved photo is not cropped to the screen's aspect. The photo therefore shows more field of view than the mirror did. Android crops the still to the viewfinder (`PhotoTransforms.toDisplayedImage`). This violates MVP-08.AC2 ("includes the current zoom crop") and RK-05 parity. Confirm with RD-21.

### QA-BUG-09 (Low, shared): NaN ring-light intensity
* `MirrorReducer.kt:66-71` uses `coerceIn`, which passes NaN through, so state and `RingLightAppearance` carry NaN. Persistence later sanitizes the value (`KeyValueSettingsRepository.sanitize`). The UIs never send NaN today. Reproducer: `RE.nonFiniteRingLightIntensityIsIgnored`.

### QA-BUG-10 (Low, shared): stale camera callback can wedge the camera
* **Steps (unit test):** ScreenStarted, ScreenStopped, then a late `onCameraStarted`, then ScreenStarted.
* **Actual:** State is RUNNING while in the background, and on return `startCameraIfNeeded` skips the start, which would leave a black preview.
* **Mitigation:** Both adapters currently drop stale callbacks (Android `startRequested`, iOS generation counter), so this cannot be reproduced end to end. The shared layer should still ignore `CameraStarted` when `!isForeground || cameraStatus == IDLE` (`MirrorReducer.kt:95-103`, `MirrorViewModel.kt:179-186`). Reproducer: `VQ.lateCameraStartedAfterStopDoesNotWedgeTheCamera`.

### QA-BUG-12 (Low, Android): spurious open-timeout error under load
* Seen on the starved emulator: logcat showed `Camera did not open within 8000ms`, and the UI switched to the "Camera problem" card while CameraX was still reopening.
* `onCameraState` relies on observing an OPEN value from LiveData. `postValue` coalesces OPEN, then CLOSING, then OPENING when the main thread is busy, so `reportedOpen` is never set and the watchdog fires (`CameraXCameraController.kt:122-127`, `:395-409`). The state self-heals when the next OPEN is observed.
* Worth hardening, because low-tier devices have busy main threads at cold start.

## 5. Acceptance-criteria coverage status

Status: **Pass** = verified by automated tests and/or exploratory runs on emulator/simulator. **Fail** = defect found. **RD** = needs a physical device (camera optics, timing, sensors). **Blocked** = could not run in this environment. Automation IDs are in TEST_PLAN.md section 2.

| Story | AC | Android | iOS | Notes |
|---|---|---|---|---|
| MVP-01 | AC1 full screen | Pass (RD-01 cutouts) | Pass (RD-01) | Edge to edge, immersive; iOS status bar hidden |
| | AC2 mirrored default | Pass | Pass | Saved-photo mirroring verified (AQE, EX-I4); physical check RD-02 |
| | AC3 no chrome, one-time hint | Pass | Pass | |
| | AC4 aspect fill, no distortion | **Fail** (QA-BUG-02, frozen after rotation) | Pass (sim) / RD | Live preview aspect fill OK |
| | AC5 start times | RD (risk QA-BUG-11) | RD | |
| | AC6 keep screen on | Pass | Pass (unit) | |
| MVP-02 | AC1 <= 150 ms | RD | RD (risk QA-BUG-13) | |
| | AC2 auto-hide 4 s | Pass | Pass | |
| | AC3 tap hides | Pass | Pass | |
| | AC4 no auto-hide with screen reader | Pass (logic) / RD-12 | Pass (logic) / RD-12 | TalkBack/VoiceOver not enabled on the test devices |
| | AC5 pinch never toggles | Pass | Pass (double tap does not toggle; pinch RD-13) | |
| MVP-03 | AC1 pinch to >= 5x | Pass (range) / RD smoothness | Pass (range) / RD | |
| | AC2 slider in sync | Pass | Pass | |
| | AC3 double tap | **Fail** (QA-BUG-03) | **Fail** (QA-BUG-03) | Basic 1x<->2x toggle passes on both |
| | AC4 transient label | Pass | Pass | |
| | AC5 FPS | RD | RD | |
| | AC6 persists | Pass | Pass | |
| MVP-04 | AC1 range + detent | Pass | Pass | iOS caps at +/-2 EV, 1/3 steps |
| | AC2 <= 200 ms | RD | RD | |
| | AC3 reset | Pass | Pass (unit) | |
| | AC4 hidden if unsupported | Pass | Pass | |
| MVP-05 | AC1 toggle, safe areas | Pass / RD-16 | Pass / RD-16 | |
| | AC2 intensity, >= 12 % | Pass (16 % at 100 %) | Pass | QA-BUG-09 hardening |
| | AC3 warmth presets | Pass | Pass | |
| | AC4 <= 100 ms | RD | RD | |
| | AC5 dark room | RD | RD | |
| | AC6 persists | Pass | Pass | |
| MVP-06 | AC1 max on foreground | Pass | Pass (unit) | |
| | AC2 restore <= 500 ms | Pass (window override cleared on onStop) | Pass (unit; resign-active restore) / RD-19 | Simulator has no brightness |
| | AC3 per-window / no system setting | Pass | Pass (unit) | |
| | AC4 user can disable | Pass (with ring light off) | Pass | Spec question S-1 |
| | AC5 thermal precedence | Pass (logic) | Pass (logic) | |
| MVP-07 | AC1 freeze = screen | Pass (saved frozen photo == frozen frame) | Pass (sim) / RD-20 | |
| | AC2 indicator not auto-hidden | Pass | Pass | |
| | AC3 8x + bounded pan | Pass | Pass (logic) / RD | |
| | AC4 resume at pre-freeze zoom | Pass | Pass | |
| | AC5 paused, live <= 300 ms | RD | RD | |
| | AC6 memory only | Pass | Pass | Sandbox scans clean |
| | AC7 background discards | Pass | Pass | |
| MVP-08 | AC1 live / frozen capture | Pass | Pass | Photo landed in MediaStore `Pictures/Smart Mirror` / Photos DCIM |
| | AC2 matches mode, zoom crop, no ring light | Pass (frozen and live aspect) / RD-21 | Pass (mode) / **risk QA-BUG-14** / RD-21 | |
| | AC3 permission optional | Pass (API 29+) / RD-22 (API <= 28) | Pass | |
| | AC4 feedback <= 1 s, announced | **Fail** for repeated saves (QA-BUG-06); single save Pass | **Fail** for repeated saves (QA-BUG-06); single save Pass | |
| | AC5 denied: message + Settings link | Pass | Pass | |
| | AC6 JPEG >= 90, no GPS | Pass (q95, no EXIF) | Pass (q0.92, no GPS) | |
| | AC7 no app copies | Pass | Pass | |
| MVP-09 | AC1 toggle | Pass | Pass | |
| | AC2 instant + label | Pass / RD timing | Pass / RD | |
| | AC3 live / freeze / capture | Pass | Pass | |
| | AC4 persists | Pass | Pass | |
| MVP-10 | AC1 upright after rotation | RD (emulated feed is synthetic) | RD | Camera not restarted |
| | AC2 no restart, state retained | **Fail** (frozen image distorted, QA-BUG-02); rest Pass | Pass | No CLOSING event on rotation |
| | AC3 re-layout, tablet | Pass (phone + simulated tablet) | Pass (iPhone + iPad) | |
| | AC4 split screen / foldable | RD / Blocked | n/a | No foldable AVD (no new AVDs allowed) |
| | AC5 iPad full screen only | n/a | **Fail / decision** (QA-BUG-07) | |
| MVP-11 | AC1 persist; exposure resets | Pass | Pass | |
| | AC2 local only | Pass | Pass | |
| | AC3 reset | Pass (no confirm dialog; iOS confirms) | Pass | |
| | AC4 corrupt store | Pass | Pass | |
| | AC5 reinstall -> defaults | Pass | Pass | Android `allowBackup=true` (allowed) |
| MVP-12 | AC1 onboarding | Pass | Pass | |
| | AC2 grant -> preview | Pass | Pass | |
| | AC3 denied / Settings / auto-resume | **Fail** (QA-BUG-01); deny, deny-twice and return from Settings Pass | Pass | |
| | AC4 no camera / failure + retry | Pass | Pass | |
| | AC5 in use / interrupted | Pass (UI + mapping) / RD-26 | Pass (mapping) / RD-26 | |
| | AC6 revoked in background | Pass (no crash; state = blocked, see QA-BUG-01) | Pass | |
| | AC7 skip onboarding later | Pass | Pass | |
| MVP-13 | AC1 no account | Pass | Pass | |
| | AC2 no INTERNET | Pass (**ACCESS_NETWORK_STATE present**, QA-BUG-05) | n/a | |
| | AC3 no networking code / SDKs | Pass | Pass, but **privacy manifest missing** (QA-BUG-04) | No URLSession/Network/HTTP anywhere |
| | AC4 no analytics SDK | Pass | Pass | |
| | AC5 no frame caches | Pass | Pass | 5-minute device session: RD |
| | AC6 privacy statement | Pass | Pass | |
| MVP-14 | AC1 release <= 500 ms | Pass (CLOSED 110-260 ms after onStop) / RD-27 indicator | RD-27 | |
| | AC2 resume with settings | Pass | Pass | NFR-02 timing RD |
| | AC3 50 cycles < 10 MB | Pass on emulator (PSS +5.0 MB, RSS +7.2 MB, no crash) / RD-28 | RD-28 (logic Pass) | |
| MVP-15 | AC1 settings contents | **Fail** (placeholder contact, QA-BUG-08); other items Pass | **Fail** (unconfirmed contact, QA-BUG-08); other items Pass | |
| | AC2 pauses auto-hide, state intact | Pass | Pass | |

### NFR status

| NFR | Status | Evidence |
|---|---|---|
| NFR-01 cold start | RD, **at risk** (QA-BUG-11) | Emulator: TotalTime 1.55-2.1 s (not an RD). No baseline profile |
| NFR-02 warm start | RD | Emulator warm `am start` 112-129 ms to Activity; frame timing not measured |
| NFR-03 FPS | RD | Policy requests 60 fps (30 when throttled) |
| NFR-04 latency | RD | |
| NFR-05 battery | Partial Pass (keep-screen-on cleared in background; camera released) / RD | |
| NFR-06 thermal | Logic Pass / RD soak | |
| NFR-07 memory | Emulator: PSS about 124-129 MB, RSS about 236-243 MB after 50 cycles (phone limit 250 MB RSS is close) / RD | |
| NFR-08 stability | Pass (0 crashes, 0 ANR in all suites and exploratory runs) | |
| NFR-09 size | Android Pass (release APK 2.2 MB) / iOS RD (thinning report) | |
| NFR-10 accessibility | Pass: labels and 44 pt / 48 dp touch targets on both platforms. Android also verified 200 % font reachability and the RTL render. Reduce Motion by code review only. iOS Dynamic Type and screen-reader passes need RD-12 | |
| NFR-11 privacy | Fail-minor (QA-BUG-05, QA-BUG-04) | |
| NFR-12 RTL | Android Pass (Compose RTL render) / iOS not run | |
| NFR-13 OS support | **Blocked** (no API 26-28 AVD and no iOS 16 runtime installed) | |
| NFR-14 architecture / coverage | Pass for structure and hooks. **Coverage not measured**: no Kover/JaCoCo configured. Recommend adding Kover to enforce the 80 % gate | |

## 6. Code-review findings (risk areas)

* **Camera lifecycle and leaks: good overall.**
  * Android binds use cases to a private lifecycle driven by start/stop.
  * `stop()` removes the state observer and calls `unbindAll`.
  * `release()` clears the analysis hook, callbacks and executors.
  * The adapters never hold the Activity strongly (weak reference plus attach/detach).
  * iOS removes NotificationCenter observers and drops stale results through a generation counter.
  * Risks: QA-BUG-10 (shared layer trusts adapters), QA-BUG-12 (watchdog), and a double `providerFuture.addListener` if start/stop/start happens quickly (`CameraXCameraController.kt:164`, harmless but rebinds twice).
* **Main-thread blocking.**
  * The shared ViewModel reads `settingsRepository.settings.value` in its constructor (`MirrorViewModel.kt:65`). If the background preload has not finished, the `by lazy` load runs synchronously on the main thread, which NFR-01 says must not happen before camera open.
  * `AndroidPermissionChecker` reads SharedPreferences on the main thread in `ScreenStarted`.
  * Android freeze copies and crops a full-screen bitmap on the main thread (`CameraXCameraController.kt:209-210`, about 10 MB). This is fine for the 100 ms budget but can drop a frame.
  * Photo encoding and saving run off the main thread on both platforms.
* **Swift/Kotlin subscription cancellation: good.**
  * `MirrorStore.deinit` cancels the `FlowSubscription` and closes the ViewModel.
  * `SystemEventsObserver` removes its observers.
  * The new `VQ.cancelledAndClosedSubscriptionsStopDelivering` covers idempotent cancel and close-after-cancel.
  * The store is app-lifetime (`@StateObject` in `App`), so deinit only runs in tests.
* **No-INTERNET / privacy.**
  * The Android manifest has no INTERNET permission (QA-BUG-05 aside).
  * iOS Info.plist has no ATS keys, only camera and add-only photo usage strings.
  * `grep` finds no URLSession, Network, HTTP, OkHttp or Ktor usage in any source set.
  * Photos: Android JPEGs carry no EXIF, iOS writes no GPS, and both delete temp data.
  * Missing iOS privacy manifest: QA-BUG-04.
* **Orientation and mirroring.**
  * Android's frozen and captured stills were verified pixel-wise against the displayed frozen frame in both modes (`AQE.frozenCapture_savedPhotoMatchesScreen_inMirrorAndTrueView`).
  * iOS saved photos flip correctly in mirror mode (EX-I4).
  * Open risks: QA-BUG-02 and QA-BUG-14. iOS landscape capture with `isVideoMirrored` plus `videoRotationAngle`, and Android live-capture rotation, need RD-21/RD-24 with a real scene; the emulator and simulator feeds are synthetic.

### Spec questions (not filed as bugs)
* **S-1:** With "Auto max brightness" OFF, turning the ring light on still forces 100 % brightness (`MirrorUiState.kt:98`, deliberate per `VT.brightnessBoostFollowsSettingsAndRingLight`). MVP-06.AC4 says the setting lets the user disable the behaviour. PO to confirm.
* **S-2:** Android "Reset to defaults" applies immediately; iOS asks for confirmation. Align the platforms.
* **S-3:** On Android a double tap briefly toggles the controls on and off, because the first tap is reported immediately to meet the 150 ms target. This causes a visible flash.

## 7. Items needing real-device testing

Camera optics and orientation (RD-02, RD-03, RD-21, RD-24), FPS/jank at 5x with ring light (RD-14), motion-to-photon (RD-29), cold/warm start with baseline profiles (RD-10), tap latency (RD-11), exposure latency (RD-15), ring-light timing and dark-room efficacy (RD-16..18), brightness on lock and calls (RD-19), freeze/resume latency (RD-20), API 26-28 storage permission (RD-22), split-screen and foldables (RD-25), camera interruptions such as calls, other apps and iPad multitasking (RD-26), camera privacy indicator (RD-27), 50-cycle memory with Instruments/Profiler (RD-28), 10-minute battery (RD-30), 30-minute thermal soak with induced serious/critical states (RD-31), TalkBack/VoiceOver full pass (RD-12), and iOS 16 minimum OS. Details: TEST_PLAN.md section 5.

## 8. What QA changed (tests and test infrastructure only)

No production code was modified and no test-only hooks were added to production code.

| File | Purpose |
|---|---|
| `sharedLogic/src/commonTest/kotlin/com/smart/mirror/app/presentation/mirror/MirrorReducerEdgeCaseTest.kt` | 22 reducer tests: zoom bounds, rapid sequences, freeze+capture, thermal, reset, 5,000-step random fuzz |
| `sharedLogic/src/commonTest/kotlin/com/smart/mirror/app/presentation/mirror/MirrorViewModelQaTest.kt` | 18 ViewModel tests: freeze+capture, 50 cycles, thermal side effects, persistence round trip, debounce, subscription cancel |
| `androidApp/src/androidTest/kotlin/com/smart/mirror/app/QaMirrorUiTest.kt` | 16 Compose UI tests: privacy manifest, MVP-15, accessibility, RTL, 200 % font, tablet targets, error/disabled states |
| `androidApp/src/androidTest/kotlin/com/smart/mirror/app/QaEndToEndTest.kt` | 4 E2E tests: saved photo == screen in mirror/true view, JPEG/EXIF/album/no leftovers, brightness + camera release on background, auto-max off |
| `iosApp/iosAppUITests/MirrorUITests.swift` | 13 XCUITests (new UI test target) |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | Adds the `iosAppUITests` UI-testing target (test target only) |
| `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme` | Shared `iosApp` scheme including `iosAppTests` and `iosAppUITests`, so both run from `xcodebuild test`. It replaces the auto-generated scheme with the same name |
| `docs/TEST_PLAN.md`, `docs/QA_REPORT.md`, `docs/qa-screenshots/` | Test plan, this report, defect evidence |

Emulator side effects: the app's data was cleared several times; `accelerometer_rotation=0` / `user_rotation=0` were left set; animation scales were restored to 1. All emulators and simulators are shut down.

## 9. Recommended next steps
1. Fix QA-BUG-01 and QA-BUG-04 (release blockers), then QA-BUG-02/03/05/06/08 and decide QA-BUG-07.
2. Add CI gates: a merged-manifest permission check (INTERNET and network permissions), Kover coverage >= 80 % for `sharedLogic`, and connected tests on a >= 3 GB AVD with Gradle daemons stopped (or on Firebase Test Lab / a device farm).
3. Add a baseline profile and a Macrobenchmark startup test (NFR-01), then run the RD matrix on all six reference devices.
