package com.smart.mirror.app.ui.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraFront
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smart.mirror.app.R
import com.smart.mirror.app.presentation.mirror.MirrorIntent

object PermissionTestTags {
    const val ONBOARDING = "screen_onboarding"
    const val DENIED = "screen_denied"
    const val BLOCKED = "screen_blocked"
    const val OVERHEATED = "screen_overheated"
    const val LOADING = "screen_loading"
}

/** MVP-12.AC1: one friendly screen, one primary button that shows the OS prompt. */
@Composable
fun OnboardingScreen(onIntent: (MirrorIntent) -> Unit) = InfoScreen(
    tag = PermissionTestTags.ONBOARDING,
    icon = Icons.Outlined.CameraFront,
    title = stringResource(R.string.onboarding_title),
    body = stringResource(R.string.onboarding_body),
) {
    Button(onClick = { onIntent(MirrorIntent.RequestCameraPermission) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.onboarding_allow))
    }
}

/** MVP-12.AC3: denied but the dialog can still be shown: "Try again" + "Open Settings". */
@Composable
fun PermissionDeniedScreen(onIntent: (MirrorIntent) -> Unit) = InfoScreen(
    tag = PermissionTestTags.DENIED,
    icon = Icons.Outlined.NoPhotography,
    title = stringResource(R.string.denied_title),
    body = stringResource(R.string.denied_body),
) {
    Button(onClick = { onIntent(MirrorIntent.RequestCameraPermission) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.denied_try_again))
    }
    OutlinedButton(onClick = { onIntent(MirrorIntent.OpenAppSettings) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.open_settings))
    }
}

/** MVP-12.AC3: "Don't ask again": only the Settings path is shown. */
@Composable
fun PermissionBlockedScreen(onIntent: (MirrorIntent) -> Unit) = InfoScreen(
    tag = PermissionTestTags.BLOCKED,
    icon = Icons.Outlined.NoPhotography,
    title = stringResource(R.string.denied_title),
    body = stringResource(R.string.blocked_body),
) {
    Button(onClick = { onIntent(MirrorIntent.OpenAppSettings) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.open_settings))
    }
}

/** NFR-06: thermal CRITICAL pauses the preview. */
@Composable
fun OverheatedScreen() = InfoScreen(
    tag = PermissionTestTags.OVERHEATED,
    icon = Icons.Outlined.Thermostat,
    title = stringResource(R.string.overheated_title),
    body = stringResource(R.string.overheated_body),
) {}

/** Permission not checked yet: plain black, never flash onboarding (MVP-12.AC7). */
@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(PermissionTestTags.LOADING))
}

@Composable
private fun InfoScreen(
    tag: String,
    icon: ImageVector,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        }
    }
}
