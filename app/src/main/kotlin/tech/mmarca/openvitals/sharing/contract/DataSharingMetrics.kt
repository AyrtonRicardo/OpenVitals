package tech.mmarca.openvitals.sharing.contract

import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.ACTIVITY
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.BODY
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.CYCLE
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.HEART
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.NUTRITION
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.RECOVERY
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.SEXUAL_ACTIVITY
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.SLEEP
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.VITALS
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup.WORKOUTS
import tech.mmarca.openvitals.sharing.contract.DataSharingTable.DAILY
import tech.mmarca.openvitals.sharing.contract.DataSharingTable.SAMPLES
import tech.mmarca.openvitals.sharing.contract.DataSharingTable.SESSIONS

/** A metric other apps can ask for. A key is unique within its group. */
data class DataSharingMetric(
    val key: String,
    val group: DataSharingGroup,
    val unit: String,
    val tables: Set<DataSharingTable>,
)

/** Units are always metric. [TEXT] means the row carries `text_value`, not `value`. */
object DataSharingUnits {
    const val COUNT = "count"
    const val METERS = "m"
    const val KILOCALORIES = "kcal"
    const val BPM = "bpm"
    const val MILLISECONDS = "ms"
    const val KILOGRAMS = "kg"
    const val PERCENT = "%"
    const val MMHG = "mmHg"
    const val MMOL_PER_LITER = "mmol/L"
    const val CELSIUS = "degC"
    const val MINUTES = "min"
    const val DAYS = "d"
    const val SCORE = "score"

    /** A 1-5 rating from the cycle journal. */
    const val LEVEL = "level"
    const val VO2_MAX = "mL/kg/min"
    const val PER_MINUTE = "/min"
    const val WATTS = "W"
    const val RPM = "rpm"
    const val METERS_PER_SECOND = "m/s"
    const val GRAMS = "g"
    const val MILLIGRAMS = "mg"
    const val LITERS = "L"
    const val KG_PER_M2 = "kg/m2"
    const val TEXT = "text"
}

/** The full catalog, served at `/metrics`. Clients filter with `metric=` using these keys. */
object DataSharingMetrics {
    val all: List<DataSharingMetric> = buildList {
        activity()
        workouts()
        sleep()
        heart()
        vitals()
        body()
        nutrition()
        recovery()
        cycle()
        // One per record. sexual_activity_protection: protected, unprotected or unknown.
        add(DataSharingMetric("sexual_activity", SEXUAL_ACTIVITY, DataSharingUnits.COUNT, setOf(DAILY, SAMPLES)))
        add(DataSharingMetric("sexual_activity_protection", SEXUAL_ACTIVITY, DataSharingUnits.TEXT, setOf(SAMPLES)))
    }

    fun forGroup(group: DataSharingGroup): List<DataSharingMetric> = all.filter { it.group == group }

    private val unitsByKey: Map<Pair<DataSharingGroup, String>, String> by lazy {
        all.associate { (it.group to it.key) to it.unit }
    }

    /** The unit of [key] in [group]. Throws for a key the catalog does not list. */
    fun unitOf(group: DataSharingGroup, key: String): String =
        requireNotNull(unitsByKey[group to key]) { "No metric $key in ${group.path}" }
}

private val DailyAndSamples = setOf(DAILY, SAMPLES)

private fun MutableList<DataSharingMetric>.metrics(
    group: DataSharingGroup,
    unit: String,
    tables: Set<DataSharingTable>,
    vararg keys: String,
) {
    keys.forEach { add(DataSharingMetric(it, group, unit, tables)) }
}

private fun MutableList<DataSharingMetric>.activity() {
    metrics(ACTIVITY, DataSharingUnits.COUNT, DailyAndSamples, "steps", "floors", "wheelchair_pushes")
    metrics(ACTIVITY, DataSharingUnits.METERS, DailyAndSamples, "distance", "elevation_gained")
    metrics(ACTIVITY, DataSharingUnits.KILOCALORIES, DailyAndSamples, "active_calories", "total_calories")
}

private fun MutableList<DataSharingMetric>.workouts() {
    val summary = setOf(SESSIONS)
    metrics(WORKOUTS, DataSharingUnits.COUNT, setOf(DAILY), "workout_count")
    metrics(WORKOUTS, DataSharingUnits.MINUTES, setOf(DAILY, SESSIONS), "duration")
    metrics(WORKOUTS, DataSharingUnits.METERS_PER_SECOND, setOf(SAMPLES), "speed")
    metrics(WORKOUTS, DataSharingUnits.WATTS, setOf(SAMPLES), "power")
    metrics(WORKOUTS, DataSharingUnits.PER_MINUTE, setOf(SAMPLES), "steps_cadence")
    metrics(WORKOUTS, DataSharingUnits.RPM, setOf(SAMPLES), "cycling_cadence")
    metrics(WORKOUTS, DataSharingUnits.MINUTES, summary, "moving_time")
    metrics(WORKOUTS, DataSharingUnits.METERS, summary, "distance", "elevation_gain")
    metrics(WORKOUTS, DataSharingUnits.KILOCALORIES, summary, "active_calories", "total_calories")
    metrics(WORKOUTS, DataSharingUnits.COUNT, summary, "steps")
    metrics(WORKOUTS, DataSharingUnits.METERS_PER_SECOND, summary, "speed_avg", "moving_speed_avg", "speed_max")
    // hrr_60s: how far the heart rate fell in the first minute of the recovery test.
    metrics(WORKOUTS, DataSharingUnits.BPM, summary, "heart_rate_avg", "heart_rate_max", "hrr_60s")
}

private fun MutableList<DataSharingMetric>.sleep() {
    val dailyAndSummary = setOf(DAILY, SESSIONS)
    metrics(
        SLEEP,
        DataSharingUnits.MINUTES,
        dailyAndSummary,
        "sleep_duration",
        "time_in_bed",
        "awake",
        "light_sleep",
        "deep_sleep",
        "rem_sleep",
    )
    metrics(SLEEP, DataSharingUnits.SCORE, setOf(DAILY), "sleep_score")
    metrics(SLEEP, DataSharingUnits.PERCENT, setOf(DAILY), "sleep_efficiency")
    // sleep_midpoint: the middle of the time in bed, as minutes after local midnight (0-1439).
    metrics(SLEEP, DataSharingUnits.MINUTES, setOf(DAILY), "waso", "sleep_midpoint")
    metrics(SLEEP, DataSharingUnits.TEXT, setOf(SAMPLES), "sleep_stage")
}

private fun MutableList<DataSharingMetric>.heart() {
    metrics(HEART, DataSharingUnits.BPM, setOf(DAILY), "heart_rate_avg", "heart_rate_min", "heart_rate_max", "max_heart_rate")
    metrics(HEART, DataSharingUnits.BPM, setOf(SAMPLES), "heart_rate")
    metrics(HEART, DataSharingUnits.BPM, DailyAndSamples, "resting_heart_rate")
    metrics(HEART, DataSharingUnits.MILLISECONDS, DailyAndSamples, "hrv_rmssd")
}

private fun MutableList<DataSharingMetric>.vitals() {
    metrics(VITALS, DataSharingUnits.MMHG, DailyAndSamples, "blood_pressure_systolic", "blood_pressure_diastolic")
    metrics(VITALS, DataSharingUnits.PERCENT, DailyAndSamples, "spo2")
    metrics(VITALS, DataSharingUnits.PER_MINUTE, DailyAndSamples, "respiratory_rate")
    // skin_temperature_delta: the change from the wearer's baseline, as Health Connect stores it.
    metrics(VITALS, DataSharingUnits.CELSIUS, DailyAndSamples, "body_temperature", "skin_temperature_delta")
    metrics(VITALS, DataSharingUnits.VO2_MAX, DailyAndSamples, "vo2_max")
    metrics(VITALS, DataSharingUnits.MMOL_PER_LITER, DailyAndSamples, "blood_glucose")
}

private fun MutableList<DataSharingMetric>.body() {
    metrics(BODY, DataSharingUnits.KILOGRAMS, DailyAndSamples, "weight", "lean_mass", "bone_mass", "body_water_mass")
    metrics(BODY, DataSharingUnits.METERS, DailyAndSamples, "height")
    metrics(BODY, DataSharingUnits.PERCENT, DailyAndSamples, "body_fat")
    metrics(BODY, DataSharingUnits.KILOCALORIES, DailyAndSamples, "bmr")
    metrics(BODY, DataSharingUnits.KILOCALORIES, setOf(DAILY), "bmr_estimate")
    metrics(BODY, DataSharingUnits.KG_PER_M2, setOf(DAILY), "bmi", "ffmi")
}

private fun MutableList<DataSharingMetric>.nutrition() {
    metrics(NUTRITION, DataSharingUnits.KILOCALORIES, DailyAndSamples, "energy")
    metrics(NUTRITION, DataSharingUnits.GRAMS, DailyAndSamples, "protein", "carbs", "fat", "saturated_fat", "fiber", "sugar")
    metrics(
        NUTRITION,
        DataSharingUnits.MILLIGRAMS,
        DailyAndSamples,
        "sodium",
        "cholesterol",
        "potassium",
        "calcium",
        "iron",
        "vitamin_a",
        "vitamin_b6",
        "vitamin_b12",
        "vitamin_c",
        "vitamin_d",
        "vitamin_e",
        "vitamin_k",
        "caffeine",
    )
    metrics(NUTRITION, DataSharingUnits.LITERS, DailyAndSamples, "hydration")
    // caffeine_active: the modelled amount still in the body, every 15 minutes.
    metrics(NUTRITION, DataSharingUnits.MILLIGRAMS, setOf(SAMPLES), "caffeine_active")
}

/**
 * Readiness, stress, HRV status, cardio load and intensity minutes are not here: working them
 * out takes too many Health Connect reads for a query. They can be added without a new API
 * version once the app keeps a daily record of them.
 */
private fun MutableList<DataSharingMetric>.recovery() {
    metrics(
        RECOVERY,
        DataSharingUnits.SCORE,
        setOf(DAILY),
        "body_energy_start",
        "body_energy_end",
        "body_energy_charged",
        "body_energy_drained",
    )
    metrics(RECOVERY, DataSharingUnits.MINUTES, setOf(DAILY, SESSIONS), "mindfulness_minutes")
    metrics(RECOVERY, DataSharingUnits.SCORE, setOf(SAMPLES), "body_energy")
}

private fun MutableList<DataSharingMetric>.cycle() {
    // From Health Connect.
    metrics(
        CYCLE,
        DataSharingUnits.TEXT,
        DailyAndSamples,
        "menstruation_flow",
        "cervical_mucus_appearance",
        "cervical_mucus_sensation",
        "ovulation_test",
    )
    metrics(CYCLE, DataSharingUnits.COUNT, DailyAndSamples, "intermenstrual_bleeding")
    metrics(CYCLE, DataSharingUnits.CELSIUS, DailyAndSamples, "basal_body_temperature")
    // Worked out by OpenVitals. next_period_* bound the predicted window; thermal_shift is the first day of a confirmed rise.
    metrics(CYCLE, DataSharingUnits.COUNT, setOf(DAILY), "cycle_day")
    metrics(
        CYCLE,
        DataSharingUnits.TEXT,
        setOf(DAILY),
        "cycle_phase",
        "next_period_start",
        "next_period_end",
        "thermal_shift",
    )
    metrics(CYCLE, DataSharingUnits.DAYS, setOf(DAILY), "cycle_length_avg", "period_length_avg")
    // The OpenVitals journal. Free-text notes are never shared.
    metrics(CYCLE, DataSharingUnits.LEVEL, setOf(DAILY), "mood", "energy", "pain")
    metrics(CYCLE, DataSharingUnits.TEXT, setOf(DAILY), "symptoms", "journal_cervical_sensation", "hcg_test", "bbt_disturbances")
    metrics(CYCLE, DataSharingUnits.COUNT, setOf(DAILY), "pill_taken")
}
