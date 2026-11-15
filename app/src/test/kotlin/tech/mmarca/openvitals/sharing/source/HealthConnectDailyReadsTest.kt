package tech.mmarca.openvitals.sharing.source

import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test
import tech.mmarca.healthcalculations.model.BasalBodyTemperatureEntry
import tech.mmarca.healthcalculations.model.CervicalMucusEntry
import tech.mmarca.healthcalculations.model.CycleData
import tech.mmarca.healthcalculations.model.MenstruationFlowEntry
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingMetrics
import tech.mmarca.openvitals.sharing.contract.DataSharingTable

class HealthConnectDailyReadsTest {
    private val reads = HealthConnectDailyReads(
        activityRepository = mockk(),
        sleepRepository = mockk(),
        heartRepository = mockk(),
        vitalsRepository = mockk(),
        bodyRepository = mockk(),
        nutritionRepository = mockk(),
        hydrationRepository = mockk(),
        mindfulnessRepository = mockk(),
        cycleRepository = mockk(),
        sleepWindowPreferences = mockk(),
    )

    @Test
    fun `every daily metric is read from Health Connect or worked out by OpenVitals`() {
        val day = LocalDate.of(2026, 10, 1)
        val missing = DataSharingGroup.entries.flatMap { group ->
            val served = reads.reads(group, day, day).flatMap { it.metrics }.toSet()
            DataSharingMetrics.forGroup(group)
                .filter { DataSharingTable.DAILY in it.tables }
                .map { it.key }
                .filter { it !in served && it !in OpenVitalsDailyMetrics }
                .map { "${group.path}/$it" }
        }

        assertThat(missing).isEmpty()
    }

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

    private companion object {
        /** Worked out or stored by OpenVitals; their sources are not Health Connect. */
        val OpenVitalsDailyMetrics = setOf(
            "sleep_score", "sleep_pressure", "sleep_efficiency", "waso", "sleep_midpoint",
            "sleep_awake_time", "sleep_need", "sleep_need_usual", "sleep_awakenings",
            "max_heart_rate", "hrv_status",
            "bmr_estimate", "bmi", "ffmi",
            "readiness", "stress", "readiness_state", "stress_level", "body_energy_start", "body_energy_end",
            "body_energy_charged", "body_energy_drained", "cardio_load", "cardio_load_week", "intensity_minutes",
            "intensity_minutes_week", "training_readiness",
            "training_load_acute", "training_load_chronic", "recovery_time", "moderate_minutes", "vigorous_minutes",
            "cycle_day", "cycle_phase", "next_period_start", "next_period_end", "thermal_shift", "cycle_length_avg",
            "period_length_avg", "mood", "energy", "pain", "symptoms", "journal_cervical_sensation", "hcg_test",
            "bbt_disturbances", "pill_taken",
        )
    }
}
