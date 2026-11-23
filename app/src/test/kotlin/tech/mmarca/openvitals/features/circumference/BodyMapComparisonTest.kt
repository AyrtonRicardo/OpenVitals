package tech.mmarca.openvitals.features.circumference

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite

class BodyMapComparisonTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun waist(date: String, cm: Double) = CircumferenceEntry(
        site = CircumferenceSite.WAIST,
        time = LocalDate.parse(date).atTime(LocalTime.NOON).atZone(zone).toInstant(),
        centimeters = cm,
    )

    private val first = waist("2026-10-01", 88.0)
    private val middle = waist("2026-10-15", 87.0)
    private val latest = waist("2026-11-21", 86.5)
    private val history = listOf(latest, first, middle)

    private fun build(entries: List<CircumferenceEntry>, compareDate: String? = null) =
        BodyMapComparison.build(entries, compareDate?.let(LocalDate::parse), zone)

    @Test fun `without a date each part compares latest with its first`() {
        val waist = build(history).getValue(CircumferenceSite.WAIST)

        assertEquals(latest, waist.latest)
        assertEquals(first, waist.baseline)
        assertEquals(-1.5, waist.changeCentimeters!!, 1e-9)
        assertNull(waist.baselineDate)
    }

    @Test fun `a part measured once compares with itself`() {
        val waist = build(listOf(middle)).getValue(CircumferenceSite.WAIST)

        assertEquals(middle, waist.baseline)
        assertEquals(0.0, waist.changeCentimeters!!, 1e-9)
    }

    @Test fun `a part never measured has nothing, and every site is present`() {
        val map = build(history)

        assertEquals(CircumferenceSite.entries.toSet(), map.keys)
        assertEquals(BodyMapValue(latest = null, baseline = null, changeCentimeters = null, baselineDate = null), map.getValue(CircumferenceSite.NECK))
    }

    @Test fun `with a date on a measuring day the baseline is that day's value`() {
        val waist = build(history, "2026-10-15").getValue(CircumferenceSite.WAIST)

        assertEquals(middle, waist.baseline)
        assertEquals(-0.5, waist.changeCentimeters!!, 1e-9)
        assertNull(waist.baselineDate)
    }

    @Test fun `with a date between measurements the baseline is the last before it, with its day`() {
        val waist = build(history, "2026-10-20").getValue(CircumferenceSite.WAIST)

        assertEquals(middle, waist.baseline)
        assertEquals(LocalDate.parse("2026-10-15"), waist.baselineDate)
    }

    @Test fun `a date before every measurement leaves no baseline`() {
        val waist = build(history, "2026-09-01").getValue(CircumferenceSite.WAIST)

        assertEquals(latest, waist.latest)
        assertNull(waist.baseline)
        assertNull(waist.changeCentimeters)
        assertNull(waist.baselineDate)
    }

    @Test fun `a date after the latest compares the latest with itself`() {
        val waist = build(history, "2026-11-30").getValue(CircumferenceSite.WAIST)

        assertEquals(latest, waist.baseline)
        assertEquals(0.0, waist.changeCentimeters!!, 1e-9)
        assertEquals(LocalDate.parse("2026-11-21"), waist.baselineDate)
    }
}
