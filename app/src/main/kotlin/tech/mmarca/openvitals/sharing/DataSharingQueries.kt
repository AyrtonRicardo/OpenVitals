package tech.mmarca.openvitals.sharing

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.mmarca.openvitals.domain.model.HealthConnectAvailability
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.features.activity.ActivityWorkoutFitFile
import tech.mmarca.openvitals.healthconnect.HealthConnectRateLimitBackoff
import tech.mmarca.openvitals.healthconnect.HealthConnectRateLimitException
import tech.mmarca.openvitals.healthconnect.withStrictHealthConnectReads
import tech.mmarca.openvitals.healthconnect.HealthConnectSyncGate
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingRequest
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus
import tech.mmarca.openvitals.sharing.contract.WorkoutFitRequest
import tech.mmarca.openvitals.sharing.source.HealthConnectSharingSource
import tech.mmarca.openvitals.sharing.source.OpenVitalsSharingSource
import tech.mmarca.openvitals.sharing.source.SharingTables
import tech.mmarca.openvitals.core.performance.runCatchingCancellable
import tech.mmarca.openvitals.sharing.source.SourceResult
import tech.mmarca.openvitals.sharing.source.worseStatus

/** Answers a request whose permission the provider has already checked. */
@Singleton
class DataSharingQueries @Inject internal constructor(
    private val store: DataSharingStore,
    private val healthConnectManager: HealthConnectManager,
    private val syncGate: HealthConnectSyncGate,
    private val healthConnect: HealthConnectSharingSource,
    private val openVitals: OpenVitalsSharingSource,
    private val workoutFitFile: ActivityWorkoutFitFile,
) {
    /** One query reads at a time: Health Connect's rate limit is shared with the app's own screens. */
    private val mutex = Mutex()

    suspend fun answer(
        request: DataSharingRequest,
        permissionFor: (DataSharingGroup) -> String,
        isGranted: (DataSharingGroup) -> Boolean,
    ): DataSharingAnswer = when (request) {
        DataSharingRequest.Groups -> DataSharingAnswers.groups(permissionFor, isGranted, status())
        DataSharingRequest.Metrics -> DataSharingAnswers.metrics()
        else -> if (store.sharingEnabled) {
            mutex.withLock { dataAnswer(request) }
        } else {
            DataSharingAnswers.empty(request, DataSharingStatus.SHARING_OFF)
        }
    }

    /**
     * A recorded workout's FIT file, the one "Save as FIT" writes. A file has no status to say
     * what is missing from it, so anything short of a whole file is a refusal with the reason:
     * sharing off, a closed Health Connect gate, a failed read, or no such workout.
     */
    suspend fun workoutFit(
        request: WorkoutFitRequest,
        onFailure: (Throwable) -> Unit = { Log.w(TAG, "A workout's FIT file could not be written", it) },
    ): WorkoutFitAnswer {
        if (!store.sharingEnabled) return WorkoutFitAnswer.Refused(DataSharingStatus.SHARING_OFF)
        return mutex.withLock {
            val gate = healthConnectGate()
            if (gate != DataSharingStatus.OK) return@withLock WorkoutFitAnswer.Refused(gate)
            try {
                val bytes = withStrictHealthConnectReads { workoutFitFile.bytes(request.sessionId, request.withRoute) }
                if (bytes == null) WorkoutFitAnswer.Refused(DataSharingStatus.NOT_FOUND) else WorkoutFitAnswer.File(bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error)
                val rateLimited = error is HealthConnectRateLimitException || HealthConnectRateLimitBackoff.isRateLimitFailure(error)
                WorkoutFitAnswer.Refused(if (rateLimited) DataSharingStatus.RATE_LIMITED else DataSharingStatus.PARTIAL)
            }
        }
    }

    /** Why Health Connect data may be missing. OpenVitals' own data does not depend on it. */
    suspend fun status(): DataSharingStatus =
        if (store.sharingEnabled) healthConnectGate() else DataSharingStatus.SHARING_OFF

    private suspend fun dataAnswer(request: DataSharingRequest): DataSharingAnswer {
        val gate = healthConnectGate()
        val readable = gate == DataSharingStatus.OK
        val granted = if (readable) healthConnectManager.grantedPermissions() else emptySet()
        suspend fun <T> healthConnectPart(read: suspend () -> SourceResult<T>): SourceResult<T>? =
            if (readable) read() else null
        return when (request) {
            is DataSharingRequest.Daily -> SharingTables.daily(
                request.group,
                merge(
                    gate,
                    healthConnectPart { healthConnect.daily(request, granted) },
                    openVitals.daily(request, granted, readable),
                ),
            )
            is DataSharingRequest.Samples -> SharingTables.samples(
                request.group,
                merge(
                    gate,
                    healthConnectPart { healthConnect.samples(request, granted) },
                    openVitals.samples(request, granted, readable),
                ),
            )
            is DataSharingRequest.Sessions -> SharingTables.sessions(
                merge(gate, healthConnectPart { healthConnect.sessions(request, granted) }, null),
            )
            is DataSharingRequest.Session -> SharingTables.summary(
                request.group,
                merge(
                    gate,
                    healthConnectPart { healthConnect.session(request, granted) },
                    openVitals.session(request, granted, readable),
                ),
            )
            is DataSharingRequest.Splits -> SharingTables.splits(
                merge(gate, null, openVitals.splits(request, granted, readable)),
            )
            is DataSharingRequest.Route -> SharingTables.route(
                merge(gate, healthConnectPart { healthConnect.route(request, granted) }, null),
            )
            DataSharingRequest.Groups,
            DataSharingRequest.Metrics,
            -> DataSharingAnswers.empty(request, DataSharingStatus.OK)
        }
    }

    private suspend fun healthConnectGate(): DataSharingStatus = when {
        healthConnectManager.availability() != HealthConnectAvailability.AVAILABLE ->
            DataSharingStatus.HEALTH_CONNECT_UNAVAILABLE
        !syncGate.isEnabled -> DataSharingStatus.HEALTH_CONNECT_PAUSED
        HealthConnectRateLimitBackoff.remainingMillis() > 0 -> DataSharingStatus.RATE_LIMITED
        !runCatchingCancellable { healthConnectManager.readsOtherAppsDataNow() }.getOrDefault(false) ->
            DataSharingStatus.BACKGROUND_READ_MISSING
        else -> DataSharingStatus.OK
    }
}

/** A workout's FIT file, or why there is none. */
sealed interface WorkoutFitAnswer {
    class File(val bytes: ByteArray) : WorkoutFitAnswer

    data class Refused(val status: DataSharingStatus) : WorkoutFitAnswer
}

private const val TAG = "DataSharing"

/** Both parts' rows. A closed gate explains the answer; otherwise the worse read status does. */
internal fun <T> merge(gate: DataSharingStatus, healthConnect: SourceResult<T>?, openVitals: SourceResult<T>?): SourceResult<T> {
    val parts = listOfNotNull(healthConnect, openVitals)
    return SourceResult(
        rows = parts.flatMap { it.rows },
        status = if (gate != DataSharingStatus.OK) gate else parts.map { it.status }.fold(DataSharingStatus.OK, ::worseStatus),
        historyStart = parts.firstNotNullOfOrNull { it.historyStart },
    )
}
