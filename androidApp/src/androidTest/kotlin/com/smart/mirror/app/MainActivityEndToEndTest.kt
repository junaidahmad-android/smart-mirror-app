package com.smart.mirror.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.presentation.mirror.MirrorScreen
import com.smart.mirror.app.ui.mirror.MirrorTestTags
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real app with the real CameraX adapter (the emulator's virtual front camera): launch ->
 * live mirror, tap -> controls, capture -> "Photo saved" in MediaStore, rotation keeps the camera.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEndToEndTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA))
        .around(ResetAppSettingsRule()) // fresh-install settings before the Activity starts
        .around(composeRule)

    private val startedAt = System.currentTimeMillis()

    private fun hostViewModel(): MirrorHostViewModel {
        var vm: MirrorHostViewModel? = null
        composeRule.activityRule.scenario.onActivity {
            vm = androidx.lifecycle.ViewModelProvider(it, MirrorHostViewModel.factory(it.application as SmartMirrorApplication))[MirrorHostViewModel::class.java]
        }
        return vm!!
    }

    private fun waitForScreen() {
        composeRule.waitUntil(E2eTimeouts.SCREEN_MS) {
            composeRule.onAllNodes(hasTestTag(MirrorTestTags.SCREEN)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCamera() {
        composeRule.waitUntil(E2eTimeouts.CAMERA_START_MS) { hostViewModel().state.value.cameraStatus == CameraStatus.RUNNING }
    }

    @Test
    fun launch_showsLiveMirror_tapShowsControls_captureSaves() {
        waitForScreen()
        assertEquals(MirrorScreen.MIRROR, hostViewModel().state.value.screen)
        waitForCamera()
        assertTrue(hostViewModel().state.value.previewMirrored, "mirrored by default (MVP-01.AC2)")
        composeRule.onNodeWithTag(MirrorTestTags.CONTROLS).assertDoesNotExist()

        composeRule.onNodeWithTag(MirrorTestTags.PREVIEW).performClick()
        composeRule.waitUntil(E2eTimeouts.UI_MS) {
            composeRule.onAllNodes(hasContentDescription("Take photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Take photo").assertIsDisplayed().performClick()
        composeRule.waitUntil(E2eTimeouts.SAVE_MS) {
            composeRule.onAllNodes(hasTestTag(MirrorTestTags.MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MirrorTestTags.MESSAGE).assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
            androidx.compose.ui.semantics.LiveRegionMode.Polite,
        ))
        assertEquals(1, savedPhotoIds().size, "exactly one photo saved")
    }

    @Test
    fun freeze_showsFrozenFrame_andResumes() {
        waitForScreen()
        waitForCamera()
        composeRule.onNodeWithTag(MirrorTestTags.PREVIEW).performClick()
        composeRule.waitUntil(E2eTimeouts.UI_MS) {
            composeRule.onAllNodes(hasContentDescription("Freeze") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Freeze").performClick()
        composeRule.waitUntil(E2eTimeouts.UI_MS) {
            composeRule.onAllNodes(hasTestTag(MirrorTestTags.FROZEN_IMAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MirrorTestTags.FROZEN_BADGE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Resume live mirror").performClick()
        composeRule.waitUntil(E2eTimeouts.UI_MS) {
            composeRule.onAllNodes(hasTestTag(MirrorTestTags.FROZEN_IMAGE)).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(CameraStatus.RUNNING, hostViewModel().state.value.cameraStatus)
    }

    @Test
    fun rotation_keepsActivityAndCamera_andSwitchesToSideRail() {
        waitForScreen()
        waitForCamera()
        val before = composeRule.activity
        composeRule.onNodeWithTag(MirrorTestTags.PREVIEW).performClick()
        try {
            composeRule.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            // Wait on the observable end state (configuration applied and the side rail composed),
            // not on waitForIdle(), which does not cover the rotation's async layout pass.
            composeRule.waitUntil(E2eTimeouts.UI_MS) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    composeRule.onAllNodes(hasTestTag(MirrorTestTags.SIDE_RAIL)).fetchSemanticsNodes().isNotEmpty()
            }
            assertSame(before, composeRule.activity, "rotation is handled in place, no recreation (MVP-10.AC2)")
            assertEquals(CameraStatus.RUNNING, hostViewModel().state.value.cameraStatus)
            composeRule.onNodeWithTag(MirrorTestTags.SIDE_RAIL).assertIsDisplayed()
        } finally {
            composeRule.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    @After
    fun deleteSavedPhotos() {
        val resolver = composeRule.activity.contentResolver
        savedPhotoIds().forEach { id ->
            resolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null)
        }
    }

    private fun savedPhotoIds(): List<Long> {
        val ids = mutableListOf<Long>()
        composeRule.activity.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf("SmartMirror_%", (startedAt / 1000 - 1).toString()),
            null,
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        return ids
    }
}
