package tech.mmarca.openvitals.sharing

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openvitals.domain.model.HealthConnectAvailability
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.healthconnect.HealthConnectSyncGate
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingRequest
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus
import tech.mmarca.openvitals.sharing.source.HealthConnectSharingSource
import tech.mmarca.openvitals.sharing.source.SharingTables
import tech.mmarca.openvitals.core.performance.runCatchingCancellable

/** Answers a request whose permission the provider has already checked. */
@Singleton
class DataSharingQueries @Inject internal constructor(
    private val store: DataSharingStore,
    private val healthConnectManager: HealthConnectManager,
    private val syncGate: HealthConnectSyncGate,
    private val healthConnect: HealthConnectSharingSource,
) {
    suspend fun answer(
        request: DataSharingRequest,
        permissionFor: (DataSharingGroup) -> String,
        isGranted: (DataSharingGroup) -> Boolean,
    ): DataSharingAnswer = when (request) {
        DataSharingRequest.Groups -> DataSharingAnswers.groups(permissionFor, isGranted, status())
        DataSharingRequest.Metrics -> DataSharingAnswers.metrics()
        else -> {
            val status = status()
            if (status == DataSharingStatus.OK) healthConnectAnswer(request) else DataSharingAnswers.empty(request, status)
        }
    }

    private suspend fun healthConnectAnswer(request: DataSharingRequest): DataSharingAnswer = when (request) {
        is DataSharingRequest.Daily -> SharingTables.daily(request.group, healthConnect.daily(request))
        is DataSharingRequest.Samples -> SharingTables.samples(request.group, healthConnect.samples(request))
        is DataSharingRequest.Sessions -> SharingTables.sessions(healthConnect.sessions(request))
        is DataSharingRequest.Session -> SharingTables.summary(request.group, healthConnect.session(request))
        is DataSharingRequest.Route -> SharingTables.route(healthConnect.route(request))
        // Splits are OpenVitals' own arithmetic, not a Health Connect record.
        is DataSharingRequest.Splits,
        DataSharingRequest.Groups,
        DataSharingRequest.Metrics,
        -> DataSharingAnswers.empty(request, DataSharingStatus.OK)
    }

    /**
     * Why Health Connect data may be missing. App data does not depend on it,
     * so a group can still return rows when this is not OK.
     */
    suspend fun status(): DataSharingStatus = when {
        !store.sharingEnabled -> DataSharingStatus.SHARING_OFF
        healthConnectManager.availability() != HealthConnectAvailability.AVAILABLE ->
            DataSharingStatus.HEALTH_CONNECT_UNAVAILABLE
        !syncGate.isEnabled -> DataSharingStatus.HEALTH_CONNECT_PAUSED
        !runCatchingCancellable { healthConnectManager.readsOtherAppsDataNow() }.getOrDefault(false) ->
            DataSharingStatus.BACKGROUND_READ_MISSING
        else -> DataSharingStatus.OK
    }
}
