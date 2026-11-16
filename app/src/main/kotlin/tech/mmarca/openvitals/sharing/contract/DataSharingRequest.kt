package tech.mmarca.openvitals.sharing.contract

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** One parsed query. [group] is the permission it needs; null for the open catalog paths. */
sealed interface DataSharingRequest {
    val group: DataSharingGroup?

    data object Groups : DataSharingRequest {
        override val group: DataSharingGroup? = null
    }

    data object Metrics : DataSharingRequest {
        override val group: DataSharingGroup? = null
    }

    /** Both days included. An empty [metrics] means every metric of the group. */
    data class Daily(
        override val group: DataSharingGroup,
        val start: LocalDate,
        val end: LocalDate,
        val metrics: Set<String>,
    ) : DataSharingRequest

    /** From [start] included to [end] excluded. */
    data class Samples(
        override val group: DataSharingGroup,
        val start: Instant,
        val end: Instant,
        val metrics: Set<String>,
    ) : DataSharingRequest

    /** Sessions that start on a day from [start] to [end], both included. */
    data class Sessions(
        override val group: DataSharingGroup,
        val start: LocalDate,
        val end: LocalDate,
    ) : DataSharingRequest

    data class Session(
        override val group: DataSharingGroup,
        val sessionId: String,
    ) : DataSharingRequest

    data class Splits(val sessionId: String) : DataSharingRequest {
        override val group: DataSharingGroup = DataSharingGroup.WORKOUTS
    }

    data class Route(val sessionId: String) : DataSharingRequest {
        override val group: DataSharingGroup = DataSharingGroup.ROUTES
    }
}

/**
 * A recorded workout as a FIT file. Not a [DataSharingRequest]: it is opened, not queried, and
 * answers with bytes. It needs the workouts group, and the routes group too when [withRoute].
 */
data class WorkoutFitRequest(val sessionId: String, val withRoute: Boolean) {
    val groups: Set<DataSharingGroup>
        get() = if (withRoute) setOf(DataSharingGroup.WORKOUTS, DataSharingGroup.ROUTES) else setOf(DataSharingGroup.WORKOUTS)
}

/**
 * Turns URI path segments and query parameters into a [DataSharingRequest].
 * Anything else throws [IllegalArgumentException], which reaches the client.
 */
object DataSharingRequestParser {
    private val MetricKey = Regex("[a-z0-9_]{1,64}")
    private const val MAX_METRICS = 64
    private const val MAX_SESSION_ID_LENGTH = 256

    fun parse(segments: List<String>, parameter: (String) -> String?): DataSharingRequest {
        val first = segments.firstOrNull() ?: fail("The path is empty.")
        if (segments.size == 1) {
            when (first) {
                DataSharingContract.PATH_GROUPS -> return DataSharingRequest.Groups
                DataSharingContract.PATH_METRICS -> return DataSharingRequest.Metrics
            }
        }
        val group = DataSharingGroup.fromPath(first) ?: fail("Unknown path: /$first")
        if (group == DataSharingGroup.ROUTES) {
            if (segments.size != 2) fail("A route path is /routes/<session_id>.")
            return DataSharingRequest.Route(sessionId(segments[1]))
        }
        val table = segments.getOrNull(1) ?: fail("Missing table after /${group.path}.")
        return when {
            table == DataSharingTable.DAILY.path && segments.size == 2 -> {
                val (start, end) = dayRange(parameter)
                DataSharingRequest.Daily(group, start, end, metrics(parameter, group, DataSharingTable.DAILY))
            }
            table == DataSharingTable.SAMPLES.path && segments.size == 2 -> {
                val (start, end) = instantRange(parameter)
                DataSharingRequest.Samples(group, start, end, metrics(parameter, group, DataSharingTable.SAMPLES))
            }
            table == DataSharingTable.SESSIONS.path && group.hasSessions -> sessionRequest(group, segments, parameter)
            else -> fail("Unknown path: /${segments.joinToString("/")}")
        }
    }

    /** The file paths. Today there is one: `workouts/sessions/<session_id>/fit?route=true|false`. */
    fun parseFile(segments: List<String>, parameter: (String) -> String?): WorkoutFitRequest {
        val isFit = segments.size == 4 &&
            segments[0] == DataSharingGroup.WORKOUTS.path &&
            segments[1] == DataSharingTable.SESSIONS.path &&
            segments[3] == DataSharingContract.PATH_FIT
        if (!isFit) fail("Not a file. The only file is /workouts/sessions/<session_id>/fit.")
        val withRoute = when (parameter(DataSharingContract.PARAM_ROUTE)) {
            null, "false" -> false
            "true" -> true
            else -> fail("route is true or false.")
        }
        return WorkoutFitRequest(sessionId(segments[2]), withRoute)
    }

    private fun sessionRequest(
        group: DataSharingGroup,
        segments: List<String>,
        parameter: (String) -> String?,
    ): DataSharingRequest = when {
        segments.size == 2 -> {
            val (start, end) = dayRange(parameter)
            DataSharingRequest.Sessions(group, start, end)
        }
        segments.size == 3 -> DataSharingRequest.Session(group, sessionId(segments[2]))
        segments.size == 4 && group == DataSharingGroup.WORKOUTS && segments[3] == DataSharingContract.PATH_SPLITS ->
            DataSharingRequest.Splits(sessionId(segments[2]))
        segments.size == 4 && group == DataSharingGroup.WORKOUTS && segments[3] == DataSharingContract.PATH_FIT ->
            fail("A workout's FIT file is a file: open it, do not query it.")
        else -> fail("Unknown path: /${segments.joinToString("/")}")
    }

    private fun dayRange(parameter: (String) -> String?): Pair<LocalDate, LocalDate> {
        val start = date(parameter, DataSharingContract.PARAM_START)
        val end = date(parameter, DataSharingContract.PARAM_END)
        if (end < start) fail("end is before start.")
        val days = ChronoUnit.DAYS.between(start, end) + 1
        if (days > DataSharingContract.MAX_RANGE_DAYS) {
            fail("The range is $days days; the most is ${DataSharingContract.MAX_RANGE_DAYS}.")
        }
        return start to end
    }

    private fun instantRange(parameter: (String) -> String?): Pair<Instant, Instant> {
        val start = epochMillis(parameter, DataSharingContract.PARAM_START)
        val end = epochMillis(parameter, DataSharingContract.PARAM_END)
        if (!end.isAfter(start)) fail("end must be after start.")
        if (Duration.between(start, end) > Duration.ofHours(DataSharingContract.MAX_SAMPLE_RANGE_HOURS)) {
            fail("The range is over ${DataSharingContract.MAX_SAMPLE_RANGE_HOURS} hours.")
        }
        return start to end
    }

    private fun date(parameter: (String) -> String?, name: String): LocalDate {
        val raw = parameter(name) ?: fail("Missing $name (YYYY-MM-DD).")
        return try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            fail("$name is not a date (YYYY-MM-DD): $raw")
        }
    }

    private fun epochMillis(parameter: (String) -> String?, name: String): Instant {
        val raw = parameter(name) ?: fail("Missing $name (epoch milliseconds).")
        val millis = raw.toLongOrNull() ?: fail("$name is not epoch milliseconds: $raw")
        return Instant.ofEpochMilli(millis)
    }

    /** A key the group does not offer in [table] is an error, so a typo is not read as "no data". */
    private fun metrics(
        parameter: (String) -> String?,
        group: DataSharingGroup,
        table: DataSharingTable,
    ): Set<String> {
        val raw = parameter(DataSharingContract.PARAM_METRIC)?.takeIf { it.isNotBlank() } ?: return emptySet()
        val keys = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.size > MAX_METRICS) fail("At most $MAX_METRICS metrics per query.")
        keys.firstOrNull { !MetricKey.matches(it) }?.let { fail("Not a metric key: $it") }
        val offered = DataSharingMetrics.forGroup(group).filter { table in it.tables }.map { it.key }.toSet()
        keys.firstOrNull { it !in offered }?.let { fail("/${group.path}/${table.path} has no metric $it.") }
        return keys.toSet()
    }

    private fun sessionId(raw: String): String {
        if (raw.isBlank() || raw.length > MAX_SESSION_ID_LENGTH) fail("Not a session id.")
        return raw
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
