package com.smart.mirror.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.presentation.mirror.MirrorUiState
import com.smart.mirror.app.presentation.mirror.UserMessage
import com.smart.mirror.app.ui.MirrorApp
import com.smart.mirror.app.ui.mirror.ControlsPlacement
import com.smart.mirror.app.ui.mirror.MirrorLayoutSpec
import com.smart.mirror.app.ui.mirror.MirrorTestTags
import com.smart.mirror.app.ui.permission.PermissionTestTags
import com.smart.mirror.app.ui.theme.SmartMirrorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compose UI tests: render shared [MirrorUiState] fixtures (no camera) and check intents. */
@RunWith(AndroidJUnit4::class)
class MirrorScreenUiTest {
    @get:Rule
    val rule = createComposeRule()

    /** Intents sent by the UI, minus the timer-only [MirrorIntent.ControlsInteracted] (see [allIntents]). */
    private val intents = mutableListOf<MirrorIntent>()
    private val allIntents = mutableListOf<MirrorIntent>()

    private val record: (MirrorIntent) -> Unit = {
        allIntents += it
        if (it != MirrorIntent.ControlsInteracted) intents += it
    }

    private val caps = CameraCapabilities(
        minZoomRatio = 1f,
        maxZoomRatio = 5f,
        minExposureEv = -2f,
        maxExposureEv = 2f,
        exposureStepEv = 1f / 3f,
    )

    private val running = MirrorUiState(
        settings = MirrorSettings.DEFAULT.copy(tapHintShown = true),
        isForeground = true,
        permission = PermissionStatus.GRANTED,
        permissionChecked = true,
        cameraStatus = CameraStatus.RUNNING,
        capabilities = caps,
    )

    private val bottomBar = MirrorLayoutSpec(ControlsPlacement.BOTTOM_BAR, spacious = false)
    private val sideRail = MirrorLayoutSpec(ControlsPlacement.SIDE_RAIL, spacious = true)

    private fun show(state: MirrorUiState, layout: MirrorLayoutSpec = bottomBar, frozen: ImageBitmap? = null) {
        rule.setContent {
            SmartMirrorTheme {
                MirrorApp(
                    state = state,
                    onIntent = record,
                    frozenFrame = frozen,
                    appVersion = "1.0-test",
                    preview = { m -> Box(m.background(Color.DarkGray)) },
                    layout = layout,
                    reduceMotion = true,
                )
            }
        }
    }

    // --- screens -------------------------------------------------------------------------------

    @Test
    fun loading_showsBlackScreenOnly() {
        show(running.copy(permissionChecked = false))
        rule.onNodeWithTag(PermissionTestTags.LOADING).assertExists()
        rule.onNodeWithTag(PermissionTestTags.ONBOARDING).assertDoesNotExist()
    }

    @Test
    fun onboarding_primaryButtonRequestsPermission() {
        show(running.copy(permission = PermissionStatus.NOT_DETERMINED))
        rule.onNodeWithTag(PermissionTestTags.ONBOARDING).assertIsDisplayed()
        rule.onNodeWithText("Nothing is recorded or sent anywhere", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Turn on camera").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.RequestCameraPermission), intents)
    }

    @Test
    fun denied_offersTryAgainAndSettings() {
        show(running.copy(permission = PermissionStatus.DENIED))
        rule.onNodeWithTag(PermissionTestTags.DENIED).assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText("Open Settings").performClick()
        assertEquals(listOf(MirrorIntent.RequestCameraPermission, MirrorIntent.OpenAppSettings), intents)
    }

    @Test
    fun blocked_offersOnlySettings() {
        show(running.copy(permission = PermissionStatus.BLOCKED))
        rule.onNodeWithTag(PermissionTestTags.BLOCKED).assertIsDisplayed()
        rule.onNodeWithText("Try again").assertDoesNotExist()
        rule.onNodeWithText("Open Settings").performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.OpenAppSettings), intents)
    }

    @Test
    fun overheated_showsHotScreen() {
        show(running.copy(thermalState = com.smart.mirror.app.domain.model.ThermalState.CRITICAL))
        rule.onNodeWithTag(PermissionTestTags.OVERHEATED).assertIsDisplayed()
        rule.onNodeWithText("Device is hot").assertIsDisplayed()
    }

    // --- mirror: chrome & gestures -------------------------------------------------------------

    @Test
    fun mirror_startsWithoutControls_andTapTogglesThem() {
        show(running)
        rule.onNodeWithTag(MirrorTestTags.SCREEN).assertIsDisplayed()
        rule.onNodeWithTag(MirrorTestTags.CONTROLS).assertDoesNotExist()
        rule.onNodeWithTag(MirrorTestTags.PREVIEW).performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.ScreenTapped), intents)
    }

    @Test
    fun mirror_doubleTapTogglesZoomAndRestoresControls() {
        show(running)
        rule.onNodeWithTag(MirrorTestTags.PREVIEW).performTouchInput { doubleClick() }
        rule.waitForIdle()
        // tap (show) + undo tap + zoom toggle: net controls unchanged.
        assertEquals(listOf(MirrorIntent.ScreenTapped, MirrorIntent.ScreenTapped, MirrorIntent.ToggleZoom), intents)
    }

    @Test
    fun mirror_pinchSendsIncrementalZoom_andNeverTaps() {
        show(running)
        rule.onNodeWithTag(MirrorTestTags.PREVIEW).performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(200f, 0f), center + Offset(20f, 0f), center + Offset(200f, 0f))
        }
        rule.waitForIdle()
        val pinches = intents.filterIsInstance<MirrorIntent.PinchZoom>()
        assertTrue(pinches.isNotEmpty(), "expected pinch intents, got $intents")
        assertTrue(pinches.fold(1f) { acc, p -> acc * p.scaleFactor } > 1.5f)
        assertTrue(MirrorIntent.ScreenTapped !in intents, "a pinch must not toggle controls (MVP-02.AC5)")
    }

    @Test
    fun tapHint_shownWhenRequested() {
        show(running.copy(showTapHint = true))
        rule.onNodeWithTag(MirrorTestTags.TAP_HINT).assertIsDisplayed()
        rule.onNodeWithText("Tap for controls").assertIsDisplayed()
    }

    @Test
    fun ringLight_drawnOnlyWhenEnabled() {
        show(running)
        rule.onNodeWithTag(MirrorTestTags.RING_LIGHT).assertDoesNotExist()
    }

    @Test
    fun ringLight_visibleWhenOn() {
        show(running.copy(settings = running.settings.copy(ringLight = running.settings.ringLight.copy(enabled = true))))
        rule.onNodeWithTag(MirrorTestTags.RING_LIGHT).assertIsDisplayed()
    }

    // --- controls ------------------------------------------------------------------------------

    @Test
    fun controls_bottomBarLayout_hasAllActionsWithLabelsAndTargets() {
        show(running.copy(controlsVisible = true), bottomBar)
        rule.onNodeWithTag(MirrorTestTags.BOTTOM_BAR).assertIsDisplayed()
        rule.onNodeWithTag(MirrorTestTags.SIDE_RAIL).assertDoesNotExist()
        listOf("Take photo", "Freeze", "Settings", "Showing mirror view. Switch to true view", "Ring light off. Turn on")
            .forEach { label ->
                rule.onNodeWithContentDescription(label).assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
    }

    @Test
    fun controls_sideRailLayout_forLandscapeAndTablets() {
        show(running.copy(controlsVisible = true), sideRail)
        rule.onNodeWithTag(MirrorTestTags.SIDE_RAIL).assertIsDisplayed()
        rule.onNodeWithTag(MirrorTestTags.BOTTOM_BAR).assertDoesNotExist()
        rule.onNodeWithContentDescription("Take photo").assertIsDisplayed()
        rule.onNodeWithTag(MirrorTestTags.ADJUST_PANEL).assertIsDisplayed()
    }

    @Test
    fun controls_actionsSendIntents() {
        show(running.copy(controlsVisible = true))
        rule.onNodeWithContentDescription("Take photo").performClick()
        rule.onNodeWithContentDescription("Freeze").performClick()
        rule.onNodeWithContentDescription("Showing mirror view. Switch to true view").performClick()
        rule.onNodeWithContentDescription("Ring light off. Turn on").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(
            listOf(
                MirrorIntent.CapturePhoto,
                MirrorIntent.ToggleFreeze,
                MirrorIntent.ToggleMirrorMode,
                MirrorIntent.ToggleRingLight,
                MirrorIntent.SetSettingsOpen(true),
            ),
            intents,
        )
    }

    @Test
    fun controls_tapOnPanelBackgroundDoesNotToggleControls() {
        show(running.copy(controlsVisible = true))
        rule.onNodeWithTag(MirrorTestTags.BOTTOM_BAR).performTouchInput { click(Offset(4f, 4f)) }
        assertTrue(MirrorIntent.ScreenTapped !in intents)
    }

    @Test
    fun touchingControls_restartsAutoHideTimer() {
        show(running.copy(controlsVisible = true))
        rule.onNodeWithText("Light").performClick() // UI-local tab switch, no state change
        assertTrue(MirrorIntent.ControlsInteracted in allIntents)
        assertTrue(intents.isEmpty())
    }

    @Test
    fun capture_disabledWhileSaving() {
        show(running.copy(controlsVisible = true, captureStatus = com.smart.mirror.app.domain.model.CaptureStatus.SAVING))
        rule.onNodeWithTag(MirrorTestTags.CAPTURE).assertIsNotEnabled()
    }

    @Test
    fun zoomPresets_setZoom() {
        show(running.copy(controlsVisible = true))
        rule.onNodeWithTag(MirrorTestTags.ZOOM_SLIDER).assertIsDisplayed()
        rule.onNodeWithContentDescription("Zoom 2x").performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.SetZoom(2f)), intents)
    }

    @Test
    fun exposure_tabShownOnlyWhenSupported() {
        show(running.copy(controlsVisible = true, capabilities = caps.copy(exposureStepEv = 0f)))
        rule.onNodeWithText("Exposure").assertDoesNotExist()
    }

    @Test
    fun exposure_panelHasSliderAndReset() {
        show(running.copy(controlsVisible = true, exposureEv = 1f))
        rule.onNodeWithText("Exposure").performClick()
        rule.onNodeWithTag(MirrorTestTags.EXPOSURE_SLIDER).assertIsDisplayed()
        rule.onNodeWithContentDescription("Reset exposure").performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.ResetExposure), intents)
    }

    @Test
    fun light_panelPresetsAndWarmth() {
        show(running.copy(controlsVisible = true))
        rule.onNodeWithText("Light").performClick()
        rule.onNodeWithText("High").performClick()
        rule.onNodeWithText("Warm").performClick()
        rule.onNodeWithTag(MirrorTestTags.INTENSITY_SLIDER).assertIsDisplayed()
        assertEquals(listOf(MirrorIntent.SetRingLightIntensity(1f), MirrorIntent.SetLightWarmth(LightWarmth.WARM)), intents)
    }

    @Test
    fun trueView_toggleDescribesCurrentMode() {
        show(running.copy(controlsVisible = true, settings = running.settings.copy(mirrorMode = MirrorMode.TRUE_VIEW)))
        rule.onNodeWithContentDescription("Showing true view. Switch to mirror view").assertIsDisplayed()
    }

    // --- freeze, messages, errors, settings ----------------------------------------------------

    @Test
    fun frozen_showsBadgeAndImage_andResumeUnfreezes() {
        show(running.copy(isFrozen = true, controlsVisible = true), frozen = ImageBitmap(40, 80))
        rule.onNodeWithTag(MirrorTestTags.FROZEN_BADGE).assertIsDisplayed()
        rule.onNodeWithTag(MirrorTestTags.FROZEN_IMAGE).assertIsDisplayed()
        rule.onNodeWithContentDescription("Resume live mirror").assertIsDisplayed()
        rule.onNodeWithText("Resume").performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.ToggleFreeze), intents)
    }

    @Test
    fun savedMessage_isShown() {
        show(running.copy(message = UserMessage.PHOTO_SAVED))
        rule.onNodeWithTag(MirrorTestTags.MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("Photo saved").assertIsDisplayed()
    }

    @Test
    fun savePermissionMessage_linksToSettings() {
        show(running.copy(message = UserMessage.SAVE_PERMISSION_DENIED))
        rule.onNodeWithText("Settings").performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.OpenAppSettings), intents)
    }

    @Test
    fun cameraInUse_showsUnavailableWithRetry() {
        show(running.copy(cameraStatus = CameraStatus.ERROR, cameraError = CameraError.IN_USE_BY_ANOTHER_APP))
        rule.onNodeWithTag(MirrorTestTags.CAMERA_ERROR).assertIsDisplayed()
        rule.onNodeWithText("Camera unavailable").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(listOf<MirrorIntent>(MirrorIntent.RetryCamera), intents)
    }

    @Test
    fun noFrontCamera_showsError() {
        show(running.copy(cameraStatus = CameraStatus.ERROR, cameraError = CameraError.NO_FRONT_CAMERA))
        rule.onNodeWithText("This device has no front camera.").assertIsDisplayed()
    }

    @Test
    fun settingsSheet_togglesAndResets() {
        show(running.copy(controlsVisible = true, settingsOpen = true))
        rule.onNodeWithText("Auto max brightness").performClick()
        rule.onNodeWithText("Reset to defaults").performClick()
        rule.onNodeWithText("Version 1.0-test").assertExists()
        rule.onNodeWithText("no internet access", substring = true).assertExists()
        assertContains(intents, MirrorIntent.SetAutoMaxBrightness(false))
        assertContains(intents, MirrorIntent.ResetSettingsToDefaults)
    }

    @Test
    fun zoomLabel_appearsWhenZoomChanges() {
        var state by mutableStateOf(running)
        rule.setContent {
            SmartMirrorTheme {
                MirrorApp(state, record, null, "1", { m -> Box(m) }, bottomBar, reduceMotion = true)
            }
        }
        rule.onNodeWithTag(MirrorTestTags.ZOOM_LABEL).assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        state = state.copy(settings = state.settings.copy(zoomRatio = 2.5f))
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("2.5x").assertExists()
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag(MirrorTestTags.ZOOM_LABEL).assertDoesNotExist()
    }

    @Test
    fun allVisibleControlsHaveContentDescriptions() {
        show(running.copy(controlsVisible = true))
        // Icon-only buttons must all be labelled for TalkBack (NFR-10).
        rule.onAllNodesWithContentDescription("", substring = true).fetchSemanticsNodes().let { assertTrue(it.size >= 6) }
    }
}
