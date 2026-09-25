package com.smart.mirror.app.ui.mirror

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smart.mirror.app.R
import com.smart.mirror.app.domain.model.CameraStatus
import com.smart.mirror.app.domain.model.CaptureStatus
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.presentation.mirror.MirrorUiState
import com.smart.mirror.app.presentation.mirror.UserMessage
import kotlinx.coroutines.delay

/**
 * The live mirror: full-bleed preview, ring light, and the tap-to-reveal controls.
 *
 * Pure rendering of [MirrorUiState] plus UI-owned concerns (gesture recognition, frozen-image
 * pan, transient labels, flash/haptics, which adjustment tab is open). [preview] is a slot so
 * UI tests can run without a camera.
 */
@Composable
fun MirrorScreen(
    state: MirrorUiState,
    onIntent: (MirrorIntent) -> Unit,
    layout: MirrorLayoutSpec,
    frozenFrame: ImageBitmap?,
    reduceMotion: Boolean,
    appVersion: String,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentState by rememberUpdatedState(state)
    val currentOnIntent by rememberUpdatedState(onIntent)
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Hoisted so the last-used adjustment tab survives the controls hiding (and rotation).
    var selectedTool by rememberSaveable { mutableStateOf(ControlTool.ZOOM) }

    // Keep the frozen-image pan inside the image whenever the scale changes; reset on resume.
    LaunchedEffect(state.frozenImageScale, frozenFrame, viewportSize) {
        pan = if (frozenFrame == null) {
            Offset.Zero
        } else {
            val (x, y) = clampPan(pan.x, pan.y, state.frozenImageScale, viewportSize.width.toFloat(), viewportSize.height.toFloat())
            Offset(x, y)
        }
    }

    val previewDescription = stringResource(if (frozenFrame != null) R.string.preview_frozen_description else R.string.preview_description)
    val tapActionLabel = stringResource(if (state.controlsVisible) R.string.action_hide_controls else R.string.action_show_controls)

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(MirrorTestTags.SCREEN),
    ) {
        // 1. Preview + gestures (tap, double tap, pinch; pan when frozen).
        Box(
            Modifier
                .fillMaxSize()
                .testTag(MirrorTestTags.PREVIEW)
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        if (zoom != 1f) currentOnIntent(MirrorIntent.PinchZoom(zoom))
                        if (currentState.isFrozen) {
                            val (x, y) = clampPan(
                                pan.x + panChange.x,
                                pan.y + panChange.y,
                                currentState.frozenImageScale,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                            pan = Offset(x, y)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapsAndDoubleTaps(
                        onTap = { currentOnIntent(MirrorIntent.ScreenTapped) },
                        onDoubleTap = {
                            // The first tap already toggled the controls; undo that, then zoom (MVP-03.AC3).
                            currentOnIntent(MirrorIntent.ScreenTapped)
                            currentOnIntent(MirrorIntent.ToggleZoom)
                        },
                    )
                }
                .semantics {
                    contentDescription = previewDescription
                    onClick(label = tapActionLabel) {
                        currentOnIntent(MirrorIntent.ScreenTapped)
                        true
                    }
                },
        ) {
            preview(Modifier.fillMaxSize())
            if (frozenFrame != null) {
                Image(
                    bitmap = frozenFrame,
                    contentDescription = null,
                    // Crop (aspect fill), never FillBounds: after rotating while frozen the
                    // landscape frame must not be stretched into a portrait viewport (QA-BUG-02).
                    // Same size as when frozen -> identical to FillBounds, i.e. exactly what was shown.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(MirrorTestTags.FROZEN_IMAGE)
                        .graphicsLayer {
                            val s = state.frozenImageScale
                            // Same flip as the live PreviewView: the frame is stored mirrored.
                            scaleX = s * if (state.previewMirrored) 1f else -1f
                            scaleY = s
                            translationX = pan.x
                            translationY = pan.y
                        },
                )
            }
        }

        // 2. Ring light over the preview, under the controls.
        RingLight(state.ringLight)

        // 3. Shutter flash + haptic feedback (skipped with reduce motion, NFR-10).
        CaptureFeedback(state, reduceMotion)

        // 4. Centre labels: tap hint, transient zoom / mode labels.
        TransientLabels(state, reduceMotion, Modifier.align(Alignment.Center))

        // 5. Camera error / unavailable.
        val error = state.cameraError
        if (state.cameraStatus == CameraStatus.ERROR && error != null) {
            CameraErrorCard(error, onIntent, Modifier.align(Alignment.Center).padding(24.dp).blockPreviewTaps())
        }

        // 6. Controls overlay (MVP-02): fades in/out within 150 ms.
        AnimatedVisibility(
            visible = state.controlsVisible && state.cameraStatus != CameraStatus.ERROR,
            enter = fadeIn(tween(if (reduceMotion) 0 else 150)),
            exit = fadeOut(tween(if (reduceMotion) 0 else 150)),
            modifier = Modifier.fillMaxSize(),
        ) {
            MirrorControls(state, onIntent, layout, selectedTool) { selectedTool = it }
        }

        // 7. Top: frozen badge (always visible while frozen) and messages.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isFrozen) FrozenBadge(onResume = { onIntent(MirrorIntent.ToggleFreeze) }, Modifier.blockPreviewTaps())
            state.message?.let { message ->
                // New node per message event, so a repeated "Photo saved" is re-announced (QA-BUG-06).
                key(state.messageId) { MessageToast(message, onIntent, Modifier.blockPreviewTaps()) }
            }
        }

        // 8. Settings (MVP-15).
        if (state.settingsOpen) {
            SettingsSheet(state, onIntent, appVersion)
        }
    }
}

@Composable
private fun MirrorControls(
    state: MirrorUiState,
    onIntent: (MirrorIntent) -> Unit,
    layout: MirrorLayoutSpec,
    selectedTool: ControlTool,
    onSelectTool: (ControlTool) -> Unit,
) {
    val tools = buildList {
        add(ControlTool.ZOOM)
        if (state.showExposureControl) add(ControlTool.EXPOSURE)
        add(ControlTool.LIGHT)
    }
    val touched = remember(onIntent) { { onIntent(MirrorIntent.ControlsInteracted) } }
    val tool = if (selectedTool in tools) selectedTool else ControlTool.ZOOM
    val gap = if (layout.spacious) 24.dp else 12.dp
    val buttonSize = if (layout.spacious) 56.dp else 48.dp
    val captureSize = if (layout.spacious) 80.dp else 72.dp
    val panelMaxWidth = if (layout.spacious) 480.dp else 420.dp

    Box(Modifier.fillMaxSize().testTag(MirrorTestTags.CONTROLS)) {
        when (layout.placement) {
            ControlsPlacement.BOTTOM_BAR -> Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .blockPreviewTaps(touched)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = gap, vertical = 8.dp)
                    .testTag(MirrorTestTags.BOTTOM_BAR),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Tablets in portrait: keep the panel a comfortable width, don't stretch it (MVP-10.AC3).
                Column(
                    Modifier
                        .widthIn(max = panelMaxWidth)
                        .fillMaxWidth()
                        .controlScrim()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AdjustPanel(state, tool, onIntent, Modifier.fillMaxWidth())
                    ToolTabs(tools, tool, onSelectTool, Modifier.fillMaxWidth())
                }
                Row(
                    Modifier.widthIn(max = panelMaxWidth).fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MirrorToggleButton(state, onIntent, buttonSize)
                    FreezeButton(state, onIntent, buttonSize)
                    CaptureButton(state.canCapture, { onIntent(MirrorIntent.CapturePhoto) }, size = captureSize)
                    LightToggleButton(state, onIntent, buttonSize)
                    SettingsButton(onIntent, buttonSize)
                }
            }

            ControlsPlacement.SIDE_RAIL -> {
                val safe = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(gap)
                // Action rail on the end edge; capture in the middle for thumb reach.
                Column(
                    safe
                        .align(Alignment.CenterEnd)
                        .blockPreviewTaps(touched)
                        .controlScrim(40.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                        .testTag(MirrorTestTags.SIDE_RAIL),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (layout.spacious) 16.dp else 8.dp),
                ) {
                    SettingsButton(onIntent, buttonSize)
                    MirrorToggleButton(state, onIntent, buttonSize)
                    CaptureButton(state.canCapture, { onIntent(MirrorIntent.CapturePhoto) }, size = captureSize)
                    FreezeButton(state, onIntent, buttonSize)
                    LightToggleButton(state, onIntent, buttonSize)
                }
                // Adjustment panel at the bottom-start corner, width-capped.
                Column(
                    safe
                        .align(Alignment.BottomStart)
                        .padding(end = captureSize + gap * 2)
                        .widthIn(max = panelMaxWidth)
                        .blockPreviewTaps(touched)
                        .controlScrim()
                        // Phone landscape is short: let the panel scroll instead of clipping (also at 200% font).
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AdjustPanel(state, tool, onIntent, Modifier.fillMaxWidth())
                    ToolTabs(tools, tool, onSelectTool, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TransientLabels(state: MirrorUiState, reduceMotion: Boolean, modifier: Modifier) {
    val zoom = if (state.isFrozen) state.frozenImageScale else state.zoomRatio
    val showZoom = rememberTransient(zoom, enabled = state.cameraStatus == CameraStatus.RUNNING)
    val showMode = rememberTransient(state.previewMirrored, enabled = state.cameraStatus == CameraStatus.RUNNING)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.showTapHint) Pill(stringResource(R.string.tap_hint), Modifier.testTag(MirrorTestTags.TAP_HINT))
        FadeIf(showZoom, reduceMotion) {
            Pill(stringResource(R.string.zoom_value, zoom), Modifier.testTag(MirrorTestTags.ZOOM_LABEL))
        }
        FadeIf(showMode, reduceMotion) {
            Pill(
                stringResource(if (state.previewMirrored) R.string.mode_mirror else R.string.mode_true_view),
                Modifier.testTag(MirrorTestTags.MODE_LABEL),
            )
        }
    }
}

@Composable
private fun FadeIf(visible: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (reduceMotion) 0 else 120)),
        exit = fadeOut(tween(if (reduceMotion) 0 else 250)),
    ) { content() }
}

/**
 * True for [TRANSIENT_LABEL_MILLIS] after [key] changes (not on first composition), e.g. the
 * "2.5x" zoom label disappears 1 s after zooming stops (MVP-03.AC4, MVP-09.AC2).
 */
@Composable
private fun <T> rememberTransient(key: T, enabled: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf(key) }
    LaunchedEffect(key, enabled) {
        if (key == previous || !enabled) {
            previous = key
            return@LaunchedEffect
        }
        previous = key
        visible = true
        delay(TRANSIENT_LABEL_MILLIS)
        visible = false
    }
    return visible
}

@Composable
private fun CaptureFeedback(state: MirrorUiState, reduceMotion: Boolean) {
    val flash = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.captureStatus) {
        if (state.captureStatus == CaptureStatus.CAPTURING && !reduceMotion) {
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(250))
        }
    }
    LaunchedEffect(state.messageId) {
        if (state.message == UserMessage.PHOTO_SAVED) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    if (flash.value > 0f) Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
}

/**
 * Tap detector that reports single taps immediately (MVP-02.AC1 wants controls within 150 ms,
 * so we don't wait out the double-tap timeout) and reports a second quick tap as a double tap.
 * A pinch/drag is consumed by the transform detector, which cancels the tap (MVP-02.AC5).
 */
private suspend fun PointerInputScope.detectTapsAndDoubleTaps(onTap: () -> Unit, onDoubleTap: () -> Unit) {
    var lastTapUptime = Long.MIN_VALUE / 2
    var lastTapPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown()
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        up.consume()
        val isDouble = up.uptimeMillis - lastTapUptime <= viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastTapPosition).getDistance() <= viewConfiguration.touchSlop * 4
        if (isDouble) {
            lastTapUptime = Long.MIN_VALUE / 2
            onDoubleTap()
        } else {
            lastTapUptime = up.uptimeMillis
            lastTapPosition = down.position
            onTap()
        }
    }
}

private const val TRANSIENT_LABEL_MILLIS = 1_000L
