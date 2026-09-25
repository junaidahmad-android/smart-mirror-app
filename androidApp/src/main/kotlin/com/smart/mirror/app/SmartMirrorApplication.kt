package com.smart.mirror.app

import android.app.Application
import androidx.camera.lifecycle.ProcessCameraProvider
import com.smart.mirror.app.data.settings.SharedPreferencesKeyValueStore
import com.smart.mirror.app.di.SharedGraph
import com.smart.mirror.app.platform.camera.CameraXProcessingPipeline

/** Owns the app-scoped shared graph (settings, frame pipeline). */
class SmartMirrorApplication : Application() {
    lateinit var graph: SharedGraph
        private set

    /** Android data plane of the frame pipeline; no processors in v1.0 (hook only, NFR-14). */
    val processingPipeline = CameraXProcessingPipeline()

    override fun onCreate() {
        super.onCreate()
        // Start CameraX initialisation as early as possible; it runs on CameraX's own threads,
        // so the camera can open in parallel with the first Compose frame (NFR-01).
        ProcessCameraProvider.getInstance(this)
        graph = SharedGraph(SharedPreferencesKeyValueStore(this), processingPipeline)
    }
}
