package tech.mmarca.openvitals.features.manualentry.circumference

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.core.performance.LoadCoordinator
import tech.mmarca.openvitals.core.presentation.ScreenError
import tech.mmarca.openvitals.core.presentation.toScreenError
import tech.mmarca.openvitals.data.repository.contract.BodyCircumferenceRepository
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.preferences.UnitSystem
import tech.mmarca.openvitals.navigation.CIRCUMFERENCE_SITE_ARG

/**
 * One measuring session: a date and a value for any of the parts, typed on the body figure. It is
 * saved to Room, so it asks for no permission.
 */
@Immutable
data class CircumferenceEntryUiState(
    val date: LocalDate,
    val selected: CircumferenceSite = CircumferenceSite.WAIST,
    /** The text typed per part, kept while another part is selected. */
    val inputs: Map<CircumferenceSite, String> = emptyMap(),
    /** Every value each part already holds on [date], oldest first, in centimetres; saving replaces them all. */
    val existingOnDate: Map<CircumferenceSite, List<Double>> = emptyMap(),
    /** Each part's last measurement before [date], shown as a hint. */
    val lastBefore: Map<CircumferenceSite, CircumferenceEntry> = emptyMap(),
    val invalidSite: CircumferenceSite? = null,
    /** The date's values have been read, so a replace can be warned about. Saving waits for it. */
    val dayLoaded: Boolean = false,
    /** The date's values could not be read; saving stays off rather than replace unseen values. */
    val dayLoadError: ScreenError? = null,
    /** The parts Save would replace, while it asks; null otherwise. */
    val confirmReplace: List<CircumferenceSite>? = null,
    val isSaving: Boolean = false,
    val saveCompleted: Boolean = false,
    val writeError: ScreenError? = null,
) {
    private val typed: List<CircumferenceSite>
        get() = CircumferenceSite.entries.filter { !inputs[it].isNullOrBlank() }

    /** The typed parts that already have a value on [date]. */
    val replaces: List<CircumferenceSite> get() = typed.filter { it in existingOnDate }

    val canSave: Boolean get() = !isSaving && dayLoaded && typed.isNotEmpty()
}

@HiltViewModel
class CircumferenceEntryViewModel(
    private val repository: BodyCircumferenceRepository,
    savedStateHandle: SavedStateHandle,
    private val today: () -> LocalDate,
    private val zone: ZoneId,
) : ViewModel() {

    @Inject
    constructor(repository: BodyCircumferenceRepository, savedStateHandle: SavedStateHandle) :
        this(repository, savedStateHandle, LocalDate::now, ZoneId.systemDefault())

    private val _uiState = MutableStateFlow(
        CircumferenceEntryUiState(
            date = today(),
            selected = savedStateHandle.get<String>(CIRCUMFERENCE_SITE_ARG)
                ?.let { name -> CircumferenceSite.entries.firstOrNull { it.name == name } }
                ?: CircumferenceSite.WAIST,
        )
    )
    val uiState: StateFlow<CircumferenceEntryUiState> = _uiState.asStateFlow()
    private val dayLoad = LoadCoordinator()

    init {
        loadDay()
    }

    fun select(site: CircumferenceSite) = update { copy(selected = site) }

    fun updateInput(text: String) = update { copy(inputs = inputs + (selected to text), invalidSite = null) }

    /** A day on or before today; the warnings and hints follow it. */
    fun selectDate(date: LocalDate) {
        if (date.isAfter(today()) || date == _uiState.value.date) return
        update { copy(date = date, existingOnDate = emptyMap(), lastBefore = emptyMap(), dayLoaded = false, dayLoadError = null) }
        loadDay()
    }

    /** Saves at once, or first asks when it would replace a value on the date. */
    fun save(unitSystem: UnitSystem) {
        val values = validValues(unitSystem) ?: return
        val replaced = _uiState.value.replaces
        if (replaced.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(confirmReplace = replaced)
            return
        }
        write(values)
    }

    fun confirmReplace(unitSystem: UnitSystem) {
        _uiState.value = _uiState.value.copy(confirmReplace = null)
        validValues(unitSystem)?.let(::write)
    }

    fun dismissReplace() {
        _uiState.value = _uiState.value.copy(confirmReplace = null)
    }

    fun onSaveCompletedHandled() {
        _uiState.value = _uiState.value.copy(saveCompleted = false)
    }

    /** Every typed value in centimetres, or null after pointing at the first one that is not a number a tape reads. */
    private fun validValues(unitSystem: UnitSystem): Map<CircumferenceSite, Double>? {
        val current = _uiState.value
        if (!current.canSave) return null
        val values = mutableMapOf<CircumferenceSite, Double>()
        CircumferenceSite.entries.forEach { site ->
            val text = current.inputs[site]
            if (text.isNullOrBlank()) return@forEach
            val centimeters = circumferenceCentimetersOrNull(text, unitSystem)
            if (centimeters == null) {
                _uiState.value = current.copy(invalidSite = site, selected = site, writeError = null)
                return null
            }
            values[site] = centimeters
        }
        return values
    }

    private fun write(values: Map<CircumferenceSite, Double>) {
        val date = _uiState.value.date
        _uiState.value = _uiState.value.copy(isSaving = true, writeError = null)
        viewModelScope.launch {
            runCatching { repository.saveDay(date, values) }
                .onSuccess { _uiState.value = _uiState.value.copy(isSaving = false, saveCompleted = true) }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(isSaving = false, writeError = error.toScreenError())
                }
        }
    }

    private fun loadDay() {
        val date = _uiState.value.date
        dayLoad.launch(viewModelScope) load@{
            val onDay = runCatching { repository.entriesOn(date) }
            // The hint is a nicety; a failed read only leaves it out.
            val before = runCatching { repository.all().first() }.getOrDefault(emptyList())
                .filter { it.time.atZone(zone).toLocalDate().isBefore(date) }
                .groupBy { it.site }
                .mapValues { (_, entries) -> entries.maxBy { it.time } }
            if (!isCurrent) return@load
            onDay
                .onSuccess { entries ->
                    _uiState.value = _uiState.value.copy(
                        existingOnDate = entries.sortedBy { it.time }.groupBy({ it.site }, { it.centimeters }),
                        lastBefore = before,
                        dayLoaded = true,
                        dayLoadError = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(lastBefore = before, dayLoaded = false, dayLoadError = error.toScreenError())
                }
        }
    }

    private inline fun update(transform: CircumferenceEntryUiState.() -> CircumferenceEntryUiState) {
        _uiState.value = _uiState.value.transform().copy(saveCompleted = false, writeError = null)
    }
}
