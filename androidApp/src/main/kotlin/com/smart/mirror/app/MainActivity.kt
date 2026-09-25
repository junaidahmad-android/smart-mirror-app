package com.smart.mirror.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smart.mirror.app.platform.system.SignalSubscription
import com.smart.mirror.app.platform.system.observeScreenReader
import com.smart.mirror.app.platform.system.observeThermalState
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.ui.CameraPreview
import com.smart.mirror.app.ui.MirrorApp
import com.smart.mirror.app.ui.theme.SmartMirrorTheme

/**
 * Single-activity host. Rotation, resizing and fold changes are handled in place
 * (`android:configChanges` in the manifest) so the camera is never re-opened (MVP-10.AC2);
 * if the Activity is recreated anyway (e.g. density change), the shared ViewModel and adapters
 * survive in [MirrorHostViewModel] and just re-attach.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MirrorHostViewModel by viewModels {
        MirrorHostViewModel.factory(application as SmartMirrorApplication)
    }
    private val subscriptions = mutableListOf<SignalSubscription>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Draw under cutouts too (MVP-01.AC1); controls use safeDrawing insets.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        viewModel.platform.attach(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = viewModel.onIntent(MirrorIntent.ScreenStarted)
            override fun onStop(owner: LifecycleOwner) = viewModel.onIntent(MirrorIntent.ScreenStopped)
        })
        subscriptions += observeThermalState(this) { viewModel.onIntent(MirrorIntent.ThermalStateChanged(it)) }
        subscriptions += observeScreenReader(this) { viewModel.onIntent(MirrorIntent.SetScreenReaderActive(it)) }

        setContent {
            SmartMirrorTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val frozen by viewModel.frozenFrame.collectAsStateWithLifecycle()
                val frozenImage = remember(frozen) { frozen?.asImageBitmap() }
                MirrorApp(
                    state = state,
                    onIntent = viewModel::onIntent,
                    frozenFrame = frozenImage,
                    appVersion = BuildConfig.VERSION_NAME,
                    preview = { modifier -> CameraPreview(viewModel.platform.camera, modifier) },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        subscriptions.forEach { it.dispose() }
        subscriptions.clear()
        viewModel.platform.detach(this)
        super.onDestroy()
    }

    /** Immersive: no system bars over the mirror; a swipe shows them transiently. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
