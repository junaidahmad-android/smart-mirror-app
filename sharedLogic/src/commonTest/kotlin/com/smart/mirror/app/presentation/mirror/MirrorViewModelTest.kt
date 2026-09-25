package com.smart.mirror.app.presentation.mirror

import com.smart.mirror.app.data.settings.InMemoryKeyValueStore
import com.smart.mirror.app.data.settings.KeyValueSettingsRepository
import com.smart.mirror.app.domain.model.CameraCaptureOutcome
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureStatus
import com.smart.mirror.app.domain.model.MirrorMode
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.SaveFailure
import com.smart.mirror.app.domain.model.SaveOutcome
import com.smart.mirror.app.domain.model.ThermalState
import com.smart.mirror.app.fakes.FakeBrightness
import com.smart.mirror.app.fakes.FakeCameraController
import com.smart.mirror.app.fakes.FakePermissions
import com.smart.mirror.app.fakes.FakePhotoSaver
import com.smart.mirror.app.fakes.FakeProcessingPipeline
import com.smart.mirror.app.fakes.TEST_CAPS
import com.smart.mirror.app.processing.PipelineCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorViewModelTest {
    private val camera = FakeCameraController()
    private val brightness = FakeBrightness()
    private val saver = FakePhotoSaver()
    private val permissions = FakePermissions()
    private val pipeline = FakeProcessingPipeline()
    private val store = InMemoryKeyValueStore()
    private val repo = KeyValueSettingsRepository(store)

    private fun TestScope.viewModel() = MirrorViewModel(
        ports = MirrorPorts(camera, brightness, saver, permissions),
        settingsRepository = repo,
        pipeline = PipelineCoordinator(pipeline),
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
    )

    /** Screen started with permission granted and the camera reporting ready. */
    private fun TestScope.runningViewModel(): MirrorViewModel = viewModel().apply {
        onIntent(MirrorIntent.ScreenStarted)
        camera.currentListener!!.onCameraStarted(TEST_CAPS)
        runCurrent()
    }

    @Test
    fun grantedPermissionStartsFrontCameraAndAppliesSettings() = runTest {
        val vm = viewModel()
        assertEquals(MirrorScreen.LOADING, vm.currentState.screen)
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(listOf("start"), camera.calls)
        assertEquals(CameraStatus.STARTING, vm.currentState.cameraStatus)

        camera.currentListener!!.onCameraStarted(TEST_CAPS)
        assertEquals(CameraStatus.RUNNING, vm.currentState.cameraStatus)
        assertEquals(1f, camera.zoom)
        assertEquals(0f, camera.exposure)
        assertEquals(true, camera.mirrored)
        assertEquals(60, camera.maxFps)
        assertEquals(1f, brightness.level)
        assertTrue(brightness.screenKeptOn)
        vm.close()
    }

    @Test
    fun notDeterminedShowsOnboardingThenRequestStartsCamera() = runTest {
        permissions.status = PermissionStatus.NOT_DETERMINED
        val vm = viewModel()
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(MirrorScreen.PERMISSION_ONBOARDING, vm.currentState.screen)
        assertTrue(camera.calls.isEmpty())
        assertFalse(brightness.boosted)

        vm.onIntent(MirrorIntent.RequestCameraPermission)
        assertEquals(1, permissions.requests)
        assertEquals(MirrorScreen.MIRROR, vm.currentState.screen)
        assertEquals(listOf("start"), camera.calls)
        vm.close()
    }

    @Test
    fun blockedPermissionOffersSettingsAndRechecksOnReturn() = runTest {
        permissions.status = PermissionStatus.BLOCKED
        val vm = viewModel()
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(MirrorScreen.PERMISSION_BLOCKED, vm.currentState.screen)
        vm.onIntent(MirrorIntent.OpenAppSettings)
        assertEquals(1, permissions.settingsOpened)

        // user enables the permission in Settings and comes back
        vm.onIntent(MirrorIntent.ScreenStopped)
        permissions.status = PermissionStatus.GRANTED
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(MirrorScreen.MIRROR, vm.currentState.screen)
        assertEquals(listOf("start"), camera.calls)
        vm.close()
    }

    @Test
    fun screenStoppedReleasesCameraAndBrightness() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ScreenStopped)
        assertEquals(listOf("start", "stop"), camera.calls)
        assertEquals(CameraStatus.IDLE, vm.currentState.cameraStatus)
        assertFalse(brightness.boosted)
        assertFalse(brightness.screenKeptOn)
        vm.close()
    }

    @Test
    fun zoomAndExposureAreForwardedToCamera() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.SetZoom(2.5f))
        assertEquals(2.5f, camera.zoom)
        vm.onIntent(MirrorIntent.SetExposure(1.1f))
        assertEquals(1f, camera.exposure)
        vm.close()
    }

    @Test
    fun settingsArePersistedDebounced() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.SetZoom(2f))
        vm.onIntent(MirrorIntent.SetZoom(3f))
        vm.onIntent(MirrorIntent.ToggleMirrorMode)
        assertEquals(1f, repo.settings.value.zoomRatio) // not yet written
        advanceTimeBy(301)
        assertEquals(3f, repo.settings.value.zoomRatio)
        assertEquals(MirrorMode.TRUE_VIEW, KeyValueSettingsRepository(store).settings.value.mirrorMode)
        vm.close()
    }

    @Test
    fun pendingSettingsAreFlushedOnClose() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.SetZoom(2f))
        vm.close()
        assertEquals(2f, repo.settings.value.zoomRatio)
    }

    @Test
    fun controlsAutoHideAndInteractionResetsTimer() = runTest {
        val vm = runningViewModel()
        assertFalse(vm.currentState.controlsVisible)
        vm.onIntent(MirrorIntent.ScreenTapped)
        assertTrue(vm.currentState.controlsVisible)
        advanceTimeBy(3_000)
        vm.onIntent(MirrorIntent.SetZoom(2f)) // interaction restarts the 4s timer
        advanceTimeBy(3_000)
        assertTrue(vm.currentState.controlsVisible)
        advanceTimeBy(1_001)
        assertFalse(vm.currentState.controlsVisible)

        vm.onIntent(MirrorIntent.ScreenTapped)
        assertTrue(vm.currentState.controlsVisible)
        advanceTimeBy(4_001)
        assertFalse(vm.currentState.controlsVisible)

        vm.onIntent(MirrorIntent.SetScreenReaderActive(true))
        vm.onIntent(MirrorIntent.ScreenTapped)
        advanceTimeBy(60_000)
        assertTrue(vm.currentState.controlsVisible, "never auto-hide with a screen reader")
        vm.close()
    }

    @Test
    fun tapHintIsShownOnceEver() = runTest {
        val vm = runningViewModel()
        assertTrue(vm.currentState.showTapHint)
        advanceTimeBy(3_001)
        assertFalse(vm.currentState.showTapHint)
        vm.close()
        assertTrue(repo.settings.value.tapHintShown)

        val second = runningViewModel()
        assertFalse(second.currentState.showTapHint)
        second.close()
    }

    @Test
    fun criticalThermalStopsCameraAndRecoversWhenCool() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.SERIOUS))
        assertEquals(30, camera.maxFps)
        assertEquals(0.8f, brightness.level)
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.CRITICAL))
        assertEquals(listOf("start", "stop"), camera.calls)
        assertEquals(MirrorScreen.OVERHEATED, vm.currentState.screen)
        assertFalse(brightness.screenKeptOn)
        vm.onIntent(MirrorIntent.ThermalStateChanged(ThermalState.FAIR))
        assertEquals(listOf("start", "stop", "start"), camera.calls)
        camera.currentListener!!.onCameraStarted(TEST_CAPS)
        assertEquals(60, camera.maxFps)
        vm.close()
    }

    @Test
    fun freezeKeepsControlsVisibleAndPausesPreview() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ToggleFreeze)
        assertEquals(true, camera.frozen)
        advanceTimeBy(10_000)
        assertTrue(vm.currentState.controlsVisible)
        vm.onIntent(MirrorIntent.ToggleFreeze)
        assertEquals(false, camera.frozen)
        vm.close()
    }

    @Test
    fun captureSavesPhotoAndShowsTransientMessage() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.ToggleMirrorMode)
        vm.onIntent(MirrorIntent.CapturePhoto)
        assertEquals(CaptureStatus.CAPTURING, vm.currentState.captureStatus)
        assertFalse(camera.pendingCapture!!.first.mirrored)

        vm.onIntent(MirrorIntent.CapturePhoto) // ignored while busy
        assertEquals(1, camera.calls.count { it == "capture" })

        camera.completeCapture()
        runCurrent()
        assertEquals(1, saver.saved.size)
        assertEquals(CaptureStatus.IDLE, vm.currentState.captureStatus)
        assertEquals(UserMessage.PHOTO_SAVED, vm.currentState.message)
        assertEquals("content://saved/1", vm.currentState.lastSavedPhotoUri)
        advanceTimeBy(2_501)
        assertNull(vm.currentState.message)
        vm.close()
    }

    @Test
    fun captureFailuresAreReported() = runTest {
        val vm = runningViewModel()
        vm.onIntent(MirrorIntent.CapturePhoto)
        camera.completeCapture(CameraCaptureOutcome.Failure(CameraError.UNKNOWN))
        runCurrent()
        assertEquals(UserMessage.CAPTURE_FAILED, vm.currentState.message)

        saver.outcome = SaveOutcome.Failed(SaveFailure.PERMISSION_DENIED)
        vm.onIntent(MirrorIntent.CapturePhoto)
        camera.completeCapture()
        runCurrent()
        assertEquals(UserMessage.SAVE_PERMISSION_DENIED, vm.currentState.message)
        vm.close()
    }

    @Test
    fun brightnessBoostFollowsSettingsAndRingLight() = runTest {
        val vm = runningViewModel()
        assertTrue(brightness.boosted)
        vm.onIntent(MirrorIntent.SetAutoMaxBrightness(false))
        assertFalse(brightness.boosted)
        vm.onIntent(MirrorIntent.ToggleRingLight) // ring light needs a bright screen
        assertTrue(brightness.boosted)
        vm.close()
        assertFalse(brightness.boosted)
    }

    @Test
    fun cameraErrorCanBeRecoveredByRestartingScreen() = runTest {
        val vm = runningViewModel()
        camera.currentListener!!.onCameraError(CameraError.IN_USE_BY_ANOTHER_APP)
        assertEquals(CameraStatus.ERROR, vm.currentState.cameraStatus)
        assertEquals(CameraError.IN_USE_BY_ANOTHER_APP, vm.currentState.cameraError)
        vm.onIntent(MirrorIntent.RetryCamera)
        assertEquals(listOf("start", "start"), camera.calls)
        camera.currentListener!!.onCameraError(CameraError.IN_USE_BY_ANOTHER_APP)
        vm.onIntent(MirrorIntent.ScreenStopped)
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(CameraStatus.STARTING, vm.currentState.cameraStatus)
        assertNull(vm.currentState.cameraError)
        vm.close()
    }

    @Test
    fun observeStateDeliversCurrentAndSubsequentStates() = runTest {
        val vm = viewModel()
        val seen = mutableListOf<MirrorScreen>()
        val sub = vm.observeState { seen += it.screen }
        vm.onIntent(MirrorIntent.ScreenStarted)
        sub.cancel()
        vm.onIntent(MirrorIntent.ScreenStopped)
        permissions.status = PermissionStatus.BLOCKED
        vm.onIntent(MirrorIntent.ScreenStarted)
        assertEquals(listOf(MirrorScreen.LOADING, MirrorScreen.MIRROR), seen.distinct())
        assertFalse(MirrorScreen.PERMISSION_BLOCKED in seen)
        vm.close()
    }

    @Test
    fun pipelineIsActivatedWhileCameraRuns() = runTest {
        val vm = runningViewModel()
        assertTrue(pipeline.currentListener != null)
        vm.onIntent(MirrorIntent.ScreenStopped)
        assertNull(pipeline.currentListener)
        advanceUntilIdle()
        vm.close()
    }
}
