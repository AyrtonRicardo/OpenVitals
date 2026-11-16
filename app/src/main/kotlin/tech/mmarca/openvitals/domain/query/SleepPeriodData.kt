package tech.mmarca.openvitals.domain.query

import tech.mmarca.healthcalculations.model.DailySleepDuration
import tech.mmarca.healthcalculations.model.SleepData

data class SleepPeriodData(
    val sessions: List<SleepData> = emptyList(),
    val previousSessions: List<SleepData> = emptyList(),
    val baselineSessions: List<SleepData> = emptyList(),
    val dailyDurations: List<DailySleepDuration> = emptyList(),
    val previousDailyDurations: List<DailySleepDuration> = emptyList(),
    val baselineDailyDurations: List<DailySleepDuration> = emptyList(),
)
