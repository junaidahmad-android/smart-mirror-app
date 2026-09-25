package com.smart.mirror.app.platform.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.smart.mirror.app.domain.model.PermissionStatus
import com.smart.mirror.app.domain.port.PermissionChecker

/**
 * Pure mapping of Android's permission signals to the shared [PermissionStatus].
 *
 * Android has no "permanently denied" API, and `shouldShowRequestPermissionRationale == false`
 * is ambiguous: it is also false after a one-time grant expires, after `pm revoke`, and after
 * unused-app auto-revoke, where the system WILL show the dialog again (QA-BUG-01). So we only
 * report BLOCKED once a real request came back denied without a rationale
 * ([deniedWithoutRationale], recorded from the dialog result), never from "asked before".
 */
fun resolvePermissionStatus(granted: Boolean, shouldShowRationale: Boolean, deniedWithoutRationale: Boolean): PermissionStatus =
    when {
        granted -> PermissionStatus.GRANTED
        shouldShowRationale -> PermissionStatus.DENIED
        deniedWithoutRationale -> PermissionStatus.BLOCKED
        else -> PermissionStatus.NOT_DETERMINED
    }

/**
 * Classifies a dialog result as "don't ask again" (QA-BUG-01). Denied without rationale means:
 *  - the system didn't even show the dialog (answer in < [AUTO_DENY_MILLIS]), or
 *  - the user denied again after an earlier denial (rationale was true before): API 30+ makes
 *    the second denial permanent.
 * A first-time dialog dismissed by tapping outside also returns "denied, no rationale" but is
 * NOT permanent, so it stays re-promptable.
 */
fun isPermanentDenial(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, resultAfterMillis: Long): Boolean =
    !granted && !rationaleAfter && (rationaleBefore || resultAfterMillis < AUTO_DENY_MILLIS)

/** A result faster than this cannot have come from a human looking at the dialog. */
const val AUTO_DENY_MILLIS = 400L

/** Camera [PermissionChecker] for Android. */
class AndroidPermissionChecker(
    context: Context,
    private val requester: ActivityPermissionRequester,
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
) : PermissionChecker {

    private val appContext = context.applicationContext

    override fun cameraPermissionStatus(): PermissionStatus {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            // Any grant (dialog, one-time, Settings) clears an old "don't ask again" verdict.
            if (prefs.getBoolean(KEY_CAMERA_BLOCKED, false)) prefs.edit().remove(KEY_CAMERA_BLOCKED).apply()
            return PermissionStatus.GRANTED
        }
        return resolvePermissionStatus(
            granted = false,
            shouldShowRationale = requester.shouldShowRationale(Manifest.permission.CAMERA),
            deniedWithoutRationale = prefs.getBoolean(KEY_CAMERA_BLOCKED, false),
        )
    }

    override fun requestCameraPermission(onResult: (PermissionStatus) -> Unit) {
        when (val current = cameraPermissionStatus()) {
            PermissionStatus.GRANTED, PermissionStatus.BLOCKED -> onResult(current) // no dialog possible/needed
            else -> {
                val rationaleBefore = current == PermissionStatus.DENIED
                val requestedAt = SystemClock.elapsedRealtime()
                requester.request(Manifest.permission.CAMERA) { granted ->
                    val blocked = isPermanentDenial(
                        granted = granted,
                        rationaleBefore = rationaleBefore,
                        rationaleAfter = requester.shouldShowRationale(Manifest.permission.CAMERA),
                        resultAfterMillis = SystemClock.elapsedRealtime() - requestedAt,
                    )
                    prefs.edit().putBoolean(KEY_CAMERA_BLOCKED, blocked).apply()
                    onResult(cameraPermissionStatus())
                }
            }
        }
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { (requester.activity ?: appContext).startActivity(intent) }
    }

    companion object {
        const val PREFS_NAME = "smart_mirror_permissions"
        /** Set only from a dialog result: denied without rationale. Replaces the old "camera.asked" heuristic. */
        const val KEY_CAMERA_BLOCKED = "camera.deniedWithoutRationale"
    }
}
