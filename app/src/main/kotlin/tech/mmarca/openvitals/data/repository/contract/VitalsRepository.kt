package tech.mmarca.openvitals.data.repository.contract

import java.time.LocalDate
import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.data.repository.VitalsPeriodMetric
import tech.mmarca.healthcalculations.model.BloodGlucoseEntry
import tech.mmarca.healthcalculations.model.BloodPressureEntry
import tech.mmarca.healthcalculations.model.DailyBloodPressurePoint
import tech.mmarca.healthcalculations.model.DailyVitalPoint
import tech.mmarca.healthcalculations.model.BodyTempEntry
import tech.mmarca.openvitals.domain.model.RefreshMode
import tech.mmarca.healthcalculations.model.RespiratoryRateEntry
import tech.mmarca.healthcalculations.model.SkinTemperatureEntry
import tech.mmarca.healthcalculations.model.SpO2Entry
import tech.mmarca.healthcalculations.model.VitalsMeasurementEntry
import tech.mmarca.healthcalculations.model.VitalsMeasurementType
import tech.mmarca.healthcalculations.model.VitalsMeasurementWriteRequest
import tech.mmarca.healthcalculations.model.Vo2MaxEntry
import tech.mmarca.openvitals.domain.query.VitalsPeriodData

interface VitalsRepository {
    val phase3Permissions: Set<String>

    fun vitalsWritePermissions(type: VitalsMeasurementType): Set<String>

    /**
     * What a blood pressure reading needs on top of [vitalsWritePermissions] to carry a
     * pulse: the pulse is a heart rate record of its own.
     */
    val bpPulseWritePermissions: Set<String>

    suspend fun missingPermissions(): Set<String>

    suspend fun loadVitalsPeriod(
        query: PeriodLoadQuery,
        metric: VitalsPeriodMetric,
        refreshMode: RefreshMode = RefreshMode.NORMAL,
    ): VitalsPeriodData

    /**
     * One point per local day for one vitals metric, cache-first. [metric]
     * must be concrete: ALL and BLOOD_PRESSURE are rejected.
     */
    suspend fun loadDailyVitals(
        metric: VitalsPeriodMetric,
        start: LocalDate,
        end: LocalDate,
    ): List<DailyVitalPoint>

    /** Daily systolic/diastolic averages, cache-first like [loadDailyVitals]. */
    suspend fun loadDailyBloodPressure(start: LocalDate, end: LocalDate): List<DailyBloodPressurePoint>

    suspend fun loadBloodPressure(start: LocalDate, end: LocalDate): List<BloodPressureEntry>

    suspend fun loadSpO2(start: LocalDate, end: LocalDate): List<SpO2Entry>

    suspend fun loadRespiratoryRate(start: LocalDate, end: LocalDate): List<RespiratoryRateEntry>

    suspend fun loadBodyTemperature(start: LocalDate, end: LocalDate): List<BodyTempEntry>

    suspend fun loadVo2Max(start: LocalDate, end: LocalDate): List<Vo2MaxEntry>

    suspend fun loadBloodGlucose(start: LocalDate, end: LocalDate): List<BloodGlucoseEntry>

    suspend fun loadSkinTemperature(start: LocalDate, end: LocalDate): List<SkinTemperatureEntry>

    suspend fun hasVitalsWritePermission(type: VitalsMeasurementType): Boolean

    suspend fun hasBpPulseWritePermission(): Boolean

    suspend fun writeVitalsMeasurementEntry(request: VitalsMeasurementWriteRequest): String

    suspend fun loadVitalsMeasurementEntry(type: VitalsMeasurementType, id: String): VitalsMeasurementEntry?

    suspend fun updateVitalsMeasurementEntry(id: String, request: VitalsMeasurementWriteRequest)

    suspend fun deleteVitalsMeasurementEntry(type: VitalsMeasurementType, id: String)
}
