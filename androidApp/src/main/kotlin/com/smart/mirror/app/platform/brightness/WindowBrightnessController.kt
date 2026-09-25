package com.smart.mirror.app.platform.brightness

import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import com.smart.mirror.app.domain.port.BrightnessController

/**
 * [BrightnessController] using the per-window brightness override and `FLAG_KEEP_SCREEN_ON`.
 * Never touches the system brightness setting (MVP-06.AC3): restoring puts back the window's
 * previous override (normally "none", i.e. follow the user's system level).
 *
 * Lifetime: owned by the host ViewModel (outlives rotation). The Activity calls [attach] in
 * `onCreate` and [detach] in `onDestroy`; the desired state is re-applied to each new window.
 * Only the Window is held, and only between attach and detach, so no Activity leaks.
 */
class WindowBrightnessController : BrightnessController {
    private var window: Window? = null
    private var requestedLevel: Float? = null
    private var keepScreenOn = false
    private var savedOverride: Float? = null

    @MainThread
    fun attach(window: Window) {
        this.window = window
        savedOverride = null
        applyToWindow()
    }

    @MainThread
    fun detach(window: Window) {
        if (this.window === window) this.window = null
    }

    override fun applyBrightness(level: Float) {
        requestedLevel = level.coerceIn(MIN_LEVEL, 1f)
        applyToWindow()
    }

    override fun restoreBrightness() {
        requestedLevel = null
        applyToWindow()
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOn = enabled
        applyToWindow()
    }

    private fun applyToWindow() {
        val w = window ?: return
        val attrs = w.attributes
        val level = requestedLevel
        if (level != null) {
            if (savedOverride == null) savedOverride = attrs.screenBrightness
            attrs.screenBrightness = level
        } else {
            attrs.screenBrightness = savedOverride ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            savedOverride = null
        }
        w.attributes = attrs
        if (keepScreenOn) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private companion object {
        /** 0 would turn some panels fully off; the shared layer only asks for 0.8 or 1.0 anyway. */
        const val MIN_LEVEL = 0.01f
    }
}
