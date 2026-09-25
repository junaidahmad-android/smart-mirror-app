# Smart Mirror: Test Plan and Traceability Matrix

| | |
|---|---|
| Owner | QA |
| Scope | v1.0 (ROADMAP MVP-01..MVP-15, NFR-01..NFR-14) |
| Platforms | Android (Compose + CameraX), iOS (SwiftUI + AVFoundation), shared KMP core |
| Last updated | 2026-09-25 |
| Results | See [QA_REPORT.md](./QA_REPORT.md) |

## 1. Test levels and suites

| Code | Level | Location | Runs on | Command |
|---|---|---|---|---|
| **RT** | Shared unit: reducer | `sharedLogic/src/commonTest/.../MirrorReducerTest.kt` | JVM + iOS sim | `./gradlew :sharedLogic:allTests` |
| **RE** | Shared unit: reducer edge cases (QA, new) | `.../presentation/mirror/MirrorReducerEdgeCaseTest.kt` | JVM + iOS sim | same |
| **VT** | Shared unit: ViewModel + fakes | `.../MirrorViewModelTest.kt` | JVM + iOS sim | same |
| **VQ** | Shared unit: ViewModel QA scenarios (new) | `.../presentation/mirror/MirrorViewModelQaTest.kt` | JVM + iOS sim | same |
| **KR / UD** | Settings repository / NSUserDefaults store | `.../data/settings/KeyValueSettingsRepositoryTest.kt`, `iosTest/.../UserDefaultsKeyValueStoreTest.kt` | JVM + iOS sim / iOS sim | same |
| **DM / PC** | Domain model, pipeline coordinator | `.../domain/model/DomainModelTest.kt`, `.../processing/PipelineCoordinatorTest.kt` | JVM + iOS sim | same |
| **AL** | Android JVM unit (camera math, mappings, layout spec) | `androidApp/src/test/.../PlatformLogicTest.kt` | JVM | `./gradlew :androidApp:testDebugUnitTest` |
| **AU** | Android Compose UI (state fixtures) | `androidApp/src/androidTest/.../MirrorScreenUiTest.kt` | Emulator | `./gradlew :androidApp:connectedDebugAndroidTest` |
| **AQ** | Android Compose UI QA additions (new) | `androidApp/src/androidTest/.../QaMirrorUiTest.kt` | Emulator | same |
| **AP** | Android adapters (brightness, MediaStore, permission, transforms) | `androidApp/src/androidTest/.../PlatformAdaptersTest.kt` | Emulator | same |
| **AE** | Android E2E, real CameraX on the emulator's virtual camera | `androidApp/src/androidTest/.../MainActivityEndToEndTest.kt` | Emulator | same |
| **AQE** | Android E2E QA additions (new) | `androidApp/src/androidTest/.../QaEndToEndTest.kt` | Emulator | same |
| **IS / IP / IC** | iOS XCTest: store bridge, ports, camera adapter | `iosApp/iosAppTests/*.swift` | iOS sim | `xcodebuild test -scheme iosApp` |
| **IU** | iOS XCUITest (new target `iosAppUITests`) | `iosApp/iosAppUITests/MirrorUITests.swift` | iPhone + iPad sim | `xcodebuild test -scheme iosApp -only-testing:iosAppUITests` |
| **EX** | Exploratory on emulator/simulator (scripted with adb/simctl) | this document, section 4 | Emulator / sim | manual |
| **RD** | Manual on reference devices (camera, thermal, performance) | this document, section 5 | Physical devices | manual |

Tests annotated `@Ignore` / `@Ignore("QA-BUG-xx")` assert the ROADMAP behaviour and currently fail. They document open bugs; remove the annotation when the bug is fixed.

## 2. Traceability matrix: MVP stories

Legend: test names are abbreviated as `Suite.testName`. **RD** = physical device required.

### MVP-01 Instant full-screen mirror preview
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 full-screen, no splash, under cutouts | VT.grantedPermissionStartsFrontCameraAndAppliesSettings | AE.launch_showsLiveMirror...; EX-A1 (screenshot); RD-01 (cutout) | IU.testSubsequentLaunchSkipsOnboarding; IS.testGrantedStarts...; RD-01 |
| AC2 mirrored by default | RT.mirrorModeToggles; VT (camera.mirrored=true) | AE (previewMirrored); AQE.frozenCapture_savedPhotoMatchesScreen...; RD-02 (raise right hand) | IS.testMirrorToggleReachesThePreviewSurface; EX-I4 (saved photo marker); RD-02 |
| AC3 no chrome, one-time hint <= 3 s | VT.tapHintIsShownOnceEver; RE.resetRestoresEveryDocumentedDefault (hint kept) | AU.mirror_startsWithoutControls..., AU.tapHint_shownWhenRequested | IU.testFirstLaunchOnboarding_thenAllow... (no controls); IS.testGrantedStarts... |
| AC4 aspect fill, no distortion | n/a (UI) | AL.centerCrop_*; EX-A5 (frozen after rotation) | EX-I6 (iPad); RD-03 (circle target) |
| AC5 cold/warm start | see NFR-01/02 | RD-10 | RD-10 |
| AC6 keep screen on | VT (screenKeptOn), VQ.fiftyBackgroundForegroundCyclesStayBalanced | AP.brightness_appliesAndRestores...; AQE.background_restoresBrightness... | IP.testKeepScreenOnDrivesIdleTimer |

### MVP-02 Tap to reveal controls, auto-hide
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 tap shows controls <= 150 ms | RT.controlsStartHidden... | AU.mirror_startsWithoutControls...; RD-11 (latency, high-speed video) | IU.testTapRevealsLabelledControls...; RD-11 |
| AC2 auto-hide 4 s, interaction restarts timer | VT.controlsAutoHideAndInteractionResetsTimer | AU.touchingControls_restartsAutoHideTimer | IU.testControlsAutoHideAfterFourSeconds |
| AC3 tap again hides | RT.controlsStartHidden... | AU.mirror_startsWithoutControls... | IU.testTapRevealsLabelledControls... |
| AC4 no auto-hide with TalkBack/VoiceOver | VT (screen reader), RE.settingsSheetPausesAutoHideAndScreenReaderDisablesIt | AL (observeScreenReader mapping); RD-12 (TalkBack on) | IP/SystemEvents; RD-12 (VoiceOver on) |
| AC5 pinch never toggles controls | n/a | AU.mirror_pinchSendsIncrementalZoom_andNeverTaps | IU.testDoubleTapTogglesZoom_withoutTogglingControls; RD-13 (pinch) |

### MVP-03 Zoom
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 pinch 1x to >= 5x | RT.zoomIsClampedToCapabilitiesAndPinchMultiplies; RE.zoomWithCapabilitiesStaysInsideTheCameraRange; RE.zoomWithoutCapabilities... | AL.advertisedMaxZoom_isAtLeastFive, AL.zoomSplit_*; AU.mirror_pinch... | IC.testCapabilitiesClampZoom...; RD-13 |
| AC2 slider synced with pinch | RT (single zoomRatio source) | AU.zoomPresets_setZoom | IU.testZoomPresetShowsTransientLabel... |
| AC3 double tap 1x <-> last non-1x (default 2x) | RT.doubleTapTogglesBetweenOneXAndLastZoom; RE.rapidDoubleTapsAlternateDeterministically; **RE.doubleTapReturnsToLastNonDefaultZoomSetBySliderPresetOrPinch (@Ignore QA-BUG-03)** | AU.mirror_doubleTapTogglesZoomAndRestoresControls | IU.testDoubleTapTogglesZoom_withoutTogglingControls |
| AC4 transient "2.5x" label, 1 s | n/a | AU.zoomLabel_appearsWhenZoomChanges | IU.testZoomPresetShowsTransientLabel... (a11y value) |
| AC5 FPS at zoom | see NFR-03 | RD-14 | RD-14 |
| AC6 zoom persists | VQ.settingsSurviveAKillAndRelaunchButExposureDoesNot | EX-A7 (force-stop + relaunch) | UD.settingsSurviveRepositoryRecreation |

### MVP-04 Exposure
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 slider >= +/-2 EV, 0 detent | RT.exposureIsSnappedToStep...; RE.exposureIsClampedSnappedAndReset... | AL.exposureIndex_roundsAndClamps; AU.exposure_panelHasSliderAndReset | IC.testCapabilitiesClampZoomToAppMaximumAndExposureToTwoEv |
| AC2 visible within 200 ms | VT.zoomAndExposureAreForwardedToCamera | RD-15 | RD-15 |
| AC3 reset to 0 | RE.exposureIsClampedSnappedAndReset... | AU.exposure_panelHasSliderAndReset | IU (exposure tab) / RD-15 |
| AC4 hidden when unsupported | RE (noEv caps); DM.exposureWithoutSupportIsZero | AU.exposure_tabShownOnlyWhenSupported | IC.testCapabilitiesWithoutExposureSupportHideTheControl |

### MVP-05 Ring light
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 toggle, border inside safe areas | RT.ringLightPresetsEnableTheLight | AU.ringLight_drawnOnlyWhenEnabled, AU.ringLight_visibleWhenOn; EX-A8 (tablet); RD-16 (cutouts) | EX-I6 (iPad screenshot); RD-16 |
| AC2 intensity 0-100 %, >= 12 % edge at 100 % | DM.ringLightGetsThickerWithIntensity; RE.ringLightIntensityIsBounded...; **RE.nonFiniteRingLightIntensityIsIgnored (@Ignore QA-BUG-09)** | AU.light_panelPresetsAndWarmth | IS / EX-I6 |
| AC3 warmth presets | DM.warmLightIsRedderThanCoolLight | AU.light_panelPresetsAndWarmth | EX-I6 |
| AC4 <= 100 ms, no stutter | n/a | RD-17 | RD-17 |
| AC5 dark-room lit face | n/a | RD-18 (comparison screenshots) | RD-18 |
| AC6 persists | VQ.settingsSurviveAKillAndRelaunch...; KR.updatesArePublishedAndSurviveRecreation | EX-A7 | IU.testTrueViewAndRingLightPersistAcrossRelaunch (mode), UD |

### MVP-06 Auto-max brightness
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 max on foreground | VT.grantedPermissionStarts...; VQ.fiftyBackgroundForegroundCycles... | AQE.background_restoresBrightness...; AP.brightness_* | IS.testGrantedStarts...; IP.testBoostRemembersUserLevelAndRestoresIt |
| AC2 restore on background within 500 ms | VT.screenStoppedReleasesCameraAndBrightness; VQ.fifty... | AQE.background_restoresBrightness...; AP | IP.testResignActiveRestoresAndBecomeActiveReapplies; RD-19 (lock/unlock) |
| AC3 per-window / UIScreen, no system setting | n/a | AP.brightness_reappliedToNewWindowAfterRecreation | IP.testBoostRemembers... |
| AC4 setting to disable | VT.brightnessBoostFollowsSettingsAndRingLight; RE.throttlingNeverRaisesBrightnessWhenTheUserOptedOut | AQE.autoMaxBrightnessOff_leavesUserBrightnessAlone; AU.settingsSheet_togglesAndResets | IU.testSettingsSheetContents... |
| AC5 thermal precedence | RE.thermalPolicyAcrossAllLevels; VQ.thermalThrottlingDrivesPortsAndRecovers | AL.thermalStatus_mapping_followsNfr06 | IP/SystemEvents.testThermalMapping |

### MVP-07 Freeze frame
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 freeze <= 100 ms, exactly what is on screen | VT.freezeKeepsControlsVisibleAndPausesPreview | AE.freeze_showsFrozenFrame_andResumes; AQE.frozenCapture_savedPhotoMatchesScreen... | IS.testTapPinchAndFreezeFlowThroughTheBridge; RD-20 |
| AC2 "Frozen" indicator not auto-hidden | RE.frozenZoomGestures... (controlsVisible) | AU.frozen_showsBadgeAndImage_andResumeUnfreezes | IU.testFreezeShowsPersistentIndicator_andResume |
| AC3 pinch up to 8x, bounded pan | RT.zoomWhileFrozenScalesFrozenImageOnlyUpTo8x; RE.frozenZoomGestures... | AL.clampPan_keepsImageCoveringViewport | code review (clampedPan); RD-20 |
| AC4 resume at pre-freeze zoom | RE.frozenZoomGesturesScaleTheImageOnlyAndResumeRestoresPreFreezeZoom | AE.freeze_... | IU.testFreezeShowsPersistentIndicator_andResume |
| AC5 camera paused, live within 300 ms | VT.freezeKeeps... (setPreviewFrozen) | RD-20 | RD-20 |
| AC6 memory only | code review | AQE.livePhoto_... (no app-private image files) | EX-I5 (container scan) |
| AC7 background discards freeze | RE.freezeIsDiscardedOnBackgroundCameraErrorAndStop; VQ.backgroundingWhileFrozenDiscardsTheFrameAndReturnsLive | EX-A4 | IU.testBackgroundWhileFrozenReturnsLive |

### MVP-08 Capture photo
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 live = new photo, frozen = frozen frame | VQ.captureWhileFrozenSaves...; VQ.liveCaptureRequestsFollowTheMode; RE.captureIsAllowedWhileFrozen... | AE.launch_...captureSaves; AQE.frozenCapture_... | IU.testCaptureSavesToPhotos_withFeedback; IU.testFreezeShows... ("Save frozen photo") |
| AC2 matches mode, zoom crop, no ring light | VQ.liveCaptureRequestsFollowTheMode | AP.photoTransforms_mirrorFlipsHorizontally_andCropsToView; AQE.frozenCapture_savedPhotoMatchesScreen_inMirrorAndTrueView; AQE.livePhoto_... (aspect); RD-21 | IP.testMirroredJpegIsFlippedHorizontally; IC.testSimulatedCaptureWritesAMirroredJpeg; EX-I4; RD-21 |
| AC3 photos permission, optional | n/a | code review (API 29+ none; <= 28 WRITE_EXTERNAL_STORAGE); RD-22 (API 26-28 device) | IP.testFirstSaveAsksOnceAndReportsDenial; IU.testCaptureSaves... |
| AC4 feedback within 1 s + announced | VT.captureSavesPhotoAndShowsTransientMessage; **VQ.secondSaveWithinMessageWindowGetsFreshFeedback (@Ignore QA-BUG-06)** | AE (LiveRegion.Polite); AU.savedMessage_isShown | IU.testCaptureSavesToPhotos_withFeedback |
| AC5 denied: non-blocking message, Settings link, no re-prompt | VT.captureFailuresAreReported | AU.savePermissionMessage_linksToSettings | IP.testDeniedDoesNotPromptAgain |
| AC6 JPEG >= 90, no location EXIF | n/a | AQE.livePhoto_isJpegWithoutLocation...; code review (quality 95) | code review (0.92, no GPS); EX-I4 |
| AC7 no app-private copies | VQ (n/a) | AP.photoSaver_writesJpegToMediaStore_andDropsBufferedBytes; AQE.livePhoto_...andNoAppCopies; EX-A10 | IP.testAuthorizedSaveReturnsAssetAndRemovesTempFile; EX-I5 |

### MVP-09 Mirror / true view
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 toggle | RT.mirrorModeToggles | AU.trueView_toggleDescribesCurrentMode | IU.testTrueViewAndRingLightPersist... |
| AC2 instant, keeps zoom/exposure, transient label | RT | AQ.modeLabelAppearsOnToggleAndDisappears; RD-23 (<= 100 ms) | code review (showModeLabel); RD-23 |
| AC3 applies to live, freeze, capture | RE.mirrorModeAppliesToFrozenAndLiveAlike; VQ.captureWhileFrozenSaves...InTheCurrentMirrorMode | AQE.frozenCapture_savedPhotoMatchesScreen_inMirrorAndTrueView | IS.testMirrorToggleReachesThePreviewSurface; EX-I4 |
| AC4 persists | VQ.settingsSurviveAKill... | EX-A7 | IU.testTrueViewAndRingLightPersistAcrossRelaunch |

### MVP-10 Orientation and form factors
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 portrait/landscape upright + mirrored | n/a | AE.rotation_keepsActivityAndCamera...; EX-A3; RD-24 (upright check needs a real scene) | IU.testRotationKeepsStateAndControls; IC.testRotationMapping; RD-24 |
| AC2 no camera restart, state retained | VQ (n/a) | AE.rotation_...; EX-A3 (no CLOSING on rotate); **EX-A5 (frozen image stretched, QA-BUG-02)** | IU.testRotationKeepsStateAndControls |
| AC3 re-layout, tablet spacing | n/a | AL.phonePortrait_usesBottomBar, AL.tablet*; AU.controls_sideRailLayout...; AQ.tabletSideRail_hasLargerTouchTargets; EX-A8 | IU (full suite on iPad Pro 11); EX-I6 |
| AC4 split screen / foldables | n/a | RD-25 (multi-window, foldable) | n/a |
| AC5 iPad full screen only | n/a | n/a | code review (UIRequiresFullScreen); **EX-I7 (windowed on iPadOS 26, QA-BUG-07)** |

### MVP-11 Persisted settings
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 persist across restarts; exposure resets | VQ.settingsSurviveAKillAndRelaunchButExposureDoesNot; KR.updatesArePublishedAndSurviveRecreation | EX-A7 (force-stop + relaunch; prefs dump) | IU.testTrueViewAndRingLightPersistAcrossRelaunch; UD.settingsSurviveRepositoryRecreation |
| AC2 local store only | KR, UD | EX-A10 (sandbox contents) | EX-I5 |
| AC3 reset to defaults | RT.resetToDefaultsKeepsHintFlagAndClampsZoom; RE.resetRestoresEveryDocumentedDefault; VQ.resetToDefaultsIsPersisted | AU.settingsSheet_togglesAndResets | IU.testTrueViewAndRingLightPersist... (reset step) |
| AC4 corrupt store -> defaults | KR.corruptOrOutOfRangeValuesFallBackSafely | n/a | UD.roundTripsPrimitivesAndReportsMissingKeysAsNull |
| AC5 reinstall starts from defaults | KR.emptyStoreYieldsDefaultsWithoutWriting | EX-A2 (pm clear) | IU (resetAuthorization + fresh container) |

### MVP-12 Permission onboarding and errors
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 single friendly onboarding screen | VT.notDeterminedShowsOnboardingThenRequestStartsCamera | AU.onboarding_primaryButtonRequestsPermission; EX-A2 | IU.testFirstLaunchOnboarding_thenAllow...; IS.testStartsInLoading... |
| AC2 granted -> preview, no restart | VQ.deniedPromptDoesNotStartCameraAndTryAgainReprompts | EX-A2 | IU.testFirstLaunchOnboarding_thenAllow... |
| AC3 denied screen, Settings, auto-start on return; don't-ask-again = Settings only | VT.blockedPermissionOffersSettingsAndRechecksOnReturn; VQ.permissionRevokedInBackground...RecoversFromSettings | AU.denied_*, AU.blocked_*; AL.permissionStatus_resolution; EX-A2 (deny, deny twice, settings, grant, return); **EX-A6 (one-time grant expiry, QA-BUG-01)** | IU.testDontAllow_showsSettingsOnlyState; IP.testDeclinedDialogIsBlocked; EX-I2 |
| AC4 no front camera / failure -> error + retry | RT.cameraPermissionErrorFlipsToDeniedScreen; VT.cameraErrorCanBeRecovered... | AU.noFrontCamera_showsError; AQ.genericCameraErrorOffersRetry; AL.cameraErrors_mapToSharedErrors | IC.testRealAdapterReportsAnErrorInsteadOfCrashingWithoutACamera |
| AC5 in use / interrupted -> unavailable, auto-resume | VT.cameraErrorCanBeRecovered... | AU.cameraInUse_showsUnavailableWithRetry; RD-26 (second camera app, phone call) | IC.testInterruptionReasonsMapToCameraErrors; RD-26 |
| AC6 revoked while backgrounded | VQ.permissionRevokedInBackgroundShowsDeniedState...; RE.permissionRevokedWhileRunning... | EX-A6 (`pm revoke`) | EX-I2 (`simctl privacy revoke`) |
| AC7 later launches skip onboarding | VT.grantedPermissionStarts... | EX-A2 | IU.testSubsequentLaunchSkipsOnboarding |

### MVP-13 Privacy
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 no login/account | n/a | code review | code review |
| AC2 no INTERNET in merged manifest | n/a | AQ.installedPackageDoesNotRequestInternet; **AQ.installedPackageRequestsNoNetworkPermissionsAtAll (@Ignore QA-BUG-05)**; EX (aapt2 dump permissions) | n/a |
| AC3 no networking code / SDKs | code review (grep) | code review (grep) | code review (grep, Info.plist); **privacy manifest missing (QA-BUG-04)** |
| AC4 no analytics/crash SDK | dependency review | `:androidApp:dependencies` review | project review (no packages) |
| AC5 no frame caches on disk | n/a | AQE.livePhoto_...andNoAppCopies; EX-A10 | EX-I5 |
| AC6 privacy statement in Settings | n/a | AQ.settingsSheet_showsEveryMvp15Item...; AU.settingsSheet_togglesAndResets | IU.testSettingsSheetContents_andCloseKeepsState |

### MVP-14 Lifecycle
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 release camera within 500 ms of background | VT.screenStoppedReleasesCameraAndBrightness | AQE.background_restoresBrightnessAndReleasesCamera...; EX-A9 (logcat timing) | RD-27 (green dot) |
| AC2 resume per NFR-02 with settings intact | VQ.fiftyBackgroundForegroundCyclesStayBalanced; **VQ.lateCameraStartedAfterStopDoesNotWedgeTheCamera (@Ignore QA-BUG-10)** | AQE.background_...foregroundReapplies | IU.testBackgroundWhileFrozenReturnsLive |
| AC3 50 cycles, < 10 MB growth, no crash/ANR | VQ.fiftyBackgroundForegroundCyclesStayBalanced; VQ.rapidStartStopBeforeTheCameraReportsNeverDoubleStarts | EX-A9 (50 cycles, PSS) ; RD-28 | RD-28 (Instruments) |

### MVP-15 Settings / about
| AC | Shared | Android | iOS |
|---|---|---|---|
| AC1 auto brightness, reset, privacy, version, licenses, contact | n/a | AQ.settingsSheet_showsEveryMvp15Item_andLicensesOpen; **AQ.supportAddressIsNotAPlaceholder (@Ignore QA-BUG-08)** | IU.testSettingsSheetContents_andCloseKeepsState |
| AC2 settings pauses auto-hide, closing keeps state | RE.settingsSheetPausesAutoHide...; VQ.settingsSheetPausesAutoHideUntilClosed | AU.settingsSheet_togglesAndResets | IU.testSettingsSheetContents_andCloseKeepsState |

## 3. Traceability matrix: NFRs

| NFR | What is testable where | Shared | Android | iOS |
|---|---|---|---|---|
| NFR-01 cold start | Timing needs RD + release build; ordering checks on emulator | n/a | EX-A11 (camera open order, double session); RD-10 (Macrobenchmark) | RD-10 (Instruments App Launch) |
| NFR-02 warm start | RD | n/a | RD-10 | RD-10 |
| NFR-03 FPS >= 30, jank < 1 % | RD | RE.thermalPolicy (60/30 fps request) | AL.fpsRange_prefersHighestUpper...; RD-14 | RD-14 |
| NFR-04 motion-to-photon <= 100 ms | RD (high-speed camera) | n/a | RD-29 | RD-29 |
| NFR-05 battery, camera paused when frozen/background, no wakelock | Partly automated | VT.freezeKeeps..., VQ.fifty... | AQE.background_... (FLAG_KEEP_SCREEN_ON cleared); RD-30 | IP.testKeepScreenOnDrivesIdleTimer; RD-30 |
| NFR-06 thermal | Policy automated; real thermal RD | RE.thermalPolicyAcrossAllLevels; VQ.thermalThrottlingDrivesPortsAndRecovers; VQ.coolingDownWhileBackgroundedWaitsForForeground | AL.thermalStatus_mapping_followsNfr06; AU.overheated_showsHotScreen; AQ.overheatedScreenHasNoControlsAndNoPreview | SystemEventsTests.testThermalMapping; RD-31 (30-min soak) |
| NFR-07 memory | Indicative on emulator; RD for limits | n/a | EX-A9; RD-28 | RD-28 |
| NFR-08 stability | Zero crashes in regression suites | all | all | all |
| NFR-09 app size | Build artefacts | n/a | release APK size (EX-A12); AAB download size RD/Play Console | Xcode thinning report (RD) |
| NFR-10 accessibility | Labels, targets, font scale, screen reader | RE.settingsSheetPausesAutoHideAndScreenReaderDisablesIt | AU.allVisibleControlsHaveContentDescriptions, AU.controls_bottomBarLayout...Targets; AQ.previewOffersAScreenReaderAction..., AQ.largestFontScale_*, AQ.tabletSideRail_hasLargerTouchTargets, AQ.genericCameraErrorOffersRetry (48 dp touch); EX-A1 (uiautomator dump); RD-12 | IU.testTapRevealsLabelledControls_withMinimumTouchTargets, IU.testDontAllow (44 pt); RD-12 |
| NFR-11 privacy | See MVP-13 | | | |
| NFR-12 localization / RTL | RTL layout | n/a | AQ.rtlLayout_rendersAllControls_andKeepsMirrorSemantics | RD / manual (RTL pseudo-language) |
| NFR-13 OS support | Min SDK / deployment target | n/a | build config (minSdk 26); RD-22 (API 26-28 device) | build config (iOS 16.0); iOS 16 sim runtime unavailable locally |
| NFR-14 shared logic + hook | Unit coverage >= 80 % | all shared suites (coverage tooling not configured, see report) | FrameAnalysisHook (code review) | FrameTap (code review) |

## 4. Exploratory charters (emulator / simulator)

| ID | Charter | Platform |
|---|---|---|
| EX-A1 | Launch, reveal controls, dump the accessibility tree (uiautomator) | Android |
| EX-A2 | `pm clear`, onboarding, Don't allow, Try again, Don't allow (permanent), Open Settings, grant, Back | Android |
| EX-A3 | Rotate portrait <-> landscape with controls visible; check camera events for CLOSING | Android |
| EX-A4 | Freeze, Home, return | Android |
| EX-A5 | Freeze in landscape, rotate to portrait while frozen | Android |
| EX-A6 | Revoke camera while backgrounded (`pm revoke`, with/without user-set flag) and one-time permission expiry | Android |
| EX-A7 | Change mode / ring light / zoom, force-stop, relaunch; inspect shared_prefs | Android |
| EX-A8 | Simulated tablet (`wm size 1600x2560`, `wm density 240`) portrait + landscape | Android |
| EX-A9 | Background timing (logcat), 50 Home/relaunch cycles with PSS/RSS | Android |
| EX-A10 | App sandbox scan after capture session | Android |
| EX-A11 | Cold-start camera state trace (CameraX OPENING/OPEN/CLOSING vs Activity Displayed) | Android |
| EX-A12 | Release APK size | Android |
| EX-I1 | Onboarding, Allow / Don't Allow (XCUITest) | iOS |
| EX-I2 | `simctl privacy revoke camera` while backgrounded, relaunch; grant and return | iOS |
| EX-I4 | Inspect saved photo in the simulator Photos library (marker position = mirroring) | iOS |
| EX-I5 | App container scan (tmp, caches) after captures | iOS |
| EX-I6 | iPad portrait/landscape layouts with `-SMDemoControls YES -SMDemoLight 1.0` | iOS |
| EX-I7 | iPad landscape via `-SMOrientation landscape` (window geometry) | iOS |

## 5. Reference-device (RD) manual cases

Physical devices only (camera, sensors, thermal, performance). Run on every RD from ROADMAP section 2.1, release build.

| ID | Case | Covers |
|---|---|---|
| RD-01 | Edge-to-edge preview under status bar, notch/Dynamic Island, nav bar | MVP-01.AC1 |
| RD-02 | Raise right hand: on-screen hand on the right (mirror), on the left (true view) | MVP-01.AC2, MVP-09 |
| RD-03 | Hold a round object: stays circular in portrait, landscape, split screen | MVP-01.AC4, MVP-10.AC4 |
| RD-10 | Cold/warm start P50/P90 (Macrobenchmark `StartupTimingMetric` + first-frame marker; Instruments) | NFR-01/02, MVP-01.AC5 |
| RD-11 | Tap-to-controls latency (240 fps video) | MVP-02.AC1 |
| RD-12 | TalkBack / VoiceOver full pass; controls never auto-hide; 200 % font | MVP-02.AC4, NFR-10 |
| RD-13 | Pinch 1x to 5x smooth, centred | MVP-03.AC1, AC5 |
| RD-14 | FPS / jank over 60 s at 5x with ring light (gfxinfo framestats; Core Animation instrument) | NFR-03 |
| RD-15 | Exposure change visible <= 200 ms; reset | MVP-04 |
| RD-16..18 | Ring light inside safe areas; switching <= 100 ms; dark-room (< 10 lux) comparison photos | MVP-05 |
| RD-19 | Brightness restored on Home, lock, app switcher, incoming call | MVP-06.AC2 |
| RD-20 | Freeze latency, frozen matches screen, resume <= 300 ms | MVP-07 |
| RD-21 | Captured photo = preview framing, zoom crop, mode; no ring light; every orientation | MVP-08.AC2 |
| RD-22 | API 26-28 device: WRITE_EXTERNAL_STORAGE prompt, deny, no re-prompt | MVP-08.AC3/AC5, NFR-13 |
| RD-23 | Mirror/true-view switch <= 100 ms | MVP-09.AC2 |
| RD-24 | Upright + mirrored after rotating the physical device in all 4 orientations | MVP-10.AC1 |
| RD-25 | Android split screen, freeform, foldable fold/unfold | MVP-10.AC4 |
| RD-26 | Camera taken by another app / phone call / iPad multitasking camera; auto-resume | MVP-12.AC5 |
| RD-27 | OS camera indicator off within 500 ms of Home / screen off | MVP-14.AC1 |
| RD-28 | 50 background/foreground cycles: memory growth < 10 MB, peak RSS <= 250/350 MB | MVP-14.AC3, NFR-07 |
| RD-29 | Motion-to-photon with high-speed camera | NFR-04 |
| RD-30 | 10-minute battery drain at max brightness + ring light | NFR-05 |
| RD-31 | 30-minute thermal soak at 22-25 °C; induced serious/critical states | NFR-06 |
