package tech.mmarca.openvitals.features.bodyfolder

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.data.repository.contract.BodyFolderMissingException
import tech.mmarca.openvitals.data.repository.contract.BodyRepository
import tech.mmarca.openvitals.data.repository.contract.FakeBodyCircumferenceRepository
import tech.mmarca.openvitals.data.repository.contract.FakeBodyFolderRepository
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.model.WeightEntry

class BodyMeasurementsExporterTest {

    private val today = LocalDate.parse("2026-11-21")
    private val now = Instant.parse("2026-11-21T18:00:00Z")
    private val historyStart = today.minusYears(30)

    private fun bodyRepository(weight: List<WeightEntry> = emptyList()): BodyRepository = mockk<BodyRepository>().also { repo ->
        coEvery { repo.loadWeightEntries(any(), any()) } returns weight
        coEvery { repo.loadHeightEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadBodyFatEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadLeanBodyMassEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadBoneMassEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadBodyWaterMassEntries(any(), any()) } returns emptyList()
    }

    @Test fun `writes measurements json to the folder root with every source in it`() = runTest {
        val body = bodyRepository(weight = listOf(WeightEntry(Instant.parse("2026-11-20T07:00:00Z"), 80.0, "scale")))
        val tape = FakeBodyCircumferenceRepository(
            listOf(
                CircumferenceEntry(CircumferenceSite.WAIST, Instant.parse("2026-11-20T08:00:00Z"), 86.5),
                CircumferenceEntry(CircumferenceSite.RIGHT_THIGH, Instant.parse("2026-11-20T08:01:00Z"), 58.0),
            ),
        )
        val folder = FakeBodyFolderRepository(initialUri = "content://tree/body")

        val count = BodyMeasurementsExporter(body, tape, folder, { today }, { now }).export()

        assertEquals(3, count)
        val document = Json.parseToJsonElement(folder.text("measurements.json")!!).jsonObject
        assertEquals("2026-11-21T18:00:00Z", document.getValue("exportedAt").jsonPrimitive.content)
        assertEquals(1, document.getValue("weight").jsonArray.size)
        assertEquals(
            listOf("waist", "right_thigh"),
            document.getValue("circumferences").jsonArray.map { it.jsonObject.getValue("site").jsonPrimitive.content },
        )
    }

    @Test fun `reads thirty years back to today, for every body type and every tape site`() = runTest {
        val body = bodyRepository()
        val tape = FakeBodyCircumferenceRepository()
        val folder = FakeBodyFolderRepository(initialUri = "content://tree/body")

        BodyMeasurementsExporter(body, tape, folder, { today }, { now }).export()

        coVerify { body.loadWeightEntries(historyStart, today) }
        coVerify { body.loadHeightEntries(historyStart, today) }
        coVerify { body.loadBodyFatEntries(historyStart, today) }
        coVerify { body.loadLeanBodyMassEntries(historyStart, today) }
        coVerify { body.loadBoneMassEntries(historyStart, today) }
        coVerify { body.loadBodyWaterMassEntries(historyStart, today) }
        assertEquals(CircumferenceSite.entries.toSet(), tape.reads.map { it.first }.toSet())
        assertEquals(setOf(historyStart), tape.reads.map { it.second.start }.toSet())
    }

    @Test fun `without a folder the export fails and writes nothing`() = runTest {
        val folder = FakeBodyFolderRepository(initialUri = null)
        val exporter = BodyMeasurementsExporter(bodyRepository(), FakeBodyCircumferenceRepository(), folder, { today }, { now })

        val error = runCatching { exporter.export() }.exceptionOrNull()

        assertTrue(error is BodyFolderMissingException)
        assertEquals(emptyMap<String, ByteArray>(), folder.files)
    }
}
