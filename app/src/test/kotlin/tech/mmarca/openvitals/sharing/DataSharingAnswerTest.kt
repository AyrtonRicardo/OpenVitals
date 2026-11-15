package tech.mmarca.openvitals.sharing

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.mmarca.openvitals.sharing.contract.DataSharingColumns
import tech.mmarca.openvitals.sharing.contract.DataSharingContract
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingMetrics
import tech.mmarca.openvitals.sharing.contract.DataSharingRequest
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

class DataSharingAnswerTest {

    @Test
    fun `groups list every group with the caller's grant`() {
        val answer = DataSharingAnswers.groups(
            permissionFor = { "app.permission.READ_${it.name}" },
            isGranted = { it == DataSharingGroup.SLEEP },
            status = DataSharingStatus.BACKGROUND_READ_MISSING,
        )

        assertThat(answer.columns).isEqualTo(DataSharingColumns.Groups.ALL)
        assertThat(answer.rows).hasSize(DataSharingGroup.entries.size)
        val sleep = answer.rows.single { it[0] == "sleep" }
        assertThat(sleep.toList()).containsExactly(
            "sleep",
            "app.permission.READ_SLEEP",
            1,
            "background_read_missing",
            DataSharingContract.API_VERSION,
        ).inOrder()
        assertThat(answer.rows.single { it[0] == "heart" }[2]).isEqualTo(0)
    }

    @Test
    fun `metrics list one row per metric and table`() {
        val answer = DataSharingAnswers.metrics()

        assertThat(answer.rows).hasSize(DataSharingMetrics.all.sumOf { it.tables.size })
        assertThat(answer.rows.map { it.toList() }).contains(listOf("sleep", "sleep_score", "score", "daily"))
    }

    @Test
    fun `a projection keeps the asked columns in the asked order`() {
        val answer = DataSharingAnswer(
            columns = arrayOf("a", "b", "c"),
            rows = listOf(arrayOf(1, 2, 3)),
            status = DataSharingStatus.PARTIAL,
        )

        val projected = answer.project(arrayOf("c", "a"))

        assertThat(projected.columns.toList()).containsExactly("c", "a").inOrder()
        assertThat(projected.rows.single().toList()).containsExactly(3, 1).inOrder()
        assertThat(projected.status).isEqualTo(DataSharingStatus.PARTIAL)
        assertThat(answer.project(null)).isSameInstanceAs(answer)
    }

    @Test
    fun `an unknown projection column fails`() {
        val answer = DataSharingAnswer(columns = arrayOf("a"), rows = emptyList())

        assertThrows(IllegalArgumentException::class.java) { answer.project(arrayOf("password")) }
    }

    @Test
    fun `an empty answer keeps the table's columns`() {
        val answer = DataSharingAnswers.empty(DataSharingRequest.Route("x"), DataSharingStatus.SHARING_OFF)

        assertThat(answer.columns).isEqualTo(DataSharingColumns.Route.ALL)
        assertThat(answer.rows).isEmpty()
        assertThat(answer.status).isEqualTo(DataSharingStatus.SHARING_OFF)
    }
}
