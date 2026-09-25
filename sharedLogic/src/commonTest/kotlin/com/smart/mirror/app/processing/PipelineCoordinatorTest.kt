package com.smart.mirror.app.processing

import com.smart.mirror.app.domain.model.ThermalState

import com.smart.mirror.app.fakes.FakeProcessingPipeline
import com.smart.mirror.app.processing.ProcessorCapability.DEGRADABLE
import com.smart.mirror.app.processing.ProcessorCapability.REQUIRES_FACE_LANDMARKS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineCoordinatorTest {
    private val landmarks = FrameProcessorDescriptor(ProcessorIds.FACE_LANDMARKS, ProcessingStage.ANALYSIS, emptySet(), 3f)
    private val denoise = FrameProcessorDescriptor(ProcessorIds.LOW_LIGHT_DENOISE, ProcessingStage.CORRECTION, setOf(DEGRADABLE), 6f)
    private val skin = FrameProcessorDescriptor(ProcessorIds.SKIN_SMOOTHING, ProcessingStage.ENHANCEMENT, setOf(DEGRADABLE, REQUIRES_FACE_LANDMARKS), 9f)
    private val pipeline = FakeProcessingPipeline(listOf(landmarks, denoise, skin))
    private val coordinator = PipelineCoordinator(pipeline, targetFps = 24f)

    private fun ids() = coordinator.appliedConfig.processors.map { it.id }

    @Test
    fun ordersByStageAddsDependenciesAndDropsUnsupported() {
        coordinator.request(
            listOf(
                ProcessorConfig(ProcessorIds.SKIN_SMOOTHING, mapOf("strength" to 0.5f)),
                ProcessorConfig(ProcessorIds.HAIR_COLOR_TRY_ON, emptyMap()), // not available on device
                ProcessorConfig(ProcessorIds.LOW_LIGHT_DENOISE, emptyMap()),
            ),
        )
        assertTrue(pipeline.applied.isEmpty(), "nothing is pushed while inactive")
        coordinator.activate()
        assertEquals(
            listOf(ProcessorIds.LOW_LIGHT_DENOISE, ProcessorIds.FACE_LANDMARKS, ProcessorIds.SKIN_SMOOTHING),
            ids(),
        )
        assertEquals(coordinator.appliedConfig, pipeline.applied.last())
    }

    @Test
    fun degradesMostExpensiveDegradableProcessorUnderLoad() {
        coordinator.request(listOf(ProcessorConfig(ProcessorIds.SKIN_SMOOTHING, emptyMap()), ProcessorConfig(ProcessorIds.LOW_LIGHT_DENOISE, emptyMap())))
        coordinator.activate()
        pipeline.currentListener!!.onStats(PipelineStats(fps = 30f, averageLatencyMs = 10f, thermalState = ThermalState.NOMINAL))
        assertEquals(3, ids().size)

        pipeline.currentListener!!.onStats(PipelineStats(fps = 18f, averageLatencyMs = 40f, thermalState = ThermalState.NOMINAL))
        assertEquals(listOf(ProcessorIds.LOW_LIGHT_DENOISE), ids())

        pipeline.currentListener!!.onStats(PipelineStats(fps = 30f, averageLatencyMs = 10f, thermalState = ThermalState.SERIOUS))
        assertEquals(emptyList(), ids())
    }

    @Test
    fun deactivateClearsPipelineAndListener() {
        coordinator.request(listOf(ProcessorConfig(ProcessorIds.LOW_LIGHT_DENOISE, emptyMap())))
        coordinator.activate()
        coordinator.deactivate()
        assertEquals(PipelineConfig.EMPTY, pipeline.applied.last())
        assertNull(pipeline.currentListener)
    }

    @Test
    fun insightsAreExposed() {
        coordinator.activate()
        val insight = FrameInsights(1L, sceneLuminance = 0.1f, faceDetected = true)
        pipeline.currentListener!!.onInsights(insight)
        assertEquals(insight, coordinator.insights.value)
    }
}
