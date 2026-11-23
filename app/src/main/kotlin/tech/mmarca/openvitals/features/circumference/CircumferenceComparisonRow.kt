package tech.mmarca.openvitals.features.circumference

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.ui.components.HealthDatePickerDialog
import tech.mmarca.openvitals.ui.theme.Spacing

/**
 * What the figure compares with: each part's first value, or a picked date. The calendar button
 * picks one; the cross clears it and goes back to first values.
 */
@Composable
internal fun CircumferenceComparisonRow(
    compareDate: LocalDate?,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onPick: (LocalDate) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = compareDate?.let {
                stringResource(R.string.circumference_compare_date, dateTimeFormatterProvider.mediumDate().format(it))
            } ?: stringResource(R.string.circumference_compare_first),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.sm),
        )
        IconButton(onClick = { picking = true }) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = stringResource(R.string.circumference_compare_pick))
        }
        if (compareDate != null) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.circumference_compare_clear))
            }
        }
    }
    if (picking) {
        HealthDatePickerDialog(
            selectedDate = compareDate ?: LocalDate.now(),
            onDismiss = { picking = false },
            onConfirm = { date ->
                picking = false
                onPick(date)
            },
        )
    }
}
