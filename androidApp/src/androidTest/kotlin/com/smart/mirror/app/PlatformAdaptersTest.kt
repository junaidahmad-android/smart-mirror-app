package com.smart.mirror.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.smart.mirror.app.domain.model.CapturedPhoto
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.SaveOutcome
import com.smart.mirror.app.platform.brightness.WindowBrightnessController
import com.smart.mirror.app.platform.camera.CapturedPhotoBuffer
import com.smart.mirror.app.platform.camera.PhotoTransforms
import com.smart.mirror.app.platform.permission.ActivityPermissionRequester
import com.smart.mirror.app.platform.permission.AndroidPermissionChecker
import com.smart.mirror.app.platform.storage.MediaStorePhotoSaver
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Instrumented tests for the Android port adapters (real framework, no camera needed). */
@RunWith(AndroidJUnit4::class)
class PlatformAdaptersTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun brightness_appliesAndRestoresWindowOverride_andKeepScreenOn() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = WindowBrightnessController()
                controller.attach(activity.window)
                controller.applyBrightness(1f)
                controller.setKeepScreenOn(true)
                assertEquals(1f, activity.window.attributes.screenBrightness)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

                controller.restoreBrightness()
                controller.setKeepScreenOn(false)
                assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, activity.window.attributes.screenBrightness)
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @Test
    fun brightness_reappliedToNewWindowAfterRecreation() {
        val controller = WindowBrightnessController()
        controller.applyBrightness(0.8f)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                controller.attach(activity.window)
                assertEquals(0.8f, activity.window.attributes.screenBrightness)
                controller.detach(activity.window)
            }
        }
    }

    @Test
    fun permissionChecker_reportsGranted() {
        val checker = AndroidPermissionChecker(context, ActivityPermissionRequester())
        assertEquals(PermissionStatus.GRANTED, checker.cameraPermissionStatus())
        var result: PermissionStatus? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            checker.requestCameraPermission { result = it }
        }
        assertEquals(PermissionStatus.GRANTED, result)
    }

    @Test
    fun photoSaver_writesJpegToMediaStore_andDropsBufferedBytes() {
        val buffer = CapturedPhotoBuffer()
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val key = buffer.put(PhotoTransforms.encodeJpeg(bitmap))
        val saver = MediaStorePhotoSaver(context, ActivityPermissionRequester(), buffer)

        val latch = CountDownLatch(1)
        var outcome: SaveOutcome? = null
        saver.save(CapturedPhoto(key, 64, 32, mirrored = true)) {
            outcome = it
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val saved = assertIs<SaveOutcome.Saved>(outcome)
        assertEquals(0, buffer.size, "no copy of the photo may remain in the app (MVP-08.AC7)")

        val uri = Uri.parse(saved.uri)
        try {
            val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            assertNotNull(decoded)
            assertEquals(64, decoded.width)
        } finally {
            context.contentResolver.delete(uri, null, null)
            saver.release()
        }
    }

    @Test
    fun photoSaver_unknownPhoto_failsExactlyOnce() {
        val saver = MediaStorePhotoSaver(context, ActivityPermissionRequester(), CapturedPhotoBuffer())
        val latch = CountDownLatch(1)
        var calls = 0
        saver.save(CapturedPhoto("memory://capture/missing", 1, 1, mirrored = false)) {
            calls++
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, calls)
        saver.release()
    }

    @Test
    fun photoTransforms_mirrorFlipsHorizontally_andCropsToView() {
        // Left half red, right half blue.
        val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        for (x in 0 until 100) for (y in 0 until 50) src.setPixel(x, y, if (x < 50) Color.RED else Color.BLUE)

        val mirrored = PhotoTransforms.toDisplayedImage(src, 0, 100, 50, 1f, mirror = true)
        assertEquals(Color.BLUE, mirrored.getPixel(5, 25))
        assertEquals(Color.RED, mirrored.getPixel(95, 25))

        // Rotated 90 degrees for a portrait 1:2 view: output is upright 50x100.
        val rotated = PhotoTransforms.toDisplayedImage(src, 90, 50, 100, 1f, mirror = false)
        assertEquals(50, rotated.width)
        assertEquals(100, rotated.height)

        // Square view: centre crop of the 100x50 image -> 50x50.
        val cropped = PhotoTransforms.toDisplayedImage(src, 0, 300, 300, 1f, mirror = false)
        assertEquals(50, cropped.width)
        assertEquals(50, cropped.height)
    }
}
