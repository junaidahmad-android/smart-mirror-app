package com.smart.mirror.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smart.mirror.app.domain.model.CameraCapabilities
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureStatus
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
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA additions (docs/TEST_PLAN.md): accessibility (NFR-10), RTL (NFR-12), settings/about content
 * (MVP-15), privacy manifest (MVP-13.AC2), transient labels (MVP-09.AC2), disabled states.
 * Rendering uses shared [MirrorUiState] fixtures; no camera.
 */
@RunWith(AndroidJUnit4::class)
class QaMirrorUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val intents = mutableListOf<MirrorIntent>()
    private val record: (MirrorIntent) -> Unit = { if (it != MirrorIntent.ControlsInteracted) intents += it }

    private val caps = CameraCapabilities(1f, 5f, -2f, 2f, 1f / 3f)
    private val running = MirrorUiState(
        settings = MirrorSettings.DEFAULT.copy(tapHintShown = true),
        isForeground = true,
        permission = PermissionStatus.GRANTED,
        permissionChecked = true,
        cameraStatus = CameraStatus.RUNNING,
        capabilities = caps,
    )
    private val bottomBar = MirrorLayoutSpec(ControlsPlacement.BOTTOM_BAR, spacious = false)
    private val tabletRail = MirrorLayoutSpec(ControlsPlacement.SIDE_RAIL, spacious = true)

    private fun show(
        state: MirrorUiState,
        layout: MirrorLayoutSpec = bottomBar,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides direction,
                LocalDensity provides Density(base.density, fontScale),
            ) {
                SmartMirrorTheme {
                    MirrorApp(state, record, null, "1.0-qa", { m -> Box(m.background(Color.DarkGray)) }, layout, reduceMotion = true)
                }
            }
        }
    }

    // --- MVP-13.AC2 / NFR-11: privacy manifest ------------------------------------------------------

    @Test
    fun installedPackageDoesNotRequestInternet() {
        val requested = requestedPermissions()
        assertFalse(Manifest.permission.INTERNET in requested, "MVP-13.AC2: $requested")
        assertContains(requested, Manifest.permission.CAMERA)
    }

    /** QA-BUG-05: androidx.media3:media3-common (transitive) merges ACCESS_NETWORK_STATE into the app. */
    @Test
    fun installedPackageRequestsNoNetworkPermissionsAtAll() {
        val network = requestedPermissions().filter { "NETWORK" in it || "WIFI" in it || it.endsWith("INTERNET") }
        assertTrue(network.isEmpty(), "NFR-11: no network-related permissions expected, found $network")
    }

    private fun requestedPermissions(): List<String> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions?.toList().orEmpty()
    }

    // --- MVP-15: settings / about ------------------------------------------------------------------

    @Test
    fun settingsSheet_showsEveryMvp15Item_andLicensesOpen() {
        show(running.copy(controlsVisible = true, settingsOpen = true))
        rule.onNodeWithText("Auto max brightness").assertExists()
        rule.onNodeWithText("Reset to defaults").assertExists()
        rule.onNodeWithText("Camera frames are never stored or sent anywhere", substring = true).assertExists()
        rule.onNodeWithText("Version 1.0-qa").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Contact support").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open-source licenses").performScrollTo().performClick()
        rule.onNodeWithText("Apache License", substring = true).assertIsDisplayed()
    }

    /** QA-BUG-08: the support address is still the template placeholder (MVP-15.AC1 contact/support). */
    @Ignore("QA-BUG-08: support_email is support@example.com (placeholder; iOS uses a different address)")
    @Test
    fun supportAddressIsNotAPlaceholder() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val email = ctx.getString(R.string.support_email)
        assertFalse(email.endsWith("@example.com"), "support address is a placeholder: $email")
    }

    // --- NFR-10: accessibility ----------------------------------------------------------------------

    @Test
    fun previewOffersAScreenReaderActionInsteadOfTapGesture() {
        show(running)
        rule.onNodeWithContentDescription("Mirror preview").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(intents == listOf<MirrorIntent>(MirrorIntent.ScreenTapped), "$intents")
    }

    @Test
    fun zoomPresetsAreTheButtonAlternativeToPinchAndDoubleTap() {
        show(running.copy(controlsVisible = true))
        rule.onNodeWithContentDescription("Zoom 2x").assertHeightIsAtLeast(48.dp).performClick()
        assertContains(intents, MirrorIntent.SetZoom(2f))
    }

    @Test
    fun tabletSideRail_hasLargerTouchTargets() {
        show(running.copy(controlsVisible = true), layout = tabletRail)
        rule.onNodeWithTag(MirrorTestTags.SIDE_RAIL).assertIsDisplayed()
        for (label in listOf("Freeze", "Settings", "Ring light off. Turn on", "Showing mirror view. Switch to true view")) {
            rule.onNodeWithContentDescription(label).assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        }
        rule.onNodeWithTag(MirrorTestTags.CAPTURE).assertWidthIsAtLeast(80.dp)
    }

    @Test
    fun largestFontScale_primaryActionsStayReachable() {
        show(running.copy(permission = PermissionStatus.NOT_DETERMINED), fontScale = 2f)
        rule.onNodeWithText("Turn on camera").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun largestFontScale_controlsAndPanelStayReachable() {
        show(running.copy(controlsVisible = true), fontScale = 2f)
        rule.onNodeWithTag(MirrorTestTags.CAPTURE).assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        rule.onNodeWithText("Light").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun thermalNoticeIsAnnouncedAndDismissable() {
        show(running.copy(message = UserMessage.THERMAL_THROTTLED))
        rule.onNodeWithText("Device is warm", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MirrorTestTags.MESSAGE).assertExists()
    }

    // --- NFR-12: RTL --------------------------------------------------------------------------------

    @Test
    fun rtlLayout_rendersAllControls_andKeepsMirrorSemantics() {
        show(running.copy(controlsVisible = true), direction = LayoutDirection.Rtl)
        for (label in listOf("Freeze", "Take photo", "Settings", "Ring light off. Turn on", "Showing mirror view. Switch to true view")) {
            rule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        rule.onNodeWithContentDescription("Showing mirror view. Switch to true view").performClick()
        assertContains(intents, MirrorIntent.ToggleMirrorMode)
    }

    // --- MVP-09.AC2 transient mode label ------------------------------------------------------------

    @Test
    fun modeLabelAppearsOnToggleAndDisappears() {
        var state by mutableStateOf(running)
        rule.setContent {
            SmartMirrorTheme { MirrorApp(state, record, null, "1", { m -> Box(m) }, bottomBar, reduceMotion = true) }
        }
        rule.mainClock.autoAdvance = false
        state = state.copy(settings = state.settings.copy(mirrorMode = MirrorMode.TRUE_VIEW))
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag(MirrorTestTags.MODE_LABEL).assertExists()
        rule.onNodeWithText("True view").assertExists()
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag(MirrorTestTags.MODE_LABEL).assertDoesNotExist()
    }

    // --- Disabled / error states (MVP-12.AC4/AC5) ---------------------------------------------------

    @Test
    fun captureAndFreezeDisabledUntilCameraRuns() {
        show(running.copy(cameraStatus = CameraStatus.STARTING, controlsVisible = true))
        rule.onNodeWithTag(MirrorTestTags.CAPTURE).assertIsNotEnabled()
        rule.onNodeWithContentDescription("Freeze").assertIsNotEnabled()
    }

    @Test
    fun captureReEnabledWhenIdleWhileFrozen() {
        show(running.copy(isFrozen = true, controlsVisible = true, captureStatus = CaptureStatus.IDLE))
        rule.onNodeWithTag(MirrorTestTags.CAPTURE).assertIsEnabled().performClick()
        assertContains(intents, MirrorIntent.CapturePhoto)
    }

    @Test
    fun genericCameraErrorOffersRetry() {
        show(running.copy(cameraStatus = CameraStatus.ERROR, cameraError = CameraError.UNKNOWN))
        rule.onNodeWithTag(MirrorTestTags.CAMERA_ERROR).assertIsDisplayed()
        val retry = rule.onNodeWithText("Retry")
        val touch = retry.fetchSemanticsNode().touchBoundsInRoot
        with(rule.density) { assertTrue(touch.height >= 48.dp.toPx() - 1, "NFR-10: 48dp touch target, was ${touch.height.toDp()}") }
        retry.performClick()
        assertContains(intents, MirrorIntent.RetryCamera)
    }

    @Test
    fun overheatedScreenHasNoControlsAndNoPreview() {
        show(running.copy(thermalState = com.smart.mirror.app.domain.model.ThermalState.CRITICAL, controlsVisible = true))
        rule.onNodeWithTag(PermissionTestTags.OVERHEATED).assertIsDisplayed()
        rule.onNodeWithTag(MirrorTestTags.PREVIEW).assertDoesNotExist()
        rule.onNodeWithTag(MirrorTestTags.CONTROLS).assertDoesNotExist()
    }
}
