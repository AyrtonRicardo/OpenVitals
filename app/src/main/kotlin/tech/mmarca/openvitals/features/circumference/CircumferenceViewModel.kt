package tech.mmarca.openvitals.features.circumference

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.core.performance.LoadCoordinator
import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.core.period.PeriodRangePreferenceKey
import tech.mmarca.openvitals.core.period.PeriodSelection
import tech.mmarca.openvitals.core.period.PeriodSelectionDriver
import tech.mmarca.openvitals.core.period.TimeRange
import tech.mmarca.openvitals.core.period.WeekPeriodMode
import tech.mmarca.openvitals.core.presentation.ScreenError
import tech.mmarca.openvitals.core.presentation.toScreenError
import tech.mmarca.openvitals.data.repository.contract.BodyCircumferenceRepository
import tech.mmarca.openvitals.data.repository.contract.PeriodPreferences
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.navigation.selectedDayOrNull

@Immutable
data class CircumferenceUiState(
    val isLoading: Boolean = true,
    val selectedRange: TimeRange = TimeRange.MONTH,
    val selectedDate: LocalDate = LocalDate.now(),
    val weekPeriodMode: WeekPeriodMode = WeekPeriodMode.MONDAY_TO_SUNDAY,
    val site: CircumferenceSite = CircumferenceSite.WAIST,
    val display: CircumferenceDisplayState = CircumferenceDisplayState(),
    /** The day the figure compares with; null compares each part's latest with its first. */
    val compareDate: LocalDate? = null,
    /** What the body figure shows for every part, over all time, whatever the period. */
    val bodyMap: Map<CircumferenceSite, BodyMapValue> = emptyMap(),
    /** The figure's own read failed; kept apart from [error], which a period reload clears. */
    val bodyMapError: ScreenError? = null,
    val error: ScreenError? = null,
)

/** One site at a time, over a period, under the body figure, whose tiles switch it. */
@HiltViewModel
class CircumferenceViewModel @Inject constructor(
    private val repository: BodyCircumferenceRepository,
    private val periodPreferences: PeriodPreferences,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialRange = periodPreferences.timeRangeFor(PeriodRangePreferenceKey.CIRCUMFERENCE)
    private val initialWeekPeriodMode = periodPreferences.weekPeriodMode

    private val periodDriver = PeriodSelectionDriver(
        initialRange = initialRange,
        initialDate = savedStateHandle.selectedDayOrNull() ?: LocalDate.now(),
        initialWeekPeriodMode = initialWeekPeriodMode,
        onRangeSelected = { range ->
            periodPreferences.setTimeRangeFor(PeriodRangePreferenceKey.CIRCUMFERENCE, range)
        },
    )
    private val _uiState = MutableStateFlow(
        CircumferenceUiState(
            selectedRange = initialRange,
            selectedDate = periodDriver.selection.selectedDate,
            weekPeriodMode = initialWeekPeriodMode,
        )
    )
    val uiState: StateFlow<CircumferenceUiState> = _uiState.asStateFlow()
    private val loadCoordinator = LoadCoordinator()
    private val compareDate = MutableStateFlow<LocalDate?>(null)

    init {
        observeWeekPeriodMode()
        observeBodyMap()
        load()
    }

    /** The figure follows every change to the table and to the comparison date. */
    private fun observeBodyMap() {
        viewModelScope.launch {
            combine(repository.all(), compareDate) { entries, date -> entries to date }
                .catch { error -> _uiState.value = _uiState.value.copy(bodyMapError = error.toScreenError()) }
                .collect { (entries, date) ->
                    val map = withContext(dispatchers.default) { BodyMapComparison.build(entries, date, ZoneId.systemDefault()) }
                    _uiState.value = _uiState.value.copy(compareDate = date, bodyMap = map, bodyMapError = null)
                }
        }
    }

    /** Compares the figure with [date]; a day after today is ignored. */
    fun selectCompareDate(date: LocalDate) {
        if (date.isAfter(LocalDate.now())) return
        compareDate.value = date
    }

    fun clearCompareDate() {
        compareDate.value = null
    }

    private fun observeWeekPeriodMode() {
        viewModelScope.launch {
            periodPreferences.weekPeriodModeFlow.drop(1).collect { mode ->
                periodDriver.weekPeriodMode = mode
                _uiState.value = _uiState.value.copy(weekPeriodMode = mode)
                if (_uiState.value.selectedRange == TimeRange.WEEK) load()
            }
        }
    }

    fun selectSite(site: CircumferenceSite) {
        if (site == _uiState.value.site) return
        _uiState.value = _uiState.value.copy(site = site, display = CircumferenceDisplayState())
        load()
    }

    fun selectRange(range: TimeRange) {
        applyPeriodSelection(periodDriver.selectRange(range))
        load()
    }

    fun previousPeriod() {
        applyPeriodSelection(periodDriver.previousPeriod())
        load()
    }

    fun nextPeriod() {
        periodDriver.nextPeriod()?.let { next ->
            applyPeriodSelection(next)
            load()
        }
    }

    fun selectDate(date: LocalDate) {
        applyPeriodSelection(periodDriver.selectDate(date))
        load()
    }

    fun selectDay(date: LocalDate) {
        applyPeriodSelection(periodDriver.selectDay(date))
        load()
    }

    /** Back on screen: follow the calendar if it moved on, and reread what the entry form saved. */
    fun resumeCurrentPeriod() {
        periodDriver.resumeCurrentPeriod()?.let(::applyPeriodSelection)
        load()
    }

    fun delete(entry: CircumferenceEntry) {
        viewModelScope.launch {
            runCatching { repository.delete(entry) }
                .onSuccess { load() }
                .onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toScreenError()) }
        }
    }

    fun load() {
        loadCoordinator.launch(viewModelScope) load@{
            val site = _uiState.value.site
            val query = PeriodLoadQuery(
                range = periodDriver.selection.selectedRange,
                anchorDate = periodDriver.selection.selectedDate,
                weekPeriodMode = _uiState.value.weekPeriodMode,
            )
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.entries(site, query.windows.current) }
                .onSuccess { entries ->
                    val display = withContext(dispatchers.default) { CircumferencePresentationMapper.build(entries) }
                    if (!isCurrent) return@load
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        selectedDate = query.selectedDate,
                        display = display,
                    )
                }
                .onFailure { error ->
                    if (!isCurrent) return@load
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        selectedDate = query.selectedDate,
                        error = error.toScreenError(),
                    )
                }
        }
    }

    private fun applyPeriodSelection(selection: PeriodSelection) {
        _uiState.value = _uiState.value.copy(
            selectedRange = selection.selectedRange,
            selectedDate = selection.selectedDate,
        )
    }
}
