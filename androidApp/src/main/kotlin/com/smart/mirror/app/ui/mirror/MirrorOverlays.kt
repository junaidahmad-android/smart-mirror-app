package com.smart.mirror.app.ui.mirror

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smart.mirror.app.R
import com.smart.mirror.app.domain.model.CameraError
import com.smart.mirror.app.domain.model.RingLightAppearance
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.presentation.mirror.UserMessage
import com.smart.mirror.app.ui.theme.OnControlScrim
import kotlin.math.min

/**
 * Ring light drawn as a border over the preview (ROADMAP open decision 1: overlay border),
 * inset from display cutouts so the light is never hidden by them (MVP-05.AC1). Thickness is a
 * fraction of the short screen side from the shared [RingLightAppearance] (>= 12% at 100%).
 */
@Composable
fun RingLight(appearance: RingLightAppearance, modifier: Modifier = Modifier) {
    if (!appearance.visible) return
    val color = Color(appearance.color.argb).copy(alpha = appearance.opacity)
    Canvas(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .testTag(MirrorTestTags.RING_LIGHT),
    ) {
        val band = appearance.edgeWidthFraction * min(size.width, size.height)
        val inner = RoundRect(
            left = band,
            top = band,
            right = size.width - band,
            bottom = size.height - band,
            cornerRadius = CornerRadius(band * 0.75f),
        )
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height)))
            addRoundRect(inner)
        }
        drawPath(path, color)
    }
}

/** Small scrim "pill" used for transient labels and badges. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = OnControlScrim,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .controlScrim()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Shown while frozen; never auto-hidden (MVP-07.AC2). */
@Composable
fun FrozenBadge(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .testTag(MirrorTestTags.FROZEN_BADGE)
            .controlScrim()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.frozen_label), color = OnControlScrim, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
    }
}

/** Transient message (toast), announced by screen readers (MVP-08.AC4). */
@Composable
fun MessageToast(message: UserMessage, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    val text = stringResource(
        when (message) {
            UserMessage.PHOTO_SAVED -> R.string.message_saved
            UserMessage.CAPTURE_FAILED -> R.string.message_capture_failed
            UserMessage.SAVE_PERMISSION_DENIED -> R.string.message_save_permission
            UserMessage.STORAGE_FULL -> R.string.message_storage_full
            UserMessage.THERMAL_THROTTLED -> R.string.message_thermal
        },
    )
    Row(
        modifier
            .testTag(MirrorTestTags.MESSAGE)
            .widthIn(max = 480.dp)
            .controlScrim(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = OnControlScrim, modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
        if (message == UserMessage.SAVE_PERMISSION_DENIED) {
            // MVP-08.AC5: non-blocking explanation with a link to Settings.
            TextButton(onClick = { onIntent(MirrorIntent.OpenAppSettings) }) {
                Text(stringResource(R.string.message_action_settings))
            }
        }
    }
}

/** Camera failure / unavailable state with retry (MVP-12.AC4, AC5). */
@Composable
fun CameraErrorCard(error: CameraError, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    val unavailable = error == CameraError.IN_USE_BY_ANOTHER_APP
    Column(
        modifier
            .testTag(MirrorTestTags.CAMERA_ERROR)
            .widthIn(max = 420.dp)
            .controlScrim()
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (unavailable) R.string.camera_unavailable_title else R.string.camera_error_title),
            style = MaterialTheme.typography.titleLarge,
            color = OnControlScrim,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(
                when (error) {
                    CameraError.IN_USE_BY_ANOTHER_APP -> R.string.camera_unavailable_body
                    CameraError.NO_FRONT_CAMERA -> R.string.camera_error_no_front
                    CameraError.DISCONNECTED -> R.string.camera_error_disconnected
                    CameraError.PERMISSION_DENIED, CameraError.UNKNOWN -> R.string.camera_error_unknown
                },
            ),
            color = OnControlScrim,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { onIntent(MirrorIntent.RetryCamera) }) { Text(stringResource(R.string.retry)) }
    }
}

/**
 * Makes an overlay hit-testable so taps on its empty areas don't fall through to the preview's
 * tap-to-toggle handler below it. Consumes nothing, so children still get their events.
 * [onTouchDown] (observed in the Initial pass, before children) lets any touch on the controls
 * restart the auto-hide timer (MVP-02.AC2).
 */
fun Modifier.blockPreviewTaps(onTouchDown: (() -> Unit)? = null): Modifier = pointerInput(onTouchDown) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (onTouchDown != null && event.changes.any { it.changedToDown() }) onTouchDown()
        }
    }
}
