package tech.mmarca.openvitals.features.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.sharing.DataSharingAppAccess
import tech.mmarca.openvitals.sharing.DataSharingStore
import tech.mmarca.openvitals.sharing.contract.DataSharingContract
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.core.performance.runCatchingCancellable
import tech.mmarca.openvitals.core.performance.DispatcherProvider

/** One app that has asked for data, as the list shows it. */
@Immutable
data class DataSharingAppUi(
    val packageName: String,
    val label: String,
    val allowedGroups: List<DataSharingGroup>,
    val lastAccessText: String,
)

@Immutable
data class DataSharingSettingsUiState(
    val sharingEnabled: Boolean = false,
    val backgroundReadMissing: Boolean = false,
    val apps: List<DataSharingAppUi> = emptyList(),
)

/** The sharing switch, the background-read grant and the apps that have asked. */
@HiltViewModel
class DataSharingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DataSharingStore,
    private val healthConnectManager: HealthConnectManager,
    private val dateTimeFormatterProvider: DateTimeFormatterProvider,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DataSharingSettingsUiState(sharingEnabled = store.sharingEnabled))
    val uiState: StateFlow<DataSharingSettingsUiState> = _uiState.asStateFlow()

    /** Health Connect's permission string for background reads, for the screen's request. */
    val backgroundReadPermission: String
        get() = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** On resume: the user may have changed a grant in Android's settings. */
    fun refresh() {
        viewModelScope.launch {
            val missing = runCatchingCancellable { healthConnectManager.isBackgroundReadGrantMissing() }.getOrDefault(false)
            val apps = withContext(dispatchers.io) { store.accessLog().map { it.toUi() } }
            _uiState.update {
                it.copy(sharingEnabled = store.sharingEnabled, backgroundReadMissing = missing, apps = apps)
            }
        }
    }

    fun setSharingEnabled(enabled: Boolean) {
        store.sharingEnabled = enabled
        _uiState.update { it.copy(sharingEnabled = enabled) }
    }

    fun forgetApp(packageName: String) {
        store.forgetApp(packageName)
        _uiState.update { state -> state.copy(apps = state.apps.filterNot { it.packageName == packageName }) }
    }

    /** An app that called the provider is visible to this one, so its name and grants can be read. */
    private fun DataSharingAppAccess.toUi(): DataSharingAppUi {
        val packageManager = context.packageManager
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val allowed = DataSharingGroup.entries.filter { group ->
            packageManager.checkPermission(DataSharingContract.permission(context.packageName, group), packageName) ==
                PackageManager.PERMISSION_GRANTED
        }
        return DataSharingAppUi(
            packageName = packageName,
            label = label,
            allowedGroups = allowed,
            lastAccessText = lastAccess.atZone(ZoneId.systemDefault()).format(dateTimeFormatterProvider.mediumDateTime()),
        )
    }
}
