package tech.mmarca.openvitals.features.bodyfolder

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.core.presentation.ScreenError
import tech.mmarca.openvitals.core.presentation.toScreenError
import tech.mmarca.openvitals.data.repository.contract.BodyFolderRepository
import tech.mmarca.openvitals.data.repository.contract.ProgressPhotoRepository
import tech.mmarca.openvitals.features.progressphotos.ProgressPhotoSaveScheduling

@Immutable
data class BodyFolderUiState(
    val folderPicked: Boolean = false,
    /** The provider's name for the folder; null while unknown or when the provider cannot answer. */
    val folderName: String? = null,
    val pendingPhotos: Int = 0,
    val isExporting: Boolean = false,
    /** How many entries the last export from this screen wrote. */
    val exportedEntries: Int? = null,
    val error: ScreenError? = null,
)

/** The body measurements folder under Body profile: picking it, the photo queue, and the export. */
@HiltViewModel
class BodyFolderViewModel @Inject constructor(
    private val folder: BodyFolderRepository,
    private val photos: ProgressPhotoRepository,
    private val exporter: BodyMeasurementsExporter,
    private val saveScheduling: ProgressPhotoSaveScheduling,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BodyFolderUiState())
    val uiState: StateFlow<BodyFolderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            folder.folderUri.collect { uri ->
                _uiState.value = _uiState.value.copy(folderPicked = uri != null, folderName = null)
                if (uri != null) {
                    val name = folder.folderName()
                    _uiState.value = _uiState.value.copy(folderName = name)
                }
            }
        }
        viewModelScope.launch {
            photos.pendingCount().collect { count -> _uiState.value = _uiState.value.copy(pendingPhotos = count) }
        }
    }

    /** The user picked [treeUri] in the system folder picker. The waiting photos go there next. */
    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch {
            runCatching { folder.choose(treeUri) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(error = null, exportedEntries = null)
                    saveScheduling.enqueue()
                }
                .onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toScreenError()) }
        }
    }

    fun forgetFolder() {
        viewModelScope.launch {
            runCatching { folder.forget() }
                .onSuccess { _uiState.value = _uiState.value.copy(error = null, exportedEntries = null) }
                .onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toScreenError()) }
        }
    }

    fun savePhotosNow() {
        if (_uiState.value.folderPicked) saveScheduling.enqueue()
    }

    fun exportNow() {
        if (_uiState.value.isExporting || !_uiState.value.folderPicked) return
        _uiState.value = _uiState.value.copy(isExporting = true, error = null, exportedEntries = null)
        viewModelScope.launch {
            runCatching { exporter.export() }
                .onSuccess { count -> _uiState.value = _uiState.value.copy(isExporting = false, exportedEntries = count) }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(isExporting = false, error = error.toScreenError())
                }
        }
    }
}
