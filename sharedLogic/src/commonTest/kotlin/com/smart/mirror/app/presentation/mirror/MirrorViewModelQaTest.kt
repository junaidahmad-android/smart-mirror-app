package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.data.settings.InMemoryKeyValueStore
import com.smart.mirror.app.data.settings.KeyValueSettingsRepository
import com.smart.mirror.app.data.settings.KeyValueStore
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureRequest
import com.smart.mirror.app.domain.model.CaptureStatus
import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.MirrorSettings
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.ThermalState
import com.smart.mirror.app.fakes.FakeBrightness
import com.smart.mirror.app.fakes.FakeCameraController
import com.smart.mirror.app.fakes.FakePermissions
import com.smart.mirror.app.fakes.FakePhotoSaver
import com.smart.mirror.app.fakes.FakeProcessingPipeline
import com.smart.mirror.app.fakes.TEST_CAPS
import com.smart.mirror.app.processing.PipelineCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Counts writes so debouncing can be verified. */
private class CountingStore(private val delegate: KeyValueStore = InMemoryKeyValueStore()) : KeyValueStore by delegate {
    var writes = 0
    override fun putFloat(key: String, value: Float) { writes++; delegate.putFloat(key, value) }
}

/**
 * QA ViewModel scenarios (docs/TEST_PLAN.md): freeze+capture interplay, rapid intents and lifecycle
 * cycles, thermal throttling side effects, settings persistence round trips, Swift-bridge
 * subscription cancellation. Tests marked @Ignore("QA-BUG-xx") currently FAIL (docs/QA_REPORT.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MirrorViewModelQaTest {
    private val camera = FakeCameraController()
    private val brightness = FakeBrightness()
    private val saver = FakePhotoSaver()
    private val permissions = FakePermissions()
    private val pipeline = FakeProcessingPipeline()
    private val store = CountingStore()

    private fun TestScope.viewModel(repo: KeyValueSettingsRepository = KeyValueSettingsRepository(store)) = MirrorViewModel(
        ports = MirrorPorts(camera, brightness, saver, permissions),
        settingsRepository = repo,
        pipeline = PipelineCoordinator(pipeline),
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
    )

    private fun TestScope.runningViewModel(repo: KeyValueSettingsRepository = KeyValueSettingsRepository(store)) = viewModel(repo).apply {
        onIntent(MirrorIntent.ScreenStarted)
        camera.currentListener!!.onCameraStarted(TEST_CAPS)
        runCurrent()
    }

    // --- Freeze + capture interplay (MVP-07 / MVP-08 / MVP-09.AC3) --------------------------------

    @Test
    fun captureWhileFrozenSavesTheFrozenFrameInTheCurrentMirrorMode() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ToggleFreeze)
        vm.onIntent(MirrorIntent.ToggleMirrorMode) // switch to true view while frozen
        vm.onIntent(MirrorIntent.CapturePhoto)
        assertEquals(CaptureRequest(mirrored = false, fromFrozenFrame = true), camera.pendingCapture!!.first)
        camera.completeCapture()
        runCurrent()
        assertEquals(UserMessage.PHOTO_SAVED, vm.currentState.message)
        assertTrue(vm.currentState.isFrozen, "still frozen after saving")
        assertEquals(true, camera.frozen)
        assertTrue(vm.currentState.controlsVisible)
        vm.close()
    }

    @Test
    fun liveCaptureRequestsFollowTheMode() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.CapturePhoto)
        assertEquals(CaptureRequest(mirrored = true, fromFrozenFrame = false), camera.pendingCapture!!.first)
        camera.completeCapture()
        runCurrent()
        vm.onIntent(MirrorIntent.SetMirrorMode(MirrorMode.TRUE_VIEW))
        vm.onIntent(MirrorIntent.CapturePhoto)
        assertEquals(CaptureRequest(mirrored = false, fromFrozenFrame = false), camera.pendingCapture!!.first)
        vm.close()
    }

    @Test
    fun rapidCaptureTapsProduceExactlyOneCaptureAndFreezeIsBlockedMeanwhile() = runTest {
        val vm = runningViewModel()
        repeat(10) { vm.onIntent(MirrorIntent.CapturePhoto) }
        vm.onIntent(MirrorIntent.ToggleFreeze)
        assertEquals(1, camera.calls.count { it == "capture" })
        assertFalse(vm.currentState.isFrozen)
        assertEquals(CaptureStatus.CAPTURING, vm.currentState.captureStatus)
        camera.completeCapture()
        runCurrent()
        assertEquals(CaptureStatus.IDLE, vm.currentState.captureStatus)
        assertEquals(1, saver.saved.size)
        vm.close()
    }

    @Test
    fun resumingDuringAFrozenCaptureStillCompletesTheSave() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ToggleFreeze)
        vm.onIntent(MirrorIntent.CapturePhoto)
        vm.onIntent(MirrorIntent.ToggleFreeze)
        assertEquals(false, camera.frozen)
        camera.completeCapture()
        runCurrent()
        assertEquals(UserMessage.PHOTO_SAVED, vm.currentState.message)
        vm.close()
    }

    @Test
    fun backgroundingWhileFrozenDiscardsTheFrameAndReturnsLive() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ToggleFreeze)
        vm.onIntent(MirrorIntent.PinchZoom(3f))
        vm.onIntent(MirrorIntent.ScreenStopped)
        assertEquals(false, camera.frozen, "adapter told to drop the frozen frame before stop")
        assertEquals("stop", camera.calls.last())
        vm.onIntent(MirrorIntent.ScreenStarted)
        camera.currentListener!!.onCameraStarted(TEST_CAPS)
        assertFalse(vm.currentState.isFrozen, "MVP-07.AC7")
        assertEquals(1f, vm.currentState.frozenImageScale)
        vm.close()
    }

    /** QA-BUG-06: MVP-08.AC4 feedback (toast, haptic, screen-reader announcement) for EVERY successful save. */

    @Test
    fun secondSaveWithinMessageWindowGetsFreshFeedback() = runTest {
        val vm = runningViewModel()
        val messages = mutableListOf<UserMessage?>()
        val sub = vm.observeState { messages += it.message }
        vm.onIntent(MirrorIntent.CapturePhoto); camera.completeCapture(); runCurrent()
        advanceTimeBy(2_000)
        vm.onIntent(MirrorIntent.CapturePhoto); camera.completeCapture(); runCurrent()
        advanceTimeBy(1_000)
        assertEquals(UserMessage.PHOTO_SAVED, vm.currentState.message, "toast should still show 1 s after the 2nd save")
        val transitionsToSaved = messages.zipWithNext().count { (a, b) -> a != UserMessage.PHOTO_SAVED && b == UserMessage.PHOTO_SAVED } +
            (if (messages.firstOrNull() == UserMessage.PHOTO_SAVED) 1 else 0)
        assertEquals(2, transitionsToSaved, "platform UIs key feedback off message changes; 2 saves need 2 changes")
        sub.cancel()
        vm.close()
    }

    // --- Rapid intents / lifecycle cycles (MVP-14.AC3) --------------------------------------------

    @Test
    fun fiftyBackgroundForegroundCyclesStayBalanced() = runTest {
        val vm = runningViewModel()
        repeat(50) {
            vm.onIntent(MirrorIntent.ScreenStopped)
            assertFalse(brightness.boosted, "MVP-06.AC2: restored on every background")
            assertFalse(brightness.screenKeptOn)
            assertNull(pipeline.currentListener)
            vm.onIntent(MirrorIntent.ScreenStarted)
            camera.currentListener!!.onCameraStarted(TEST_CAPS)
            assertTrue(brightness.boosted)
            assertTrue(brightness.screenKeptOn)
        }
        assertEquals(51, camera.calls.count { it == "start" })
        assertEquals(50, camera.calls.count { it == "stop" })
        camera.calls.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "start/stop strictly alternate: ${camera.calls}") }
        vm.close()
        assertFalse(brightness.boosted)
        assertEquals("stop", camera.calls.last())
    }

    @Test
    fun rapidStartStopBeforeTheCameraReportsNeverDoubleStarts() = runTest {
        val vm = viewModel()
        repeat(20) {
            vm.onIntent(MirrorIntent.ScreenStarted)
            vm.onIntent(MirrorIntent.ScreenStarted) // iOS sends it from onAppear AND scenePhase
            vm.onIntent(MirrorIntent.ScreenStopped)
        }
        assertEquals(20, camera.calls.count { it == "start" })
        assertEquals(20, camera.calls.count { it == "stop" })
        assertEquals(CameraStatus.IDLE, vm.currentState.cameraStatus)
        vm.close()
    }

    /** QA-BUG-10: shared layer trusts adapters to drop stale callbacks; a late onCameraStarted after stop() wedges the camera. */

    @Test
    fun lateCameraStartedAfterStopDoesNotWedgeTheCamera() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.ScreenStarted)
        val listener = camera.currentListener!!
        vm.onIntent(MirrorIntent.ScreenStopped)
        listener.onCameraStarted(TEST_CAPS) // late callback from the stopped session
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(2, camera.calls.count { it == "start" }, "returning to foreground must (re)start the camera: ${camera.calls}")
        vm.close()
    }

    // --- Thermal throttling side effects (NFR-06) --------------------------------------------------

    @Test
    fun thermalThrottlingDrivesPortsAndRecovers() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.SERIOUS))
        assertEquals(30, camera.maxFps)
        assertEquals(0.8f, brightness.level)
        assertEquals(UserMessage.THERMAL_THROTTLED, vm.currentState.message)
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.NOMINAL))
        assertEquals(60, camera.maxFps)
        assertEquals(1f, brightness.level)

        vm.onIntent(MirrorIntent.ToggleFreeze)
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.CRITICAL))
        assertEquals(MirrorScreen.OVERHEATED, vm.currentState.screen)
        assertFalse(vm.currentState.isFrozen)
        assertFalse(brightness.boosted)
        assertEquals("stop", camera.calls.last())
        vm.close()
    }

    @Test
    fun coolingDownWhileBackgroundedWaitsForForeground() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.CRITICAL))
        vm.onIntent(MirrorIntent.ScreenStopped)
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.NOMINAL))
        assertEquals(listOf("start", "stop"), camera.calls, "no camera start while in background")
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(listOf("start", "stop", "start"), camera.calls)
        vm.close()
    }

    // --- Permission revoked while backgrounded (MVP-12.AC6 / AC3) ---------------------------------

    @Test
    fun permissionRevokedInBackgroundShowsDeniedStateThenRecoversFromSettings() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ScreenStopped)
        permissions.status = PermissionStatus.BLOCKED
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(MirrorScreen.PERMISSION_BLOCKED, vm.currentState.screen)
        assertEquals(listOf("start", "stop"), camera.calls)
        assertFalse(brightness.boosted)
        assertFalse(brightness.screenKeptOn)

        vm.onIntent(MirrorIntent.OpenAppSettings)
        assertEquals(1, permissions.settingsOpened)
        vm.onIntent(MirrorIntent.ScreenStopped)
        permissions.status = PermissionStatus.GRANTED
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(MirrorScreen.MIRROR, vm.currentState.screen)
        assertEquals("start", camera.calls.last(), "MVP-12.AC3: preview starts automatically on return")
        vm.close()
    }

    @Test
    fun deniedPromptDoesNotStartCameraAndTryAgainReprompts() = runTest {
        permissions.status = PermissionStatus.NOT_DETERMINED
        permissions.requestResult = PermissionStatus.DENIED
        val vm = viewModel()
        vm.onIntent(MirrorIntent.ScreenStarted)
        vm.onIntent(MirrorIntent.RequestCameraPermission)
        assertEquals(MirrorScreen.PERMISSION_DENIED, vm.currentState.screen)
        assertTrue(camera.calls.isEmpty())
        permissions.requestResult = PermissionStatus.GRANTED
        vm.onIntent(MirrorIntent.RequestCameraPermission)
        assertEquals(2, permissions.requests)
        assertEquals(listOf("start"), camera.calls, "MVP-12.AC2: no restart needed")
        vm.close()
    }

    // --- Settings persistence round trip (MVP-11) --------------------------------------------------

    @Test
    fun settingsSurviveAKillAndRelaunchButExposureDoesNot() = runTest {
        val first = runningViewModel()
        first.onIntent(MirrorIntent.SetMirrorMode(MirrorMode.TRUE_VIEW))
        first.onIntent(MirrorIntent.SetRingLightIntensity(0.8f))
        first.onIntent(MirrorIntent.SetLightWarmth(LightWarmth.WARM))
        first.onIntent(MirrorIntent.SetAutoMaxBrightness(false))
        first.onIntent(MirrorIntent.SetZoom(3f))
        first.onIntent(MirrorIntent.SetExposure(1.5f))
        advanceTimeBy(301)
        val expected = first.currentState.settings
        first.close()

        // "Kill": a brand-new repository and ViewModel over the same persistent store.
        val second = runningViewModel(KeyValueSettingsRepository(store))
        assertEquals(expected, second.currentState.settings)
        assertEquals(3f, camera.zoom, "restored zoom pushed to the camera")
        assertEquals(false, camera.mirrored)
        assertEquals(0f, second.currentState.exposureEv, "MVP-11.AC1: exposure resets each launch")
        assertTrue(second.currentState.ringLight.visible)
        second.close()
    }

    @Test
    fun resetToDefaultsIsPersisted() = runTest {
        val first = runningViewModel()
        first.onIntent(MirrorIntent.SetZoom(3f))
        first.onIntent(MirrorIntent.ToggleRingLight)
        advanceTimeBy(301)
        first.onIntent(MirrorIntent.ResetSettingsToDefaults)
        first.close() // flushes the pending write
        val reloaded = KeyValueSettingsRepository(store).settings.value
        assertEquals(MirrorSettings.DEFAULT.copy(tapHintShown = true), reloaded)
    }

    @Test
    fun aBurstOfSliderIntentsIsWrittenOnceWithTheFinalValue() = runTest {
        val repo = KeyValueSettingsRepository(store)
        val vm = runningViewModel(repo)
        advanceTimeBy(301) // tap-hint flag write settles
        val before = store.writes
        for (i in 1..60) vm.onIntent(MirrorIntent.SetZoom(1f + i * 0.05f))
        advanceTimeBy(299)
        assertEquals(before, store.writes, "nothing written inside the debounce window")
        advanceTimeBy(2)
        assertEquals(4f, KeyValueSettingsRepository(store).settings.value.zoomRatio)
        assertEquals(before + 2, store.writes, "one save = two float keys (zoom + intensity)")
        vm.close()
    }

    // --- Settings sheet & auto-hide (MVP-15.AC2, MVP-02) ------------------------------------------

    @Test
    fun settingsSheetPausesAutoHideUntilClosed() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ScreenTapped)
        vm.onIntent(MirrorIntent.SetSettingsOpen(true))
        advanceTimeBy(30_000)
        assertTrue(vm.currentState.controlsVisible)
        vm.onIntent(MirrorIntent.SetSettingsOpen(false))
        assertTrue(vm.currentState.controlsVisible, "closing returns to the mirror with state intact")
        advanceTimeBy(4_001)
        assertFalse(vm.currentState.controlsVisible)
        vm.close()
    }

    // --- Swift bridge subscription (ARCHITECTURE section 4) ---------------------------------------

    @Test
    fun cancelledAndClosedSubscriptionsStopDelivering() = runTest {
        val vm = runningViewModel()
        var a = 0
        var b = 0
        val subA = vm.observeState { a++ }
        vm.observeState { b++ }
        vm.onIntent(MirrorIntent.ScreenTapped)
        val aAfterTap = a
        subA.cancel()
        subA.cancel() // idempotent
        vm.onIntent(MirrorIntent.ScreenTapped)
        assertEquals(aAfterTap, a, "no delivery after cancel()")
        val bBeforeClose = b
        vm.close()
        vm.onIntent(MirrorIntent.ScreenTapped) // late UI event after deinit must not crash or deliver
        assertEquals(bBeforeClose, b, "close() ends every subscription")
    }
}
