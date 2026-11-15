package tech.mmarca.openvitals.healthconnect

import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import tech.mmarca.healthcalculations.model.CaloriesBurnedSource
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.ExerciseDeviceData
import tech.mmarca.healthcalculations.model.ExerciseLapData
import tech.mmarca.healthcalculations.model.ExerciseRouteData
import tech.mmarca.healthcalculations.model.ExerciseRoutePoint
import tech.mmarca.healthcalculations.model.ExerciseRouteStatus
import tech.mmarca.healthcalculations.model.ExerciseSegmentData
import tech.mmarca.healthcalculations.model.SleepData
import tech.mmarca.healthcalculations.model.SleepDeviceData
import tech.mmarca.healthcalculations.model.SleepStage
import tech.mmarca.healthcalculations.model.resolveSleepStages
import tech.mmarca.healthcalculations.model.sleepDurationMsFromStages
import tech.mmarca.healthcalculations.model.withRouteBackfilledMetrics
import tech.mmarca.openvitals.domain.model.MindfulnessSession

internal fun ExerciseSessionRecord.toExerciseData(
    steps: Long? = null,
    totalDistanceMeters: Double? = null,
    totalCaloriesKcal: Double? = null,
    totalCaloriesSource: CaloriesBurnedSource = if (totalCaloriesKcal != null) {
        CaloriesBurnedSource.RECORDED_TOTAL
    } else {
        CaloriesBurnedSource.NO_DATA
    },
    activeCaloriesKcal: Double? = null,
    floorsClimbed: Int? = null,
    elevationGainedMeters: Double? = null,
    wheelchairPushes: Long? = null,
    averageSpeedMetersPerSecond: Double? = null,
    averagePowerWatts: Double? = null,
    averageStepsCadenceRate: Double? = null,
    averageCyclingCadenceRpm: Double? = null,
    averageHeartRateBpm: Long? = null,
    appPackageName: String? = null,
    backfillRouteMetrics: Boolean = false,
) = ExerciseData(
    id = metadata.id,
    title = title,
    exerciseType = exerciseType,
    startTime = startTime,
    endTime = endTime,
    durationMs = endTime.toEpochMilli() - startTime.toEpochMilli(),
    source = SyncedSourceOverlay.displaySource(metadata),
    totalDistanceMeters = totalDistanceMeters,
    totalCaloriesKcal = totalCaloriesKcal,
    totalCaloriesSource = totalCaloriesSource,
    activeCaloriesKcal = activeCaloriesKcal,
    steps = steps,
    wheelchairPushes = wheelchairPushes,
    averageSpeedMetersPerSecond = averageSpeedMetersPerSecond,
    averagePowerWatts = averagePowerWatts,
    averageStepsCadenceRate = averageStepsCadenceRate,
    averageCyclingCadenceRpm = averageCyclingCadenceRpm,
    averageHeartRateBpm = averageHeartRateBpm,
    floorsClimbed = floorsClimbed,
    elevationGainedMeters = elevationGainedMeters,
    notes = notes,
    startZoneOffset = startZoneOffset,
    endZoneOffset = endZoneOffset,
    lastModifiedTime = metadata.lastModifiedTime,
    clientRecordId = metadata.clientRecordId,
    clientRecordVersion = metadata.clientRecordVersion,
    recordingMethod = metadata.recordingMethod,
    device = metadata.device?.let { device ->
        ExerciseDeviceData(
            type = device.type,
            manufacturer = device.manufacturer,
            model = device.model,
        )
    },
    plannedExerciseSessionId = plannedExerciseSessionId,
    segments = segments.map { segment ->
        ExerciseSegmentData(
            startTime = segment.startTime,
            endTime = segment.endTime,
            segmentType = segment.segmentType,
            repetitions = segment.repetitions,
            setIndex = segment.setIndex,
            weightKg = segment.weight?.inKilograms,
        )
    },
    laps = laps.map { lap ->
        ExerciseLapData(
            startTime = lap.startTime,
            endTime = lap.endTime,
            lengthMeters = lap.length?.inMeters,
        )
    },
    route = exerciseRouteResult.toExerciseRouteData(),
    isOwnEntry = appPackageName?.let { isOpenVitalsRecord(metadata.dataOrigin.packageName, it) } ?: false,
).let { exercise ->
    if (backfillRouteMetrics) exercise.withRouteBackfilledMetrics() else exercise
}

private fun ExerciseRouteResult.toExerciseRouteData(): ExerciseRouteData = when (this) {
    is ExerciseRouteResult.Data -> ExerciseRouteData(
        status = ExerciseRouteStatus.DATA,
        points = exerciseRoute.route.map { point ->
            ExerciseRoutePoint(
                time = point.time,
                latitude = point.latitude,
                longitude = point.longitude,
                altitudeMeters = point.altitude?.inMeters,
                horizontalAccuracyMeters = point.horizontalAccuracy?.inMeters,
                verticalAccuracyMeters = point.verticalAccuracy?.inMeters,
            )
        },
    )
    is ExerciseRouteResult.ConsentRequired -> ExerciseRouteData(status = ExerciseRouteStatus.CONSENT_REQUIRED)
    is ExerciseRouteResult.NoData -> ExerciseRouteData(status = ExerciseRouteStatus.NO_DATA)
    else -> ExerciseRouteData(status = ExerciseRouteStatus.NO_DATA)
}

internal fun SleepSessionRecord.toSleepData(): SleepData {
    val mappedStages = resolveSleepStages(
        stages = stages.map { stage ->
            SleepStage(
                startTime = stage.startTime,
                endTime = stage.endTime,
                stageType = stage.stage,
            )
        },
        sessionStart = startTime,
        sessionEnd = endTime,
    )
    val spanDurationMs = endTime.toEpochMilli() - startTime.toEpochMilli()

    return SleepData(
        id = metadata.id,
        title = title,
        startTime = startTime,
        endTime = endTime,
        durationMs = sleepDurationMsFromStages(mappedStages, spanDurationMs),
        source = metadata.dataOrigin.packageName,
        notes = notes,
        startZoneOffset = startZoneOffset,
        endZoneOffset = endZoneOffset,
        lastModifiedTime = metadata.lastModifiedTime,
        clientRecordId = metadata.clientRecordId,
        clientRecordVersion = metadata.clientRecordVersion,
        recordingMethod = metadata.recordingMethod,
        device = metadata.device?.let { device ->
            SleepDeviceData(
                type = device.type,
                manufacturer = device.manufacturer,
                model = device.model,
            )
        },
        stages = mappedStages,
    )
}

internal fun MindfulnessSessionRecord.toMindfulnessSession(appPackageName: String? = null) = MindfulnessSession(
    id = metadata.id,
    title = title,
    // Flutter-era builds stored the literal "null" for missing notes.
    notes = notes?.takeUnless { it.isBlank() || it == "null" },
    startTime = startTime,
    endTime = endTime,
    durationMs = endTime.toEpochMilli() - startTime.toEpochMilli(),
    source = SyncedSourceOverlay.displaySource(metadata),
    isOwnEntry = appPackageName?.let { isOpenVitalsRecord(metadata.dataOrigin.packageName, it) } ?: false,
)
