package com.smart.mirror.app

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smart.mirror.app.platform.AndroidPlatformPorts
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.presentation.mirror.MirrorUiState
import com.smart.mirror.app.presentation.mirror.MirrorViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin androidx wrapper so the shared [MirrorViewModel] and the platform adapters survive
 * configuration changes (MVP-10.AC2). All logic stays in the shared class.
 */
class MirrorHostViewModel(
    private val shared: MirrorViewModel,
    val platform: AndroidPlatformPorts,
) : ViewModel() {
    val state: StateFlow<MirrorUiState> = shared.state

    /** Platform-only pixel data for the freeze overlay (never part of the shared state). */
    val frozenFrame: StateFlow<Bitmap?> = platform.camera.frozenFrame

    fun onIntent(intent: MirrorIntent) = shared.onIntent(intent)

    override fun onCleared() {
        shared.close()
        platform.release()
    }

    companion object {
        fun factory(app: SmartMirrorApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val platform = AndroidPlatformPorts(app, app.processingPipeline)
                MirrorHostViewModel(app.graph.createMirrorViewModel(platform.asMirrorPorts()), platform)
            }
        }
    }
}
