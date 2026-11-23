package tech.mmarca.openvitals.data.repository.contract

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite

/** In-memory stand-in for [BodyCircumferenceRepository]. Days are read in the system zone. */
class FakeBodyCircumferenceRepository(
    initial: List<CircumferenceEntry> = emptyList(),
) : BodyCircumferenceRepository {
    private val store = MutableStateFlow(initial)

    val entries: List<CircumferenceEntry> get() = store.value

    /** The periods each read asked for, in order. */
    val reads = mutableListOf<Pair<CircumferenceSite, DatePeriod>>()

    /** Runs before a read answers, so a test can hold it in flight or make it fail. */
    var beforeRead: suspend (CircumferenceSite, DatePeriod) -> Unit = { _, _ -> }

    var failSaves: Throwable? = null
    var failDeletes: Throwable? = null

    override suspend fun entries(site: CircumferenceSite, period: DatePeriod): List<CircumferenceEntry> {
        reads += site to period
        beforeRead(site, period)
        val zone = ZoneId.systemDefault()
        return store.value
            .filter { it.site == site }
            .filter { entry ->
                val day = entry.time.atZone(zone).toLocalDate()
                !day.isBefore(period.start) && !day.isAfter(period.end)
            }
            .sortedBy { it.time }
    }

    override fun latest(site: CircumferenceSite): Flow<CircumferenceEntry?> =
        store.map { all -> all.filter { it.site == site }.maxByOrNull { it.time } }

    /** Each [saveDay] call, in order. */
    val savedDays = mutableListOf<Pair<LocalDate, Map<CircumferenceSite, Double>>>()

    /** Makes [all] fail when collected. */
    var failAll: Throwable? = null

    override fun all(): Flow<List<CircumferenceEntry>> =
        store.map { all -> failAll?.let { throw it } ?: all.sortedBy { it.time } }

    /** Runs before a day read answers, so a test can hold it in flight or make it fail. */
    var beforeDayRead: suspend (LocalDate) -> Unit = {}

    override suspend fun entriesOn(date: LocalDate): List<CircumferenceEntry> {
        beforeDayRead(date)
        val zone = ZoneId.systemDefault()
        return store.value.filter { it.time.atZone(zone).toLocalDate() == date }.sortedBy { it.time }
    }

    override suspend fun saveDay(date: LocalDate, values: Map<CircumferenceSite, Double>) {
        failSaves?.let { throw it }
        savedDays += date to values
        val zone = ZoneId.systemDefault()
        val noon = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        store.value = store.value.filterNot { it.site in values && it.time.atZone(zone).toLocalDate() == date } +
            values.map { (site, centimeters) -> CircumferenceEntry(site, noon, centimeters) }
    }

    override suspend fun save(entry: CircumferenceEntry) {
        failSaves?.let { throw it }
        store.value = store.value.filterNot { it.site == entry.site && it.time == entry.time } + entry
    }

    override suspend fun delete(entry: CircumferenceEntry) {
        failDeletes?.let { throw it }
        store.value = store.value.filterNot { it.site == entry.site && it.time == entry.time }
    }
}
