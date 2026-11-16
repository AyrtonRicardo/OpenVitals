package tech.mmarca.openvitals.sharing.source

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.openvitals.sharing.contract.DataSharingColumns
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingOrigin
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

class SharingTablesTest {
    private val t0 = Instant.parse("2026-10-01T06:00:00Z")

    @Test
    fun `daily rows are sorted and carry the catalog's unit`() {
        val answer = SharingTables.daily(
            DataSharingGroup.ACTIVITY,
            SourceResult(
                rows = listOf(
                    DailyRow(LocalDate.of(2026, 10, 2), "steps", 900.0),
                    DailyRow(LocalDate.of(2026, 10, 1), "steps", 1_200.0),
                    DailyRow(LocalDate.of(2026, 10, 1), "distance", 800.0, origin = DataSharingOrigin.OPENVITALS),
                ),
                status = DataSharingStatus.PARTIAL,
                historyStart = LocalDate.of(2026, 9, 2),
            ),
        )

        assertThat(answer.columns).isEqualTo(DataSharingColumns.Daily.ALL)
        assertThat(answer.rows.map { it.toList() }).containsExactly(
            listOf("2026-10-01", "distance", 800.0, null, "m", "openvitals"),
            listOf("2026-10-01", "steps", 1_200.0, null, "count", "health_connect"),
            listOf("2026-10-02", "steps", 900.0, null, "count", "health_connect"),
        ).inOrder()
        assertThat(answer.status).isEqualTo(DataSharingStatus.PARTIAL)
        assertThat(answer.historyStart).isEqualTo(LocalDate.of(2026, 9, 2))
    }

    @Test
    fun `samples use epoch milliseconds`() {
        val answer = SharingTables.samples(
            DataSharingGroup.HEART,
            SourceResult(listOf(SampleRow(t0, null, "heart_rate", 61.0))),
        )

        assertThat(answer.rows.single().toList())
            .containsExactly(t0.toEpochMilli(), null, "heart_rate", 61.0, null, "bpm", "health_connect").inOrder()
    }

    @Test
    fun `a session says whether it was planned`() {
        val answer = SharingTables.sessions(
            SourceResult(
                listOf(
                    SessionRow("b", t0.plusSeconds(60), t0.plusSeconds(120), "running", planned = true),
                    SessionRow("a", t0, t0.plusSeconds(60), "running", title = "Morning run"),
                ),
            ),
        )

        assertThat(answer.rows.map { it[0] to it[4] }).containsExactly("a" to "recorded", "b" to "planned").inOrder()
    }

    @Test
    fun `a metric outside the catalog is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            SharingTables.daily(DataSharingGroup.ACTIVITY, SourceResult(listOf(DailyRow(LocalDate.EPOCH, "made_up", 1.0))))
        }
    }

    @Test
    fun `a workout summary keeps what Health Connect recorded`() {
        val workout = ExerciseData(
            id = "w",
            title = null,
            exerciseType = 0,
            startTime = t0,
            endTime = t0.plusSeconds(1_800),
            durationMs = 1_800_000,
            source = "app",
            totalDistanceMeters = 5_000.0,
            averageHeartRateBpm = 150,
        )

        assertThat(workoutSummary(workout).map { it.metric to it.value }).containsExactly(
            "duration" to 30.0,
            "distance" to 5_000.0,
            "heart_rate_avg" to 150.0,
        )
    }
}
