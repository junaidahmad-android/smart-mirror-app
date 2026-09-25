import XCTest

/// QA XCUITests (docs/TEST_PLAN.md). Drive the real app on a simulator with the simulated camera
/// (`-SMCamera simulated`, the default in the Simulator). The system permission prompts are real:
/// camera authorization is reset per test where the flow needs it.
///
/// Run: xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
///        -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:iosAppUITests
final class MirrorUITests: XCTestCase {
    private var app: XCUIApplication!
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["-SMCamera", "simulated"]
    }

    override func tearDownWithError() throws {
        app.terminate()
    }

    // MARK: - Helpers

    /// The full-screen gesture layer ("Mirror", button trait) exists only on the live mirror screen.
    private var mirror: XCUIElement { app.buttons["Mirror"] }

    private func tapSystemAlert(_ labels: [String], timeout: TimeInterval = 5) -> Bool {
        let alert = springboard.alerts.firstMatch
        guard alert.waitForExistence(timeout: timeout) else { return false }
        for label in labels where alert.buttons[label].exists {
            alert.buttons[label].tap()
            return true
        }
        return false
    }

    /// Launches with camera access granted (answers the prompt if it appears).
    private func launchGranted() {
        app.launch()
        if app.buttons["Open Settings"].waitForExistence(timeout: 2) {
            // A previous test denied access: reset so the prompt can be answered again.
            app.terminate()
            app.resetAuthorizationStatus(for: .camera)
            app.launch()
        }
        let onboarding = app.buttons["Continue"]
        if onboarding.waitForExistence(timeout: 3) {
            onboarding.tap()
            XCTAssertTrue(tapSystemAlert(["Allow", "OK"]), "camera prompt expected")
        }
        XCTAssertTrue(mirror.waitForExistence(timeout: 10), "live mirror expected")
    }

    private func showControls() {
        if !app.buttons["Take photo"].exists && !app.buttons["Save frozen photo"].exists {
            mirror.tap()
        }
        XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 3))
    }

    private func assertTouchTarget(_ element: XCUIElement, min: CGFloat = 44, file: StaticString = #filePath, line: UInt = #line) {
        let f = element.frame
        XCTAssertGreaterThanOrEqual(f.width, min - 0.5, "\(element.label) width \(f.width)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(f.height, min - 0.5, "\(element.label) height \(f.height)", file: file, line: line)
    }

    // MARK: - MVP-12 permission onboarding / denied

    func testFirstLaunchOnboarding_thenAllow_showsMirrorWithoutRestart() {
        app.resetAuthorizationStatus(for: .camera)
        app.launch()
        XCTAssertTrue(app.buttons["Continue"].waitForExistence(timeout: 5), "MVP-12.AC1: one primary button")
        XCTAssertTrue(app.staticTexts["Smart Mirror uses your front camera. Nothing is recorded or sent anywhere."].exists)
        app.buttons["Continue"].tap()
        XCTAssertTrue(tapSystemAlert(["Allow", "OK"]))
        XCTAssertTrue(mirror.waitForExistence(timeout: 10), "MVP-12.AC2: preview without restart")
        XCTAssertFalse(app.buttons["Take photo"].exists, "MVP-01.AC3: no chrome on first display")
    }

    func testDontAllow_showsSettingsOnlyState() {
        app.resetAuthorizationStatus(for: .camera)
        app.launch()
        XCTAssertTrue(app.buttons["Continue"].waitForExistence(timeout: 5))
        app.buttons["Continue"].tap()
        XCTAssertTrue(tapSystemAlert(["Don’t Allow", "Don't Allow"]))
        XCTAssertTrue(app.staticTexts["Camera access is off"].waitForExistence(timeout: 5), "MVP-12.AC3")
        XCTAssertTrue(app.buttons["Open Settings"].exists)
        XCTAssertFalse(app.buttons["Try again"].exists, "iOS cannot re-prompt; Settings is the only path")
        assertTouchTarget(app.buttons["Open Settings"])
    }

    func testSubsequentLaunchSkipsOnboarding() {
        launchGranted()
        app.terminate()
        app.launch()
        XCTAssertTrue(mirror.waitForExistence(timeout: 10), "MVP-12.AC7")
        XCTAssertFalse(app.buttons["Continue"].exists)
    }

    // MARK: - MVP-02 controls, NFR-10 accessibility

    func testTapRevealsLabelledControls_withMinimumTouchTargets() {
        launchGranted()
        showControls()
        for label in ["Settings", "Take photo", "Freeze frame", "True view"] {
            let button = app.buttons[label]
            XCTAssertTrue(button.exists, "missing control \(label)")
            assertTouchTarget(button)
        }
        XCTAssertTrue(app.buttons["Zoom 2×"].exists, "button alternative to pinch/double tap (NFR-10)")
        mirror.tap()
        XCTAssertFalse(app.buttons["Take photo"].waitForExistence(timeout: 1), "MVP-02.AC3: tap again hides")
    }

    func testControlsAutoHideAfterFourSeconds() {
        launchGranted()
        showControls()
        let capture = app.buttons["Take photo"]
        XCTAssertTrue(capture.exists)
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: capture)
        waitForExpectations(timeout: 7) // MVP-02.AC2: 4 s + animation + polling slack
    }

    func testZoomPresetShowsTransientLabelAndUpdatesAccessibilityValue() {
        launchGranted()
        showControls()
        app.buttons["Zoom 3×"].tap()
        XCTAssertEqual(mirror.value as? String, "Zoom 3×")
        app.buttons["Zoom 1×"].tap()
        XCTAssertEqual(mirror.value as? String, "Zoom 1×")
    }

    func testDoubleTapTogglesZoom_withoutTogglingControls() {
        launchGranted()
        showControls()
        app.buttons["Zoom 1×"].tap() // zoom persists across launches; normalise
        sleep(6) // controls auto-hide and transient labels clear (a tap right before a double tap can merge)
        for expected in ["Zoom 2×", "Zoom 1×"] {
            mirror.doubleTap()
            expectation(for: NSPredicate(format: "value == %@", expected), evaluatedWith: mirror)
            waitForExpectations(timeout: 3) // MVP-03.AC3
            XCTAssertFalse(app.buttons["Take photo"].exists, "double tap must not toggle the controls")
        }
    }

    // MARK: - MVP-07 freeze, MVP-08 capture

    func testFreezeShowsPersistentIndicator_andResume() {
        launchGranted()
        showControls()
        app.buttons["Freeze frame"].tap()
        let frozen = app.buttons["Frozen"]
        XCTAssertTrue(frozen.waitForExistence(timeout: 2), "MVP-07.AC2")
        XCTAssertTrue(app.buttons["Save frozen photo"].exists, "capture saves the frozen frame (MVP-08.AC1)")
        sleep(6)
        XCTAssertTrue(frozen.exists, "indicator/controls are not auto-hidden while frozen")
        frozen.tap()
        XCTAssertFalse(frozen.waitForExistence(timeout: 1))
        XCTAssertTrue(app.buttons["Freeze frame"].exists, "MVP-07.AC4: back to live")
    }

    func testCaptureSavesToPhotos_withFeedback() {
        app.resetAuthorizationStatus(for: .photos)
        launchGranted()
        showControls()
        app.buttons["Take photo"].tap()
        _ = tapSystemAlert(["Allow", "OK", "Allow Access"], timeout: 5) // "Add Photos Only" prompt (MVP-08.AC3)
        XCTAssertTrue(app.staticTexts["Saved to Photos"].waitForExistence(timeout: 5), "MVP-08.AC4")
    }

    // MARK: - MVP-15 settings, MVP-11 persistence

    func testSettingsSheetContents_andCloseKeepsState() {
        launchGranted()
        showControls()
        app.buttons["Settings"].tap()
        XCTAssertTrue(app.switches["Auto max brightness"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Reset to defaults"].exists)
        XCTAssertTrue(app.staticTexts["Version"].exists)
        let privacy = app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] 'nothing you see is recorded or sent anywhere'")).firstMatch
        XCTAssertTrue(privacy.exists, "MVP-13.AC6 privacy statement")
        let about = [app.buttons["Open-source licenses"], app.buttons["Contact support"]]
        for element in about {
            if !element.exists { app.swipeUp() }
            XCTAssertTrue(element.exists, "MVP-15.AC1 missing \(element)")
        }
        sleep(5) // MVP-15.AC2: auto-hide paused while open
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["Take photo"].waitForExistence(timeout: 2), "controls still visible after closing settings")
    }

    func testTrueViewAndRingLightPersistAcrossRelaunch() {
        launchGranted()
        showControls()
        let trueView = app.buttons["True view"]
        if (trueView.value as? String)?.hasPrefix("On") == true { trueView.tap() } // normalise to mirror
        trueView.tap()
        XCTAssertEqual(trueView.value as? String, "On, showing how others see you")
        sleep(1) // > 300 ms persistence debounce
        app.terminate()

        app.launch()
        XCTAssertTrue(mirror.waitForExistence(timeout: 10))
        showControls()
        XCTAssertEqual(app.buttons["True view"].value as? String, "On, showing how others see you", "MVP-09.AC4 / MVP-11.AC1")

        // Reset to defaults restores mirror mode (MVP-11.AC3).
        app.buttons["Settings"].tap()
        app.buttons["Reset to defaults"].tap()
        let confirm = app.sheets.buttons["Reset"].exists ? app.sheets.buttons["Reset"] : app.buttons["Reset"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 2))
        confirm.tap()
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["True view"].waitForExistence(timeout: 2))
        XCTAssertEqual(app.buttons["True view"].value as? String, "Off, showing mirror image")
    }

    // MARK: - MVP-10 orientation

    func testRotationKeepsStateAndControls() {
        launchGranted()
        showControls()
        app.buttons["Zoom 2×"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        sleep(1)
        XCTAssertEqual(mirror.value as? String, "Zoom 2×", "MVP-10.AC2: zoom retained")
        showControls()
        XCTAssertTrue(app.buttons["Take photo"].exists)
        assertTouchTarget(app.buttons["Take photo"])
        XCTAssertTrue(app.buttons["Take photo"].isHittable, "capture reachable in landscape")
        app.buttons["Zoom 1×"].tap()
    }

    // MARK: - MVP-14 lifecycle

    func testBackgroundWhileFrozenReturnsLive() {
        launchGranted()
        showControls()
        app.buttons["Freeze frame"].tap()
        XCTAssertTrue(app.buttons["Frozen"].waitForExistence(timeout: 2))
        XCUIDevice.shared.press(.home)
        sleep(2)
        app.activate()
        XCTAssertTrue(mirror.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Frozen"].exists, "MVP-07.AC7: frozen frame discarded on background")
    }
}
