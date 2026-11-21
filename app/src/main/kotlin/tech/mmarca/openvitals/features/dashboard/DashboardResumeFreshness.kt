package tech.mmarca.openvitals.features.dashboard

import java.time.LocalDate

/**
 * Whether a resume may keep what the dashboard shows instead of reading the day
 * again. A full reload is every tile's reads, and on a watch that writes heart
 * rate every second it takes minutes; coming back from Settings used to pay it
 * each time.
 *
 * The day on screen is kept when nothing this app knows of has changed since
 * its load began: the app has not been in the background ([foregroundEpoch];
 * another app may have written meanwhile), it has written nothing to Health
 * Connect and no setting has changed ([localChangeEpoch]), and the load is no
 * older than [maxAgeMillis]. Anything else reloads, as does a pull to refresh.
 */
internal class DashboardResumeFreshness(
    private val foregroundEpoch: () -> Int,
    private val localChangeEpoch: () -> Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxAgeMillis: Long = DashboardResumeFreshMillis,
) {
    /** What a load began under. Taken before its first read, so a change during the load counts. */
    class Stamp internal constructor(
        internal val date: LocalDate,
        internal val foregroundEpoch: Int,
        internal val localChangeEpoch: Long,
    )

    private var settled: Stamp? = null
    private var settledAtMillis = 0L

    /** A load of [date] is starting: what was settled is not to be trusted until it ends. */
    fun begin(date: LocalDate): Stamp {
        settled = null
        return Stamp(date, foregroundEpoch(), localChangeEpoch())
    }

    /** The load that began with [stamp] read every tile and none was refused or failed. */
    fun settle(stamp: Stamp) {
        settled = stamp
        settledAtMillis = nowMillis()
    }

    fun isFresh(date: LocalDate): Boolean {
        val stamp = settled ?: return false
        return stamp.date == date &&
            stamp.foregroundEpoch == foregroundEpoch() &&
            stamp.localChangeEpoch == localChangeEpoch() &&
            nowMillis() - settledAtMillis < maxAgeMillis
    }
}

/** As long as Body Energy keeps today's timeline before it reads it again. */
internal const val DashboardResumeFreshMillis = 15 * 60_000L
