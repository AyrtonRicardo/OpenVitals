package tech.mmarca.openvitals.features.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.ui.components.SectionHeader
import tech.mmarca.openvitals.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openvitals.ui.theme.LayoutMetrics

/** Share with other apps: the switch, the data groups and the apps that have asked. */
@Composable
fun DataSharingSettingsScreen(viewModel: DataSharingSettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Grants change in Android's settings, outside this screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val backgroundAccessLauncher = rememberHealthConnectPermissionLauncher(onResult = viewModel::refresh)

    SettingsSectionList {
        item { SectionHeader(stringResource(SettingsSection.DATA_SHARING.titleRes)) }
        item {
            DataSharingSwitchCard(
                enabled = state.sharingEnabled,
                backgroundReadMissing = state.backgroundReadMissing,
                onEnabledChange = viewModel::setSharingEnabled,
                onAllowBackgroundRead = { backgroundAccessLauncher.launch(setOf(viewModel.backgroundReadPermission)) },
                modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter),
            )
        }
        item { SectionHeader(stringResource(R.string.data_sharing_groups_header)) }
        item { DataSharingGroupsCard(modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter)) }
        item { SectionHeader(stringResource(R.string.data_sharing_apps_header)) }
        if (state.apps.isEmpty()) {
            item { DataSharingNoAppsCard(modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter)) }
        }
        items(state.apps, key = { it.packageName }) { app ->
            DataSharingAppCard(
                app = app,
                onManage = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", app.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                onForget = { viewModel.forgetApp(app.packageName) },
                modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter),
            )
            SettingsCardSpacer()
        }
    }
}
