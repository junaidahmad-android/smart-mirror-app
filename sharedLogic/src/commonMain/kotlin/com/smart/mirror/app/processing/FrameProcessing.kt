package com.smart.mirror.app.processing

import com.smart.mirror.app.domain.model.ThermalState

/*
 * Frame-processing CONTROL PLANE.
 *
 * Kotlin decides WHICH processors run, in WHAT order, with WHICH parameters, and how to degrade
 * under load. The DATA PLANE (per-frame pixel work on GPU/NPU: Metal/Core ML/Vision on iOS,
 * GL/Vulkan/TFLite/MediaPipe/NNAPI on Android) lives entirely in native code behind
 * [ProcessingPipeline]. No pixel buffer crosses into Kotlin per frame. See docs/ARCHITECTURE.md.
 */

/** Stable processor ids shared by both platforms' native implementations. */
object ProcessorIds {
    const val LOW_LIGHT_DENOISE = "low_light_denoise"
    const val LIGHT_CORRECTION = "light_correction"
    const val FACE_LANDMARKS = "face_landmarks"
    const val SKIN_SMOOTHING = "skin_smoothing"
    const val HAIR_COLOR_TRY_ON = "hair_color_try_on"
    const val MAKEUP_TRY_ON = "makeup_try_on"
    const val DELAYED_MIRROR = "delayed_mirror"
}

/** Position in the chain; the pipeline always executes stages in this order. */
enum class ProcessingStage(val order: Int) {
    /** Denoise, exposure/white-balance correction. */
    CORRECTION(0),

    /** Face landmarks, segmentation masks: produce metadata consumed downstream. */
    ANALYSIS(1),

    /** Skin/light enhancement (may use ANALYSIS output). */
    ENHANCEMENT(2),

    /** AR hair/makeup try-on, guides. */
    OVERLAY(3),

    /** Frame ring buffers (delayed mirror). Runs last so it buffers the final image. */
    TEMPORAL(4),
}

enum class ProcessorCapability {
    RUNS_ON_GPU,
    RUNS_ON_NEURAL_ENGINE,
    HAS_CPU_FALLBACK,
    MODIFIES_PIXELS,
    PRODUCES_METADATA,
    REQUIRES_FACE_LANDMARKS,
    KEEPS_TEMPORAL_STATE,

    /** May be switched off automatically when fps drops or the device heats up. */
    DEGRADABLE,

    /** Should also be applied to the saved photo, not only the preview. */
    AFFECTS_CAPTURE,
}

data class FrameProcessorDescriptor(
    val id: String,
    val stage: ProcessingStage,
    val capabilities: Set<ProcessorCapability>,
    /** Rough per-frame cost on a mid-range device; used to pick what to drop first. */
    val estimatedCostMs: Float,
)

/**
 * Common part of a native frame processor. Platform code extends this with the per-frame method
 * using native types (e.g. Swift `func process(_ buffer: CVPixelBuffer, ...)`, Android
 * `fun process(input: HardwareBuffer/GL texture, ...)`). Kotlin never calls a per-frame method.
 */
interface FrameProcessor {
    val descriptor: FrameProcessorDescriptor
    fun updateParameters(params: Map<String, Float>)
    fun release()
}

data class ProcessorConfig(val id: String, val params: Map<String, Float>)

/** The ordered chain the native pipeline must run. */
data class PipelineConfig(val processors: List<ProcessorConfig>) {
    companion object {
        val EMPTY = PipelineConfig(emptyList())
    }
}

/** Low-frequency (~1-2 Hz) performance report from the native pipeline. */
data class PipelineStats(
    val fps: Float,
    val averageLatencyMs: Float,
    val thermalState: ThermalState,
)

/** Low-frequency derived metadata (never pixels), e.g. to suggest the ring light when it is dark. */
data class FrameInsights(
    val timestampMillis: Long,
    /** Mean scene luminance 0..1. */
    val sceneLuminance: Float,
    val faceDetected: Boolean,
)

interface PipelineListener {
    fun onStats(stats: PipelineStats)
    fun onInsights(insights: FrameInsights)
}

/**
 * Platform port: the native frame-processing engine attached to the camera's output.
 * Listener callbacks must be delivered on the main thread and at low frequency.
 */
interface ProcessingPipeline {
    fun availableProcessors(): List<FrameProcessorDescriptor>
    fun apply(config: PipelineConfig)
    fun setListener(listener: PipelineListener?)
}

/** MVP implementation: no processors, preview is shown untouched. */
class NoOpProcessingPipeline : ProcessingPipeline {
    override fun availableProcessors(): List<FrameProcessorDescriptor> = emptyList()
    override fun apply(config: PipelineConfig) = Unit
    override fun setListener(listener: PipelineListener?) = Unit
}
