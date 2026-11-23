package tech.mmarca.openvitals.features.circumference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import kotlin.math.abs
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.core.presentation.UnitFormatter
import tech.mmarca.openvitals.core.presentation.resolve
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.preferences.UnitQuantity
import tech.mmarca.openvitals.ui.components.EmptyState
import tech.mmarca.openvitals.ui.components.MetricDetailScaffold
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.components.SectionHeader
import tech.mmarca.openvitals.ui.components.SwipeToDeleteEntryRow
import tech.mmarca.openvitals.ui.theme.WeightColor

/**
 * Tape measurements for one site over a period. The data is in Room, not Health Connect,
 * so there is no Health Connect shell around it.
 */
@Composable
fun CircumferenceScreen(
    viewModel: CircumferenceViewModel,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onAddMeasurement: (CircumferenceSite) -> Unit,
    onOpenProgressPhotos: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.resumeCurrentPeriod()
    }

    MetricDetailScaffold(
        isLoading = state.isLoading,
        selectedRange = state.selectedRange,
        selectedDate = state.selectedDate,
        screenError = state.error,
        onRefresh = viewModel::load,
        onSelectRange = viewModel::selectRange,
        onPreviousPeriod = viewModel::previousPeriod,
        onNextPeriod = viewModel::nextPeriod,
        onSelectDate = viewModel::selectDate,
        onSelectDay = viewModel::selectDay,
        weekPeriodMode = state.weekPeriodMode,
        headerItems = {
            item(key = "circumference_compare") {
                CircumferenceComparisonRow(
                    compareDate = state.compareDate,
                    dateTimeFormatterProvider = dateTimeFormatterProvider,
                    onPick = viewModel::selectCompareDate,
                    onClear = viewModel::clearCompareDate,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item(key = "circumference_body_map") {
                // A tap on a part goes straight to typing its value; its history follows below on return.
                BodyMap(
                    tiles = comparisonTiles(state, unitFormatter, dateTimeFormatterProvider),
                    onSelect = { site ->
                        viewModel.selectSite(site)
                        onAddMeasurement(site)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            state.bodyMapError?.let { error ->
                item(key = "circumference_body_map_error") {
                    Text(
                        text = error.resolve() ?: stringResource(R.string.unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item(key = "circumference_progress_photos") {
                ProgressPhotosLink(onClick = onOpenProgressPhotos)
            }
        },
    ) { _ ->
        circumferenceContent(
            display = state.display,
            unitFormatter = unitFormatter,
            dateTimeFormatterProvider = dateTimeFormatterProvider,
            onDelete = viewModel::delete,
        )
    }
}

private fun LazyListScope.circumferenceContent(
    display: CircumferenceDisplayState,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onDelete: (CircumferenceEntry) -> Unit,
) {
    if (display.isEmpty) {
        item(key = "circumference_empty") {
            EmptyState(
                message = stringResource(R.string.circumference_empty),
                icon = Icons.Outlined.Straighten,
            )
        }
        return
    }
    item(key = "circumference_summary") {
        CircumferenceSummaryCard(display = display, unitFormatter = unitFormatter)
    }
    item(key = "circumference_entries_header") {
        SectionHeader(
            text = stringResource(R.string.circumference_measurements_title),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    items(display.entriesNewestFirst, key = { "${it.site}_${it.time.toEpochMilli()}" }) { entry ->
        CircumferenceEntryRow(
            entry = entry,
            unitFormatter = unitFormatter,
            dateTimeFormatterProvider = dateTimeFormatterProvider,
            onDelete = { onDelete(entry) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** The progress photos sit beside the tape: the same body, measured another way. */
@Composable
private fun ProgressPhotosLink(onClick: () -> Unit) {
    OpenVitalsCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null, tint = WeightColor)
            Text(text = stringResource(R.string.screen_progress_photos), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CircumferenceSummaryCard(
    display: CircumferenceDisplayState,
    unitFormatter: UnitFormatter,
) {
    OpenVitalsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            display.latest?.let { latest ->
                CircumferenceStat(
                    label = stringResource(R.string.metric_latest),
                    value = unitFormatter.circumference(latest.centimeters).text,
                )
            }
            display.changeCentimeters?.let { change ->
                CircumferenceStat(
                    label = stringResource(R.string.metric_change),
                    value = signedCircumference(change, unitFormatter),
                )
            }
            display.averageCentimeters?.let { average ->
                CircumferenceStat(
                    label = stringResource(R.string.stat_average),
                    value = unitFormatter.circumference(average).text,
                )
            }
        }
    }
}

@Composable
private fun CircumferenceStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = WeightColor)
    }
}

@Composable
private fun CircumferenceEntryRow(
    entry: CircumferenceEntry,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SwipeToDeleteEntryRow(onDelete = onDelete, modifier = modifier) {
        OpenVitalsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateTimeFormatterProvider.mediumDate().format(entry.time.atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = unitFormatter.circumference(entry.centimeters).text,
                    style = MaterialTheme.typography.titleMedium,
                    color = WeightColor,
                )
            }
        }
    }
}

/** A change with its sign, so a shrinking waist reads as one: "−1.5 cm", "+0.5 cm". */
private fun signedCircumference(changeCentimeters: Double, unitFormatter: UnitFormatter): String {
    val magnitude = unitFormatter.circumference(abs(changeCentimeters)).text
    return when {
        changeCentimeters > 0.0 -> "+$magnitude"
        changeCentimeters < 0.0 -> "−$magnitude"
        else -> magnitude
    }
}

/** Each part's tile: its latest value and its change from the baseline, with the baseline's day when it is another. */
@Composable
private fun comparisonTiles(
    state: CircumferenceUiState,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
): List<BodyMapTile> = CircumferenceSite.entries.map { site ->
    val value = state.bodyMap[site]
    val latest = value?.latest?.let { unitFormatter.circumference(it.centimeters).text }
    val change = value?.changeCentimeters?.let { arrowChange(it, unitFormatter) } ?: NotComparable
    val spokenChange = spokenChange(value?.changeCentimeters, unitFormatter)
    val name = stringResource(site.titleRes())
    BodyMapTile(
        site = site,
        value = latest,
        detail = listOfNotNull(
            change.takeIf { latest != null },
            value?.baselineDate?.let { dateTimeFormatterProvider.mediumDate().format(it) },
        ).joinToString("\n").ifEmpty { null },
        state = if (latest != null) BodyMapTileState.FILLED else BodyMapTileState.EMPTY,
        selected = site == state.site,
        description = stringResource(
            R.string.circumference_tile_description,
            name,
            latest ?: stringResource(R.string.circumference_not_measured),
            spokenChange,
        ),
    )
}

private const val NotComparable = "—"

/** ▼ smaller, ▲ larger, = unchanged; no good or bad colour, since either can be the goal. */
private fun arrowChange(changeCentimeters: Double, unitFormatter: UnitFormatter): String {
    val magnitude = unitFormatter.circumference(abs(changeCentimeters)).text
    return when (changeDirection(changeCentimeters, unitFormatter.unitSystem(UnitQuantity.HEIGHT))) {
        ChangeDirection.SMALLER -> "▼ $magnitude"
        ChangeDirection.LARGER -> "▲ $magnitude"
        ChangeDirection.SAME, ChangeDirection.UNKNOWN -> "= $magnitude"
    }
}

/** The change in words, for a screen reader, which would read the arrows as symbol names. */
@Composable
private fun spokenChange(changeCentimeters: Double?, unitFormatter: UnitFormatter): String {
    val magnitude = changeCentimeters?.let { unitFormatter.circumference(abs(it)).text }.orEmpty()
    return when (changeDirection(changeCentimeters, unitFormatter.unitSystem(UnitQuantity.HEIGHT))) {
        ChangeDirection.SMALLER -> stringResource(R.string.circumference_change_smaller, magnitude)
        ChangeDirection.LARGER -> stringResource(R.string.circumference_change_larger, magnitude)
        ChangeDirection.SAME -> stringResource(R.string.circumference_change_none)
        ChangeDirection.UNKNOWN -> stringResource(R.string.circumference_change_unknown)
    }
}
