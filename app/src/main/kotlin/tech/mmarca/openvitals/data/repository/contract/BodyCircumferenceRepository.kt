package tech.mmarca.openvitals.data.repository.contract

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite

/**
 * Tape measurements, which Health Connect has no record type for. A thin seam over Room:
 * the screens interpret the values, the repository only stores and returns them.
 */
interface BodyCircumferenceRepository {
    /** A site's measurements on the days of [period], both ends included, oldest first. */
    suspend fun entries(site: CircumferenceSite, period: DatePeriod): List<CircumferenceEntry>

    /** A site's newest measurement, emitted again on every change. */
    fun latest(site: CircumferenceSite): Flow<CircumferenceEntry?>

    /** Every measurement of every site, oldest first, emitted again on every change. */
    fun all(): Flow<List<CircumferenceEntry>>

    /** Every site's measurements on [date], a whole local day, oldest first. */
    suspend fun entriesOn(date: LocalDate): List<CircumferenceEntry>

    /**
     * Stores each value at local noon of [date], first removing whatever that site holds on that
     * day, whatever its time. All sites are written together or none is.
     */
    suspend fun saveDay(date: LocalDate, values: Map<CircumferenceSite, Double>)

    /** Stores the measurement, replacing one at the same site and instant. */
    suspend fun save(entry: CircumferenceEntry)

    suspend fun delete(entry: CircumferenceEntry)
}
