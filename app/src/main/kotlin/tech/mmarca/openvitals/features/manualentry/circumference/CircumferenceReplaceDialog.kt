package tech.mmarca.openvitals.features.manualentry.circumference

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.features.circumference.titleRes
import tech.mmarca.openvitals.features.manualentry.localizedDateText

/** Asked once on Save when it would replace values the date already holds. */
@Composable
internal fun CircumferenceReplaceDialog(
    sites: List<CircumferenceSite>,
    date: LocalDate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parts = sites.map { stringResource(it.titleRes()) }.joinToString(", ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.circumference_replace_title)) },
        text = { Text(pluralStringResource(R.plurals.circumference_replace_body, sites.size, parts, date.localizedDateText())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.circumference_replace_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
