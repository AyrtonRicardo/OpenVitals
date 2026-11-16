package tech.mmarca.openvitals.features.activity

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.ExerciseRouteData
import tech.mmarca.healthcalculations.model.ExerciseRoutePoint
import tech.mmarca.healthcalculations.model.ExerciseRouteStatus
import tech.mmarca.healthcalculations.model.HeartRateSample
import tech.mmarca.healthcalculations.model.SpeedSample
import tech.mmarca.healthcalculations.model.withSampleBackfilledMetrics
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.data.repository.contract.ActivityRepository
import tech.mmarca.openvitals.data.repository.contract.HeartRepository

/** The file another app is given is the one "Save as FIT" writes. */
class ActivityWorkoutFitFileTest {
    private val start = Instant.parse("2026-05-26T08:30:00Z")
    private val end = Instant.parse("2026-05-26T09:30:00Z")
    private val heartRate = listOf(
        HeartRateSample(time = start.plusSeconds(600), beatsPerMinute = 140, source = "test"),
        HeartRateSample(time = start.plusSeconds(1_200), beatsPerMinute = 172, source = "test"),
    )
    private val speed = listOf(SpeedSample(time = start.plusSeconds(600), metersPerSecond = 3.1, source = "test"))
    private val route = ExerciseRouteData(
        status = ExerciseRouteStatus.DATA,
        points = listOf(point(start.plusSeconds(60), 41.38), point(start.plusSeconds(120), 41.39)),
    )

    private val activityRepository = mockk<ActivityRepository>()
    private val heartRepository = mockk<HeartRepository>()
    private val file = ActivityWorkoutFitFile(activityRepository, heartRepository, DefaultDispatcherProvider)

    @Test
    fun `the bytes are the in-app FIT export's`() = runTest {
        serve(workout())

        assertThat(file.bytes("activity-1", withRoute = false)).isEqualTo(export(workout(), ActivityWorkoutExportFormat.FIT))
    }

    @Test
    fun `the route is in the file only when asked for`() = runTest {
        serve(workout(route))

        val without = file.bytes("activity-1", withRoute = false)
        val with = file.bytes("activity-1", withRoute = true)

        assertThat(without).isEqualTo(export(workout(route), ActivityWorkoutExportFormat.FIT))
        assertThat(with).isEqualTo(export(workout(route), ActivityWorkoutExportFormat.FIT_WITH_ROUTE))
        assertThat(with).isNotEqualTo(without)
    }

    @Test
    fun `a workout without a readable route gives the routeless file`() = runTest {
        val consent = workout(ExerciseRouteData(status = ExerciseRouteStatus.CONSENT_REQUIRED))
        serve(consent)

        assertThat(file.bytes("activity-1", withRoute = true)).isEqualTo(export(consent, ActivityWorkoutExportFormat.FIT))
    }

    @Test
    fun `an id no workout has gives no file`() = runTest {
        coEvery { activityRepository.loadWorkout("gone") } returns null

        assertThat(file.bytes("gone", withRoute = true)).isNull()
    }

    @Test
    fun `a failed series read fails the file instead of thinning it`() {
        serve(workout())
        coEvery { activityRepository.loadSpeedSamples(any(), any()) } throws IllegalStateException("read failed")

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { file.bytes("activity-1", withRoute = false) }
        }
    }

    private fun serve(workout: ExerciseData) {
        coEvery { activityRepository.loadWorkout("activity-1") } returns workout
        coEvery { heartRepository.loadHeartRateSamples(start, end) } returns heartRate
        coEvery { activityRepository.loadSpeedSamples(start, end) } returns speed
        coEvery { activityRepository.loadActivityCadenceSamples(start, end) } returns emptyList()
    }

    private fun export(workout: ExerciseData, format: ActivityWorkoutExportFormat): ByteArray =
        ByteArrayOutputStream().also { output ->
            writeActivityWorkoutExport(
                workout = workout.withSampleBackfilledMetrics(
                    heartRateSamples = heartRate,
                    speedSamples = speed,
                    cadenceSamples = emptyList(),
                ),
                series = ActivityWorkoutSeries(heartRate, speed, emptyList()),
                format = format,
                output = output,
            )
        }.toByteArray()

    private fun workout(route: ExerciseRouteData = ExerciseRouteData()) = ExerciseData(
        id = "activity-1",
        title = "Morning run",
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        startTime = start,
        endTime = end,
        durationMs = 3_600_000,
        source = "test",
        totalDistanceMeters = 10250.5,
        route = route,
    )

    private fun point(time: Instant, latitude: Double) = ExerciseRoutePoint(
        time = time,
        latitude = latitude,
        longitude = 2.1734,
        altitudeMeters = 12.0,
        horizontalAccuracyMeters = null,
        verticalAccuracyMeters = null,
    )
}
