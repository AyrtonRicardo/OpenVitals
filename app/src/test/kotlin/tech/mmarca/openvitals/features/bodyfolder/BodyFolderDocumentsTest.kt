package tech.mmarca.openvitals.features.bodyfolder

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.BodyFatEntry
import tech.mmarca.openvitals.domain.model.BodyWaterMassEntry
import tech.mmarca.openvitals.domain.model.BoneMassEntry
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.model.HeightEntry
import tech.mmarca.openvitals.domain.model.LeanBodyMassEntry
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.domain.model.WeightEntry

/**
 * The files are a contract with the web dashboard: its tests read the same fixtures, so a change
 * to the shape fails here and there together.
 */
class BodyFolderDocumentsTest {

    private val exportedAt = Instant.parse("2026-11-21T18:00:00Z")

    private fun at(text: String) = Instant.parse(text)

    private fun fixture(name: String) = Json.parseToJsonElement(File("$FixtureDirectory/$name").readText())

    @Test fun `measurements match the dashboard fixture, sorted by time and rounded to two decimals`() {
        val snapshot = BodyMeasurementsSnapshot(
            weight = listOf(
                WeightEntry(at("2026-11-20T07:00:00Z"), 80.123, "scale"),
                WeightEntry(at("2026-11-01T07:00:00Z"), 82.4, "scale"),
                WeightEntry(at("2026-11-15T07:00:00Z"), 81.55, "scale"),
            ),
            height = listOf(HeightEntry(at("2026-01-10T09:00:00Z"), 180.0, "phone")),
            bodyFat = listOf(
                BodyFatEntry(at("2026-11-20T07:00:00Z"), 21.8, "scale"),
                BodyFatEntry(at("2026-11-01T07:00:00Z"), 22.5, "scale"),
            ),
            leanMass = listOf(LeanBodyMassEntry(at("2026-11-20T07:00:00Z"), 62.7, "scale")),
            boneMass = listOf(BoneMassEntry(at("2026-11-20T07:00:00Z"), 3.2, "scale")),
            bodyWater = listOf(BodyWaterMassEntry(at("2026-11-20T07:00:00Z"), 45.1, "scale")),
            circumferences = listOf(
                CircumferenceEntry(CircumferenceSite.LEFT_FOREARM, at("2026-11-20T08:05:00Z"), 34.0),
                CircumferenceEntry(CircumferenceSite.WAIST, at("2026-11-20T08:00:00Z"), 86.5),
                CircumferenceEntry(CircumferenceSite.HIPS, at("2026-11-20T08:02:00Z"), 100.0),
                CircumferenceEntry(CircumferenceSite.WAIST, at("2026-11-01T08:00:00Z"), 88.0),
            ),
        )

        val document = Json.parseToJsonElement(BodyFolderDocuments.measurements(snapshot, exportedAt))

        assertEquals(fixture("measurements.json"), document)
        assertEquals(13, snapshot.entryCount)
    }

    @Test fun `an empty snapshot still writes every series, empty`() {
        val document = BodyFolderDocuments.measurements(BodyMeasurementsSnapshot(), exportedAt)

        val keys = Json.parseToJsonElement(document).toString()
        listOf("weight", "height", "bodyFat", "leanMass", "boneMass", "bodyWater", "circumferences").forEach { key ->
            assertTrue("$key is missing or not empty in $keys", "\"$key\":[]" in keys)
        }
    }

    @Test fun `the photo index lists saved photos only, dated on the phone's local day`() {
        val photos = listOf(
            ProgressPhoto("b", at("2026-11-20T23:30:00Z"), "2026-11-21_003000_bbbb2222.jpg", saved = true),
            ProgressPhoto("c", at("2026-11-21T10:00:00Z"), "2026-11-21_110000_cccc3333.jpg", saved = false),
            ProgressPhoto("a", at("2026-11-01T06:30:00Z"), "2026-11-01_073000_aaaa1111.jpg", saved = true),
        )

        val document = BodyFolderDocuments.photos(photos, ZoneId.of("Europe/Berlin"), exportedAt)

        assertEquals(fixture("photos.json"), Json.parseToJsonElement(document))
    }

    private companion object {
        const val FixtureDirectory = "../tools/body-dashboard/test/fixtures"
    }
}
