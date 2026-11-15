package tech.mmarca.openvitals.healthconnect

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import java.time.Instant
import kotlin.reflect.KClass

/** One reading as another app may be given it. [end] is null for an instant; metric units are metric. */
data class SharedSample(
    val start: Instant,
    val end: Instant?,
    val metric: String,
    val value: Double? = null,
    val text: String? = null,
)

/** The kinds of data a sample belongs to. The data sharing API has one permission for each. */
enum class SharedSampleFamily { ACTIVITY, WORKOUTS, SLEEP, HEART, VITALS, BODY, NUTRITION, CYCLE, SEXUAL_ACTIVITY }

/**
 * How one Health Connect record type becomes samples. [metrics] lists every
 * key [toSamples] can return, so a query skips types it did not ask for.
 */
internal class SharedSampleType(
    val family: SharedSampleFamily,
    val recordType: KClass<out Record>,
    val metrics: Set<String>,
    val toSamples: (Record) -> List<SharedSample>,
)

/**
 * Reads every record of a type in a range and maps it to samples, for the data
 * sharing API. A screen read: paced, counted and behind the one backoff, so a
 * refusal fails this read at once instead of holding the caller. It throws
 * under [withStrictHealthConnectReads], which is how the API tells "no data"
 * from "not read".
 */
internal class SharedSamplesHealthReader(private val support: HealthConnectReaderSupport) {

    @Suppress("UNCHECKED_CAST")
    suspend fun read(type: SharedSampleType, start: Instant, end: Instant): List<SharedSample> =
        support.withLogging("readSharedSamples[${type.recordType.simpleName}]", emptyList()) {
            val samples = mutableListOf<SharedSample>()
            var pageToken: String? = null
            do {
                val response = support.client().readRecords(
                    ReadRecordsRequest(
                        recordType = type.recordType as KClass<Record>,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = PageSize,
                        pageToken = pageToken,
                    ),
                )
                response.records.flatMapTo(samples) { type.toSamples(it) }
                pageToken = response.pageToken
            } while (pageToken != null)
            // A record that overlaps the range can hold samples outside it.
            samples.filter { !it.start.isBefore(start) && it.start.isBefore(end) }
        }

    private companion object {
        const val PageSize = 1000
    }
}

/** Every Health Connect type each family serves as samples. Pure, so the JVM tests reach it. */
internal object SharedSampleTypes {
    // Before [all], which reads it while the object initializes.
    private val NutritionSampleKeys = listOf(
        "energy", "protein", "carbs", "fat", "saturated_fat", "fiber", "sugar", "sodium", "cholesterol",
        "potassium", "calcium", "iron", "vitamin_a", "vitamin_b6", "vitamin_b12", "vitamin_c", "vitamin_d",
        "vitamin_e", "vitamin_k", "caffeine",
    )

    val all: List<SharedSampleType> = listOf(
        // Activity: intervals.
        spec<StepsRecord>(SharedSampleFamily.ACTIVITY, "steps") { interval(it.startTime, it.endTime, "steps", it.count.toDouble()) },
        spec<DistanceRecord>(SharedSampleFamily.ACTIVITY, "distance") {
            interval(it.startTime, it.endTime, "distance", it.distance.inMeters)
        },
        spec<FloorsClimbedRecord>(SharedSampleFamily.ACTIVITY, "floors") { interval(it.startTime, it.endTime, "floors", it.floors) },
        spec<ElevationGainedRecord>(SharedSampleFamily.ACTIVITY, "elevation_gained") {
            interval(it.startTime, it.endTime, "elevation_gained", it.elevation.inMeters)
        },
        spec<WheelchairPushesRecord>(SharedSampleFamily.ACTIVITY, "wheelchair_pushes") {
            interval(it.startTime, it.endTime, "wheelchair_pushes", it.count.toDouble())
        },
        spec<ActiveCaloriesBurnedRecord>(SharedSampleFamily.ACTIVITY, "active_calories") {
            interval(it.startTime, it.endTime, "active_calories", it.energy.inKilocalories)
        },
        spec<TotalCaloriesBurnedRecord>(SharedSampleFamily.ACTIVITY, "total_calories") {
            interval(it.startTime, it.endTime, "total_calories", it.energy.inKilocalories)
        },

        // Workouts: the series a session holds.
        spec<SpeedRecord>(SharedSampleFamily.WORKOUTS, "speed") { record ->
            record.samples.map { SharedSample(it.time, null, "speed", it.speed.inMetersPerSecond) }
        },
        spec<PowerRecord>(SharedSampleFamily.WORKOUTS, "power") { record ->
            record.samples.map { SharedSample(it.time, null, "power", it.power.inWatts) }
        },
        spec<StepsCadenceRecord>(SharedSampleFamily.WORKOUTS, "steps_cadence") { record ->
            record.samples.map { SharedSample(it.time, null, "steps_cadence", it.rate) }
        },
        spec<CyclingPedalingCadenceRecord>(SharedSampleFamily.WORKOUTS, "cycling_cadence") { record ->
            record.samples.map { SharedSample(it.time, null, "cycling_cadence", it.revolutionsPerMinute) }
        },

        spec<SleepSessionRecord>(SharedSampleFamily.SLEEP, "sleep_stage") { record ->
            record.stages.map { SharedSample(it.startTime, it.endTime, "sleep_stage", text = sleepStageName(it.stage)) }
        },

        spec<HeartRateRecord>(SharedSampleFamily.HEART, "heart_rate") { record ->
            record.samples.map { SharedSample(it.time, null, "heart_rate", it.beatsPerMinute.toDouble()) }
        },
        spec<RestingHeartRateRecord>(SharedSampleFamily.HEART, "resting_heart_rate") {
            instant(it.time, "resting_heart_rate", it.beatsPerMinute.toDouble())
        },
        spec<HeartRateVariabilityRmssdRecord>(SharedSampleFamily.HEART, "hrv_rmssd") {
            instant(it.time, "hrv_rmssd", it.heartRateVariabilityMillis)
        },

        spec<BloodPressureRecord>(SharedSampleFamily.VITALS, "blood_pressure_systolic", "blood_pressure_diastolic") {
            instant(it.time, "blood_pressure_systolic", it.systolic.inMillimetersOfMercury) +
                instant(it.time, "blood_pressure_diastolic", it.diastolic.inMillimetersOfMercury)
        },
        spec<OxygenSaturationRecord>(SharedSampleFamily.VITALS, "spo2") { instant(it.time, "spo2", it.percentage.value) },
        spec<RespiratoryRateRecord>(SharedSampleFamily.VITALS, "respiratory_rate") {
            instant(it.time, "respiratory_rate", it.rate)
        },
        spec<BodyTemperatureRecord>(SharedSampleFamily.VITALS, "body_temperature") {
            instant(it.time, "body_temperature", it.temperature.inCelsius)
        },
        spec<SkinTemperatureRecord>(SharedSampleFamily.VITALS, "skin_temperature_delta") { record ->
            record.deltas.map { SharedSample(it.time, null, "skin_temperature_delta", it.delta.inCelsius) }
        },
        spec<Vo2MaxRecord>(SharedSampleFamily.VITALS, "vo2_max") { instant(it.time, "vo2_max", it.vo2MillilitersPerMinuteKilogram) },
        spec<BloodGlucoseRecord>(SharedSampleFamily.VITALS, "blood_glucose") {
            instant(it.time, "blood_glucose", it.level.inMillimolesPerLiter)
        },

        spec<WeightRecord>(SharedSampleFamily.BODY, "weight") { instant(it.time, "weight", it.weight.inKilograms) },
        spec<HeightRecord>(SharedSampleFamily.BODY, "height") { instant(it.time, "height", it.height.inMeters) },
        spec<BodyFatRecord>(SharedSampleFamily.BODY, "body_fat") { instant(it.time, "body_fat", it.percentage.value) },
        spec<LeanBodyMassRecord>(SharedSampleFamily.BODY, "lean_mass") { instant(it.time, "lean_mass", it.mass.inKilograms) },
        spec<BoneMassRecord>(SharedSampleFamily.BODY, "bone_mass") { instant(it.time, "bone_mass", it.mass.inKilograms) },
        spec<BodyWaterMassRecord>(SharedSampleFamily.BODY, "body_water_mass") {
            instant(it.time, "body_water_mass", it.mass.inKilograms)
        },
        spec<BasalMetabolicRateRecord>(SharedSampleFamily.BODY, "bmr") {
            instant(it.time, "bmr", it.basalMetabolicRate.inKilocaloriesPerDay)
        },

        spec<NutritionRecord>(SharedSampleFamily.NUTRITION, *NutritionSampleKeys.toTypedArray()) { nutritionRows(it) },
        spec<HydrationRecord>(SharedSampleFamily.NUTRITION, "hydration") {
            interval(it.startTime, it.endTime, "hydration", it.volume.inLiters)
        },

        spec<MenstruationFlowRecord>(SharedSampleFamily.CYCLE, "menstruation_flow") {
            listOf(SharedSample(it.time, null, "menstruation_flow", text = flowName(it.flow)))
        },
        spec<IntermenstrualBleedingRecord>(SharedSampleFamily.CYCLE, "intermenstrual_bleeding") {
            instant(it.time, "intermenstrual_bleeding", 1.0)
        },
        spec<CervicalMucusRecord>(SharedSampleFamily.CYCLE, "cervical_mucus_appearance", "cervical_mucus_sensation") {
            listOf(
                SharedSample(it.time, null, "cervical_mucus_appearance", text = mucusAppearanceName(it.appearance)),
                SharedSample(it.time, null, "cervical_mucus_sensation", text = mucusSensationName(it.sensation)),
            )
        },
        spec<OvulationTestRecord>(SharedSampleFamily.CYCLE, "ovulation_test") {
            listOf(SharedSample(it.time, null, "ovulation_test", text = ovulationResultName(it.result)))
        },
        spec<BasalBodyTemperatureRecord>(SharedSampleFamily.CYCLE, "basal_body_temperature") {
            instant(it.time, "basal_body_temperature", it.temperature.inCelsius)
        },

        spec<SexualActivityRecord>(SharedSampleFamily.SEXUAL_ACTIVITY, "sexual_activity", "sexual_activity_protection") {
            listOf(
                SharedSample(it.time, null, "sexual_activity", 1.0),
                SharedSample(it.time, null, "sexual_activity_protection", text = protectionName(it.protectionUsed)),
            )
        },
    )

    fun forFamily(family: SharedSampleFamily): List<SharedSampleType> = all.filter { it.family == family }

    private inline fun <reified T : Record> spec(
        family: SharedSampleFamily,
        vararg metrics: String,
        crossinline toSamples: (T) -> List<SharedSample>,
    ) = SharedSampleType(family, T::class, metrics.toSet()) { record -> toSamples(record as T) }

    private fun interval(start: Instant, end: Instant, metric: String, value: Double) =
        listOf(SharedSample(start, end, metric, value))

    private fun instant(time: Instant, metric: String, value: Double) = listOf(SharedSample(time, null, metric, value))

    private fun nutritionRows(record: NutritionRecord): List<SharedSample> {
        fun row(metric: String, value: Double?) =
            value?.let { SharedSample(record.startTime, record.endTime, metric, it) }
        fun grams(mass: Mass?) = mass?.inGrams
        fun milligrams(mass: Mass?) = mass?.inMilligrams
        return listOfNotNull(
            row("energy", record.energy?.inKilocalories),
            row("protein", grams(record.protein)),
            row("carbs", grams(record.totalCarbohydrate)),
            row("fat", grams(record.totalFat)),
            row("saturated_fat", grams(record.saturatedFat)),
            row("fiber", grams(record.dietaryFiber)),
            row("sugar", grams(record.sugar)),
            row("sodium", milligrams(record.sodium)),
            row("cholesterol", milligrams(record.cholesterol)),
            row("potassium", milligrams(record.potassium)),
            row("calcium", milligrams(record.calcium)),
            row("iron", milligrams(record.iron)),
            row("vitamin_a", milligrams(record.vitaminA)),
            row("vitamin_b6", milligrams(record.vitaminB6)),
            row("vitamin_b12", milligrams(record.vitaminB12)),
            row("vitamin_c", milligrams(record.vitaminC)),
            row("vitamin_d", milligrams(record.vitaminD)),
            row("vitamin_e", milligrams(record.vitaminE)),
            row("vitamin_k", milligrams(record.vitaminK)),
            row("caffeine", milligrams(record.caffeine)),
        )
    }
}

/** Health Connect's integer codes as the API's text values. Shared by the daily and sample tables. */
internal fun sleepStageName(stage: Int): String = when (stage) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
    else -> "unknown"
}

internal fun flowName(flow: Int): String = when (flow) {
    MenstruationFlowRecord.FLOW_LIGHT -> "light"
    MenstruationFlowRecord.FLOW_MEDIUM -> "medium"
    MenstruationFlowRecord.FLOW_HEAVY -> "heavy"
    else -> "unknown"
}

internal fun mucusAppearanceName(appearance: Int): String = when (appearance) {
    CervicalMucusRecord.APPEARANCE_DRY -> "dry"
    CervicalMucusRecord.APPEARANCE_STICKY -> "sticky"
    CervicalMucusRecord.APPEARANCE_CREAMY -> "creamy"
    CervicalMucusRecord.APPEARANCE_WATERY -> "watery"
    CervicalMucusRecord.APPEARANCE_EGG_WHITE -> "egg_white"
    CervicalMucusRecord.APPEARANCE_UNUSUAL -> "unusual"
    else -> "unknown"
}

internal fun mucusSensationName(sensation: Int): String = when (sensation) {
    CervicalMucusRecord.SENSATION_LIGHT -> "light"
    CervicalMucusRecord.SENSATION_MEDIUM -> "medium"
    CervicalMucusRecord.SENSATION_HEAVY -> "heavy"
    else -> "unknown"
}

internal fun ovulationResultName(result: Int): String = when (result) {
    OvulationTestRecord.RESULT_POSITIVE -> "positive"
    OvulationTestRecord.RESULT_HIGH -> "high"
    OvulationTestRecord.RESULT_NEGATIVE -> "negative"
    else -> "inconclusive"
}

internal fun protectionName(protection: Int): String = when (protection) {
    SexualActivityRecord.PROTECTION_USED_PROTECTED -> "protected"
    SexualActivityRecord.PROTECTION_USED_UNPROTECTED -> "unprotected"
    else -> "unknown"
}
