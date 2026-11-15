package tech.mmarca.openvitals.sharing.source

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test
import tech.mmarca.openvitals.healthconnect.HealthConnectRateLimitException
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

class RunHealthConnectReadsTest {
    private val day = LocalDate.of(2026, 10, 1)
    private var ran = mutableListOf<String>()

    @Test
    fun `only reads that serve a wanted metric run`() = runTest {
        val result = run(
            reads = listOf(read("a", "steps"), read("b", "weight")),
            wanted = setOf("weight"),
        )

        assertThat(ran).containsExactly("b")
        assertThat(result.rows.map { it.metric }).containsExactly("weight")
        assertThat(result.status).isEqualTo(DataSharingStatus.OK)
    }

    @Test
    fun `an empty wanted set reads everything`() = runTest {
        val result = run(reads = listOf(read("a", "steps"), read("b", "weight")), wanted = emptySet())

        assertThat(ran).containsExactly("a", "b")
        assertThat(result.rows).hasSize(2)
    }

    @Test
    fun `a missing permission skips the read and makes the answer partial`() = runTest {
        val result = run(
            reads = listOf(read("a", "steps", requires = "perm.steps"), read("b", "weight")),
            wanted = emptySet(),
            granted = emptySet(),
        )

        assertThat(ran).containsExactly("b")
        assertThat(result.status).isEqualTo(DataSharingStatus.PARTIAL)
    }

    @Test
    fun `a metric whose own permission is missing is dropped from a shared read`() = runTest {
        val shared = HealthConnectRead(setOf("steps", "floors"), emptySet()) {
            listOf(DailyRow(day, "steps", 1.0), DailyRow(day, "floors", 2.0))
        }

        val result = runHealthConnectReads(
            reads = listOf(shared),
            wanted = emptySet(),
            granted = setOf("perm.steps"),
            permissionFor = { "perm.$it" },
            metricOf = { it.metric },
            onFailure = {},
        )

        assertThat(result.rows.map { it.metric }).containsExactly("steps")
        assertThat(result.status).isEqualTo(DataSharingStatus.PARTIAL)
    }

    @Test
    fun `a failed read keeps the others and makes the answer partial`() = runTest {
        val result = run(reads = listOf(failing(IllegalStateException("boom")), read("b", "weight")), wanted = emptySet())

        assertThat(result.rows.map { it.metric }).containsExactly("weight")
        assertThat(result.status).isEqualTo(DataSharingStatus.PARTIAL)
    }

    @Test
    fun `a rate-limited read outranks partial`() = runTest {
        val result = run(
            reads = listOf(
                failing(IllegalStateException("boom")),
                failing(HealthConnectRateLimitException(retryAfterMillis = 1_000, message = "slow down")),
            ),
            wanted = emptySet(),
        )

        assertThat(result.status).isEqualTo(DataSharingStatus.RATE_LIMITED)
    }

    @Test
    fun `reads without metrics always run`() = runTest {
        val sessions = HealthConnectRead<String>(emptySet(), emptySet()) { listOf("session") }

        val result = runHealthConnectReads(listOf(sessions), wanted = setOf("anything"), granted = emptySet(), onFailure = {})

        assertThat(result.rows).containsExactly("session")
    }

    private suspend fun run(
        reads: List<HealthConnectRead<DailyRow>>,
        wanted: Set<String>,
        granted: Set<String> = setOf("perm.steps"),
    ) = runHealthConnectReads(reads, wanted, granted, metricOf = { it.metric }, onFailure = {})

    private fun read(name: String, metric: String, requires: String? = null) =
        HealthConnectRead(setOf(metric), setOfNotNull(requires)) {
            ran += name
            listOf(DailyRow(day, metric, 1.0))
        }

    private fun failing(error: Throwable) =
        HealthConnectRead<DailyRow>(setOf("broken"), emptySet()) { throw error }
}
