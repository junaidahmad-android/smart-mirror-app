package com.smart.mirror.app

import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.model.ThermalState
import com.smart.mirror.app.platform.camera.CameraStateErrorCodes
import com.smart.mirror.app.platform.camera.CameraZoom
import com.smart.mirror.app.platform.camera.CapturedPhotoBuffer
import com.smart.mirror.app.platform.camera.CropRect
import com.smart.mirror.app.platform.camera.cameraErrorFor
import com.smart.mirror.app.platform.camera.centerCropFor
import com.smart.mirror.app.platform.camera.chooseFpsRange
import com.smart.mirror.app.platform.camera.exposureIndexFor
import com.smart.mirror.app.platform.permission.isPermanentDenial
import com.smart.mirror.app.platform.permission.resolvePermissionStatus
import com.smart.mirror.app.platform.system.ThermalStatusCodes
import com.smart.mirror.app.platform.system.thermalStateFor
import com.smart.mirror.app.ui.mirror.ControlsPlacement
import com.smart.mirror.app.ui.mirror.MirrorLayoutSpec
import com.smart.mirror.app.ui.mirror.clampPan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraMathTest {
    @Test
    fun centerCrop_portraitViewOnLandscapeishImage_cropsWidth() {
        // Upright 1080x1920 (9:16) still, 1080x2400 (9:20) portrait screen -> crop width.
        val crop = centerCropFor(1080, 1920, 1080, 2400)
        assertEquals(1920, crop.height)
        assertEquals(864, crop.width)
        assertEquals((1080 - 864) / 2, crop.left)
        assertEquals(0, crop.top)
    }

    @Test
    fun centerCrop_wideView_cropsHeight() {
        val crop = centerCropFor(1440, 1080, 1600, 900)
        assertEquals(1440, crop.width)
        assertEquals(810, crop.height)
        assertEquals(135, crop.top)
    }

    @Test
    fun centerCrop_appliesDigitalZoomAroundCentre() {
        val crop = centerCropFor(1000, 1000, 500, 500, extraZoom = 2f)
        assertEquals(CropRect(250, 250, 750, 750), crop)
    }

    @Test
    fun centerCrop_unknownViewSize_keepsFullImage() {
        assertEquals(CropRect(0, 0, 640, 480), centerCropFor(640, 480, 0, 0))
    }

    @Test
    fun zoomSplit_withinHardwareRange_isAllHardware() {
        val split = CameraZoom.split(3f, 1f, 8f)
        assertEquals(3f, split.hardware)
        assertEquals(1f, split.digital)
    }

    @Test
    fun zoomSplit_aboveHardwareMax_topsUpDigitally() {
        val split = CameraZoom.split(5f, 1f, 2f)
        assertEquals(2f, split.hardware)
        assertEquals(2.5f, split.digital, 1e-4f)
    }

    @Test
    fun zoomSplit_noHardwareZoom_isAllDigital() {
        val split = CameraZoom.split(4f, 1f, 1f)
        assertEquals(1f, split.hardware)
        assertEquals(4f, split.digital)
    }

    @Test
    fun advertisedMaxZoom_isAtLeastFive() {
        assertEquals(5f, CameraZoom.advertisedMax(1f))
        assertEquals(10f, CameraZoom.advertisedMax(10f))
    }

    @Test
    fun exposureIndex_roundsAndClamps() {
        val third = 1f / 3f
        assertEquals(0, exposureIndexFor(0f, third, -6, 6))
        assertEquals(2, exposureIndexFor(0.7f, third, -6, 6))
        assertEquals(6, exposureIndexFor(5f, third, -6, 6))
        assertEquals(-6, exposureIndexFor(-5f, third, -6, 6))
        assertEquals(0, exposureIndexFor(1f, 0f, 0, 0))
        assertEquals(0, exposureIndexFor(Float.NaN, third, -6, 6))
    }

    @Test
    fun fpsRange_prefersHighestUpperThenWidestRange() {
        val ranges = listOf(15..15, 7..30, 30..30, 15..60, 60..60)
        assertEquals(15..60, chooseFpsRange(ranges, 60))
        assertEquals(7..30, chooseFpsRange(ranges, 30))
        assertNull(chooseFpsRange(listOf(60..60), 30))
    }

    @Test
    fun cameraErrors_mapToSharedErrors() {
        assertEquals(CameraError.IN_USE_BY_ANOTHER_APP, cameraErrorFor(CameraStateErrorCodes.CAMERA_IN_USE))
        assertEquals(CameraError.IN_USE_BY_ANOTHER_APP, cameraErrorFor(CameraStateErrorCodes.MAX_CAMERAS_IN_USE))
        assertEquals(CameraError.IN_USE_BY_ANOTHER_APP, cameraErrorFor(CameraStateErrorCodes.CAMERA_DISABLED))
        assertEquals(CameraError.DISCONNECTED, cameraErrorFor(CameraStateErrorCodes.OTHER_RECOVERABLE_ERROR))
        assertEquals(CameraError.DISCONNECTED, cameraErrorFor(CameraStateErrorCodes.CAMERA_REMOVED))
        assertEquals(CameraError.UNKNOWN, cameraErrorFor(CameraStateErrorCodes.CAMERA_FATAL_ERROR))
        assertEquals(CameraError.UNKNOWN, cameraErrorFor(CameraStateErrorCodes.STREAM_CONFIG))
    }
}

class CapturedPhotoBufferTest {
    @Test
    fun takeReturnsBytesOnce() {
        val buffer = CapturedPhotoBuffer()
        val key = buffer.put(byteArrayOf(1, 2, 3))
        assertTrue(key.startsWith(CapturedPhotoBuffer.SCHEME))
        assertContentEquals(byteArrayOf(1, 2, 3), buffer.take(key))
        assertNull(buffer.take(key))
        assertEquals(0, buffer.size)
    }

    @Test
    fun discardAndClearDropPhotos() {
        val buffer = CapturedPhotoBuffer()
        val a = buffer.put(byteArrayOf(1))
        buffer.put(byteArrayOf(2))
        buffer.discard(a)
        assertEquals(1, buffer.size)
        buffer.clear()
        assertEquals(0, buffer.size)
    }
}

class PermissionAndThermalTest {
    @Test
    fun permissionStatus_resolution() {
        assertEquals(PermissionStatus.GRANTED, resolvePermissionStatus(granted = true, shouldShowRationale = false, deniedWithoutRationale = true))
        assertEquals(PermissionStatus.NOT_DETERMINED, resolvePermissionStatus(granted = false, shouldShowRationale = false, deniedWithoutRationale = false))
        assertEquals(PermissionStatus.DENIED, resolvePermissionStatus(granted = false, shouldShowRationale = true, deniedWithoutRationale = false))
        // The dialog itself came back denied with no rationale: "don't ask again" -> Settings only.
        assertEquals(PermissionStatus.BLOCKED, resolvePermissionStatus(granted = false, shouldShowRationale = false, deniedWithoutRationale = true))
    }

    /** QA-BUG-01: expired one-time grant / pm revoke / auto-revoke must allow re-prompting. */
    @Test
    fun expiredOneTimeGrant_isNotBlocked() {
        // Previously granted (so no denial was ever recorded), now revoked, no rationale.
        assertEquals(PermissionStatus.NOT_DETERMINED, resolvePermissionStatus(granted = false, shouldShowRationale = false, deniedWithoutRationale = false))
    }

    @Test
    fun permanentDenial_classification() {
        // System refused to show the dialog (already "don't ask again").
        assertTrue(isPermanentDenial(granted = false, rationaleBefore = false, rationaleAfter = false, resultAfterMillis = 50))
        // Second explicit denial (API 30+).
        assertTrue(isPermanentDenial(granted = false, rationaleBefore = true, rationaleAfter = false, resultAfterMillis = 3_000))
        // First dialog dismissed by tapping outside: still re-promptable.
        assertFalse(isPermanentDenial(granted = false, rationaleBefore = false, rationaleAfter = false, resultAfterMillis = 3_000))
        // First explicit denial: rationale becomes true -> DENIED, not blocked.
        assertFalse(isPermanentDenial(granted = false, rationaleBefore = false, rationaleAfter = true, resultAfterMillis = 3_000))
        assertFalse(isPermanentDenial(granted = true, rationaleBefore = true, rationaleAfter = false, resultAfterMillis = 10))
    }

    @Test
    fun thermalStatus_mapping_followsNfr06() {
        assertEquals(ThermalState.NOMINAL, thermalStateFor(ThermalStatusCodes.NONE))
        assertEquals(ThermalState.NOMINAL, thermalStateFor(ThermalStatusCodes.LIGHT))
        assertEquals(ThermalState.FAIR, thermalStateFor(ThermalStatusCodes.MODERATE))
        assertEquals(ThermalState.SERIOUS, thermalStateFor(ThermalStatusCodes.SEVERE))
        assertEquals(ThermalState.CRITICAL, thermalStateFor(ThermalStatusCodes.CRITICAL))
        assertEquals(ThermalState.CRITICAL, thermalStateFor(ThermalStatusCodes.EMERGENCY))
        assertEquals(ThermalState.CRITICAL, thermalStateFor(ThermalStatusCodes.SHUTDOWN))
    }
}

class LayoutLogicTest {
    @Test
    fun phonePortrait_usesBottomBar() {
        val spec = MirrorLayoutSpec.forWindow(411, 891)
        assertEquals(ControlsPlacement.BOTTOM_BAR, spec.placement)
        assertFalse(spec.spacious)
    }

    @Test
    fun phoneLandscape_usesSideRail() {
        val spec = MirrorLayoutSpec.forWindow(891, 411)
        assertEquals(ControlsPlacement.SIDE_RAIL, spec.placement)
        assertFalse(spec.spacious) // height is compact
    }

    @Test
    fun tabletPortrait_usesSpaciousBottomBar() {
        val spec = MirrorLayoutSpec.forWindow(800, 1280)
        assertEquals(ControlsPlacement.BOTTOM_BAR, spec.placement)
        assertTrue(spec.spacious)
    }

    @Test
    fun tabletLandscapeAndUnfoldedFoldable_useSpaciousSideRail() {
        assertEquals(MirrorLayoutSpec(ControlsPlacement.SIDE_RAIL, spacious = true), MirrorLayoutSpec.forWindow(1280, 800))
        // Unfolded foldable, nearly square and expanded width.
        assertEquals(MirrorLayoutSpec(ControlsPlacement.SIDE_RAIL, spacious = true), MirrorLayoutSpec.forWindow(841, 900))
    }

    @Test
    fun clampPan_keepsImageCoveringViewport() {
        assertEquals(0f to 0f, clampPan(100f, -100f, 1f, 1000f, 2000f))
        // 2x: may move half the overflow = 500 / 1000.
        assertEquals(500f to -1000f, clampPan(900f, -5000f, 2f, 1000f, 2000f))
        assertEquals(10f to 20f, clampPan(10f, 20f, 2f, 1000f, 2000f))
    }
}
