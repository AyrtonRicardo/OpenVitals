package tech.mmarca.openvitals.sharing.source

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingMetrics
import tech.mmarca.openvitals.sharing.contract.DataSharingTable

/** Every metric the catalog offers has a read behind it, so `/metrics` never promises what no query returns. */
class CatalogCoverageTest {
    private val day = LocalDate.of(2026, 10, 1)
    private val healthConnect = HealthConnectDailyReads(
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
    )
    private val openVitals = OpenVitalsReads(
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
        mockk(), mockk(),
    )

    @Test
    fun `every daily metric has a read`() {
        val missing = DataSharingGroup.entries.flatMap { group ->
            val served = (healthConnect.reads(group, day, day) + openVitals.daily(group, day, day, emptySet()))
                .flatMap { it.metrics }
                .toSet()
            offered(group, DataSharingTable.DAILY).filter { it !in served }.map { "${group.path}/$it" }
        }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `every sample metric has a read`() {
        val start = Instant.parse("2026-10-01T00:00:00Z")
        val missing = DataSharingGroup.entries.flatMap { group ->
            val served = sampleTypes(group).flatMap { it.metrics } +
                openVitals.samples(group, start, start.plusSeconds(3_600)).flatMap { it.metrics }
            offered(group, DataSharingTable.SAMPLES).filter { it !in served }.map { "${group.path}/$it" }
        }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `every workout summary metric has a read`() {
        val served = WorkoutHealthConnectKeys + openVitals.workoutSummary("w", emptySet()).metrics

        assertThat(served).containsAtLeastElementsIn(offered(DataSharingGroup.WORKOUTS, DataSharingTable.SESSIONS))
    }

    private fun offered(group: DataSharingGroup, table: DataSharingTable): List<String> =
        DataSharingMetrics.forGroup(group).filter { table in it.tables }.map { it.key }

    private companion object {
        /** What [workoutSummary] in HealthConnectSessionReads.kt can return. */
        val WorkoutHealthConnectKeys = setOf(
            "duration", "distance", "active_calories", "total_calories", "steps", "speed_avg", "heart_rate_avg",
            "elevation_gain",
        )
    }
}
