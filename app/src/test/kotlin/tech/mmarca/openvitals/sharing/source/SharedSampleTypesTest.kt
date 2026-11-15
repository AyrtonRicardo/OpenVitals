package tech.mmarca.openvitals.sharing.source

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test
import tech.mmarca.openvitals.healthconnect.SharedSample
import tech.mmarca.openvitals.healthconnect.SharedSampleTypes
import tech.mmarca.openvitals.healthconnect.flowName
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingMetrics
import tech.mmarca.openvitals.sharing.contract.DataSharingTable

class SharedSampleTypesTest {
    private val t0 = Instant.parse("2026-10-01T06:00:00Z")

    @Test
    fun `every spec metric is a sample metric of its group`() {
        SharedSampleTypes.all.forEach { spec ->
            val offered = DataSharingMetrics.forGroup(DataSharingGroup.valueOf(spec.family.name)).filter { DataSharingTable.SAMPLES in it.tables }.map { it.key }
            assertThat(offered).containsAtLeastElementsIn(spec.metrics)
        }
    }

    @Test
    fun `every sample metric is served by Health Connect or by OpenVitals`() {
        val fromHealthConnect = SharedSampleTypes.all.flatMap { spec -> spec.metrics.map { DataSharingGroup.valueOf(spec.family.name) to it } }.toSet()
        val missing = DataSharingMetrics.all
            .filter { DataSharingTable.SAMPLES in it.tables }
            .map { it.group to it.key }
            .filter { it !in fromHealthConnect && it.second !in OpenVitalsSampleMetrics }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `heart rate becomes one row per sample`() {
        val record = HeartRateRecord(
            startTime = t0,
            startZoneOffset = null,
            endTime = t0.plusSeconds(2),
            endZoneOffset = null,
            samples = listOf(HeartRateRecord.Sample(t0, 60), HeartRateRecord.Sample(t0.plusSeconds(1), 62)),
            metadata = Metadata.manualEntry(),
        )

        val rows = rowsOf(DataSharingGroup.HEART, record)

        assertThat(rows).containsExactly(
            SharedSample(t0, null, "heart_rate", 60.0),
            SharedSample(t0.plusSeconds(1), null, "heart_rate", 62.0),
        ).inOrder()
    }

    @Test
    fun `blood pressure becomes two metrics`() {
        val record = BloodPressureRecord(
            time = t0,
            zoneOffset = null,
            metadata = Metadata.manualEntry(),
            systolic = Pressure.millimetersOfMercury(118.0),
            diastolic = Pressure.millimetersOfMercury(76.0),
        )

        assertThat(rowsOf(DataSharingGroup.VITALS, record).map { it.metric to it.value }).containsExactly(
            "blood_pressure_systolic" to 118.0,
            "blood_pressure_diastolic" to 76.0,
        )
    }

    @Test
    fun `nutrition keeps macros in grams and the rest in milligrams`() {
        val record = NutritionRecord(
            startTime = t0,
            startZoneOffset = null,
            endTime = t0.plusSeconds(600),
            endZoneOffset = null,
            metadata = Metadata.manualEntry(),
            energy = Energy.kilocalories(600.0),
            protein = Mass.grams(30.0),
            sodium = Mass.grams(1.2),
            caffeine = Mass.milligrams(95.0),
        )

        val values = rowsOf(DataSharingGroup.NUTRITION, record).associate { it.metric to it.value!! }

        assertThat(values.keys).containsExactly("energy", "protein", "sodium", "caffeine")
        assertThat(values.getValue("energy")).isWithin(1e-9).of(600.0)
        assertThat(values.getValue("protein")).isWithin(1e-9).of(30.0)
        assertThat(values.getValue("sodium")).isWithin(1e-6).of(1_200.0)
        assertThat(values.getValue("caffeine")).isWithin(1e-6).of(95.0)
    }

    @Test
    fun `sleep stages and codes become text`() {
        val record = SleepSessionRecord(
            startTime = t0,
            startZoneOffset = null,
            endTime = t0.plusSeconds(3_600),
            endZoneOffset = null,
            metadata = Metadata.manualEntry(),
            stages = listOf(
                SleepSessionRecord.Stage(t0, t0.plusSeconds(1_800), SleepSessionRecord.STAGE_TYPE_DEEP),
                SleepSessionRecord.Stage(t0.plusSeconds(1_800), t0.plusSeconds(3_600), SleepSessionRecord.STAGE_TYPE_REM),
            ),
        )

        assertThat(rowsOf(DataSharingGroup.SLEEP, record).map { it.text }).containsExactly("deep", "rem").inOrder()
        assertThat(flowName(MenstruationFlowRecord.FLOW_HEAVY)).isEqualTo("heavy")
        assertThat(flowName(99)).isEqualTo("unknown")
    }

    @Test
    fun `sexual activity is a count plus the protection`() {
        val record = SexualActivityRecord(
            time = t0,
            zoneOffset = null,
            metadata = Metadata.manualEntry(),
            protectionUsed = SexualActivityRecord.PROTECTION_USED_PROTECTED,
        )

        assertThat(rowsOf(DataSharingGroup.SEXUAL_ACTIVITY, record)).containsExactly(
            SharedSample(t0, null, "sexual_activity", 1.0),
            SharedSample(t0, null, "sexual_activity_protection", text = "protected"),
        )
    }

    private fun rowsOf(group: DataSharingGroup, record: Record): List<SharedSample> =
        sampleTypes(group).single { it.recordType.isInstance(record) }.toSamples(record)

    private companion object {
        /** Worked out or stored by OpenVitals; their sources are not Health Connect. */
        val OpenVitalsSampleMetrics = setOf("caffeine_active", "body_energy")
    }
}
