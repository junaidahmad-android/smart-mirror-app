package com.smart.mirror.app.platform.permission

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import java.lang.ref.WeakReference

/**
 * Bridges runtime-permission dialogs (which need an Activity) to adapters that outlive it.
 *
 * The Activity calls [attach] in `onCreate` (before STARTED, as required by the Activity Result
 * API) and [detach] in `onDestroy`. A request in flight survives recreation: the result is
 * delivered to the re-registered launcher and routed to the pending callback here.
 */
class ActivityPermissionRequester {
    private var launcher: ActivityResultLauncher<String>? = null
    private var activityRef: WeakReference<Activity>? = null
    private var pending: ((Boolean) -> Unit)? = null

    val activity: Activity? get() = activityRef?.get()

    @MainThread
    fun attach(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pending
            pending = null
            callback?.invoke(granted)
        }
    }

    @MainThread
    fun detach(activity: ComponentActivity) {
        if (activityRef?.get() !== activity) return
        activityRef = null
        launcher = null
    }

    fun shouldShowRationale(permission: String): Boolean =
        activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false

    /**
     * Shows the system dialog. [onResult] is called exactly once (false immediately if no Activity
     * is attached or another request is already showing).
     */
    @MainThread
    fun request(permission: String, onResult: (granted: Boolean) -> Unit) {
        val l = launcher
        if (l == null || pending != null) {
            onResult(false)
            return
        }
        pending = onResult
        try {
            l.launch(permission)
        } catch (e: IllegalStateException) {
            pending = null
            onResult(false)
        }
    }
}
