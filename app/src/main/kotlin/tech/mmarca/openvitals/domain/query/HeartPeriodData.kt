package tech.mmarca.openvitals.domain.query

import tech.mmarca.healthcalculations.model.DailyHrv
import tech.mmarca.healthcalculations.model.DailyRestingHR
import tech.mmarca.healthcalculations.model.HeartRateSample
import tech.mmarca.healthcalculations.model.HeartRateSummary
import tech.mmarca.healthcalculations.model.HrvSample
import tech.mmarca.healthcalculations.model.RestingHeartRateSample

data class HeartPeriodData(
    val daySamples: List<HeartRateSample> = emptyList(),
    val previousDayAvgBpm: Long? = null,
    val dailySummaries: List<HeartRateSummary> = emptyList(),
    val previousDailySummaries: List<HeartRateSummary> = emptyList(),
    val baselineDailySummaries: List<HeartRateSummary> = emptyList(),
    val dayRestingSamples: List<RestingHeartRateSample> = emptyList(),
    val dayRestingBpm: Long? = null,
    val previousDayRestingBpm: Long? = null,
    val dayHrvSamples: List<HrvSample> = emptyList(),
    val dayHrvMs: Double? = null,
    val previousDayHrvMs: Double? = null,
    val dailyRestingHR: List<DailyRestingHR> = emptyList(),
    val previousDailyRestingHR: List<DailyRestingHR> = emptyList(),
    val baselineDailyRestingHR: List<DailyRestingHR> = emptyList(),
    val dailyHrv: List<DailyHrv> = emptyList(),
    val previousDailyHrv: List<DailyHrv> = emptyList(),
    val baselineDailyHrv: List<DailyHrv> = emptyList(),
)
