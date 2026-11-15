package tech.mmarca.openvitals.sharing.source

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingRequest

/**
 * What OpenVitals stores or works out itself. When Health Connect cannot be
 * read now, only the reads that need no Health Connect permission run: the
 * answer's status already says why the rest is missing.
 */
@Singleton
internal class OpenVitalsSharingSource @Inject constructor(
    private val reads: OpenVitalsReads,
) {
    suspend fun daily(request: DataSharingRequest.Daily, granted: Set<String>, healthConnectReadable: Boolean) = run(
        reads.daily(request.group, request.start, request.end, granted),
        request.metrics,
        granted,
        healthConnectReadable,
    ) { it.metric }

    suspend fun samples(request: DataSharingRequest.Samples, granted: Set<String>, healthConnectReadable: Boolean) = run(
        reads.samples(request.group, request.start, request.end),
        request.metrics,
        granted,
        healthConnectReadable,
    ) { it.metric }

    suspend fun session(request: DataSharingRequest.Session, granted: Set<String>, healthConnectReadable: Boolean) =
        if (request.group == DataSharingGroup.WORKOUTS) {
            run(listOf(reads.workoutSummary(request.sessionId, granted)), emptySet(), granted, healthConnectReadable) { it.metric }
        } else {
            SourceResult(emptyList())
        }

    suspend fun splits(request: DataSharingRequest.Splits, granted: Set<String>, healthConnectReadable: Boolean) =
        run(listOf(reads.splits(request.sessionId, granted)), emptySet(), granted, healthConnectReadable) { null }

    private suspend fun <T> run(
        reads: List<SourceRead<T>>,
        wanted: Set<String>,
        granted: Set<String>,
        healthConnectReadable: Boolean,
        metricOf: (T) -> String?,
    ): SourceResult<T> = runSourceReads(
        reads = reads.filter { healthConnectReadable || it.requires.isEmpty() },
        wanted = wanted,
        granted = granted,
        metricOf = metricOf,
    )
}
