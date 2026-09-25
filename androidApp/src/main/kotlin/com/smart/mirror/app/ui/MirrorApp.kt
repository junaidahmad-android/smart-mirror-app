package com.smart.mirror.app.ui

import android.provider.Settings
import androidx.camera.view.PreviewView
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.window.core.layout.WindowSizeClass
import com.smart.mirror.app.platform.camera.CameraXCameraController
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.presentation.mirror.MirrorScreen
import com.smart.mirror.app.presentation.mirror.MirrorUiState
import com.smart.mirror.app.ui.mirror.MirrorLayoutSpec
import com.smart.mirror.app.ui.mirror.MirrorScreen as MirrorScreenUi
import com.smart.mirror.app.ui.permission.LoadingScreen
import com.smart.mirror.app.ui.permission.OnboardingScreen
import com.smart.mirror.app.ui.permission.OverheatedScreen
import com.smart.mirror.app.ui.permission.PermissionBlockedScreen
import com.smart.mirror.app.ui.permission.PermissionDeniedScreen

/** Top-level router: one native screen per shared [MirrorScreen] value. */
@Composable
fun MirrorApp(
    state: MirrorUiState,
    onIntent: (MirrorIntent) -> Unit,
    frozenFrame: ImageBitmap?,
    appVersion: String,
    preview: @Composable (Modifier) -> Unit,
    layout: MirrorLayoutSpec = rememberMirrorLayoutSpec(),
    reduceMotion: Boolean = rememberReduceMotion(),
) {
    when (state.screen) {
        MirrorScreen.LOADING -> LoadingScreen()
        MirrorScreen.PERMISSION_ONBOARDING -> OnboardingScreen(onIntent)
        MirrorScreen.PERMISSION_DENIED -> PermissionDeniedScreen(onIntent)
        MirrorScreen.PERMISSION_BLOCKED -> PermissionBlockedScreen(onIntent)
        MirrorScreen.OVERHEATED -> OverheatedScreen()
        MirrorScreen.MIRROR -> MirrorScreenUi(
            state = state,
            onIntent = onIntent,
            layout = layout,
            frozenFrame = frozenFrame,
            reduceMotion = reduceMotion,
            appVersion = appVersion,
            preview = preview,
        )
    }
}

/** Window size class (material3-adaptive) + aspect -> bottom bar or side rail. */
@Composable
fun rememberMirrorLayoutSpec(): MirrorLayoutSpec {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    return remember(sizeClass, landscape) {
        MirrorLayoutSpec.from(
            widthAtLeastMedium = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND),
            widthAtLeastExpanded = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND),
            heightAtLeastMedium = sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND),
            isLandscape = landscape,
        )
    }
}

/** "Remove animations" (animator duration scale 0) means reduce motion (NFR-10). */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * The CameraX viewfinder: a [PreviewView] owned by Compose and attached to the camera adapter.
 * See [CameraXCameraController] for why PreviewView/TextureView over `CameraXViewfinder`.
 */
@Composable
fun CameraPreview(camera: CameraXCameraController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PreviewView(context).also { camera.attachPreviewView(it) }
        },
        onRelease = { camera.detachPreviewView(it) },
        modifier = modifier,
    )
}
