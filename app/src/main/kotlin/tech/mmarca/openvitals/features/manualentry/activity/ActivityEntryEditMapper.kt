package tech.mmarca.openvitals.features.manualentry.activity

import tech.mmarca.openvitals.features.manualentry.*
import tech.mmarca.openvitals.features.manualentry.activity.*
import tech.mmarca.openvitals.features.manualentry.activity.recording.*
import tech.mmarca.openvitals.features.manualentry.activity.routeimport.*
import tech.mmarca.openvitals.features.manualentry.body.*
import tech.mmarca.openvitals.features.manualentry.hydration.*
import tech.mmarca.openvitals.features.manualentry.mindfulness.*
import tech.mmarca.openvitals.features.manualentry.vitals.*
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import tech.mmarca.openvitals.domain.preferences.UnitSystem
import tech.mmarca.healthcalculations.model.ActivityPauseInterval
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.ExerciseLapData
import tech.mmarca.healthcalculations.model.ExerciseRoutePoint
import tech.mmarca.healthcalculations.model.ExerciseRouteStatus
import tech.mmarca.openvitals.data.repository.contract.ActivityRepository
import tech.mmarca.healthcalculations.insights.CompendiumMet
import tech.mmarca.healthcalculations.insights.BmrInput
import tech.mmarca.healthcalculations.insights.WorkoutEnergyResult
import tech.mmarca.healthcalculations.insights.bmrMissingInputs
import tech.mmarca.healthcalculations.insights.estimateWorkoutEnergy
import tech.mmarca.healthcalculations.insights.heartRateRecoveryWindowFor
import tech.mmarca.openvitals.domain.model.BleHeartRateSample
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openvitals.domain.model.ActivityFormMetric
import tech.mmarca.openvitals.features.activity.exerciseTypeLabelRes
import tech.mmarca.healthcalculations.insights.matchExerciseType

internal fun ExerciseData.toEditState(
    units: ActivityEntryUnits,
    clock: Clock,
    repository: ActivityRepository,
    canWrite: Boolean,
    isCheckingPermission: Boolean,
): ActivityEntryUiState {
    val selectedType = inferStoredActivityType()
    val routeImport = route.takeIf { it.status == ExerciseRouteStatus.DATA && it.points.isNotEmpty() }
        ?.let { routeData ->
            RouteFileImport(
                fileName = null,
                points = routeData.points,
                distanceMeters = totalDistanceMeters ?: 0.0,
                elevationGainedMeters = elevationGainedMeters ?: 0.0,
                startTime = startTime,
                endTime = endTime,
                name = title,
                description = notes,
                hasRecordedTimestamps = true,
                hasImportedTimeRange = true,
            )
        }
    val start = startTime.atZone(clock.zone)
    val durationMinutes = ceil(
        Duration.between(startTime, endTime).seconds.coerceAtLeast(1).toDouble() / 60.0
    ).toLong().coerceIn(1, MaxActivityDurationMinutes)
    val repetitionEditState = toRepetitionEditState(selectedType)
    val distanceText = totalDistanceMeters?.takeIf { it > 0.0 }?.toDistanceInputText(units.distance).orEmpty()
    val elevationText = elevationGainedMeters?.takeIf { it > 0.0 }?.toElevationInputText(units.elevation).orEmpty()
    val activeCaloriesText = activeCaloriesKcal?.takeIf { it > 0.0 }?.toInputText(maxFractionDigits = 1).orEmpty()
    val totalCaloriesText = totalCaloriesKcal?.takeIf { it > 0.0 }?.toInputText(maxFractionDigits = 1).orEmpty()
    return ActivityEntryUiState(
        mode = if (routeImport == null) ActivityEntryMode.MANUAL else ActivityEntryMode.ROUTE_IMPORT,
        selectedActivityType = selectedType,
        titleText = title.orEmpty(),
        notesText = notes.orEmpty(),
        startDateText = DateTimeFormatter.ISO_LOCAL_DATE.format(start),
        startTimeText = TimeFormatter.format(start.toLocalTime()),
        durationMinutesText = durationMinutes.toString(),
        distanceText = distanceText,
        elevationText = elevationText,
        activeCaloriesText = activeCaloriesText,
        totalCaloriesText = totalCaloriesText,
        editPrefilledMetricTexts = mapOf(
            ActivityFormMetric.DISTANCE to distanceText,
            ActivityFormMetric.ELEVATION to elevationText,
            ActivityFormMetric.ACTIVE_CALORIES to activeCaloriesText,
            ActivityFormMetric.TOTAL_CALORIES to totalCaloriesText,
            // Steps share the repetition field, and only step-counting types show it.
            ActivityFormMetric.STEPS to
                if (selectedType.supportsStepCounting) repetitionEditState.totalText else "",
        ),
        repetitionMode = repetitionEditState.mode,
        repetitionTotalText = repetitionEditState.totalText,
        repetitionSets = repetitionEditState.sets,
        // Carried so an edit keeps the plan completed.
        linkedPlan = plannedExerciseSessionId?.let { ActivityLinkedPlan(id = it, title = null) },
        importedRoute = routeImport,
        recordedPauseIntervals = segments
            .filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }
            .map { ActivityPauseInterval(startTime = it.startTime, endTime = it.endTime) },
        recordedLaps = laps,
        // A recovery test ends with a REST segment that marks where the effort stopped. The
        // form cannot show it, so the edit carries it, or the save would rebuild the
        // segments without it and the session would leave the recovery screen. A session
        // with sets keeps its own segments and needs no mark.
        recordedRecoveryStartTime = heartRateRecoveryWindowFor(this)?.recoveryStart
            ?.takeIf {
                segments.none { segment ->
                    segment.segmentType != ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE &&
                        segment.segmentType != ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST
                }
            },
        writePermissions = repository.activityWritePermissions(),
        canWrite = canWrite,
        isCheckingPermission = isCheckingPermission,
        editRecordId = id,
        editEntryLoaded = true,
    )
}

internal fun ExerciseData.inferStoredActivityType(): ActivityEntryType {
    val titleText = title.orEmpty().lowercase()
    val activeSegments = segments.filterNot {
        it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE ||
            it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST
    }
    val distinctActiveTypes = activeSegments.map { it.segmentType }.distinct()
    return when {
        distinctActiveTypes.size > 1 && (
            exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS ||
                exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            ) -> genericSetActivityType(exerciseType)
        exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> {
            DefaultActivityEntryTypes.first { it.id == "treadmill" }
        }
        exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS &&
            activeSegments.any { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP } -> {
            DefaultActivityEntryTypes.first { it.id == "pull_ups" }
        }
        exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS &&
            activeSegments.any { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE } -> {
            DefaultActivityEntryTypes.first { it.id == "rope_skipping" }
        }
        exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS && titleText.contains("push") -> {
            DefaultActivityEntryTypes.first { it.id == "push_ups" }
        }
        exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS && titleText.contains("trampoline") -> {
            DefaultActivityEntryTypes.first { it.id == "trampoline_jumping" }
        }
        else -> activityEntryTypeForExerciseType(exerciseType)
    }
}

/**
 * The form's plain entry for a Health Connect type: the non-repetition one when the type
 * has several, else [storedActivityType] for a type the form does not list. What an import
 * keeps for a type (distance, elevation) is what the edit form would show for it.
 */
internal fun activityEntryTypeForExerciseType(exerciseType: Int): ActivityEntryType =
    DefaultActivityEntryTypes
        .firstOrNull { it.exerciseType == exerciseType && !it.isRepetitionLike }
        ?: DefaultActivityEntryTypes.firstOrNull { it.exerciseType == exerciseType }
        ?: storedActivityType(exerciseType)

/**
 * A type the form does not list: one Health Connect added after this build. The edit
 * keeps it. The old fallback was the first type in the list, so saving a new title
 * turned the session into a run.
 */
internal fun storedActivityType(exerciseType: Int): ActivityEntryType = ActivityEntryType(
    exerciseType = exerciseType,
    id = "stored_$exerciseType",
    labelRes = exerciseTypeLabelRes(exerciseType),
    // Nothing to record in an edit. Distance and elevation stay, so stored values are not dropped.
    supportsGpsRoute = false,
    supportsDistance = true,
    supportsElevation = true,
)

internal data class RepetitionEditState(
    val mode: ActivityRepetitionEntryMode = ActivityRepetitionEntryMode.TOTAL,
    val totalText: String = "",
    val sets: List<ActivityRepetitionSetInput> = listOf(ActivityRepetitionSetInput()),
)

internal fun ExerciseData.toRepetitionEditState(type: ActivityEntryType): RepetitionEditState {
    if (type.repetitionUnit == ActivityRepetitionUnit.STEPS) {
        return RepetitionEditState(totalText = steps?.takeIf { it > 0L }?.toString().orEmpty())
    }
    if (type.repetitionUnit != ActivityRepetitionUnit.REPETITIONS) return RepetitionEditState()

    val activeSegments = segments
        .filterNot {
            it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST ||
                it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE
        }
        .filter { it.repetitions > 0 || it.segmentType != type.segmentType }
        .sortedBy { it.startTime }
    if (activeSegments.isEmpty()) return RepetitionEditState()
    if (activeSegments.size == 1 && activeSegments.first().segmentType == type.segmentType) {
        return RepetitionEditState(totalText = activeSegments.first().repetitions.toString())
    }

    val sortedSegments = segments.sortedBy { it.startTime }
    val sets = activeSegments.mapIndexed { index, segment ->
        val restSeconds = sortedSegments
            .firstOrNull {
                it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST &&
                    !it.startTime.isBefore(segment.endTime) &&
                    activeSegments.getOrNull(index + 1)?.let { next -> !it.endTime.isAfter(next.startTime) } != false
            }
            ?.let { rest ->
                Duration.between(rest.startTime, rest.endTime).seconds
                    .takeIf { it > 0L }
                    ?.toString()
            }
            .orEmpty()
        val isDuration = segment.repetitions <= 0
        ActivityRepetitionSetInput(
            repetitionsText = if (isDuration) {
                Duration.between(segment.startTime, segment.endTime).seconds.coerceAtLeast(1L).toString()
            } else {
                segment.repetitions.toString()
            },
            restMinutesText = restSeconds,
            segmentType = segment.segmentType.takeIf { it != type.segmentType },
            isDuration = isDuration,
            weightKgText = segment.weightKg?.toWeightKgText().orEmpty(),
        )
    }
    return RepetitionEditState(
        mode = ActivityRepetitionEntryMode.SETS,
        sets = sets.ifEmpty { listOf(ActivityRepetitionSetInput()) },
    )
}

internal fun Double.toDistanceInputText(unitSystem: UnitSystem): String {
    val value = when (unitSystem) {
        UnitSystem.METRIC -> this / 1000.0
        UnitSystem.IMPERIAL -> this / MilesToMeters
    }
    return value.toInputText(maxFractionDigits = 2)
}

internal fun Double.toElevationInputText(unitSystem: UnitSystem): String {
    val value = when (unitSystem) {
        UnitSystem.METRIC -> this
        UnitSystem.IMPERIAL -> this / FeetToMeters
    }
    return value.toInputText(maxFractionDigits = 1)
}

internal fun inferActivityType(
    routeImport: RouteFileImport,
    currentType: ActivityEntryType,
): ActivityEntryType {
    // The declared sport is asked first, on its own. Matching one string of
    // sport, name and file name once imported a bike ride as a run.
    val declared = routeImport.type?.lowercase()?.trim().orEmpty()
    val guessed = listOfNotNull(routeImport.name, routeImport.fileName)
        .joinToString(separator = " ")
        .lowercase()

    // Only when it names something: FIT's `training` and `fitness equipment`
    // are generic buckets, and then the name is all there is.
    val declaredType = declared.takeIf { it.isNotEmpty() }?.let(::matchExerciseType)
    val declaredNamesIt =
        declaredType != null && declaredType != ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    val exerciseType = if (declaredNamesIt) {
        declaredType
    } else {
        matchExerciseType(guessed) ?: declaredType
    }

    val requiresGpsRoute = routeImport.points.isNotEmpty()
    return DefaultActivityEntryTypes
        .firstOrNull { it.exerciseType == exerciseType && (!requiresGpsRoute || it.supportsGpsRoute) }
        ?: currentType.takeIf { !requiresGpsRoute || it.supportsGpsRoute }
        ?: DefaultActivityEntryTypes.first { !requiresGpsRoute || it.supportsGpsRoute }
}

internal data class ActivityCalorieEstimate(
    val activeCaloriesText: String,
    val totalCaloriesText: String,
)

/** What a workout's calorie estimate reads besides the session: the wearer and the day. */
internal data class CalorieEstimateContext(
    val profile: BodyProfile,
    val today: LocalDate,
)

/**
 * The estimate for a session that recorded no calories: [estimateWorkoutEnergy]
 * from the session's mean heart rate, its distance on foot or the activity's
 * Compendium MET, with the body profile's weight. Null without a weight: the
 * form says so ([calorieEstimateMissingInputs]) rather than guess one.
 */
internal fun activityCalorieEstimate(
    activityType: ActivityEntryType,
    distanceMeters: Double?,
    durationMinutesText: String,
    heartRateSamples: List<BleHeartRateSample>,
    context: CalorieEstimateContext,
): ActivityCalorieEstimate? {
    val durationMinutes = durationMinutesText.trim().toLongOrNull()
        ?.takeIf { it in 1..MaxActivityDurationMinutes }
        ?: return null
    val estimate = estimateWorkoutEnergy(
        exerciseType = activityType.exerciseType,
        durationMinutes = durationMinutes.toDouble(),
        meanHeartRateBpm = heartRateSamples.takeIf { it.isNotEmpty() }?.map { it.beatsPerMinute }?.average(),
        profile = context.profile,
        today = context.today,
        distanceMeters = distanceMeters,
    ) as? WorkoutEnergyResult.Estimate ?: return null
    return ActivityCalorieEstimate(
        activeCaloriesText = estimate.activeKcal.toCaloriesInputText(),
        totalCaloriesText = estimate.totalKcal.toCaloriesInputText(),
    )
}

/**
 * The body profile fields a session's estimate still wants: without a weight
 * there is none, without sex, age or height it is less exact. Empty for an
 * activity with no sourced energy cost.
 */
internal fun calorieEstimateMissingInputs(
    activityType: ActivityEntryType,
    context: CalorieEstimateContext,
): Set<BmrInput> =
    if (CompendiumMet.forExerciseType(activityType.exerciseType) != null) {
        context.profile.bmrMissingInputs(context.today)
    } else {
        emptySet()
    }

internal fun parseDistanceMeters(text: String, unitSystem: UnitSystem): Double? {
    val value = text.toPositiveDoubleOrNull() ?: return null
    return when (unitSystem) {
        UnitSystem.METRIC -> value * 1000.0
        UnitSystem.IMPERIAL -> value * MilesToMeters
    }
}

internal fun parseElevationMeters(text: String, unitSystem: UnitSystem): Double? {
    val value = text.toPositiveDoubleOrNull() ?: return null
    return when (unitSystem) {
        UnitSystem.METRIC -> value
        UnitSystem.IMPERIAL -> value * FeetToMeters
    }
}

internal fun routeDistanceInputText(routeImport: RouteFileImport, unitSystem: UnitSystem): String {
    val distance = routeImport.distanceMeters.takeIf { it > 0.0 } ?: return ""
    val value = when (unitSystem) {
        UnitSystem.METRIC -> distance / 1000.0
        UnitSystem.IMPERIAL -> distance / MilesToMeters
    }
    return value.toInputText(maxFractionDigits = 2)
}

internal fun routeElevationInputText(routeImport: RouteFileImport, unitSystem: UnitSystem): String =
    elevationInputText(routeImport.elevationGainedMeters, unitSystem)

internal fun elevationInputText(elevationMeters: Double, unitSystem: UnitSystem): String {
    val elevation = elevationMeters.takeIf { it > 0.0 } ?: return ""
    val value = when (unitSystem) {
        UnitSystem.METRIC -> elevation
        UnitSystem.IMPERIAL -> elevation / FeetToMeters
    }
    return value.toInputText(maxFractionDigits = 1)
}

internal fun List<ExerciseRoutePoint>.withActivityTimeRange(
    start: java.time.Instant,
    end: java.time.Instant,
): List<ExerciseRoutePoint> {
    if (isEmpty()) return emptyList()
    val totalMillis = Duration.between(start, end)
        .toMillis()
        .coerceAtLeast(size.toLong())
    val lastOffset = (totalMillis - 1).coerceAtLeast(0L)
    return mapIndexed { index, point ->
        val offset = if (size == 1) {
            0L
        } else {
            lastOffset * index / (size - 1)
        }
        point.copy(time = start.plusMillis(offset))
    }
}

internal fun List<ActivityPauseInterval>.insideActivityRange(
    start: java.time.Instant,
    end: java.time.Instant,
): List<ActivityPauseInterval> =
    sortedBy { it.startTime }
        .filter { interval ->
            !interval.startTime.isBefore(start) &&
                interval.startTime.isBefore(interval.endTime) &&
                !interval.endTime.isAfter(end)
        }

internal fun List<ExerciseLapData>.insideLapActivityRange(
    start: java.time.Instant,
    end: java.time.Instant,
): List<ExerciseLapData> =
    sortedBy { it.startTime }
        .filter { lap ->
            !lap.startTime.isBefore(start) &&
                lap.startTime.isBefore(lap.endTime) &&
                !lap.endTime.isAfter(end)
        }

internal fun Double.toInputText(maxFractionDigits: Int): String =
    "%.${maxFractionDigits}f"
        .format(Locale.US, this)
        .trimEnd('0')
        .trimEnd('.')

internal fun Double.toCaloriesInputText(): String =
    roundToInt()
        .coerceAtLeast(1)
        .toString()

internal fun String.toPositiveDoubleOrNull(): Double? =
    trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it > 0.0 }

internal fun String.toPositiveIntOrNull(max: Int): Int? =
    trim()
        .toIntOrNull()
        ?.takeIf { it in 1..max }

internal fun String.toPositiveLongOrNull(max: Long): Long? =
    trim()
        .toLongOrNull()
        ?.takeIf { it in 1..max }

internal fun String.toOptionalNonNegativeLongOrNull(max: Long): Long? {
    val trimmed = trim()
    if (trimmed.isBlank()) return 0L
    return trimmed.toLongOrNull()?.takeIf { it in 0..max }
}

internal val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
