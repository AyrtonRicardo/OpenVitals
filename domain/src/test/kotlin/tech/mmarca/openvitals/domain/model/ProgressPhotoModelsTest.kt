package tech.mmarca.openvitals.domain.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressPhotoModelsTest {

    @Test fun `the file name is the local time, then the start of the id`() {
        val name = progressPhotoFileName(
            id = "1A2B3C4D-5e6f-7a8b",
            takenAt = Instant.parse("2026-11-21T17:30:05Z"),
            zone = ZoneId.of("Europe/Berlin"),
        )

        assertEquals("2026-11-21_183005_1a2b3c4d.jpg", name)
    }

    @Test fun `a short id is used whole, without its punctuation`() {
        assertEquals(
            "2026-01-02_030405_ab1.jpg",
            progressPhotoFileName("a-b-1", Instant.parse("2026-01-02T03:04:05Z"), ZoneId.of("UTC")),
        )
    }
}
