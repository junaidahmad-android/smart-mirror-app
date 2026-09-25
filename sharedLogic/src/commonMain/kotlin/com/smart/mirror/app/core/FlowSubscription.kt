package com.smart.mirror.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Handle returned to platform code (mainly Swift) when it subscribes to a shared Kotlin Flow.
 * Call [cancel] when the observer goes away (e.g. in `deinit`).
 *
 * Deliberately NOT named `Cancellable` to avoid clashing with Combine's `Cancellable` in Swift.
 */
fun interface FlowSubscription {
    fun cancel()
}

/** Collects this flow in [scope] and forwards every value to [onEach]. */
internal fun <T> Flow<T>.subscribeIn(scope: CoroutineScope, onEach: (T) -> Unit): FlowSubscription {
    val job = scope.launch { collect { onEach(it) } }
    return FlowSubscription { job.cancel() }
}
