package tech.mmarca.openvitals.sharing.contract

/**
 * The public API that other apps read. Other apps depend on these names:
 * add new ones, never rename or remove one.
 */
object DataSharingContract {
    /** Raise when a change could break a client. Adding a metric or a column does not. */
    const val API_VERSION = 1

    /** Appended to the application id: `tech.mmarca.openvitals.data`. */
    const val AUTHORITY_SUFFIX = ".data"

    /** Opens the sharing settings screen. A client sends the user there when sharing is off. */
    const val ACTION_MANAGE_DATA_SHARING = "tech.mmarca.openvitals.action.MANAGE_DATA_SHARING"

    const val PATH_GROUPS = "groups"
    const val PATH_METRICS = "metrics"
    const val PATH_SPLITS = "splits"

    const val PARAM_START = "start"
    const val PARAM_END = "end"
    const val PARAM_METRIC = "metric"

    /** Daily and session ranges, both days counted. */
    const val MAX_RANGE_DAYS = 366L

    /** Sample ranges. Heart rate alone can reach 86,400 rows a day. */
    const val MAX_SAMPLE_RANGE_HOURS = 48L

    /** Cursor extras. */
    const val EXTRA_STATUS = "status"
    const val EXTRA_HISTORY_START = "history_start"

    fun authority(applicationId: String): String = applicationId + AUTHORITY_SUFFIX

    fun permission(applicationId: String, group: DataSharingGroup): String =
        "$applicationId.permission.READ_${group.name}"
}

/** One permission each. The path is the first URI segment. */
enum class DataSharingGroup(val path: String, val hasSessions: Boolean = false) {
    ACTIVITY("activity"),
    WORKOUTS("workouts", hasSessions = true),
    ROUTES("routes"),
    SLEEP("sleep", hasSessions = true),
    HEART("heart"),
    VITALS("vitals"),
    BODY("body"),
    NUTRITION("nutrition"),
    RECOVERY("recovery", hasSessions = true),
    CYCLE("cycle"),
    SEXUAL_ACTIVITY("sexual_activity"),
    ;

    companion object {
        fun fromPath(path: String): DataSharingGroup? = entries.firstOrNull { it.path == path }
    }
}

/** The second URI segment. A session's summary lives under [SESSIONS]. */
enum class DataSharingTable(val path: String) {
    DAILY("daily"),
    SAMPLES("samples"),
    SESSIONS("sessions"),
}

/** Where a row's value came from. */
enum class DataSharingOrigin(val value: String) {
    HEALTH_CONNECT("health_connect"),
    OPENVITALS("openvitals"),
}

/** Why a cursor holds fewer rows than asked for. */
enum class DataSharingStatus(val value: String) {
    OK("ok"),

    /** Some sources failed; the rows that came back are correct. */
    PARTIAL("partial"),

    /** OpenVitals lacks Health Connect's background-read grant, so Health Connect data is left out. */
    BACKGROUND_READ_MISSING("background_read_missing"),
    HEALTH_CONNECT_PAUSED("health_connect_paused"),
    HEALTH_CONNECT_UNAVAILABLE("health_connect_unavailable"),
    RATE_LIMITED("rate_limited"),
    SHARING_OFF("sharing_off"),
}

/** Column names, one object per table. */
object DataSharingColumns {
    object Groups {
        const val GROUP = "group"
        const val PERMISSION = "permission"
        const val GRANTED = "granted"
        const val STATUS = "status"
        const val API_VERSION = "api_version"
        val ALL = arrayOf(GROUP, PERMISSION, GRANTED, STATUS, API_VERSION)
    }

    object Metrics {
        const val GROUP = "group"
        const val METRIC = "metric"
        const val UNIT = "unit"
        const val TABLE = "table"
        val ALL = arrayOf(GROUP, METRIC, UNIT, TABLE)
    }

    /** `value` is null when the metric is text; `text_value` is null when it is a number. */
    object Daily {
        const val DATE = "date"
        const val METRIC = "metric"
        const val VALUE = "value"
        const val TEXT_VALUE = "text_value"
        const val UNIT = "unit"
        const val ORIGIN = "origin"
        val ALL = arrayOf(DATE, METRIC, VALUE, TEXT_VALUE, UNIT, ORIGIN)
    }

    /** Times are epoch milliseconds. `end_time` is null for an instant. */
    object Samples {
        const val START_TIME = "start_time"
        const val END_TIME = "end_time"
        const val METRIC = "metric"
        const val VALUE = "value"
        const val TEXT_VALUE = "text_value"
        const val UNIT = "unit"
        const val ORIGIN = "origin"
        val ALL = arrayOf(START_TIME, END_TIME, METRIC, VALUE, TEXT_VALUE, UNIT, ORIGIN)
    }

    /** `kind` is `recorded`, or `planned` for a workout plan. */
    object Sessions {
        const val SESSION_ID = "session_id"
        const val START_TIME = "start_time"
        const val END_TIME = "end_time"
        const val TYPE = "type"
        const val KIND = "kind"
        const val TITLE = "title"
        const val ORIGIN = "origin"
        val ALL = arrayOf(SESSION_ID, START_TIME, END_TIME, TYPE, KIND, TITLE, ORIGIN)
    }

    /** One session's summary, in the daily shape without the date. */
    object SessionSummary {
        const val METRIC = "metric"
        const val VALUE = "value"
        const val TEXT_VALUE = "text_value"
        const val UNIT = "unit"
        val ALL = arrayOf(METRIC, VALUE, TEXT_VALUE, UNIT)
    }

    /** `source` is `device_laps`, `route`, `speed_samples` or `estimated`, most trustworthy first. */
    object Splits {
        const val INDEX = "index"
        const val DISTANCE_M = "distance_m"
        const val DURATION_S = "duration_s"
        const val PACE_S_PER_KM = "pace_s_per_km"
        const val ELEVATION_GAIN_M = "elevation_gain_m"
        const val HEART_RATE_AVG = "heart_rate_avg"
        const val SOURCE = "source"
        val ALL = arrayOf(INDEX, DISTANCE_M, DURATION_S, PACE_S_PER_KM, ELEVATION_GAIN_M, HEART_RATE_AVG, SOURCE)
    }

    object Route {
        const val TIME = "time"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        const val ALTITUDE_M = "altitude_m"
        val ALL = arrayOf(TIME, LATITUDE, LONGITUDE, ALTITUDE_M)
    }
}
