package tech.mmarca.openvitals.features.imports.applehealth

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openvitals.data.repository.AppleHealthImportRepository

/**
 * A vendor app that re-syncs into Apple Health saves the same reading again, so the export holds
 * copies that differ only in what Apple stamps on each save: creationDate, sourceVersion, device
 * and metadata (#370).
 */
class AppleHealthImportResyncDuplicateTest {

    @Test
    fun `a re-synced blood pressure correlation imports once`() = runTest {
        val export = FakeHealthConnect(
            healthData(
                bloodPressureCorrelation(created = "2018-09-17 10:00:00 +0900", version = "4.1"),
                bloodPressureCorrelation(created = "2020-12-07 10:00:00 +0900", version = "5.2"),
                bloodPressureCorrelation(created = "2021-03-06 10:00:00 +0900", version = "5.3"),
            ),
        )

        val result = export.service.importAppleHealthExport(export.uri)

        assertEquals(1, export.inserted.count { it is BloodPressureRecord })
        assertEquals(2, result.duplicateSkippedRecords)
    }

    @Test
    fun `a re-synced copy in a later batch is found in health connect`() = runTest {
        // 400 heart-rate records between the copies put them in different 300-record batches.
        val export = FakeHealthConnect(
            healthData(
                bloodPressureCorrelation(created = "2018-09-17 10:00:00 +0900", version = "4.1"),
                heartRates(count = 400),
                bloodPressureCorrelation(created = "2021-10-04 10:00:00 +0900", version = "5.4"),
            ),
        )

        export.service.importAppleHealthExport(export.uri)

        assertEquals(1, export.inserted.count { it is BloodPressureRecord })
    }

    @Test
    fun `a reading listed on its own and in its correlation imports once`() = runTest {
        val created = "2018-09-17 10:00:00 +0900"
        val export = FakeHealthConnect(
            healthData(
                sample("HKQuantityTypeIdentifierBloodPressureSystolic", "mmHg", "135", created, "4.1"),
                sample("HKQuantityTypeIdentifierBloodPressureDiastolic", "mmHg", "85", created, "4.1"),
                bloodPressureCorrelation(created = created, version = "4.1"),
            ),
        )

        export.service.importAppleHealthExport(export.uri)

        assertEquals(1, export.inserted.count { it is BloodPressureRecord })
    }

    @Test
    fun `a re-synced weigh-in with new metadata imports once`() = runTest {
        val export = FakeHealthConnect(
            healthData(
                sample("HKQuantityTypeIdentifierBodyMass", "kg", "72.4", "2018-09-17 10:00:00 +0900", "4.1", synced = "a"),
                sample("HKQuantityTypeIdentifierBodyMass", "kg", "72.4", "2020-12-07 10:00:00 +0900", "5.2", synced = "b"),
            ),
        )

        export.service.importAppleHealthExport(export.uri)

        assertEquals(1, export.inserted.count { it is WeightRecord })
    }

    @Test
    fun `two glasses of water logged for the same time both import`() = runTest {
        val export = FakeHealthConnect(
            healthData(
                sample("HKQuantityTypeIdentifierDietaryWater", "mL", "250", "2018-05-06 09:09:20 +0900", "4.1"),
                sample("HKQuantityTypeIdentifierDietaryWater", "mL", "250", "2018-05-06 09:09:40 +0900", "4.1"),
            ),
        )

        export.service.importAppleHealthExport(export.uri)

        assertEquals(2, export.inserted.count { it is HydrationRecord })
    }

    @Test
    fun `re-importing an export an earlier release imported writes nothing`() = runTest {
        // The ids the importer wrote for this fixture before the identity left out Apple's save stamps.
        val earlierIds = resource("synthetic_supported_export_legacy_ids.txt").lines().filter { it.isNotBlank() }
        val export = FakeHealthConnect(resource("synthetic_supported_export.xml"), alreadyImported = earlierIds)

        val result = export.service.importAppleHealthExport(export.uri)

        assertEquals(35, earlierIds.size)
        assertEquals(0, result.importedRecords)
        assertEquals(earlierIds.size, result.duplicateSkippedRecords)
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/apple_health/$name")) { "Missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun healthData(vararg elements: String): String =
        "<HealthData>\n${elements.joinToString("\n")}\n</HealthData>"

    private fun stamp(created: String, version: String, measured: String = Measured): String =
        """sourceName="Health Mate" sourceVersion="$version" device="scale $version" creationDate="$created" """ +
            """startDate="$measured" endDate="$measured""""

    private fun sample(
        type: String,
        unit: String,
        value: String,
        created: String,
        version: String,
        synced: String? = null,
    ): String {
        val metadata = synced?.let { """<MetadataEntry key="Synced" value="$it" />""" }.orEmpty()
        return """<Record type="$type" ${stamp(created, version)} unit="$unit" value="$value">$metadata</Record>"""
    }

    private fun bloodPressureCorrelation(created: String, version: String): String =
        """
        <Correlation type="HKCorrelationTypeIdentifierBloodPressure" ${stamp(created, version)}>
            <MetadataEntry key="Synced" value="$created" />
            ${sample("HKQuantityTypeIdentifierBloodPressureSystolic", "mmHg", "135", created, version)}
            ${sample("HKQuantityTypeIdentifierBloodPressureDiastolic", "mmHg", "85", created, version)}
        </Correlation>
        """.trimIndent()

    private fun heartRates(count: Int): String =
        (0 until count).joinToString("\n") { index ->
            val minute = "%02d".format(index % 60)
            val measured = "2018-06-${"%02d".format(1 + index / 60)} 08:$minute:00 +0900"
            """<Record type="HKQuantityTypeIdentifierHeartRate" sourceName="Watch" """ +
                """startDate="$measured" endDate="$measured" unit="count/min" value="60" />"""
        }

    /** Health Connect as the import sees it: a record written earlier is found by its clientRecordId. */
    private class FakeHealthConnect(xml: String, alreadyImported: List<String> = emptyList()) {
        val uri = mockk<Uri>()
        private val repository = mockk<AppleHealthImportRepository>()
        private val insertedBatches: MutableList<List<Record>> = Collections.synchronizedList(mutableListOf())
        val inserted: List<Record> get() = synchronized(insertedBatches) { insertedBatches.flatten() }
        val service: AppleHealthImportService

        init {
            val resolver = mockk<ContentResolver>()
            val context = mockk<Context>()
            every { context.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } returns ByteArrayInputStream(xml.toByteArray())
            every { repository.isMindfulnessAvailable() } returns true
            coEvery { repository.findMatchingImportedClientRecordIds(any(), any(), any(), any()) } coAnswers {
                val stored = alreadyImported + inserted.mapNotNull { it.metadata.clientRecordId }
                arg<Set<String>>(3).intersect(stored.toSet())
            }
            coEvery { repository.insertImportedRecords(any()) } coAnswers { insertedBatches += firstArg<List<Record>>() }
            service = AppleHealthImportService(context, repository)
        }
    }

    private companion object {
        /** When the cuff took the reading; every copy agrees on it. */
        const val Measured = "2018-05-06 09:09:17 +0900"
    }
}
