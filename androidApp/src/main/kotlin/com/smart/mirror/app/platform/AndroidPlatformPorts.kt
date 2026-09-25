package com.smart.mirror.app.platform

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import com.smart.mirror.app.platform.brightness.WindowBrightnessController
import com.smart.mirror.app.platform.camera.CameraXCameraController
import com.smart.mirror.app.platform.camera.CapturedPhotoBuffer
import com.smart.mirror.app.platform.camera.FrameAnalysisHook
import com.smart.mirror.app.platform.permission.ActivityPermissionRequester
import com.smart.mirror.app.platform.permission.AndroidPermissionChecker
import com.smart.mirror.app.platform.storage.MediaStorePhotoSaver
import com.smart.mirror.app.presentation.mirror.MirrorPorts

/**
 * The real Android adapters for the shared ports (replaces `PreviewPorts` in the app).
 *
 * Created by the host ViewModel factory, so it lives exactly as long as the shared ViewModel
 * (across rotation). Per ARCHITECTURE section 5 it never holds an Activity strongly: the
 * Activity [attach]es in `onCreate` and [detach]es in `onDestroy`.
 */
class AndroidPlatformPorts(context: Context, frameHook: FrameAnalysisHook?) {
    private val photoBuffer = CapturedPhotoBuffer()
    private val requester = ActivityPermissionRequester()

    val camera = CameraXCameraController(context, photoBuffer, frameHook)
    val brightness = WindowBrightnessController()
    val permissions = AndroidPermissionChecker(context, requester)
    val photoSaver = MediaStorePhotoSaver(context, requester, photoBuffer)

    fun asMirrorPorts() = MirrorPorts(
        camera = camera,
        brightness = brightness,
        photoSaver = photoSaver,
        permissions = permissions,
    )

    @MainThread
    fun attach(activity: ComponentActivity) {
        requester.attach(activity)
        brightness.attach(activity.window)
    }

    @MainThread
    fun detach(activity: ComponentActivity) {
        requester.detach(activity)
        brightness.detach(activity.window)
    }

    /** Call after the shared ViewModel is closed. */
    fun release() {
        camera.release()
        photoSaver.release()
        photoBuffer.clear()
    }
}
