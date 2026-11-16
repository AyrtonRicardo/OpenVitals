package tech.mmarca.openvitals.domain.query

import tech.mmarca.healthcalculations.model.BloodGlucoseEntry
import tech.mmarca.healthcalculations.model.BloodPressureEntry
import tech.mmarca.healthcalculations.model.BodyTempEntry
import tech.mmarca.healthcalculations.model.DailyBloodPressurePoint
import tech.mmarca.healthcalculations.model.DailyVitalPoint
import tech.mmarca.healthcalculations.model.RespiratoryRateEntry
import tech.mmarca.healthcalculations.model.SkinTemperatureEntry
import tech.mmarca.healthcalculations.model.SpO2Entry
import tech.mmarca.healthcalculations.model.Vo2MaxEntry
import tech.mmarca.openvitals.data.repository.VitalsPeriodMetric

data class VitalsPeriodData(
    val missingVitalsPermissions: Set<String> = emptySet(),
    /** Metrics whose daily read blew its budget. Their lists are empty; the UI says so. */
    val timedOutMetrics: Set<VitalsPeriodMetric> = emptySet(),
    val bloodPressure: List<BloodPressureEntry> = emptyList(),
    val previousBloodPressure: List<BloodPressureEntry> = emptyList(),
    val baselineBloodPressure: List<BloodPressureEntry> = emptyList(),
    val spO2: List<SpO2Entry> = emptyList(),
    val previousSpO2: List<SpO2Entry> = emptyList(),
    val baselineSpO2: List<SpO2Entry> = emptyList(),
    val respiratoryRate: List<RespiratoryRateEntry> = emptyList(),
    val previousRespiratoryRate: List<RespiratoryRateEntry> = emptyList(),
    val baselineRespiratoryRate: List<RespiratoryRateEntry> = emptyList(),
    val bodyTemperature: List<BodyTempEntry> = emptyList(),
    val previousBodyTemperature: List<BodyTempEntry> = emptyList(),
    val baselineBodyTemperature: List<BodyTempEntry> = emptyList(),
    val vo2Max: List<Vo2MaxEntry> = emptyList(),
    val previousVo2Max: List<Vo2MaxEntry> = emptyList(),
    val baselineVo2Max: List<Vo2MaxEntry> = emptyList(),
    val bloodGlucose: List<BloodGlucoseEntry> = emptyList(),
    val previousBloodGlucose: List<BloodGlucoseEntry> = emptyList(),
    val baselineBloodGlucose: List<BloodGlucoseEntry> = emptyList(),
    val skinTemperature: List<SkinTemperatureEntry> = emptyList(),
    val previousSkinTemperature: List<SkinTemperatureEntry> = emptyList(),
    val baselineSkinTemperature: List<SkinTemperatureEntry> = emptyList(),
    // Non-day loads carry one point per day plus the window's latest reading.
    val bloodPressureDaily: List<DailyBloodPressurePoint> = emptyList(),
    val spO2Daily: List<DailyVitalPoint> = emptyList(),
    val respiratoryRateDaily: List<DailyVitalPoint> = emptyList(),
    val bodyTemperatureDaily: List<DailyVitalPoint> = emptyList(),
    val vo2MaxDaily: List<DailyVitalPoint> = emptyList(),
    val bloodGlucoseDaily: List<DailyVitalPoint> = emptyList(),
    val skinTemperatureDaily: List<DailyVitalPoint> = emptyList(),
    val latestBloodPressure: BloodPressureEntry? = null,
    val latestSpO2: SpO2Entry? = null,
    val latestRespiratoryRate: RespiratoryRateEntry? = null,
    val latestBodyTemperature: BodyTempEntry? = null,
    val latestVo2Max: Vo2MaxEntry? = null,
    val latestBloodGlucose: BloodGlucoseEntry? = null,
    val latestSkinTemperature: SkinTemperatureEntry? = null,
)
