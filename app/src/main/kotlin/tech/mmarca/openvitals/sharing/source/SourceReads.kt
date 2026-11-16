package tech.mmarca.openvitals.sharing.source

import android.util.Log
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import tech.mmarca.openvitals.healthconnect.HealthConnectRateLimitBackoff
import tech.mmarca.openvitals.healthconnect.HealthConnectRateLimitException
import tech.mmarca.openvitals.healthconnect.withStrictHealthConnectReads
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

/**
 * One read that serves [metrics]. It runs only when OpenVitals holds every
 * Health Connect permission in [requires]; a metric's own type is checked too.
 * A read of OpenVitals' own Room data requires nothing.
 */
internal class SourceRead<T>(
    val metrics: Set<String>,
    val requires: Set<String>,
    val read: suspend () -> List<T>,
)

internal fun readPermission(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)

/**
 * Runs each read whose metrics were asked for. A read or a metric whose
 * permission OpenVitals lacks is skipped, and so is a failed read; either
 * makes the answer partial. A rate-limited read makes it rate limited.
 * An empty [wanted] means every metric. Reads without metrics always run.
 */
internal suspend fun <T> runSourceReads(
    reads: List<SourceRead<T>>,
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
            status = worseStatus(status, DataSharingStatus.PARTIAL)
            continue
        }
        if (readable.size < keys.size) status = worseStatus(status, DataSharingStatus.PARTIAL)
        try {
            val result = withStrictHealthConnectReads { read.read() }
            rows += if (read.metrics.isEmpty()) result else result.filter { row -> metricOf(row)?.let { it in readable } ?: true }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            status = worseStatus(
                status,
                if (error.isRateLimit()) DataSharingStatus.RATE_LIMITED else DataSharingStatus.PARTIAL,
            )
        }
    }
    return SourceResult(rows, status)
}

private fun Throwable.isRateLimit(): Boolean =
    this is HealthConnectRateLimitException || HealthConnectRateLimitBackoff.isRateLimitFailure(this)

/** The worse of two read statuses: rate limited over partial over OK. */
internal fun worseStatus(a: DataSharingStatus, b: DataSharingStatus): DataSharingStatus =
    if (StatusRank.indexOf(b) > StatusRank.indexOf(a)) b else a

private val StatusRank = listOf(DataSharingStatus.OK, DataSharingStatus.PARTIAL, DataSharingStatus.RATE_LIMITED)

private const val TAG = "DataSharing"
