package tech.mmarca.openvitals.sharing.source

import android.util.Log
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.healthconnect.HealthConnectRateLimitBackoff
import tech.mmarca.openvitals.healthconnect.HealthConnectRateLimitException
import tech.mmarca.openvitals.healthconnect.SharedSampleFamily
import tech.mmarca.openvitals.healthconnect.SharedSampleType
import tech.mmarca.openvitals.healthconnect.SharedSampleTypes
import tech.mmarca.openvitals.healthconnect.historyReadStart
import tech.mmarca.openvitals.healthconnect.withStrictHealthConnectReads
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingRequest
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

/**
 * Health Connect data for the data sharing API. The caller has checked the
 * gates: sharing on, Health Connect available, not paused, readable now.
 * One query reads at a time: Health Connect's rate limit is shared with the
 * app's own screens.
 */
@Singleton
internal class HealthConnectSharingSource @Inject constructor(
    private val hc: HealthConnectManager,
    private val dailyReads: HealthConnectDailyReads,
    private val sessionReads: HealthConnectSessionReads,
) {
    private val mutex = Mutex()

    suspend fun daily(request: DataSharingRequest.Daily): SourceResult<DailyRow> = guarded { granted ->
        val start = hc.historyReadStart(request.start, request.end, granted)
        runHealthConnectReads(
            reads = dailyReads.reads(request.group, start, request.end),
            wanted = request.metrics,
            granted = granted,
            permissionFor = { samplePermissionFor(request.group, it) },
            metricOf = { it.metric },
        ).copy(historyStart = start.takeIf { it != request.start })
    }

    suspend fun samples(request: DataSharingRequest.Samples): SourceResult<SampleRow> = guarded { granted ->
        val zone = ZoneId.systemDefault()
        val firstDay = request.start.atZone(zone).toLocalDate()
        val historyStart = hc.historyReadStart(firstDay, request.end.atZone(zone).toLocalDate(), granted)
        val start = maxOf(request.start, historyStart.atStartOfDay(zone).toInstant())
        // The whole range is older than Health Connect will serve.
        if (!start.isBefore(request.end)) return@guarded SourceResult(emptyList(), historyStart = historyStart)
        val reads = sampleTypes(request.group).map { type ->
            HealthConnectRead(type.metrics, setOf(readPermission(type.recordType))) {
                hc.readSharedSamples(type, start, request.end).map { SampleRow(it.start, it.end, it.metric, it.value, it.text) }
            }
        }
        runHealthConnectReads(
            reads = reads,
            wanted = request.metrics,
            granted = granted,
            permissionFor = { samplePermissionFor(request.group, it) },
            metricOf = { it.metric },
        ).copy(historyStart = historyStart.takeIf { it != firstDay })
    }

    suspend fun sessions(request: DataSharingRequest.Sessions): SourceResult<SessionRow> = guarded { granted ->
        val start = hc.historyReadStart(request.start, request.end, granted)
        runHealthConnectReads(sessionReads.sessions(request.group, start, request.end), emptySet(), granted)
            .copy(historyStart = start.takeIf { it != request.start })
    }

    suspend fun session(request: DataSharingRequest.Session): SourceResult<SummaryRow> = guarded { granted ->
        runHealthConnectReads(sessionReads.summary(request.group, request.sessionId), emptySet(), granted)
    }

    suspend fun route(request: DataSharingRequest.Route): SourceResult<RouteRow> = guarded { granted ->
        runHealthConnectReads(listOf(sessionReads.route(request.sessionId)), emptySet(), granted)
    }

    private suspend fun <T> guarded(block: suspend (Set<String>) -> SourceResult<T>): SourceResult<T> {
        if (HealthConnectRateLimitBackoff.remainingMillis() > 0) {
            return SourceResult(emptyList(), DataSharingStatus.RATE_LIMITED)
        }
        return mutex.withLock { block(hc.grantedPermissions()) }
    }
}

/**
 * Runs each read whose metrics were asked for. A read or a metric whose
 * permission OpenVitals lacks is skipped, and so is a failed read; either
 * makes the answer partial. A rate-limited read makes it rate limited.
 * An empty [wanted] means every metric. Reads without metrics always run.
 */
internal suspend fun <T> runHealthConnectReads(
    reads: List<HealthConnectRead<T>>,
    wanted: Set<String>,
    granted: Set<String>,
    permissionFor: (String) -> String? = { null },
    metricOf: (T) -> String? = { null },
    onFailure: (Throwable) -> Unit = { Log.w(TAG, "A data sharing read failed", it) },
): SourceResult<T> {
    val rows = mutableListOf<T>()
    var status = DataSharingStatus.OK
    for (read in reads) {
        val keys = if (wanted.isEmpty()) read.metrics else read.metrics intersect wanted
        if (read.metrics.isNotEmpty() && keys.isEmpty()) continue
        val readable = keys.filterTo(mutableSetOf()) { key -> permissionFor(key)?.let { it in granted } ?: true }
        if (read.requires.any { it !in granted } || (keys.isNotEmpty() && readable.isEmpty())) {
            status = worse(status, DataSharingStatus.PARTIAL)
            continue
        }
        if (readable.size < keys.size) status = worse(status, DataSharingStatus.PARTIAL)
        try {
            val result = withStrictHealthConnectReads { read.read() }
            rows += if (read.metrics.isEmpty()) result else result.filter { row -> metricOf(row)?.let { it in readable } ?: true }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            status = worse(
                status,
                if (error.isRateLimit()) DataSharingStatus.RATE_LIMITED else DataSharingStatus.PARTIAL,
            )
        }
    }
    return SourceResult(rows, status)
}

private fun Throwable.isRateLimit(): Boolean =
    this is HealthConnectRateLimitException || HealthConnectRateLimitBackoff.isRateLimitFailure(this)

private fun worse(a: DataSharingStatus, b: DataSharingStatus): DataSharingStatus =
    if (StatusRank.indexOf(b) > StatusRank.indexOf(a)) b else a

private val StatusRank = listOf(DataSharingStatus.OK, DataSharingStatus.PARTIAL, DataSharingStatus.RATE_LIMITED)

private const val TAG = "DataSharing"

/** The Health Connect types a group serves as samples. Routes have none. */
internal fun sampleTypes(group: DataSharingGroup): List<SharedSampleType> =
    SharedSampleFamily.entries.firstOrNull { it.name == group.name }?.let(SharedSampleTypes::forFamily).orEmpty()

/** For the samples' and the daily tables' per-metric permission check. */
internal fun samplePermissionFor(group: DataSharingGroup, metric: String): String? =
    sampleTypes(group).firstOrNull { metric in it.metrics }?.let { readPermission(it.recordType) }
