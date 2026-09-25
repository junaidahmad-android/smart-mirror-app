package com.smart.mirror.app.processing

import com.smart.mirror.app.domain.model.ThermalState

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared, testable policy for the native [ProcessingPipeline]: filters out processors the device
 * lacks, orders the chain by [ProcessingStage], pulls in dependencies (face landmarks) and drops
 * [ProcessorCapability.DEGRADABLE] processors when fps or thermal state degrade.
 * Must be used from the main thread.
 */
class PipelineCoordinator(
    private val pipeline: ProcessingPipeline,
    private val targetFps: Float = 24f,
) {
    private var requested: List<ProcessorConfig> = emptyList()
    private val degraded = mutableSetOf<String>()
    private var active = false

    private val _insights = MutableStateFlow<FrameInsights?>(null)
    val insights: StateFlow<FrameInsights?> = _insights.asStateFlow()

    var appliedConfig: PipelineConfig = PipelineConfig.EMPTY
        private set

    private val listener = object : PipelineListener {
        override fun onStats(stats: PipelineStats) = handleStats(stats)
        override fun onInsights(insights: FrameInsights) { _insights.value = insights }
    }

    fun request(processors: List<ProcessorConfig>) {
        requested = processors
        degraded.clear()
        if (active) push()
    }

    fun activate() {
        if (active) return
        active = true
        pipeline.setListener(listener)
        push()
    }

    fun deactivate() {
        if (!active) return
        active = false
        pipeline.setListener(null)
        appliedConfig = PipelineConfig.EMPTY
        pipeline.apply(PipelineConfig.EMPTY)
    }

    internal fun buildConfig(): PipelineConfig {
        val available = pipeline.availableProcessors().associateBy { it.id }
        val wanted = requested.filter { it.id in available && it.id !in degraded }.toMutableList()
        val needsLandmarks = wanted.any {
            ProcessorCapability.REQUIRES_FACE_LANDMARKS in available.getValue(it.id).capabilities
        }
        if (needsLandmarks && wanted.none { it.id == ProcessorIds.FACE_LANDMARKS } &&
            ProcessorIds.FACE_LANDMARKS in available
        ) {
            wanted += ProcessorConfig(ProcessorIds.FACE_LANDMARKS, emptyMap())
        }
        return PipelineConfig(wanted.sortedBy { available.getValue(it.id).stage.order })
    }

    private fun handleStats(stats: PipelineStats) {
        if (!active) return
        val overloaded = stats.fps < targetFps || stats.thermalState >= ThermalState.SERIOUS
        if (!overloaded) return
        val available = pipeline.availableProcessors().associateBy { it.id }
        val victim = appliedConfig.processors
            .mapNotNull { available[it.id] }
            .filter { ProcessorCapability.DEGRADABLE in it.capabilities }
            .maxByOrNull { it.estimatedCostMs }
            ?: return
        degraded += victim.id
        push()
    }

    private fun push() {
        val config = buildConfig()
        if (config == appliedConfig) return
        appliedConfig = config
        pipeline.apply(config)
    }
}
