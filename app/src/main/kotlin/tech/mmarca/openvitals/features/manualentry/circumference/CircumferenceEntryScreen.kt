package tech.mmarca.openvitals.features.manualentry.circumference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.UnitFormatter
import tech.mmarca.openvitals.core.presentation.resolve
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.preferences.UnitQuantity
import tech.mmarca.openvitals.domain.preferences.UnitSystem
import tech.mmarca.openvitals.features.circumference.BodyMap
import tech.mmarca.openvitals.features.circumference.BodyMapTile
import tech.mmarca.openvitals.features.circumference.BodyMapTileState
import tech.mmarca.openvitals.features.circumference.titleRes
import tech.mmarca.openvitals.features.manualentry.ManualEntryPickerButton
import tech.mmarca.openvitals.features.manualentry.localizedDateText
import tech.mmarca.openvitals.ui.components.HealthDatePickerDialog
import tech.mmarca.openvitals.ui.components.OpenVitalsButton
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.theme.LayoutMetrics
import tech.mmarca.openvitals.ui.theme.Spacing
import tech.mmarca.openvitals.ui.theme.WeightColor

/**
 * A measuring session on the body figure: pick the date, tap a part, type its value, and save
 * every typed part together. Values a save would replace are flagged before it happens.
 */
@Composable
fun CircumferenceEntryScreen(
    viewModel: CircumferenceEntryViewModel,
    unitFormatter: UnitFormatter,
    onEntrySaved: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A tape reads in the unit the height setting uses.
    val unitSystem = unitFormatter.unitSystem(UnitQuantity.HEIGHT)

    LaunchedEffect(state.saveCompleted) {
        if (state.saveCompleted) {
            viewModel.onSaveCompletedHandled()
            onEntrySaved()
        }
    }

    LazyColumn(contentPadding = PaddingValues(vertical = Spacing.sm)) {
        item {
            CircumferenceEntryCard(
                state = state,
                unitSystem = unitSystem,
                unitFormatter = unitFormatter,
                onSelect = viewModel::select,
                onInputChanged = viewModel::updateInput,
                onDateChanged = viewModel::selectDate,
                onSave = { viewModel.save(unitSystem) },
                modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter, vertical = Spacing.sm),
            )
        }
    }

    state.confirmReplace?.let { sites ->
        CircumferenceReplaceDialog(
            sites = sites,
            date = state.date,
            onConfirm = { viewModel.confirmReplace(unitSystem) },
            onDismiss = viewModel::dismissReplace,
        )
    }
}

@Composable
private fun CircumferenceEntryCard(
    state: CircumferenceEntryUiState,
    unitSystem: UnitSystem,
    unitFormatter: UnitFormatter,
    onSelect: (CircumferenceSite) -> Unit,
    onInputChanged: (String) -> Unit,
    onDateChanged: (java.time.LocalDate) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.isSaving
    var showDatePicker by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // The form opens on the part that was tapped, ready for its value.
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LayoutMetrics.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Outlined.Straighten, contentDescription = null, tint = WeightColor)
                Column(modifier = Modifier.padding(horizontal = Spacing.md)) {
                    Text(text = stringResource(R.string.metric_circumferences), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.circumference_entry_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ManualEntryPickerButton(
                label = stringResource(R.string.manual_entry_date_label),
                value = state.date.localizedDateText(),
                icon = Icons.Outlined.CalendarMonth,
                enabled = enabled,
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            )

            // A tap on a part puts the cursor in its value, so it can be typed at once.
            BodyMap(
                tiles = entryTiles(state, unitSystem, unitFormatter),
                onSelect = { site ->
                    onSelect(site)
                    if (enabled) focusRequester.requestFocus()
                },
            )

            SelectedPartField(
                state = state,
                unitSystem = unitSystem,
                unitFormatter = unitFormatter,
                enabled = enabled,
                focusRequester = focusRequester,
                onInputChanged = onInputChanged,
            )

            OpenVitalsButton(onClick = onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(Spacing.lg))
                Text(text = stringResource(R.string.circumference_save), modifier = Modifier.padding(start = Spacing.sm))
            }

            state.dayLoadError?.resolve()?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            state.writeError?.let { error ->
                Text(
                    text = stringResource(
                        R.string.body_entry_write_failed,
                        error.resolve() ?: stringResource(R.string.unknown_error),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDatePicker) {
        HealthDatePickerDialog(
            selectedDate = state.date,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                showDatePicker = false
                onDateChanged(date)
            },
        )
    }
}

/** The value box for the selected part, with the replace warning or the last value under it. */
@Composable
private fun SelectedPartField(
    state: CircumferenceEntryUiState,
    unitSystem: UnitSystem,
    unitFormatter: UnitFormatter,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onInputChanged: (String) -> Unit,
) {
    val site = state.selected
    val siteTitle = stringResource(site.titleRes())
    val invalid = state.invalidSite == site
    val existing = state.existingOnDate[site].orEmpty()
    val replacing = site in state.replaces && existing.isNotEmpty()
    val last = state.lastBefore[site]
    val supporting = when {
        invalid -> stringResource(R.string.body_entry_invalid_value)
        replacing -> pluralStringResource(
            R.plurals.circumference_replace_warning,
            existing.size,
            siteTitle,
            existing.joinToString(", ") { unitFormatter.circumference(it).text },
            state.date.localizedDateText(),
        )
        last != null -> stringResource(
            R.string.circumference_last_value,
            unitFormatter.circumference(last.centimeters).text,
            last.time.atZone(java.time.ZoneId.systemDefault()).toLocalDate().localizedDateText(),
        )
        else -> null
    }
    OutlinedTextField(
        value = state.inputs[site].orEmpty(),
        onValueChange = onInputChanged,
        enabled = enabled,
        isError = invalid || replacing,
        singleLine = true,
        label = { Text(stringResource(R.string.body_entry_value_label, siteTitle, circumferenceInputUnitLabel(unitSystem))) },
        supportingText = supporting?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

/** Each part's tile: what was typed, else the last value as a hint; flagged when saving replaces. */
@Composable
private fun entryTiles(
    state: CircumferenceEntryUiState,
    unitSystem: UnitSystem,
    unitFormatter: UnitFormatter,
): List<BodyMapTile> {
    val unit = circumferenceInputUnitLabel(unitSystem)
    return CircumferenceSite.entries.map { site ->
        val typed = state.inputs[site]?.takeIf { it.isNotBlank() }?.trim()
        val name = stringResource(site.titleRes())
        val value = typed?.let { "$it $unit" }
        val last = state.lastBefore[site]
        BodyMapTile(
            site = site,
            value = value,
            detail = if (typed == null) last?.let { unitFormatter.circumference(it.centimeters).text } else null,
            state = when {
                site in state.replaces -> BodyMapTileState.REPLACES
                typed != null -> BodyMapTileState.FILLED
                else -> BodyMapTileState.EMPTY
            },
            selected = site == state.selected,
            description = stringResource(
                R.string.circumference_tile_value_description,
                name,
                value ?: stringResource(R.string.circumference_not_measured),
            ),
        )
    }
}
