package com.smart.mirror.app

import android.Manifest
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.ui.mirror.MirrorTestTags
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA end-to-end checks with the real CameraX adapter on the emulator's virtual front camera:
 * saved-photo mirroring consistency with the preview (MVP-08.AC2, MVP-09.AC3, RK-05), JPEG/EXIF
 * (MVP-08.AC6), no app-private copies (MVP-08.AC7), brightness/keep-awake restore and camera
 * release on background (MVP-06.AC2, MVP-14.AC1).
 */
@RunWith(AndroidJUnit4::class)
class QaEndToEndTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA)).around(composeRule)

    private val startedAt = System.currentTimeMillis()

    private fun vm(): MirrorHostViewModel {
        var vm: MirrorHostViewModel? = null
        composeRule.activityRule.scenario.onActivity {
            vm = ViewModelProvider(it, MirrorHostViewModel.factory(it.application as SmartMirrorApplication))[MirrorHostViewModel::class.java]
        }
        return vm!!
    }

    private fun waitForCamera() {
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(hasTestTag(MirrorTestTags.SCREEN)).fetchSemanticsNodes().isNotEmpty() &&
                vm().state.value.cameraStatus == CameraStatus.RUNNING
        }
    }

    private fun showControls() {
        if (!vm().state.value.controlsVisible) composeRule.onNodeWithTag(MirrorTestTags.PREVIEW).performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasContentDescription("Take photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun frozenCapture_savedPhotoMatchesScreen_inMirrorAndTrueView() {
        waitForCamera()
        showControls()
        composeRule.onNodeWithContentDescription("Freeze").performClick()
        composeRule.waitUntil(5_000) { vm().frozenFrame.value != null }
        val frozen = vm().frozenFrame.value!!

        // 1. Mirror mode: the saved JPEG must equal the frozen frame as displayed (scaleX = +1).
        composeRule.onNodeWithTag(MirrorTestTags.CAPTURE).performClick()
        composeRule.waitUntil(15_000) { savedPhotos().size == 1 }

        // 2. True view (toggle while frozen): the saved JPEG must be the horizontal flip.
        composeRule.onNodeWithContentDescription("Showing mirror view. Switch to true view").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasContentDescription("Take photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MirrorTestTags.CAPTURE).performClick()
        composeRule.waitUntil(15_000) { savedPhotos().size == 2 }

        val (mirrorUri, trueUri) = savedPhotos().let { it[0] to it[1] }
        val mirrorPhoto = decode(mirrorUri)
        val truePhoto = decode(trueUri)
        assertEquals(frozen.width, mirrorPhoto.width, "frozen save keeps the on-screen size")
        assertEquals(frozen.height, mirrorPhoto.height)

        val shown = thumb(frozen, flip = false)
        val shownFlipped = thumb(frozen, flip = true)
        val asymmetry = diff(shown, shownFlipped)
        assertTrue(diff(thumb(mirrorPhoto, false), shown) < 12.0, "MVP-08.AC2: mirror-mode photo == what the frozen screen shows")
        assertTrue(diff(thumb(truePhoto, false), shownFlipped) < 12.0, "MVP-09.AC3: true-view photo == horizontal flip")
        if (asymmetry > 20.0) {
            assertTrue(diff(thumb(truePhoto, false), shown) > asymmetry / 2, "true-view photo must NOT equal the mirrored frame")
        }
    }

    @Test
    fun livePhoto_isJpegWithoutLocation_inPicturesAlbum_andNoAppCopies() {
        waitForCamera()
        showControls()
        composeRule.onNodeWithTag(MirrorTestTags.CAPTURE).performClick()
        composeRule.waitUntil(15_000) { savedPhotos().size == 1 }
        val uri = savedPhotos().single()
        val resolver = composeRule.activity.contentResolver
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("image/jpeg", it.getString(0))
            assertTrue(it.getString(1).startsWith("Pictures/Smart Mirror"), "saved in Pictures/Smart Mirror, was ${it.getString(1)}")
        }
        resolver.openInputStream(uri)!!.use { stream ->
            val exif = ExifInterface(stream)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE), "MVP-08.AC6: no location EXIF")
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        }
        val photo = decode(uri)
        val view = composeRule.activity.window.decorView
        val viewAspect = view.width.toFloat() / view.height
        val photoAspect = photo.width.toFloat() / photo.height
        assertTrue(abs(viewAspect - photoAspect) < 0.05f, "MVP-08.AC2: photo is cropped like the full-screen preview ($photoAspect vs $viewAspect)")

        // MVP-08.AC7 / MVP-13.AC5: nothing image-like left in app-private storage.
        val ctx = composeRule.activity
        val leftovers = listOf(ctx.cacheDir, ctx.filesDir, ctx.externalCacheDirs.firstOrNull()).filterNotNull()
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "yuv", "webp") }.toList() }
        assertTrue(leftovers.isEmpty(), "app-private image files remain: $leftovers")
    }

    @Test
    fun background_restoresBrightnessAndReleasesCamera_foregroundReapplies() {
        waitForCamera()
        val window = composeRule.activity.window
        assertEquals(1f, window.attributes.screenBrightness, "MVP-06.AC1: max brightness in foreground")
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0, "MVP-01.AC6")

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // onStop
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness, "MVP-06.AC2")
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, "no wake lock in background (NFR-05)")
        assertEquals(CameraStatus.IDLE, vm().state.value.cameraStatus, "MVP-14.AC1: camera released")

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForCamera()
        assertEquals(1f, composeRule.activity.window.attributes.screenBrightness, "re-applied on return")
    }

    @Test
    fun autoMaxBrightnessOff_leavesUserBrightnessAlone() {
        waitForCamera()
        composeRule.runOnUiThread { vm().onIntent(com.smart.mirror.app.presentation.mirror.MirrorIntent.SetAutoMaxBrightness(false)) }
        composeRule.waitForIdle()
        try {
            assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, composeRule.activity.window.attributes.screenBrightness, "MVP-06.AC4")
        } finally {
            composeRule.runOnUiThread { vm().onIntent(com.smart.mirror.app.presentation.mirror.MirrorIntent.ResetSettingsToDefaults) }
            composeRule.waitForIdle()
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    @After
    fun deleteSavedPhotos() {
        val resolver = composeRule.activity.contentResolver
        savedPhotos().forEach { resolver.delete(it, null, null) }
    }

    private fun savedPhotos(): List<Uri> {
        val out = mutableListOf<Uri>()
        composeRule.activity.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf("SmartMirror_%", (startedAt / 1000 - 1).toString()),
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { c -> while (c.moveToNext()) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
        return out
    }

    private fun decode(uri: Uri): Bitmap =
        assertNotNull(composeRule.activity.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) })

    /** 24x24 luminance thumbnail, optionally flipped horizontally. */
    private fun thumb(src: Bitmap, flip: Boolean): IntArray {
        val small = Bitmap.createScaledBitmap(src.copy(Bitmap.Config.ARGB_8888, false), 24, 24, true)
        return IntArray(24 * 24) { i ->
            val x = i % 24
            val y = i / 24
            val p = small.getPixel(if (flip) 23 - x else x, y)
            ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
        }
    }

    private fun diff(a: IntArray, b: IntArray): Double = a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size
}
