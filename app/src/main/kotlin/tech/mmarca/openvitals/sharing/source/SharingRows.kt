package tech.mmarca.openvitals.sharing.source

import java.time.Instant
import java.time.LocalDate
import tech.mmarca.openvitals.sharing.DataSharingAnswer
import tech.mmarca.openvitals.sharing.contract.DataSharingColumns
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingMetrics
import tech.mmarca.openvitals.sharing.contract.DataSharingOrigin
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

/** One metric on one day. A number sets [value]; a text metric sets [text]. */
data class DailyRow(
    val date: LocalDate,
    val metric: String,
    val value: Double? = null,
    val text: String? = null,
    val origin: DataSharingOrigin = DataSharingOrigin.HEALTH_CONNECT,
)

/** One reading. [end] is null for an instant. */
data class SampleRow(
    val start: Instant,
    val end: Instant?,
    val metric: String,
    val value: Double? = null,
    val text: String? = null,
    val origin: DataSharingOrigin = DataSharingOrigin.HEALTH_CONNECT,
)

data class SessionRow(
    val id: String,
    val start: Instant,
    val end: Instant,
    val type: String,
    val planned: Boolean = false,
    val title: String? = null,
    val origin: DataSharingOrigin = DataSharingOrigin.HEALTH_CONNECT,
)

data class SummaryRow(
    val metric: String,
    val value: Double? = null,
    val text: String? = null,
)

/** One split; [paceSecondsPerKm] is null when it has no distance. */
data class SplitRow(
    val index: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val paceSecondsPerKm: Double?,
    val elevationGainMeters: Double?,
    val heartRateAvg: Double?,
    val source: String,
)

data class RouteRow(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
)

/** What a source read, and why it may be short. */
data class SourceResult<T>(
    val rows: List<T>,
    val status: DataSharingStatus = DataSharingStatus.OK,
    val historyStart: LocalDate? = null,
)

/** Rows to tables. Units come from the catalog, so a row cannot carry a wrong one. */
object SharingTables {
    fun daily(group: DataSharingGroup, result: SourceResult<DailyRow>): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.Daily.ALL,
        rows = result.rows
            .sortedWith(compareBy({ it.date }, { it.metric }))
            .map { row ->
                arrayOf(
                    row.date.toString(),
                    row.metric,
                    row.value,
                    row.text,
                    DataSharingMetrics.unitOf(group, row.metric),
                    row.origin.value,
                )
            },
        status = result.status,
        historyStart = result.historyStart,
    )

    fun samples(group: DataSharingGroup, result: SourceResult<SampleRow>): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.Samples.ALL,
        rows = result.rows
            .sortedWith(compareBy({ it.start }, { it.metric }))
            .map { row ->
                arrayOf(
                    row.start.toEpochMilli(),
                    row.end?.toEpochMilli(),
                    row.metric,
                    row.value,
                    row.text,
                    DataSharingMetrics.unitOf(group, row.metric),
                    row.origin.value,
                )
            },
        status = result.status,
        historyStart = result.historyStart,
    )

    fun sessions(result: SourceResult<SessionRow>): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.Sessions.ALL,
        rows = result.rows.sortedBy { it.start }.map { row ->
            arrayOf(
                row.id,
                row.start.toEpochMilli(),
                row.end.toEpochMilli(),
                row.type,
                if (row.planned) "planned" else "recorded",
                row.title,
                row.origin.value,
            )
        },
        status = result.status,
        historyStart = result.historyStart,
    )

    fun summary(group: DataSharingGroup, result: SourceResult<SummaryRow>): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.SessionSummary.ALL,
        rows = result.rows.map { row ->
            arrayOf(row.metric, row.value, row.text, DataSharingMetrics.unitOf(group, row.metric))
        },
        status = result.status,
    )

    fun splits(result: SourceResult<SplitRow>): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.Splits.ALL,
        rows = result.rows.sortedBy { it.index }.map { row ->
            arrayOf(
                row.index,
                row.distanceMeters,
                row.durationSeconds,
                row.paceSecondsPerKm,
                row.elevationGainMeters,
                row.heartRateAvg,
                row.source,
            )
        },
        status = result.status,
    )

    fun route(result: SourceResult<RouteRow>): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.Route.ALL,
        rows = result.rows.sortedBy { it.time }.map { row ->
            arrayOf(row.time.toEpochMilli(), row.latitude, row.longitude, row.altitudeMeters)
        },
        status = result.status,
    )
}
