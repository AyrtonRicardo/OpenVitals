package tech.mmarca.openvitals.features.circumference

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite

class CircumferencePresentationMapperTest {

    private fun waist(cm: Double, epochSeconds: Long) =
        CircumferenceEntry(CircumferenceSite.WAIST, Instant.ofEpochSecond(epochSeconds), cm)

    @Test fun `an empty period has nothing to show`() {
        val display = CircumferencePresentationMapper.build(emptyList())

        assertTrue(display.isEmpty)
        assertNull(display.latest)
        assertNull(display.changeCentimeters)
        assertNull(display.averageCentimeters)
    }

    @Test fun `one measurement has a latest value and an average but no change`() {
        val display = CircumferencePresentationMapper.build(listOf(waist(82.0, 1_000)))

        assertEquals(82.0, display.latest!!.centimeters, 0.0)
        assertEquals(82.0, display.averageCentimeters!!, 0.0)
        assertNull(display.changeCentimeters)
    }

    @Test fun `the change is newest minus oldest, whatever order the entries came in`() {
        val display = CircumferencePresentationMapper.build(
            listOf(waist(81.0, 3_000), waist(84.0, 1_000), waist(82.5, 2_000)),
        )

        assertEquals(81.0, display.latest!!.centimeters, 0.0)
        assertEquals(-3.0, display.changeCentimeters!!, 1e-9)
        assertEquals(82.5, display.averageCentimeters!!, 1e-9)
        assertEquals(listOf(81.0, 82.5, 84.0), display.entriesNewestFirst.map { it.centimeters })
    }
}
