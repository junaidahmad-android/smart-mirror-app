package com.smart.mirror.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.smart.mirror.app.data.settings.SharedPreferencesKeyValueStore
import com.smart.mirror.app.domain.model.MirrorSettings
import org.junit.rules.ExternalResource
import kotlin.test.assertEquals

/**
 * Makes end-to-end tests hermetic with respect to persisted user settings.
 *
 * The real app keeps settings in two places that outlive a single test:
 * - on disk (`SharedPreferences` "smart_mirror_settings"), which survives `installDebug -r`, so
 *   anything left by manual/exploratory use or an earlier `am instrument` run leaks into the
 *   next connected run (e.g. TRUE_VIEW or ring light on);
 * - in memory, in the app-scoped `SharedGraph` owned by [SmartMirrorApplication], which lives as
 *   long as the instrumentation process (all tests of a run, unless the orchestrator is used).
 *   A test that toggles a setting (e.g. frozen capture switching to true view) therefore changes
 *   the starting state of every later test in the same process.
 *
 * Before each test (and again after it) this rule wipes the preferences file and puts the
 * in-memory repository back to [MirrorSettings.DEFAULT], i.e. a fresh-install state. It must
 * wrap the Activity rule so the reset happens before the Activity (and its ViewModel) is created.
 *
 * Test-only: uses the app's public graph API; production code is unchanged.
 */
class ResetAppSettingsRule : ExternalResource() {
    override fun before() = reset()
    override fun after() = reset()

    private fun reset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as SmartMirrorApplication
        instrumentation.runOnMainSync {
            app.getSharedPreferences(SharedPreferencesKeyValueStore.DEFAULT_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
            app.graph.settingsRepository.update { MirrorSettings.DEFAULT }
        }
        assertEquals(MirrorSettings.DEFAULT, app.graph.settingsRepository.settings.value, "settings reset to defaults")
    }
}

/** Generous bounds for a cold CI emulator (software GPU, first CameraX open). */
object E2eTimeouts {
    const val SCREEN_MS = 20_000L
    const val CAMERA_START_MS = 30_000L
    const val UI_MS = 10_000L
    const val SAVE_MS = 20_000L
}
