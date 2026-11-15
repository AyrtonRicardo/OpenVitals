package tech.mmarca.openvitals.sharing

import java.time.LocalDate
import tech.mmarca.openvitals.sharing.contract.DataSharingColumns
import tech.mmarca.openvitals.sharing.contract.DataSharingContract
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingMetrics
import tech.mmarca.openvitals.sharing.contract.DataSharingRequest
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

/**
 * One table to send back. Each row lines up with [columns]. [status] and
 * [historyStart] travel as cursor extras. Pure, so the JVM tests reach it.
 */
class DataSharingAnswer(
    val columns: Array<String>,
    val rows: List<Array<Any?>>,
    val status: DataSharingStatus = DataSharingStatus.OK,
    val historyStart: LocalDate? = null,
) {
    /** Keeps [projection]'s columns in its order. Null keeps them all; an unknown name throws. */
    fun project(projection: Array<out String>?): DataSharingAnswer {
        if (projection == null) return this
        val indexes = projection.map { name ->
            columns.indexOf(name).takeIf { it >= 0 } ?: throw IllegalArgumentException("Unknown column: $name")
        }
        return DataSharingAnswer(
            columns = projection.map { it }.toTypedArray(),
            rows = rows.map { row -> Array(indexes.size) { row[indexes[it]] } },
            status = status,
            historyStart = historyStart,
        )
    }
}

/** The rows for the open catalog paths, and the shape of every table. */
object DataSharingAnswers {
    fun groups(
        permissionFor: (DataSharingGroup) -> String,
        isGranted: (DataSharingGroup) -> Boolean,
        status: DataSharingStatus,
    ): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.Groups.ALL,
        rows = DataSharingGroup.entries.map { group ->
            arrayOf(
                group.path,
                permissionFor(group),
                if (isGranted(group)) 1 else 0,
                status.value,
                DataSharingContract.API_VERSION,
            )
        },
        status = status,
    )

    fun metrics(): DataSharingAnswer = DataSharingAnswer(
        columns = DataSharingColumns.Metrics.ALL,
        rows = DataSharingMetrics.all.flatMap { metric ->
            metric.tables.sortedBy { it.ordinal }.map { table ->
                arrayOf<Any?>(metric.group.path, metric.key, metric.unit, table.path)
            }
        },
    )

    fun empty(request: DataSharingRequest, status: DataSharingStatus): DataSharingAnswer =
        DataSharingAnswer(columns = columnsFor(request), rows = emptyList(), status = status)

    fun columnsFor(request: DataSharingRequest): Array<String> = when (request) {
        DataSharingRequest.Groups -> DataSharingColumns.Groups.ALL
        DataSharingRequest.Metrics -> DataSharingColumns.Metrics.ALL
        is DataSharingRequest.Daily -> DataSharingColumns.Daily.ALL
        is DataSharingRequest.Samples -> DataSharingColumns.Samples.ALL
        is DataSharingRequest.Sessions -> DataSharingColumns.Sessions.ALL
        is DataSharingRequest.Session -> DataSharingColumns.SessionSummary.ALL
        is DataSharingRequest.Splits -> DataSharingColumns.Splits.ALL
        is DataSharingRequest.Route -> DataSharingColumns.Route.ALL
    }
}
