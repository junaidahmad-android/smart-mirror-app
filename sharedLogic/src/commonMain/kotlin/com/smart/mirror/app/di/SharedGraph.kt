package com.smart.mirror.app.di

import com.smart.mirror.app.data.settings.KeyValueSettingsRepository
import com.smart.mirror.app.data.settings.KeyValueStore
import com.smart.mirror.app.data.settings.SettingsRepository
import com.smart.mirror.app.presentation.mirror.MirrorPorts
import com.smart.mirror.app.presentation.mirror.MirrorViewModel
import com.smart.mirror.app.processing.NoOpProcessingPipeline
import com.smart.mirror.app.processing.PipelineCoordinator
import com.smart.mirror.app.processing.ProcessingPipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual, compile-time-checked dependency graph (no DI framework).
 *
 * App-scoped: create ONE instance at app start (Android `Application`, iOS `App` struct) and keep
 * it. Screen-scoped objects (ViewModels) are created through factory methods that take the
 * screen's platform ports.
 *
 * Swift: `SharedGraph(keyValueStore: UserDefaultsKeyValueStore(defaults: .standard), processingPipeline: NoOpProcessingPipeline())`
 * Android: `SharedGraph(SharedPreferencesKeyValueStore(context), NoOpProcessingPipeline())`
 */
class SharedGraph internal constructor(
    keyValueStore: KeyValueStore,
    private val processingPipeline: ProcessingPipeline,
    private val mainDispatcher: CoroutineDispatcher,
) {
    constructor(keyValueStore: KeyValueStore, processingPipeline: ProcessingPipeline) :
        this(keyValueStore, processingPipeline, Dispatchers.Main.immediate)

    /** Convenience for the MVP: no frame processing. */
    constructor(keyValueStore: KeyValueStore) : this(keyValueStore, NoOpProcessingPipeline())

    private val settings = KeyValueSettingsRepository(keyValueStore)
    val settingsRepository: SettingsRepository get() = settings

    /** App-lifetime background work (e.g. warming the settings cache off the main thread). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        appScope.launch { settings.preload() }
    }

    fun createMirrorViewModel(ports: MirrorPorts): MirrorViewModel = MirrorViewModel(
        ports = ports,
        settingsRepository = settingsRepository,
        pipeline = PipelineCoordinator(processingPipeline),
        scope = CoroutineScope(SupervisorJob() + mainDispatcher),
    )
}
