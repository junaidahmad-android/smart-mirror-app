package com.smart.mirror.app.platform.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.smart.mirror.app.processing.FrameProcessor
import com.smart.mirror.app.processing.FrameProcessorDescriptor
import com.smart.mirror.app.processing.PipelineConfig
import com.smart.mirror.app.processing.PipelineListener
import com.smart.mirror.app.processing.ProcessingPipeline
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hook through which the camera adapter feeds frames to the native (Android) data plane of the
 * frame-processing pipeline (docs/ARCHITECTURE.md section 7). The camera binds an
 * [ImageAnalysis] use case ONLY while [isAnalysisEnabled] is true, so the MVP pays nothing.
 */
interface FrameAnalysisHook {
    val isAnalysisEnabled: Boolean
    val analysisExecutor: Executor
    val analyzer: ImageAnalysis.Analyzer

    /** The camera registers here to rebind its use cases when analysis is switched on/off. */
    fun setOnAnalysisEnabledChanged(callback: (() -> Unit)?)
}

/** Android extension of the shared [FrameProcessor] with the per-frame method (native types). */
interface AndroidFrameProcessor : FrameProcessor {
    /** Called on the analysis thread. Must not close [image]. Pixels never go to shared Kotlin. */
    fun process(image: ImageProxy)
}

/**
 * Android [ProcessingPipeline]: the shared `PipelineCoordinator` decides which processors run;
 * this class runs them on the camera's analysis stream with a latest-frame-wins policy.
 *
 * v1.0 registers no processors, so [availableProcessors] is empty, the applied config is always
 * empty and ImageAnalysis is never bound (NFR-14: hook present, no-op in the MVP).
 */
class CameraXProcessingPipeline(
    private val processors: List<AndroidFrameProcessor> = emptyList(),
) : ProcessingPipeline, FrameAnalysisHook {

    private val executor: ExecutorService by lazy { Executors.newSingleThreadExecutor { Thread(it, "frame-analysis") } }

    @Volatile
    private var active: List<AndroidFrameProcessor> = emptyList()
    private var onEnabledChanged: (() -> Unit)? = null
    private var listener: PipelineListener? = null

    override fun availableProcessors(): List<FrameProcessorDescriptor> = processors.map { it.descriptor }

    override fun apply(config: PipelineConfig) {
        val byId = processors.associateBy { it.descriptor.id }
        val wasEnabled = isAnalysisEnabled
        active = config.processors.mapNotNull { cfg -> byId[cfg.id]?.also { it.updateParameters(cfg.params) } }
        if (wasEnabled != isAnalysisEnabled) onEnabledChanged?.invoke()
    }

    override fun setListener(listener: PipelineListener?) {
        this.listener = listener
    }

    override val isAnalysisEnabled: Boolean get() = active.isNotEmpty()
    override val analysisExecutor: Executor get() = executor

    override val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            for (processor in active) processor.process(image)
        } finally {
            image.close()
        }
    }

    override fun setOnAnalysisEnabledChanged(callback: (() -> Unit)?) {
        onEnabledChanged = callback
    }
}
