package tech.mmarca.openvitals.data.repository

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.data.local.circumference.BodyCircumferenceDao
import tech.mmarca.openvitals.data.local.circumference.BodyCircumferenceEntity
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.util.MainDispatcherRule

/**
 * In-memory stand-in for [BodyCircumferenceDao]. There is no SQLite in the unit suite, so each
 * `@Query`'s SQL is reimplemented over a map keyed like the table's primary key.
 */
private class FakeBodyCircumferenceDao : BodyCircumferenceDao {
    val rows = MutableStateFlow<Map<Pair<String, Long>, BodyCircumferenceEntity>>(emptyMap())

    override suspend fun between(site: String, startMillis: Long, endMillis: Long): List<BodyCircumferenceEntity> =
        rows.value.values
            .filter { it.site == site && it.measuredAtMillis >= startMillis && it.measuredAtMillis < endMillis }
            .sortedBy { it.measuredAtMillis }

    override fun latest(site: String): Flow<BodyCircumferenceEntity?> =
        rows.map { all -> all.values.filter { it.site == site }.maxByOrNull { it.measuredAtMillis } }

    override fun all(): Flow<List<BodyCircumferenceEntity>> =
        rows.map { all -> all.values.sortedBy { it.measuredAtMillis } }

    override suspend fun allBetween(startMillis: Long, endMillis: Long): List<BodyCircumferenceEntity> =
        rows.value.values.filter { it.measuredAtMillis >= startMillis && it.measuredAtMillis < endMillis }
            .sortedBy { it.measuredAtMillis }

    override suspend fun deleteBetween(site: String, startMillis: Long, endMillis: Long) {
        rows.value = rows.value.filterValues {
            !(it.site == site && it.measuredAtMillis >= startMillis && it.measuredAtMillis < endMillis)
        }
    }

    override suspend fun upsert(entry: BodyCircumferenceEntity) {
        rows.value = rows.value + ((entry.site to entry.measuredAtMillis) to entry)
    }

    override suspend fun delete(site: String, measuredAtMillis: Long) {
        rows.value = rows.value - (site to measuredAtMillis)
    }
}

class BodyCircumferenceRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // A zone with a daylight-saving change, so a day is read in the zone, not as 24 UTC hours.
    private val zone = ZoneId.of("Europe/Berlin")
    private val dao = FakeBodyCircumferenceDao()
    private val repository = BodyCircumferenceRepositoryImpl(dao, mainDispatcherRule.dispatcherProvider) { zone }

    private fun at(dateTime: String, site: CircumferenceSite = CircumferenceSite.WAIST, cm: Double = 80.0) =
        CircumferenceEntry(site, LocalDateTime.parse(dateTime).atZone(zone).toInstant(), cm)

    @Test fun `a period takes both of its days whole, in the device's zone`() = runTest {
        val beforeStart = at("2026-10-31T23:59")
        val firstMinute = at("2026-11-01T00:00")
        val lastMinute = at("2026-11-30T23:59")
        val afterEnd = at("2026-12-01T00:00")
        listOf(beforeStart, firstMinute, lastMinute, afterEnd).forEach { repository.save(it) }

        val entries = repository.entries(
            CircumferenceSite.WAIST,
            DatePeriod(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")),
        )

        assertEquals(listOf(firstMinute, lastMinute), entries)
    }

    @Test fun `a period across the clock change keeps the day's last hour`() = runTest {
        // 25 October 2026 is 25 hours long in Berlin.
        val lateOnTheLongDay = at("2026-10-25T23:30")
        repository.save(lateOnTheLongDay)

        val entries = repository.entries(
            CircumferenceSite.WAIST,
            DatePeriod(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-25")),
        )

        assertEquals(listOf(lateOnTheLongDay), entries)
    }

    @Test fun `a site's read leaves the other sites out`() = runTest {
        repository.save(at("2026-11-10T08:00", CircumferenceSite.WAIST, 82.0))
        repository.save(at("2026-11-10T08:00", CircumferenceSite.HIPS, 98.0))

        val hips = repository.entries(
            CircumferenceSite.HIPS,
            DatePeriod(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")),
        )

        assertEquals(listOf(98.0), hips.map { it.centimeters })
    }

    @Test fun `saving at the same site and instant replaces the value`() = runTest {
        repository.save(at("2026-11-10T08:00", cm = 82.0))
        repository.save(at("2026-11-10T08:00", cm = 81.0))

        assertEquals(listOf(81.0), dao.rows.value.values.map { it.centimeters })
    }

    @Test fun `latest follows the newest measurement and its deletion`() = runTest {
        val older = at("2026-11-01T08:00", cm = 84.0)
        val newer = at("2026-11-20T08:00", cm = 82.0)
        repository.save(older)
        repository.save(newer)
        assertEquals(newer, repository.latest(CircumferenceSite.WAIST).first())

        repository.delete(newer)

        assertEquals(older, repository.latest(CircumferenceSite.WAIST).first())
        assertNull(repository.latest(CircumferenceSite.NECK).first())
    }

    private fun day(text: String) = LocalDate.parse(text)

    private suspend fun onDay(date: String) = repository.entriesOn(day(date)).sortedBy { it.site }

    @Test fun `saving a day stores each site at local noon`() = runTest {
        repository.saveDay(day("2026-11-21"), mapOf(CircumferenceSite.WAIST to 86.5, CircumferenceSite.HIPS to 99.5))

        assertEquals(
            listOf(
                at("2026-11-21T12:00", CircumferenceSite.WAIST, 86.5),
                at("2026-11-21T12:00", CircumferenceSite.HIPS, 99.5),
            ),
            onDay("2026-11-21"),
        )
    }

    @Test fun `saving a day replaces that site's timed entries on that day only`() = runTest {
        repository.save(at("2026-11-21T23:45", CircumferenceSite.WAIST, 87.0))
        val nextDay = at("2026-11-22T00:10", CircumferenceSite.WAIST, 87.5)
        val hips = at("2026-11-21T08:00", CircumferenceSite.HIPS, 99.0)
        repository.save(nextDay)
        repository.save(hips)

        repository.saveDay(day("2026-11-21"), mapOf(CircumferenceSite.WAIST to 86.0))

        assertEquals(listOf(at("2026-11-21T12:00", CircumferenceSite.WAIST, 86.0), hips), onDay("2026-11-21"))
        assertEquals(listOf(nextDay), onDay("2026-11-22"))
    }

    @Test fun `the long clock-change day is replaced whole`() = runTest {
        repository.save(at("2026-10-25T23:30", CircumferenceSite.WAIST, 81.0))

        repository.saveDay(day("2026-10-25"), mapOf(CircumferenceSite.WAIST to 80.0))

        assertEquals(listOf(at("2026-10-25T12:00", CircumferenceSite.WAIST, 80.0)), onDay("2026-10-25"))
    }

    @Test fun `all lists every site oldest first`() = runTest {
        val third = at("2026-11-20T08:00", CircumferenceSite.HIPS, 99.0)
        val first = at("2026-11-01T08:00", CircumferenceSite.WAIST, 84.0)
        val second = at("2026-11-10T08:00", CircumferenceSite.NECK, 39.0)
        listOf(third, first, second).forEach { repository.save(it) }

        assertEquals(listOf(first, second, third), repository.all().first())
    }
}
