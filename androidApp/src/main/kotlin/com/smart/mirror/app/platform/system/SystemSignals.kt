package com.smart.mirror.app.platform.system

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.smart.mirror.app.domain.model.ThermalState

/** Handle to stop observing a system signal. */
fun interface SignalSubscription {
    fun dispose()
}

/** `PowerManager.THERMAL_STATUS_*` codes, mirrored so the mapping is JVM-testable. */
object ThermalStatusCodes {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6
}

/**
 * Android thermal status -> shared [ThermalState]. NFR-06: SEVERE (and above) throttles
 * (SERIOUS = 30 fps, 80% brightness); CRITICAL and above pauses the preview.
 */
fun thermalStateFor(status: Int): ThermalState = when {
    status >= ThermalStatusCodes.CRITICAL -> ThermalState.CRITICAL
    status >= ThermalStatusCodes.SEVERE -> ThermalState.SERIOUS
    status >= ThermalStatusCodes.MODERATE -> ThermalState.FAIR
    else -> ThermalState.NOMINAL
}

/** Reports the current thermal state, then every change. No-op below API 29 (no thermal API). */
fun observeThermalState(context: Context, onChange: (ThermalState) -> Unit): SignalSubscription {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SignalSubscription { }
    val pm = context.getSystemService(PowerManager::class.java) ?: return SignalSubscription { }
    onChange(thermalStateFor(pm.currentThermalStatus))
    val listener = PowerManager.OnThermalStatusChangedListener { onChange(thermalStateFor(it)) }
    pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
    return SignalSubscription { pm.removeThermalStatusListener(listener) }
}

/** Reports whether a touch-exploring screen reader (TalkBack) is on, then every change. */
fun observeScreenReader(context: Context, onChange: (Boolean) -> Unit): SignalSubscription {
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return SignalSubscription { }
    onChange(am.isTouchExplorationEnabled)
    val listener = AccessibilityManager.TouchExplorationStateChangeListener { onChange(it) }
    am.addTouchExplorationStateChangeListener(listener)
    return SignalSubscription { am.removeTouchExplorationStateChangeListener(listener) }
}
