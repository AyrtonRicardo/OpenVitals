package tech.mmarca.openvitals.features.bodyfolder

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.resolve
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.components.OpenVitalsOutlinedButton
import tech.mmarca.openvitals.ui.components.OpenVitalsTonalButton
import tech.mmarca.openvitals.ui.theme.LayoutMetrics
import tech.mmarca.openvitals.ui.theme.Spacing

private val LeadingIconOpticalOffset = 2.dp
private val LeadingIconSize = 20.dp

/**
 * The folder for full progress photos and the measurements export, under Body profile. The
 * system folder picker grants it; a document provider app can offer a network share there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BodyFolderCard(
    state: BodyFolderUiState,
    onFolderPicked: (String) -> Unit,
    onForget: () -> Unit,
    onExport: () -> Unit,
    onSavePhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { onFolderPicked(it.toString()) }
    }
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LayoutMetrics.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.FolderShared,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = LeadingIconOpticalOffset)
                        .size(LeadingIconSize),
                )
                Column(
                    modifier = Modifier
                        .padding(start = Spacing.md)
                        .weight(1f),
                ) {
                    Text(text = stringResource(R.string.body_folder_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.body_folder_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
            Text(text = folderLine(state), style = MaterialTheme.typography.bodyMedium)
            if (state.folderPicked && state.pendingPhotos > 0) {
                Text(
                    text = stringResource(R.string.progress_photos_pending, state.pendingPhotos),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.exportedEntries?.let { count ->
                Text(
                    text = stringResource(R.string.body_folder_exported, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.error.resolve()?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OpenVitalsTonalButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(if (state.folderPicked) R.string.body_folder_change else R.string.body_folder_pick))
                }
                if (state.folderPicked) {
                    OpenVitalsTonalButton(onClick = onExport, enabled = !state.isExporting) {
                        Text(
                            stringResource(
                                if (state.isExporting) R.string.body_folder_exporting else R.string.body_folder_export,
                            ),
                        )
                    }
                    if (state.pendingPhotos > 0) {
                        OpenVitalsOutlinedButton(onClick = onSavePhotos) { Text(stringResource(R.string.progress_photos_retry)) }
                    }
                    OpenVitalsOutlinedButton(onClick = onForget) { Text(stringResource(R.string.body_folder_forget)) }
                }
            }
        }
    }
}

@Composable
private fun folderLine(state: BodyFolderUiState): String = when {
    !state.folderPicked -> stringResource(R.string.body_folder_none)
    state.folderName != null -> stringResource(R.string.body_folder_current, state.folderName)
    else -> stringResource(R.string.body_folder_unnamed)
}
