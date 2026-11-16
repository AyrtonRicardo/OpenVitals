package tech.mmarca.openvitals.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardDataTest {

    @Test fun `DashboardData optional readings default to null`() {
        val data = DashboardData(date = LocalDate.of(2026, 1, 1))

        assertEquals(
            listOf(null, null, null, null, null, null),
            listOf(
                data.weightKg,
                data.weightTime,
                data.heightCm,
                data.heightTime,
                data.floorsClimbed,
                data.elevationGainedMeters,
            ),
        )
    }
}
