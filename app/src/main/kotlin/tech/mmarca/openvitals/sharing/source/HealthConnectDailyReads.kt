package tech.mmarca.openvitals.sharing.source

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.reflect.KClass
import tech.mmarca.openvitals.data.repository.VitalsPeriodMetric
import tech.mmarca.openvitals.data.repository.contract.ActivityRepository
import tech.mmarca.openvitals.data.repository.contract.BodyRepository
import tech.mmarca.openvitals.data.repository.contract.CycleRepository
import tech.mmarca.openvitals.data.repository.contract.HeartRepository
import tech.mmarca.openvitals.data.repository.contract.HydrationRepository
import tech.mmarca.openvitals.data.repository.contract.MindfulnessRepository
import tech.mmarca.openvitals.data.repository.contract.NutritionRepository
import tech.mmarca.openvitals.data.repository.contract.SleepRepository
import tech.mmarca.openvitals.data.repository.contract.SleepWindowPreferences
import tech.mmarca.openvitals.data.repository.contract.VitalsRepository
import tech.mmarca.healthcalculations.model.CaloriesBurnedSource
import tech.mmarca.healthcalculations.model.CycleData
import tech.mmarca.openvitals.domain.model.NutritionNutrient
import tech.mmarca.openvitals.domain.model.minutesByStartDate
import tech.mmarca.openvitals.healthconnect.flowName
import tech.mmarca.openvitals.healthconnect.mucusAppearanceName
import tech.mmarca.openvitals.healthconnect.mucusSensationName
import tech.mmarca.openvitals.healthconnect.ovulationResultName
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingOrigin

/**
 * One read that serves [metrics]. It runs only when OpenVitals holds every
 * Health Connect permission in [requires]; a metric's own type is checked too.
 */
internal class HealthConnectRead<T>(
    val metrics: Set<String>,
    val requires: Set<String>,
    val read: suspend () -> List<T>,
)

internal fun readPermission(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)

/**
 * The daily Health Connect values of each group, through the same repository
 * reads the PDF report uses, so the Room caches apply. A day belongs to the
 * phone's current time zone.
 */
internal class HealthConnectDailyReads @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val sleepRepository: SleepRepository,
    private val heartRepository: HeartRepository,
    private val vitalsRepository: VitalsRepository,
    private val bodyRepository: BodyRepository,
    private val nutritionRepository: NutritionRepository,
    private val hydrationRepository: HydrationRepository,
    private val mindfulnessRepository: MindfulnessRepository,
    private val cycleRepository: CycleRepository,
    private val sleepWindowPreferences: SleepWindowPreferences,
) {
    fun reads(group: DataSharingGroup, start: LocalDate, end: LocalDate): List<HealthConnectRead<DailyRow>> {
        val zone = ZoneId.systemDefault()
        val inRange = { date: LocalDate -> !date.isBefore(start) && !date.isAfter(end) }
        return when (group) {
            DataSharingGroup.ACTIVITY -> activity(start, end)
            DataSharingGroup.WORKOUTS -> listOf(
                read(setOf("workout_count", "duration"), ExerciseSessionRecord::class) {
                    activityRepository.loadWorkouts(start, end)
                        .groupBy { it.startTime.atZone(zone).toLocalDate() }
                        .filterKeys(inRange)
                        .flatMap { (date, workouts) ->
                            listOf(
                                DailyRow(date, "workout_count", workouts.size.toDouble()),
                                DailyRow(date, "duration", workouts.sumOf { it.durationMs } / MillisPerMinute),
                            )
                        }
                },
            )
            DataSharingGroup.SLEEP -> sleep(start, end, zone, inRange)
            DataSharingGroup.HEART -> heart(start, end)
            DataSharingGroup.VITALS -> vitals(start, end)
            DataSharingGroup.BODY -> body(start, end, zone, inRange)
            DataSharingGroup.NUTRITION -> nutrition(start, end)
            DataSharingGroup.RECOVERY -> listOf(
                read(setOf("mindfulness_minutes"), MindfulnessSessionRecord::class) {
                    mindfulnessRepository.loadMindfulnessSessions(start, end)
                        .minutesByStartDate(zone)
                        .filterKeys(inRange)
                        .map { (date, minutes) -> DailyRow(date, "mindfulness_minutes", minutes) }
                },
            )
            DataSharingGroup.CYCLE -> listOf(
                HealthConnectRead(CycleDailyKeys, emptySet()) {
                    cycleDailyRows(cycleRepository.loadCycleData(start, end), zone).filter { inRange(it.date) }
                },
            )
            DataSharingGroup.SEXUAL_ACTIVITY -> listOf(
                HealthConnectRead(setOf("sexual_activity"), emptySet()) {
                    cycleRepository.loadCycleData(start, end).sexualActivity
                        .groupBy { it.time.atZone(zone).toLocalDate() }
                        .filterKeys(inRange)
                        .map { (date, entries) -> DailyRow(date, "sexual_activity", entries.size.toDouble()) }
                },
            )
            DataSharingGroup.ROUTES -> emptyList()
        }
    }

    private fun activity(start: LocalDate, end: LocalDate) = listOf(
        HealthConnectRead(
            setOf("steps", "distance", "active_calories", "floors", "elevation_gained", "wheelchair_pushes"),
            setOf(readPermission(StepsRecord::class)),
        ) {
            activityRepository.loadDailySteps(start, end, includeWheelchairPushes = true).flatMap { day ->
                listOfNotNull(
                    positive(day.date, "steps", day.steps.toDouble()),
                    positive(day.date, "distance", day.distanceMeters),
                    positive(day.date, "active_calories", day.activeCaloriesKcal),
                    positive(day.date, "floors", day.floorsClimbed?.toDouble()),
                    positive(day.date, "elevation_gained", day.elevationGainedMeters),
                    positive(day.date, "wheelchair_pushes", day.wheelchairPushes?.toDouble()),
                )
            }
        },
        read(setOf("total_calories"), TotalCaloriesBurnedRecord::class) {
            activityRepository.loadDailyNutrition(start, end).mapNotNull { day ->
                // An estimate from active calories and BMR is OpenVitals' own number.
                val origin = if (day.caloriesBurnedSource == CaloriesBurnedSource.RECORDED_TOTAL) {
                    DataSharingOrigin.HEALTH_CONNECT
                } else {
                    DataSharingOrigin.OPENVITALS
                }
                positive(day.date, "total_calories", day.caloriesBurnedKcal)?.copy(origin = origin)
            }
        },
    )

    private fun sleep(start: LocalDate, end: LocalDate, zone: ZoneId, inRange: (LocalDate) -> Boolean) = listOf(
        read(setOf("sleep_duration"), SleepSessionRecord::class) {
            sleepRepository.loadDailySleepDurations(start, end, sleepWindowPreferences.sleepWindow)
                .mapNotNull { positive(it.date, "sleep_duration", it.durationMs / MillisPerMinute) }
        },
        // A night belongs to the day it ends on.
        read(SleepSessionSummaryKeys - "sleep_duration", SleepSessionRecord::class) {
            sleepRepository.loadSleepSessions(start, end)
                .groupBy { it.endTime.atZone(zone).toLocalDate() }
                .filterKeys(inRange)
                .flatMap { (date, nights) ->
                    nights.flatMap { night -> sleepSummary(night.durationMs, night.stages.map { it.stageType to it.durationMs }) }
                        .filter { it.metric != "sleep_duration" }
                        .groupBy { it.metric }
                        .map { (metric, rows) -> DailyRow(date, metric, rows.sumOf { it.value ?: 0.0 }) }
                }
        },
    )

    private fun heart(start: LocalDate, end: LocalDate) = listOf(
        read(setOf("heart_rate_avg", "heart_rate_min", "heart_rate_max"), HeartRateRecord::class) {
            heartRepository.loadDailyHeartRateSummaries(start, end).flatMap { day ->
                listOf(
                    DailyRow(day.date, "heart_rate_avg", day.avgBpm.toDouble()),
                    DailyRow(day.date, "heart_rate_min", day.minBpm.toDouble()),
                    DailyRow(day.date, "heart_rate_max", day.maxBpm.toDouble()),
                )
            }
        },
        read(setOf("resting_heart_rate"), RestingHeartRateRecord::class) {
            heartRepository.loadDailyRestingHR(start, end).map { DailyRow(it.date, "resting_heart_rate", it.bpm.toDouble()) }
        },
        read(setOf("hrv_rmssd"), HeartRateVariabilityRmssdRecord::class) {
            heartRepository.loadDailyHRV(start, end).map { DailyRow(it.date, "hrv_rmssd", it.rmssdMs) }
        },
    )

    private fun vitals(start: LocalDate, end: LocalDate): List<HealthConnectRead<DailyRow>> {
        fun cached(metric: String, type: KClass<out Record>, vitals: VitalsPeriodMetric) = read(setOf(metric), type) {
            vitalsRepository.loadDailyVitals(vitals, start, end).map { DailyRow(it.date, metric, it.value) }
        }
        return listOf(
            read(setOf("blood_pressure_systolic", "blood_pressure_diastolic"), BloodPressureRecord::class) {
                vitalsRepository.loadDailyBloodPressure(start, end).flatMap { day ->
                    listOf(
                        DailyRow(day.date, "blood_pressure_systolic", day.systolic),
                        DailyRow(day.date, "blood_pressure_diastolic", day.diastolic),
                    )
                }
            },
            cached("spo2", OxygenSaturationRecord::class, VitalsPeriodMetric.SPO2),
            cached("respiratory_rate", RespiratoryRateRecord::class, VitalsPeriodMetric.RESPIRATORY_RATE),
            cached("body_temperature", BodyTemperatureRecord::class, VitalsPeriodMetric.BODY_TEMPERATURE),
            cached("skin_temperature_delta", SkinTemperatureRecord::class, VitalsPeriodMetric.SKIN_TEMPERATURE),
            cached("vo2_max", Vo2MaxRecord::class, VitalsPeriodMetric.VO2_MAX),
            cached("blood_glucose", BloodGlucoseRecord::class, VitalsPeriodMetric.BLOOD_GLUCOSE),
        )
    }

    private fun body(
        start: LocalDate,
        end: LocalDate,
        zone: ZoneId,
        inRange: (LocalDate) -> Boolean,
    ): List<HealthConnectRead<DailyRow>> {
        fun mean(metric: String, type: KClass<out Record>, entries: suspend () -> List<Pair<Instant, Double>>) =
            read(setOf(metric), type) {
                entries()
                    .groupBy({ it.first.atZone(zone).toLocalDate() }, { it.second })
                    .filterKeys(inRange)
                    .map { (date, values) -> DailyRow(date, metric, values.average()) }
            }
        return listOf(
            mean("weight", WeightRecord::class) { bodyRepository.loadWeightEntries(start, end).map { it.time to it.weightKg } },
            mean("height", HeightRecord::class) {
                bodyRepository.loadHeightEntries(start, end).map { it.time to it.heightCm / CentimetersPerMeter }
            },
            mean("body_fat", BodyFatRecord::class) { bodyRepository.loadBodyFatEntries(start, end).map { it.time to it.percent } },
            mean("lean_mass", LeanBodyMassRecord::class) {
                bodyRepository.loadLeanBodyMassEntries(start, end).map { it.time to it.massKg }
            },
            mean("bone_mass", BoneMassRecord::class) { bodyRepository.loadBoneMassEntries(start, end).map { it.time to it.massKg } },
            mean("body_water_mass", BodyWaterMassRecord::class) {
                bodyRepository.loadBodyWaterMassEntries(start, end).map { it.time to it.massKg }
            },
            mean("bmr", BasalMetabolicRateRecord::class) { bodyRepository.loadBmrEntries(start, end).map { it.time to it.kcalPerDay } },
        )
    }

    private fun nutrition(start: LocalDate, end: LocalDate) = listOf(
        read(NutrientKeys.keys, NutritionRecord::class) {
            nutritionRepository.loadDailyMacros(start, end).flatMap { day ->
                NutrientKeys.mapNotNull { (key, nutrient) ->
                    val grams = day.nutrientValues[nutrient] ?: return@mapNotNull null
                    val value = if (nutrient in MilligramNutrients) grams * MilligramsPerGram else grams
                    positive(day.date, key, value)
                }
            }
        },
        read(setOf("hydration"), HydrationRecord::class) {
            hydrationRepository.loadDailyHydration(start, end).mapNotNull { positive(it.date, "hydration", it.liters) }
        },
    )

    private fun read(metrics: Set<String>, type: KClass<out Record>, block: suspend () -> List<DailyRow>) =
        HealthConnectRead(metrics, setOf(readPermission(type)), block)

    private fun positive(date: LocalDate, metric: String, value: Double?): DailyRow? =
        value?.takeIf { it > 0.0 }?.let { DailyRow(date, metric, it) }

    private companion object {
        const val MillisPerMinute = 60_000.0
        const val CentimetersPerMeter = 100.0
        const val MilligramsPerGram = 1_000.0

        /** The repository keeps energy in kcal and every mass in grams. */
        val NutrientKeys: Map<String, NutritionNutrient> = mapOf(
            "energy" to NutritionNutrient.ENERGY,
            "protein" to NutritionNutrient.PROTEIN,
            "carbs" to NutritionNutrient.TOTAL_CARBOHYDRATE,
            "fat" to NutritionNutrient.TOTAL_FAT,
            "saturated_fat" to NutritionNutrient.SATURATED_FAT,
            "fiber" to NutritionNutrient.DIETARY_FIBER,
            "sugar" to NutritionNutrient.SUGAR,
            "sodium" to NutritionNutrient.SODIUM,
            "cholesterol" to NutritionNutrient.CHOLESTEROL,
            "potassium" to NutritionNutrient.POTASSIUM,
            "calcium" to NutritionNutrient.CALCIUM,
            "iron" to NutritionNutrient.IRON,
            "vitamin_a" to NutritionNutrient.VITAMIN_A,
            "vitamin_b6" to NutritionNutrient.VITAMIN_B6,
            "vitamin_b12" to NutritionNutrient.VITAMIN_B12,
            "vitamin_c" to NutritionNutrient.VITAMIN_C,
            "vitamin_d" to NutritionNutrient.VITAMIN_D,
            "vitamin_e" to NutritionNutrient.VITAMIN_E,
            "vitamin_k" to NutritionNutrient.VITAMIN_K,
            "caffeine" to NutritionNutrient.CAFFEINE,
        )

        val MilligramNutrients: Set<NutritionNutrient> =
            NutrientKeys.values.toSet() - setOf(
                NutritionNutrient.ENERGY,
                NutritionNutrient.PROTEIN,
                NutritionNutrient.TOTAL_CARBOHYDRATE,
                NutritionNutrient.TOTAL_FAT,
                NutritionNutrient.SATURATED_FAT,
                NutritionNutrient.DIETARY_FIBER,
                NutritionNutrient.SUGAR,
            )

        val CycleDailyKeys = setOf(
            "menstruation_flow",
            "intermenstrual_bleeding",
            "cervical_mucus_appearance",
            "cervical_mucus_sensation",
            "ovulation_test",
            "basal_body_temperature",
        )
    }
}

/** The sleep keys a session or a day sums up. */
internal val SleepSessionSummaryKeys = setOf("sleep_duration", "time_in_bed", "awake", "light_sleep", "deep_sleep", "rem_sleep")

/**
 * One night's totals in minutes. Asleep time leaves out the awake and out-of-bed
 * stages. Stage keys appear only when the night has stages.
 */
internal fun sleepSummary(durationMs: Long, stages: List<Pair<Int, Long>>): List<SummaryRow> {
    fun minutes(ms: Long) = ms / 60_000.0
    val byStage = stages.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.sum() }
    val awakeMs = (byStage[SleepSessionRecord.STAGE_TYPE_AWAKE] ?: 0L) +
        (byStage[SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED] ?: 0L)
    val outOfBedMs = byStage[SleepSessionRecord.STAGE_TYPE_OUT_OF_BED] ?: 0L
    val rows = mutableListOf(
        SummaryRow("time_in_bed", minutes(durationMs - outOfBedMs)),
        SummaryRow("sleep_duration", minutes(durationMs - awakeMs - outOfBedMs)),
    )
    if (stages.isNotEmpty()) {
        rows += SummaryRow("awake", minutes(awakeMs))
        rows += SummaryRow("light_sleep", minutes(byStage[SleepSessionRecord.STAGE_TYPE_LIGHT] ?: 0L))
        rows += SummaryRow("deep_sleep", minutes(byStage[SleepSessionRecord.STAGE_TYPE_DEEP] ?: 0L))
        rows += SummaryRow("rem_sleep", minutes(byStage[SleepSessionRecord.STAGE_TYPE_REM] ?: 0L))
    }
    return rows
}

/**
 * A day's cycle values: the heaviest flow, the last mucus and ovulation reading,
 * the count of spotting records, and the mean basal temperature.
 */
internal fun cycleDailyRows(data: CycleData, zone: ZoneId): List<DailyRow> {
    fun Instant.day() = atZone(zone).toLocalDate()
    val rows = mutableListOf<DailyRow>()
    data.menstruationFlows.groupBy { it.time.day() }.forEach { (date, entries) ->
        rows += DailyRow(date, "menstruation_flow", text = flowName(entries.maxBy { flowRank(it.flow) }.flow))
    }
    data.intermenstrualBleeding.groupBy { it.time.day() }.forEach { (date, entries) ->
        rows += DailyRow(date, "intermenstrual_bleeding", entries.size.toDouble())
    }
    data.cervicalMucus.groupBy { it.time.day() }.forEach { (date, entries) ->
        val last = entries.maxBy { it.time }
        rows += DailyRow(date, "cervical_mucus_appearance", text = mucusAppearanceName(last.appearance))
        rows += DailyRow(date, "cervical_mucus_sensation", text = mucusSensationName(last.sensation))
    }
    data.ovulationTests.groupBy { it.time.day() }.forEach { (date, entries) ->
        rows += DailyRow(date, "ovulation_test", text = ovulationResultName(entries.maxBy { it.time }.result))
    }
    data.basalBodyTemperature.groupBy { it.time.day() }.forEach { (date, entries) ->
        rows += DailyRow(date, "basal_body_temperature", entries.map { it.temperatureCelsius }.average())
    }
    return rows
}

/** Unknown ranks below any recorded amount. */
private fun flowRank(flow: Int): Int = when (flow) {
    MenstruationFlowRecord.FLOW_LIGHT -> 1
    MenstruationFlowRecord.FLOW_MEDIUM -> 2
    MenstruationFlowRecord.FLOW_HEAVY -> 3
    else -> 0
}
