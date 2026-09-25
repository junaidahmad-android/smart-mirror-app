package com.smart.mirror.app.ui.mirror

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smart.mirror.app.R
import com.smart.mirror.app.presentation.mirror.MirrorIntent
import com.smart.mirror.app.presentation.mirror.MirrorUiState

/** Minimal settings / about (MVP-15). Opening it pauses control auto-hide (shared logic). */
@Composable
fun SettingsSheet(state: MirrorUiState, onIntent: (MirrorIntent) -> Unit, appVersion: String) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val supportEmail = stringResource(R.string.support_email)
    val supportSubject = stringResource(R.string.support_subject)

    ModalBottomSheet(
        onDismissRequest = { onIntent(MirrorIntent.SetSettingsOpen(false)) },
        sheetState = sheetState,
        modifier = Modifier.testTag(MirrorTestTags.SETTINGS_SHEET),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            val autoMax = state.settings.autoMaxBrightness
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = autoMax,
                        role = Role.Switch,
                        onValueChange = { onIntent(MirrorIntent.SetAutoMaxBrightness(it)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_auto_brightness), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_auto_brightness_summary), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = autoMax, onCheckedChange = null)
            }
            OutlinedButton(
                onClick = { onIntent(MirrorIntent.ResetSettingsToDefaults) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_reset)) }

            HorizontalDivider()
            Text(
                stringResource(R.string.settings_privacy_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            TextButton(onClick = { showLicenses = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_licenses))
            }
            TextButton(
                onClick = {
                    // mailto opens the user's mail app; the app itself makes no network request.
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$supportEmail"))
                        .putExtra(Intent.EXTRA_SUBJECT, supportSubject)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_support)) }
            Text(stringResource(R.string.settings_version, appVersion), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.settings_close)) } },
            title = { Text(stringResource(R.string.settings_licenses)) },
            text = { Text(stringResource(R.string.licenses_body)) },
        )
    }
}
