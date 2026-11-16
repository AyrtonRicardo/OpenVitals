package tech.mmarca.openvitals.sharing

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus
import tech.mmarca.openvitals.sharing.source.SourceResult

class MergeSourceResultsTest {

    @Test
    fun `a closed gate explains the answer and app rows still come through`() {
        val merged = merge(DataSharingStatus.BACKGROUND_READ_MISSING, null, SourceResult(listOf("journal")))

        assertThat(merged.rows).containsExactly("journal")
        assertThat(merged.status).isEqualTo(DataSharingStatus.BACKGROUND_READ_MISSING)
    }

    @Test
    fun `an open gate takes the worse read status and both parts' rows`() {
        val merged = merge(
            DataSharingStatus.OK,
            SourceResult(listOf("steps"), DataSharingStatus.PARTIAL, historyStart = LocalDate.of(2026, 9, 3)),
            SourceResult(listOf("score"), DataSharingStatus.OK),
        )

        assertThat(merged.rows).containsExactly("steps", "score").inOrder()
        assertThat(merged.status).isEqualTo(DataSharingStatus.PARTIAL)
        assertThat(merged.historyStart).isEqualTo(LocalDate.of(2026, 9, 3))
    }
}
