# Smart Mirror iOS app

SwiftUI UI and AVFoundation/Photos/UIKit adapters for the shared `SharedLogic` framework
(see `docs/ARCHITECTURE.md` §8). Build and test with the `iosApp` scheme; the
"Compile Kotlin Framework" phase needs `JAVA_HOME` set to a JDK 17+ (for example Android Studio's JBR).

## Known limitations

### iPad windowing (QA-BUG-07, MVP-10.AC5)

v1.0 decided "full screen only on iPad" and `Info.plist` still sets `UIRequiresFullScreen = true`.
That key is ignored on iPadOS 26, where every app can run in a freely resizable window. We can't
enforce full screen there, so instead the app is made robust at arbitrary window sizes:

* The control layout is chosen from the actual window size, not only size classes
  (`MirrorScreenView.layout(for:horizontalSizeClass:)`): bottom bar below 480 × 320 pt or for
  narrow portrait-like windows, side rail otherwise. Both the tool card and the action column
  scroll when a window is too short.
* The preview, frozen image, ring light and captured-photo crop all follow the window's own size.
* `AVCaptureSession.isMultitaskingCameraAccessEnabled` is turned on where supported. Where it
  isn't, the system interrupts the camera while other apps share the screen, and the app shows
  "Camera unavailable" and resumes automatically (MVP-12.AC5).

Full resizable-window QA (R12-04) is still pending: windowed sizes are covered by layout logic and
unit tests, not by a device test pass. iPadOS 16–25 honour `UIRequiresFullScreen`.

### Photo framing (QA-BUG-14)

Saved photos are center-cropped to the on-screen aspect-fill viewport, so they show the same field of
view as the mirror (live capture and frozen frame). The extra pinch zoom / pan applied to a *frozen*
image is display-only; the frozen capture saves the full frozen frame at the viewport's aspect.

### Double tap (QA-BUG-13)

To keep single-tap latency under 150 ms, single taps act immediately. A double tap therefore
briefly toggles the controls before the second tap reverts that toggle and toggles zoom.
