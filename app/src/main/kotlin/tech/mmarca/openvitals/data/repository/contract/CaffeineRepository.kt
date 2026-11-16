package tech.mmarca.openvitals.data.repository.contract

import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.healthcalculations.model.CaffeinePeriodData

interface CaffeineRepository {
    suspend fun loadCaffeinePeriod(query: PeriodLoadQuery): CaffeinePeriodData =
        loadCaffeineData(query.windows.current)

    suspend fun loadCaffeineData(period: DatePeriod): CaffeinePeriodData
}
