package tech.mmarca.openvitals.sharing.source

import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test
import tech.mmarca.healthcalculations.model.BasalBodyTemperatureEntry
import tech.mmarca.healthcalculations.model.CervicalMucusEntry
import tech.mmarca.healthcalculations.model.CycleData
import tech.mmarca.healthcalculations.model.MenstruationFlowEntry

class HealthConnectDailyReadsTest {
    @Test
    fun `a night's asleep time leaves out the awake stages`() {
        val hour = 3_600_000L
        val rows = sleepSummary(
            durationMs = 8 * hour,
            stages = listOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT to 4 * hour,
                SleepSessionRecord.STAGE_TYPE_DEEP to 2 * hour,
                SleepSessionRecord.STAGE_TYPE_AWAKE to hour,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to hour,
            ),
        ).associate { it.metric to it.value }

        assertThat(rows["time_in_bed"]).isEqualTo(7 * 60.0)
        assertThat(rows["sleep_duration"]).isEqualTo(6 * 60.0)
        assertThat(rows["awake"]).isEqualTo(60.0)
        assertThat(rows["rem_sleep"]).isEqualTo(0.0)
    }

    @Test
    fun `a night without stages reports only its length`() {
        val rows = sleepSummary(durationMs = 3_600_000L, stages = emptyList())

        assertThat(rows.map { it.metric }).containsExactly("time_in_bed", "sleep_duration")
    }

    @Test
    fun `a cycle day keeps the heaviest flow, the last mucus reading and the mean temperature`() {
        val morning = Instant.parse("2026-10-01T07:00:00Z")
        val evening = Instant.parse("2026-10-01T20:00:00Z")
        val data = CycleData(
            menstruationFlows = listOf(
                MenstruationFlowEntry(morning, MenstruationFlowRecord.FLOW_HEAVY, "app"),
                MenstruationFlowEntry(evening, MenstruationFlowRecord.FLOW_LIGHT, "app"),
            ),
            cervicalMucus = listOf(
                CervicalMucusEntry(morning, CervicalMucusRecord.APPEARANCE_DRY, CervicalMucusRecord.SENSATION_LIGHT, "app"),
                CervicalMucusEntry(evening, CervicalMucusRecord.APPEARANCE_EGG_WHITE, CervicalMucusRecord.SENSATION_HEAVY, "app"),
            ),
            basalBodyTemperature = listOf(
                BasalBodyTemperatureEntry(morning, 36.4, 0, "app"),
                BasalBodyTemperatureEntry(evening, 36.6, 0, "app"),
            ),
        )

        val rows = cycleDailyRows(data, ZoneOffset.UTC).associateBy { it.metric }

        assertThat(rows.getValue("menstruation_flow").text).isEqualTo("heavy")
        assertThat(rows.getValue("cervical_mucus_appearance").text).isEqualTo("egg_white")
        assertThat(rows.getValue("cervical_mucus_sensation").text).isEqualTo("heavy")
        assertThat(rows.getValue("basal_body_temperature").value!!).isWithin(1e-9).of(36.5)
    }
}
