package tech.mmarca.openvitals.features.progressphotos

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import tech.mmarca.openvitals.data.repository.contract.BodyFolderRepository
import tech.mmarca.openvitals.data.repository.contract.PeriodPreferences
import tech.mmarca.openvitals.data.repository.contract.ProgressPhotoRepository
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.navigation.selectedDayOrNull

@Immutable
data class ProgressPhotosUiState(
    val isLoading: Boolean = true,
    val selectedRange: TimeRange = TimeRange.MONTH,
    val selectedDate: LocalDate = LocalDate.now(),
    val weekPeriodMode: WeekPeriodMode = WeekPeriodMode.MONDAY_TO_SUNDAY,
    val display: ProgressPhotosDisplayState = ProgressPhotosDisplayState(),
    /** Photos whose full image is still only on the phone. */
    val pendingCount: Int = 0,
    val folderPicked: Boolean = false,
    /** A picked or captured image is being encoded and kept. */
    val isAdding: Boolean = false,
    val error: ScreenError? = null,
)

/** Progress photos over a period. A new photo is linked to the day on screen in Day mode, else to now. */
@HiltViewModel
class ProgressPhotosViewModel(
    private val repository: ProgressPhotoRepository,
    private val folder: BodyFolderRepository,
    private val importer: ProgressPhotoImporter,
    private val saver: ProgressPhotoSaver,
    private val saveScheduling: ProgressPhotoSaveScheduling,
    private val periodPreferences: PeriodPreferences,
    savedStateHandle: SavedStateHandle,
    private val dispatchers: DispatcherProvider,
    private val zone: () -> ZoneId,
    private val now: () -> Instant,
) : ViewModel() {

    @Inject
    constructor(
        repository: ProgressPhotoRepository,
        folder: BodyFolderRepository,
        importer: ProgressPhotoImporter,
        saver: ProgressPhotoSaver,
        saveScheduling: ProgressPhotoSaveScheduling,
        periodPreferences: PeriodPreferences,
        savedStateHandle: SavedStateHandle,
    ) : this(
        repository, folder, importer, saver, saveScheduling, periodPreferences, savedStateHandle,
        DefaultDispatcherProvider, ZoneId::systemDefault, Instant::now,
    )

    private val initialRange = periodPreferences.timeRangeFor(PeriodRangePreferenceKey.PROGRESS_PHOTOS)
    private val initialWeekPeriodMode = periodPreferences.weekPeriodMode

    private val periodDriver = PeriodSelectionDriver(
        initialRange = initialRange,
        initialDate = savedStateHandle.selectedDayOrNull() ?: LocalDate.now(),
        initialWeekPeriodMode = initialWeekPeriodMode,
        onRangeSelected = { range ->
            periodPreferences.setTimeRangeFor(PeriodRangePreferenceKey.PROGRESS_PHOTOS, range)
        },
    )
    private val _uiState = MutableStateFlow(
        ProgressPhotosUiState(
            selectedRange = initialRange,
            selectedDate = periodDriver.selection.selectedDate,
            weekPeriodMode = initialWeekPeriodMode,
        )
    )
    val uiState: StateFlow<ProgressPhotosUiState> = _uiState.asStateFlow()
    private val loadCoordinator = LoadCoordinator()

    init {
        observeWeekPeriodMode()
        observeQueue()
        load()
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

    private fun observeQueue() {
        viewModelScope.launch {
            repository.pendingCount().collect { count -> _uiState.value = _uiState.value.copy(pendingCount = count) }
        }
        viewModelScope.launch {
            folder.folderUri.collect { uri -> _uiState.value = _uiState.value.copy(folderPicked = uri != null) }
        }
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

    /** Back on screen: follow the calendar, reread, and retry the photos still waiting for the folder. */
    fun resumeCurrentPeriod() {
        periodDriver.resumeCurrentPeriod()?.let(::applyPeriodSelection)
        load()
        retryPendingSaves()
    }

    fun retryPendingSaves() {
        val state = _uiState.value
        if (state.folderPicked && state.pendingCount > 0) saveScheduling.enqueue()
    }

    /** Keeps the image at [uri], a picked or captured one, and queues its save to the folder. */
    fun addPhoto(uri: String) {
        val takenAt = takenAtForNewPhoto()
        _uiState.value = _uiState.value.copy(isAdding = true, error = null)
        viewModelScope.launch {
            runCatching {
                val encoded = importer.encode(uri)
                repository.add(takenAt, encoded.image, encoded.thumbnail)
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isAdding = false)
                    saveScheduling.enqueue()
                    load()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(isAdding = false, error = error.toScreenError())
                }
        }
    }

    fun delete(photo: ProgressPhoto) {
        viewModelScope.launch {
            runCatching { saver.delete(photo) }
                .onSuccess { load() }
                .onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toScreenError()) }
        }
    }

    fun thumbnailFile(photo: ProgressPhoto): File = repository.thumbnailFile(photo)

    fun load() {
        loadCoordinator.launch(viewModelScope) load@{
            val query = PeriodLoadQuery(
                range = periodDriver.selection.selectedRange,
                anchorDate = periodDriver.selection.selectedDate,
                weekPeriodMode = _uiState.value.weekPeriodMode,
            )
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.photos(query.windows.current) }
                .onSuccess { photos ->
                    val display = withContext(dispatchers.default) { ProgressPhotosPresentationMapper.build(photos, zone()) }
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

    /** In Day mode on an earlier day, the photo belongs to that day, at noon; otherwise it is now. */
    private fun takenAtForNewPhoto(): Instant {
        val state = _uiState.value
        val zone = zone()
        val nowInstant = now()
        val today = nowInstant.atZone(zone).toLocalDate()
        return if (state.selectedRange == TimeRange.DAY && state.selectedDate.isBefore(today)) {
            state.selectedDate.atTime(LocalTime.NOON).atZone(zone).toInstant()
        } else {
            nowInstant
        }
    }

    private fun applyPeriodSelection(selection: PeriodSelection) {
        _uiState.value = _uiState.value.copy(
            selectedRange = selection.selectedRange,
            selectedDate = selection.selectedDate,
        )
    }
}
