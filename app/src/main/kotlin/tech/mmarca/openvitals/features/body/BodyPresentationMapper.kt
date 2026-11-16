package tech.mmarca.openvitals.features.body

import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.core.period.displayPeriodFor
import tech.mmarca.openvitals.domain.query.BodyPeriodData
import tech.mmarca.healthcalculations.insights.fatFreeMassIndex
import tech.mmarca.healthcalculations.insights.bodyMassIndex
import tech.mmarca.healthcalculations.insights.adjustedFatFreeMassIndex

object BodyPresentationMapper {

    fun build(
        query: PeriodLoadQuery,
        data: BodyPeriodData,
    ): BodyDisplayState {
        val heightCm = data.heightEntries.maxByOrNull { it.time }?.heightCm ?: data.heightCm
        val leanMassKg = data.leanMassEntries.maxByOrNull { it.time }?.massKg ?: data.leanMassKg
        val bmrKcal = data.bmrEntries.maxByOrNull { it.time }?.kcalPerDay ?: data.bmrKcal
        val boneMassKg = data.boneMassEntries.maxByOrNull { it.time }?.massKg ?: data.boneMassKg
        val bodyWaterMassKg = data.bodyWaterMassEntries.maxByOrNull { it.time }?.massKg ?: data.bodyWaterMassKg
        // A body-fat reading at a period's edge may have its weight just across it.
        val weights = data.weightEntries + data.previousWeightEntries + data.baselineWeightEntries
        return BodyDisplayState(
            selectedPeriod = displayPeriodFor(
                range = query.range,
                anchorDate = query.selectedDate,
                weekPeriodMode = query.weekPeriodMode,
            ),
            summary = data.summary(
                heightCm = heightCm,
                leanMassKg = leanMassKg,
                bmrKcal = bmrKcal,
                boneMassKg = boneMassKg,
                bodyWaterMassKg = bodyWaterMassKg,
            ),
            ffmiReadings = ffmiReadings(data.bodyFatEntries, weights, heightCm),
            previousFfmiReadings = ffmiReadings(data.previousBodyFatEntries, weights, heightCm),
            baselineFfmiReadings = ffmiReadings(data.baselineBodyFatEntries, weights, heightCm),
        )
    }

    fun build(
        query: PeriodLoadQuery,
        state: BodyUiState,
    ): BodyDisplayState =
        build(
            query = query,
            data = state.toPeriodData(),
        )
}

private fun BodyUiState.toPeriodData() =
    tech.mmarca.openvitals.domain.query.BodyPeriodData(
        weightEntries = weightEntries,
        previousWeightEntries = previousWeightEntries,
        baselineWeightEntries = baselineWeightEntries,
        heightEntries = heightEntries,
        previousHeightEntries = previousHeightEntries,
        baselineHeightEntries = baselineHeightEntries,
        bodyFatEntries = bodyFatEntries,
        previousBodyFatEntries = previousBodyFatEntries,
        baselineBodyFatEntries = baselineBodyFatEntries,
        leanMassEntries = leanMassEntries,
        previousLeanMassEntries = previousLeanMassEntries,
        baselineLeanMassEntries = baselineLeanMassEntries,
        bmrEntries = bmrEntries,
        previousBmrEntries = previousBmrEntries,
        baselineBmrEntries = baselineBmrEntries,
        boneMassEntries = boneMassEntries,
        previousBoneMassEntries = previousBoneMassEntries,
        baselineBoneMassEntries = baselineBoneMassEntries,
        bodyWaterMassEntries = bodyWaterMassEntries,
        previousBodyWaterMassEntries = previousBodyWaterMassEntries,
        baselineBodyWaterMassEntries = baselineBodyWaterMassEntries,
    )

private fun BodyPeriodData.summary(
    heightCm: Double?,
    leanMassKg: Double?,
    bmrKcal: Double?,
    boneMassKg: Double?,
    bodyWaterMassKg: Double?,
): BodySummaryDisplay {
    val latestWeightKg = weightEntries.maxByOrNull { it.time }?.weightKg ?: latestWeightKg
    val previousLatestWeightKg = previousWeightEntries.maxByOrNull { it.time }?.weightKg
    val firstWeightKg = weightEntries.minByOrNull { it.time }?.weightKg
    val latestHeightCm = heightEntries.maxByOrNull { it.time }?.heightCm ?: heightCm
    val latestBodyFatPercent = bodyFatEntries.maxByOrNull { it.time }?.percent ?: this.latestBodyFatPercent
    val ffmi = latestWeightKg.ffmiWith(heightCm, latestBodyFatPercent)
    return BodySummaryDisplay(
        heightCm = heightCm,
        leanMassKg = leanMassKg,
        bmrKcal = bmrKcal,
        boneMassKg = boneMassKg,
        bodyWaterMassKg = bodyWaterMassKg,
        latestWeightKg = latestWeightKg,
        previousLatestWeightKg = previousLatestWeightKg,
        firstWeightKg = firstWeightKg,
        weightChangeKg = if (latestWeightKg != null && firstWeightKg != null && latestWeightKg != firstWeightKg) {
            latestWeightKg - firstWeightKg
        } else {
            null
        },
        latestBodyFatPercent = latestBodyFatPercent,
        previousLatestBodyFatPercent = previousBodyFatEntries.maxByOrNull { it.time }?.percent,
        bmi = latestWeightKg.bmiWith(heightCm),
        ffmi = ffmi,
        adjustedFfmi = adjustedFatFreeMassIndex(latestWeightKg, heightCm, latestBodyFatPercent),
        latestHeightCm = latestHeightCm,
        previousLatestHeightCm = previousHeightEntries.maxByOrNull { it.time }?.heightCm,
        latestLeanMassKg = leanMassEntries.maxByOrNull { it.time }?.massKg ?: leanMassKg,
        previousLatestLeanMassKg = previousLeanMassEntries.maxByOrNull { it.time }?.massKg,
        latestBmrKcal = bmrEntries.maxByOrNull { it.time }?.kcalPerDay ?: bmrKcal,
        previousLatestBmrKcal = previousBmrEntries.maxByOrNull { it.time }?.kcalPerDay,
        latestBoneMassKg = boneMassEntries.maxByOrNull { it.time }?.massKg ?: boneMassKg,
        previousLatestBoneMassKg = previousBoneMassEntries.maxByOrNull { it.time }?.massKg,
        latestBodyWaterMassKg = bodyWaterMassEntries.maxByOrNull { it.time }?.massKg ?: bodyWaterMassKg,
        previousLatestBodyWaterMassKg = previousBodyWaterMassEntries.maxByOrNull { it.time }?.massKg,
        previousBmi = previousLatestWeightKg.bmiWith(heightCm),
    )
}

internal fun Double?.bmiWith(heightCm: Double?): Double? = bodyMassIndex(this, heightCm)

internal fun Double?.ffmiWith(heightCm: Double?, bodyFatPercent: Double?): Double? =
    fatFreeMassIndex(this, heightCm, bodyFatPercent)
