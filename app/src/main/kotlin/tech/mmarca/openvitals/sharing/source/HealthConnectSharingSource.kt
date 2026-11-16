package tech.mmarca.openvitals.sharing.source

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.healthconnect.SharedSampleFamily
import tech.mmarca.openvitals.healthconnect.SharedSampleType
import tech.mmarca.openvitals.healthconnect.SharedSampleTypes
import tech.mmarca.openvitals.healthconnect.historyReadStart
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingRequest

/**
 * Health Connect data for the data sharing API. The caller has checked the
 * gates (sharing on, Health Connect available, not paused, readable now) and
 * passes the permissions OpenVitals holds.
 */
@Singleton
internal class HealthConnectSharingSource @Inject constructor(
    private val hc: HealthConnectManager,
    private val dailyReads: HealthConnectDailyReads,
    private val sessionReads: HealthConnectSessionReads,
) {
    suspend fun daily(request: DataSharingRequest.Daily, granted: Set<String>): SourceResult<DailyRow> {
        val start = hc.historyReadStart(request.start, request.end, granted)
        return runSourceReads(
            reads = dailyReads.reads(request.group, start, request.end),
            wanted = request.metrics,
            granted = granted,
            permissionFor = { samplePermissionFor(request.group, it) },
            metricOf = { it.metric },
        ).copy(historyStart = start.takeIf { it != request.start })
    }

    suspend fun samples(request: DataSharingRequest.Samples, granted: Set<String>): SourceResult<SampleRow> {
        val zone = ZoneId.systemDefault()
        val firstDay = request.start.atZone(zone).toLocalDate()
        val historyStart = hc.historyReadStart(firstDay, request.end.atZone(zone).toLocalDate(), granted)
        val start = maxOf(request.start, historyStart.atStartOfDay(zone).toInstant())
        // The whole range is older than Health Connect will serve.
        if (!start.isBefore(request.end)) return SourceResult(emptyList(), historyStart = historyStart)
        val reads = sampleTypes(request.group).map { type ->
            SourceRead(type.metrics, setOf(readPermission(type.recordType))) {
                hc.readSharedSamples(type, start, request.end).map { SampleRow(it.start, it.end, it.metric, it.value, it.text) }
            }
        }
        return runSourceReads(
            reads = reads,
            wanted = request.metrics,
            granted = granted,
            permissionFor = { samplePermissionFor(request.group, it) },
            metricOf = { it.metric },
        ).copy(historyStart = historyStart.takeIf { it != firstDay })
    }

    suspend fun sessions(request: DataSharingRequest.Sessions, granted: Set<String>): SourceResult<SessionRow> {
        val start = hc.historyReadStart(request.start, request.end, granted)
        return runSourceReads(sessionReads.sessions(request.group, start, request.end), emptySet(), granted)
            .copy(historyStart = start.takeIf { it != request.start })
    }

    suspend fun session(request: DataSharingRequest.Session, granted: Set<String>): SourceResult<SummaryRow> =
        runSourceReads(sessionReads.summary(request.group, request.sessionId), emptySet(), granted)

    suspend fun route(request: DataSharingRequest.Route, granted: Set<String>): SourceResult<RouteRow> =
        runSourceReads(listOf(sessionReads.route(request.sessionId)), emptySet(), granted)
}

/** The Health Connect types a group serves as samples. Routes have none. */
internal fun sampleTypes(group: DataSharingGroup): List<SharedSampleType> =
    SharedSampleFamily.entries.firstOrNull { it.name == group.name }?.let(SharedSampleTypes::forFamily).orEmpty()

/** For the samples' and the daily tables' per-metric permission check. */
internal fun samplePermissionFor(group: DataSharingGroup, metric: String): String? =
    sampleTypes(group).firstOrNull { metric in it.metrics }?.let { readPermission(it.recordType) }
