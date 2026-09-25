package com.smart.mirror.app.ui.mirror

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smart.mirror.app.R
import com.smart.mirror.app.domain.model.LightIntensityPreset
import com.smart.mirror.app.domain.model.LightWarmth
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.presentation.mirror.MirrorUiState
import com.smart.mirror.app.ui.theme.ControlScrim
import com.smart.mirror.app.ui.theme.OnControlScrim
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which adjustment the panel above/next to the action buttons shows (UI-local). */
enum class ControlTool { ZOOM, EXPOSURE, LIGHT }

object MirrorTestTags {
    const val SCREEN = "mirror_screen"
    const val PREVIEW = "mirror_preview"
    const val FROZEN_IMAGE = "frozen_image"
    const val RING_LIGHT = "ring_light"
    const val CONTROLS = "controls"
    const val BOTTOM_BAR = "controls_bottom_bar"
    const val SIDE_RAIL = "controls_side_rail"
    const val ADJUST_PANEL = "adjust_panel"
    const val ZOOM_SLIDER = "zoom_slider"
    const val EXPOSURE_SLIDER = "exposure_slider"
    const val INTENSITY_SLIDER = "intensity_slider"
    const val CAPTURE = "capture_button"
    const val FROZEN_BADGE = "frozen_badge"
    const val ZOOM_LABEL = "zoom_label"
    const val MODE_LABEL = "mode_label"
    const val TAP_HINT = "tap_hint"
    const val MESSAGE = "message"
    const val CAMERA_ERROR = "camera_error"
    const val SETTINGS_SHEET = "settings_sheet"
}

/** Frozen-image scale presets for the zoom panel while frozen (MVP-07.AC3: up to 8x). */
private val FROZEN_PRESETS = listOf(1f, 2f, 4f, 8f)

@Composable
fun ToolTabs(
    tools: List<ControlTool>,
    selected: ControlTool,
    onSelect: (ControlTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        tools.forEachIndexed { index, tool ->
            SegmentedButton(
                selected = tool == selected,
                onClick = { onSelect(tool) },
                shape = SegmentedButtonDefaults.itemShape(index, tools.size),
                modifier = Modifier.heightIn(min = 48.dp),
                colors = segmentedColors(),
                label = { Text(stringResource(tool.labelRes()), maxLines = 1) },
            )
        }
    }
}

private fun ControlTool.labelRes() = when (this) {
    ControlTool.ZOOM -> R.string.control_zoom
    ControlTool.EXPOSURE -> R.string.control_exposure
    ControlTool.LIGHT -> R.string.control_light
}

@Composable
private fun segmentedColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = OnControlScrim,
)

/** The adjustment for the selected [tool]: zoom, exposure or ring light. */
@Composable
fun AdjustPanel(
    state: MirrorUiState,
    tool: ControlTool,
    onIntent: (MirrorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(MirrorTestTags.ADJUST_PANEL), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (tool) {
            ControlTool.ZOOM -> ZoomPanel(state, onIntent)
            ControlTool.EXPOSURE -> ExposurePanel(state, onIntent)
            ControlTool.LIGHT -> LightPanel(state, onIntent)
        }
    }
}

@Composable
private fun ZoomPanel(state: MirrorUiState, onIntent: (MirrorIntent) -> Unit) {
    val caps = state.capabilities
    val frozen = state.isFrozen
    val value = if (frozen) state.frozenImageScale else state.zoomRatio
    val range = when {
        frozen -> 1f..MirrorUiState.MAX_FROZEN_IMAGE_SCALE
        caps != null && caps.supportsZoom -> caps.minZoomRatio..caps.maxZoomRatio
        else -> 1f..1.01f
    }
    val enabled = frozen || caps?.supportsZoom == true
    val valueText = stringResource(R.string.zoom_value, value)
    val zoomLabel = stringResource(R.string.control_zoom)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(valueText, color = OnControlScrim, modifier = Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onIntent(MirrorIntent.SetZoom(it)) },
            valueRange = range,
            enabled = enabled,
            colors = sliderColors(),
            modifier = Modifier
                .weight(1f)
                .testTag(MirrorTestTags.ZOOM_SLIDER)
                .semantics {
                    contentDescription = zoomLabel
                    stateDescription = valueText
                },
        )
    }
    val presets = if (frozen) FROZEN_PRESETS else state.zoomPresets
    if (presets.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            presets.forEach { preset ->
                val selected = abs(value - preset) < 0.05f
                val description = stringResource(R.string.zoom_preset_description, preset)
                TextButton(
                    onClick = { onIntent(MirrorIntent.SetZoom(preset)) },
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = description
                            this.selected = selected
                        },
                ) {
                    Text(
                        stringResource(R.string.zoom_preset, preset),
                        color = if (selected) MaterialTheme.colorScheme.primary else OnControlScrim,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExposurePanel(state: MirrorUiState, onIntent: (MirrorIntent) -> Unit) {
    val caps = state.capabilities ?: return
    if (!state.showExposureControl) return
    val stepCount = ((caps.maxExposureEv - caps.minExposureEv) / caps.exposureStepEv).roundToInt()
    val valueText = stringResource(R.string.exposure_value, state.exposureEv)
    val label = stringResource(R.string.control_exposure)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(valueText, color = OnControlScrim, modifier = Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center)
        Slider(
            value = state.exposureEv,
            // The shared reducer snaps to the device EV step, so 0 is always an exact detent (MVP-04.AC1).
            onValueChange = { onIntent(MirrorIntent.SetExposure(it)) },
            valueRange = caps.minExposureEv..caps.maxExposureEv,
            steps = (stepCount - 1).coerceIn(0, 60),
            colors = sliderColors(),
            modifier = Modifier
                .weight(1f)
                .testTag(MirrorTestTags.EXPOSURE_SLIDER)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                },
        )
        ControlIconButton(
            icon = Icons.Filled.Refresh,
            contentDescription = stringResource(R.string.control_exposure_reset),
            onClick = { onIntent(MirrorIntent.ResetExposure) },
            enabled = state.exposureEv != 0f,
        )
    }
}

@Composable
private fun LightPanel(state: MirrorUiState, onIntent: (MirrorIntent) -> Unit) {
    val ring = state.settings.ringLight
    val presets = LightIntensityPreset.entries
    Text(stringResource(R.string.light_intensity), color = OnControlScrim, style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        presets.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = ring.enabled && abs(ring.intensity - preset.intensity) < 0.01f,
                onClick = { onIntent(MirrorIntent.SetRingLightIntensity(preset.intensity)) },
                shape = SegmentedButtonDefaults.itemShape(index, presets.size),
                modifier = Modifier.heightIn(min = 48.dp),
                colors = segmentedColors(),
                label = { Text(stringResource(preset.labelRes()), maxLines = 1) },
            )
        }
    }
    val percent = (ring.intensity * 100).roundToInt()
    val percentText = stringResource(R.string.light_intensity_value, percent)
    val intensityLabel = stringResource(R.string.light_intensity)
    Slider(
        value = ring.intensity,
        onValueChange = { onIntent(MirrorIntent.SetRingLightIntensity(it)) },
        valueRange = 0.1f..1f,
        colors = sliderColors(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MirrorTestTags.INTENSITY_SLIDER)
            .semantics {
                contentDescription = intensityLabel
                stateDescription = percentText
            },
    )
    Text(stringResource(R.string.light_warmth), color = OnControlScrim, style = MaterialTheme.typography.labelLarge)
    val warmths = listOf(LightWarmth.COOL, LightWarmth.DAYLIGHT, LightWarmth.WARM)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        warmths.forEachIndexed { index, warmth ->
            SegmentedButton(
                selected = ring.enabled && ring.warmth == warmth,
                onClick = { onIntent(MirrorIntent.SetLightWarmth(warmth)) },
                shape = SegmentedButtonDefaults.itemShape(index, warmths.size),
                modifier = Modifier.heightIn(min = 48.dp),
                colors = segmentedColors(),
                label = { Text(stringResource(warmth.labelRes()), maxLines = 1) },
            )
        }
    }
}

private fun LightIntensityPreset.labelRes() = when (this) {
    LightIntensityPreset.LOW -> R.string.intensity_low
    LightIntensityPreset.MEDIUM -> R.string.intensity_medium
    LightIntensityPreset.HIGH -> R.string.intensity_high
}

fun LightWarmth.labelRes() = when (this) {
    LightWarmth.COOL -> R.string.warmth_cool
    LightWarmth.DAYLIGHT -> R.string.warmth_daylight
    LightWarmth.WARM -> R.string.warmth_warm
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
)

/** Round icon button with its own scrim; >= 48dp touch target (NFR-10). */
@Composable
fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 48.dp,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else ControlScrim,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else OnControlScrim,
            disabledContainerColor = ControlScrim,
            disabledContentColor = OnControlScrim.copy(alpha = 0.38f),
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/** Big shutter button (72dp). */
@Composable
fun CaptureButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val description = stringResource(R.string.control_capture)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(4.dp, if (enabled) Color.White else Color.White.copy(alpha = 0.38f)),
        modifier = modifier
            .size(size)
            .testTag(MirrorTestTags.CAPTURE)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Box(Modifier.padding(8.dp).clip(CircleShape).background(if (enabled) Color.White else Color.White.copy(alpha = 0.38f)))
    }
}

/** The four primary actions besides capture, shared by bar and rail layouts. */
@Composable
fun MirrorToggleButton(state: MirrorUiState, onIntent: (MirrorIntent) -> Unit, size: Dp) = ControlIconButton(
    icon = Icons.Filled.Flip,
    contentDescription = stringResource(if (state.previewMirrored) R.string.control_mirror_on else R.string.control_true_view_on),
    onClick = { onIntent(MirrorIntent.ToggleMirrorMode) },
    selected = !state.previewMirrored,
    size = size,
)

@Composable
fun FreezeButton(state: MirrorUiState, onIntent: (MirrorIntent) -> Unit, size: Dp) = ControlIconButton(
    icon = if (state.isFrozen) Icons.Filled.PlayArrow else Icons.Filled.Pause,
    contentDescription = stringResource(if (state.isFrozen) R.string.control_unfreeze else R.string.control_freeze),
    onClick = { onIntent(MirrorIntent.ToggleFreeze) },
    enabled = state.isFrozen || state.canFreeze,
    selected = state.isFrozen,
    size = size,
)

@Composable
fun LightToggleButton(state: MirrorUiState, onIntent: (MirrorIntent) -> Unit, size: Dp) {
    val on = state.settings.ringLight.enabled
    ControlIconButton(
        icon = if (on) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
        contentDescription = stringResource(if (on) R.string.control_light_on else R.string.control_light_off),
        onClick = { onIntent(MirrorIntent.ToggleRingLight) },
        selected = on,
        size = size,
    )
}

@Composable
fun SettingsButton(onIntent: (MirrorIntent) -> Unit, size: Dp) = ControlIconButton(
    icon = Icons.Filled.Settings,
    contentDescription = stringResource(R.string.control_settings),
    onClick = { onIntent(MirrorIntent.SetSettingsOpen(true)) },
    size = size,
)

/** Rounded scrim container for control groups. */
fun Modifier.controlScrim(radius: Dp = 24.dp): Modifier =
    this
        .clip(RoundedCornerShape(radius))
        .background(ControlScrim)
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(radius))
