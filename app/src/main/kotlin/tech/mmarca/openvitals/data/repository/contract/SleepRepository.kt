package tech.mmarca.openvitals.data.repository.contract

import java.time.LocalDate
import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.domain.query.SleepPeriodData
import tech.mmarca.healthcalculations.model.DailySleepDuration
import tech.mmarca.healthcalculations.model.SleepData
import tech.mmarca.healthcalculations.preferences.SleepWindow

interface SleepRepository {
    suspend fun loadSleepPeriod(
        query: PeriodLoadQuery,
        sleepWindow: SleepWindow,
    ): SleepPeriodData

    /** One duration per day without fetching sessions. Days with no sleep read `durationMs == 0`. */
    suspend fun loadDailySleepDurations(
        start: LocalDate,
        end: LocalDate,
        sleepWindow: SleepWindow,
    ): List<DailySleepDuration>

    suspend fun loadSleepSessions(start: LocalDate, end: LocalDate): List<SleepData>

    suspend fun loadSleepSession(id: String): SleepData?
}
