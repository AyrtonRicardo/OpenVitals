package tech.mmarca.openvitals.features.dashboard

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardResumeFreshnessTest {

    private val today = LocalDate.of(2026, 3, 10)
    private var foregroundEpoch = 1
    private var localChangeEpoch = 0L
    private var now = 0L

    private val freshness = DashboardResumeFreshness(
        foregroundEpoch = { foregroundEpoch },
        localChangeEpoch = { localChangeEpoch },
        nowMillis = { now },
    )

    private fun settledLoad(date: LocalDate = today) = freshness.settle(freshness.begin(date))

    @Test fun `nothing is fresh before a load settles`() {
        assertFalse(freshness.isFresh(today))
        freshness.begin(today)
        assertFalse(freshness.isFresh(today))
    }

    @Test fun `a settled load is fresh until it ages out`() {
        settledLoad()

        now = DashboardResumeFreshMillis - 1
        assertTrue(freshness.isFresh(today))
        now = DashboardResumeFreshMillis
        assertFalse(freshness.isFresh(today))
    }

    @Test fun `a trip to the background ends it, since another app may have written`() {
        settledLoad()
        foregroundEpoch += 1

        assertFalse(freshness.isFresh(today))
    }

    @Test fun `a write or a setting changed by this app ends it`() {
        settledLoad()
        localChangeEpoch += 1

        assertFalse(freshness.isFresh(today))
    }

    @Test fun `a change that lands while the load is reading ends it too`() {
        val stamp = freshness.begin(today)
        // Written after the load's reads began: they may have missed it.
        localChangeEpoch += 1
        freshness.settle(stamp)

        assertFalse(freshness.isFresh(today))
    }

    @Test fun `another day, or a newer load still running, is not fresh`() {
        settledLoad()
        assertFalse(freshness.isFresh(today.plusDays(1)))

        freshness.begin(today)
        assertFalse(freshness.isFresh(today))
    }
}
