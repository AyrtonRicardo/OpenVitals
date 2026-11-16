package tech.mmarca.openvitals.features.activity

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import tech.mmarca.healthcalculations.model.ExerciseRouteStatus
import tech.mmarca.healthcalculations.model.withSampleBackfilledMetrics
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.data.repository.contract.ActivityRepository
import tech.mmarca.openvitals.data.repository.contract.HeartRepository

/**
 * A recorded workout as the FIT file "Save as FIT" writes, without a screen: for the data
 * sharing API, so another app gets the same file the user would save by hand.
 *
 * The reads are not softened here. Under `withStrictHealthConnectReads` a failed read throws,
 * and the caller refuses rather than hand out a file with a series missing.
 */
@Singleton
internal class ActivityWorkoutFitFile @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val heartRepository: HeartRepository,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * The file's bytes, or null when no recorded workout has this id. [withRoute] adds the GPS
     * track when the workout has one that may be read; otherwise the file is the routeless one.
     */
    suspend fun bytes(sessionId: String, withRoute: Boolean): ByteArray? {
        val workout = activityRepository.loadWorkout(sessionId) ?: return null
        val heartRate = heartRepository.loadHeartRateSamples(workout.startTime, workout.endTime)
        val speed = activityRepository.loadSpeedSamples(workout.startTime, workout.endTime)
        val cadence = activityRepository.loadActivityCadenceSamples(workout.startTime, workout.endTime)
        return withContext(dispatchers.default) {
            val backfilled = workout.withSampleBackfilledMetrics(
                heartRateSamples = heartRate,
                speedSamples = speed,
                cadenceSamples = cadence,
            )
            val hasRoute = backfilled.route.status == ExerciseRouteStatus.DATA && backfilled.route.points.isNotEmpty()
            ByteArrayOutputStream().also { output ->
                writeActivityWorkoutExport(
                    workout = backfilled,
                    series = ActivityWorkoutSeries(heartRate, speed, cadence),
                    format = if (withRoute && hasRoute) ActivityWorkoutExportFormat.FIT_WITH_ROUTE else ActivityWorkoutExportFormat.FIT,
                    output = output,
                )
            }.toByteArray()
        }
    }
}
