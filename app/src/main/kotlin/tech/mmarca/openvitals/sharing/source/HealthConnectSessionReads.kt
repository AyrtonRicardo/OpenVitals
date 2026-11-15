package tech.mmarca.openvitals.sharing.source

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.reflect.KClass
import tech.mmarca.openvitals.data.repository.contract.ActivityRepository
import tech.mmarca.openvitals.data.repository.contract.MindfulnessRepository
import tech.mmarca.openvitals.data.repository.contract.SleepRepository
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.ExerciseRouteStatus
import tech.mmarca.openvitals.domain.model.ExerciseTypeNames
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup

/** Workouts, nights and mindfulness sessions, one at a time or by start day. */
internal class HealthConnectSessionReads @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val sleepRepository: SleepRepository,
    private val mindfulnessRepository: MindfulnessRepository,
) {
    fun sessions(group: DataSharingGroup, start: LocalDate, end: LocalDate): List<HealthConnectRead<SessionRow>> {
        val zone = ZoneId.systemDefault()
        val startsInRange = { session: SessionRow ->
            val day = session.start.atZone(zone).toLocalDate()
            !day.isBefore(start) && !day.isAfter(end)
        }
        return when (group) {
            DataSharingGroup.WORKOUTS -> listOf(
                read(ExerciseSessionRecord::class) {
                    activityRepository.loadWorkouts(start, end).map { workout ->
                        SessionRow(
                            id = workout.id,
                            start = workout.startTime,
                            end = workout.endTime,
                            type = ExerciseTypeNames.exportName(workout.exerciseType),
                            title = workout.title,
                        )
                    }.filter(startsInRange)
                },
                read(PlannedExerciseSessionRecord::class) {
                    activityRepository.loadPlannedWorkouts(start, end).map { plan ->
                        SessionRow(
                            id = plan.id,
                            start = plan.startTime,
                            end = plan.endTime,
                            type = ExerciseTypeNames.exportName(plan.exerciseType),
                            planned = true,
                            title = plan.title,
                        )
                    }.filter(startsInRange)
                },
            )
            DataSharingGroup.SLEEP -> listOf(
                read(SleepSessionRecord::class) {
                    sleepRepository.loadSleepSessions(start, end)
                        .map { SessionRow(it.id, it.startTime, it.endTime, type = "sleep", title = it.title) }
                        .filter(startsInRange)
                },
            )
            DataSharingGroup.RECOVERY -> listOf(
                read(MindfulnessSessionRecord::class) {
                    mindfulnessRepository.loadMindfulnessSessions(start, end)
                        .map { SessionRow(it.id, it.startTime, it.endTime, type = "mindfulness", title = it.title) }
                        .filter(startsInRange)
                },
            )
            else -> emptyList()
        }
    }

    fun summary(group: DataSharingGroup, id: String): List<HealthConnectRead<SummaryRow>> = when (group) {
        DataSharingGroup.WORKOUTS -> listOf(
            read(ExerciseSessionRecord::class) {
                activityRepository.loadWorkout(id)?.let(::workoutSummary)
                    ?: activityRepository.loadPlannedWorkout(id)?.let { plan ->
                        listOf(SummaryRow("duration", plan.durationMs / MillisPerMinute))
                    }
                    ?: emptyList()
            },
        )
        DataSharingGroup.SLEEP -> listOf(
            read(SleepSessionRecord::class) {
                sleepRepository.loadSleepSession(id)
                    ?.let { night -> sleepSummary(night.durationMs, night.stages.map { it.stageType to it.durationMs }) }
                    .orEmpty()
            },
        )
        DataSharingGroup.RECOVERY -> listOf(
            read(MindfulnessSessionRecord::class) {
                mindfulnessRepository.loadMindfulnessSession(id)
                    ?.let { listOf(SummaryRow("mindfulness_minutes", it.durationMs / MillisPerMinute)) }
                    .orEmpty()
            },
        )
        else -> emptyList()
    }

    /** Health Connect asks the user before it shows a route to an app; a refusal throws, so the answer says partial. */
    fun route(id: String): HealthConnectRead<RouteRow> = HealthConnectRead(
        metrics = emptySet(),
        requires = setOf(readPermission(ExerciseSessionRecord::class), HealthPermission.PERMISSION_READ_EXERCISE_ROUTES),
    ) {
        val route = activityRepository.loadWorkout(id)?.route ?: return@HealthConnectRead emptyList()
        check(route.status != ExerciseRouteStatus.CONSENT_REQUIRED) { "The route needs the user's consent." }
        route.points.map { RouteRow(it.time, it.latitude, it.longitude, it.altitudeMeters) }
    }

    private fun <T> read(type: KClass<out Record>, block: suspend () -> List<T>) =
        HealthConnectRead(emptySet(), setOf(readPermission(type)), block)

    private companion object {
        const val MillisPerMinute = 60_000.0
    }
}

/** What Health Connect recorded for one workout. The app's own figures (splits, recovery) come later. */
internal fun workoutSummary(workout: ExerciseData): List<SummaryRow> = listOfNotNull(
    SummaryRow("duration", workout.durationMs / 60_000.0),
    workout.totalDistanceMeters?.let { SummaryRow("distance", it) },
    workout.activeCaloriesKcal?.let { SummaryRow("active_calories", it) },
    workout.totalCaloriesKcal?.let { SummaryRow("total_calories", it) },
    workout.steps?.let { SummaryRow("steps", it.toDouble()) },
    workout.averageSpeedMetersPerSecond?.let { SummaryRow("speed_avg", it) },
    workout.averageHeartRateBpm?.let { SummaryRow("heart_rate_avg", it.toDouble()) },
    workout.elevationGainedMeters?.let { SummaryRow("elevation_gain", it) },
)
