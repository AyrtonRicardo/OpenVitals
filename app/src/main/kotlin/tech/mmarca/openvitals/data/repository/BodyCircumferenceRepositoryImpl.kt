package tech.mmarca.openvitals.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.data.local.circumference.BodyCircumferenceDao
import tech.mmarca.openvitals.data.local.circumference.BodyCircumferenceEntity
import tech.mmarca.openvitals.data.repository.contract.BodyCircumferenceRepository
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite

/** Tape measurements live in Room because Health Connect has no record type for them. */
@Singleton
class BodyCircumferenceRepositoryImpl(
    private val dao: BodyCircumferenceDao,
    private val dispatchers: DispatcherProvider,
    private val zone: () -> ZoneId,
) : BodyCircumferenceRepository {

    @Inject
    constructor(dao: BodyCircumferenceDao) : this(dao, DefaultDispatcherProvider, ZoneId::systemDefault)

    override suspend fun entries(site: CircumferenceSite, period: DatePeriod): List<CircumferenceEntry> =
        withContext(dispatchers.io) {
            val zone = zone()
            val start = period.start.atStartOfDay(zone).toInstant()
            val end = period.end.plusDays(1).atStartOfDay(zone).toInstant()
            dao.between(site.name, start.toEpochMilli(), end.toEpochMilli()).map { it.toEntry(site) }
        }

    override fun latest(site: CircumferenceSite): Flow<CircumferenceEntry?> =
        dao.latest(site.name).map { it?.toEntry(site) }

    override fun all(): Flow<List<CircumferenceEntry>> =
        dao.all().map { rows -> rows.mapNotNull { it.toEntryOrNull() } }

    override suspend fun entriesOn(date: LocalDate): List<CircumferenceEntry> = withContext(dispatchers.io) {
        val (start, end) = dayBounds(date)
        dao.allBetween(start, end).mapNotNull { it.toEntryOrNull() }
    }

    override suspend fun saveDay(date: LocalDate, values: Map<CircumferenceSite, Double>) = withContext(dispatchers.io) {
        val (start, end) = dayBounds(date)
        val noon = date.atTime(LocalTime.NOON).atZone(zone()).toInstant().toEpochMilli()
        dao.replaceDay(
            startMillis = start,
            endMillis = end,
            entries = values.map { (site, centimeters) ->
                BodyCircumferenceEntity(site = site.name, measuredAtMillis = noon, centimeters = centimeters)
            },
        )
    }

    /** The local day as epoch milliseconds, start inclusive and end exclusive, so a 23- or 25-hour day is whole. */
    private fun dayBounds(date: LocalDate): Pair<Long, Long> {
        val zone = zone()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    override suspend fun save(entry: CircumferenceEntry) = withContext(dispatchers.io) {
        dao.upsert(
            BodyCircumferenceEntity(
                site = entry.site.name,
                measuredAtMillis = entry.time.toEpochMilli(),
                centimeters = entry.centimeters,
            )
        )
    }

    override suspend fun delete(entry: CircumferenceEntry) = withContext(dispatchers.io) {
        dao.delete(entry.site.name, entry.time.toEpochMilli())
    }
}

/** A row whose site this version does not know is skipped rather than failing the read. */
private fun BodyCircumferenceEntity.toEntryOrNull(): CircumferenceEntry? =
    CircumferenceSite.entries.firstOrNull { it.name == site }?.let { toEntry(it) }

/** Every query is by site, so the row's site is the one asked for. */
private fun BodyCircumferenceEntity.toEntry(site: CircumferenceSite): CircumferenceEntry =
    CircumferenceEntry(site = site, time = Instant.ofEpochMilli(measuredAtMillis), centimeters = centimeters)
