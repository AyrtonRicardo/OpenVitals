package tech.mmarca.openvitals.features.circumference

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.preferences.UnitSystem

/** What the body figure shows for one part: its latest value and what it is compared with. */
@Immutable
data class BodyMapValue(
    val latest: CircumferenceEntry?,
    /** The part's first measurement, or its last one on or before the comparison date. */
    val baseline: CircumferenceEntry?,
    /** Latest minus baseline, in centimetres; null when either is missing. */
    val changeCentimeters: Double?,
    /** The baseline's local day, when a comparison date is set and the baseline is from another day. */
    val baselineDate: LocalDate?,
)

/** Which way a part changed, as it is shown: a change that rounds to zero counts as none. */
enum class ChangeDirection { SMALLER, LARGER, SAME, UNKNOWN }

/**
 * The direction of [changeCentimeters] once rounded to the tenth the screen shows in
 * [unitSystem], so no arrow points at "0.0 in".
 */
fun changeDirection(changeCentimeters: Double?, unitSystem: UnitSystem): ChangeDirection {
    if (changeCentimeters == null) return ChangeDirection.UNKNOWN
    val shown = if (unitSystem == UnitSystem.IMPERIAL) changeCentimeters / CentimetersPerInch else changeCentimeters
    val tenths = (abs(shown) * 10).roundToLong()
    return when {
        tenths == 0L -> ChangeDirection.SAME
        shown < 0 -> ChangeDirection.SMALLER
        else -> ChangeDirection.LARGER
    }
}

private const val CentimetersPerInch = 2.54

internal object BodyMapComparison {
    /**
     * Every site's figure value from [entries], in any order. Without [compareDate] each part's
     * latest is compared with its first; with one, with its last value on or before that day.
     */
    fun build(entries: List<CircumferenceEntry>, compareDate: LocalDate?, zone: ZoneId): Map<CircumferenceSite, BodyMapValue> {
        val bySite = entries.groupBy { it.site }.mapValues { (_, siteEntries) -> siteEntries.sortedBy { it.time } }
        return CircumferenceSite.entries.associateWith { site ->
            val history = bySite[site].orEmpty()
            val latest = history.lastOrNull()
            val baseline = if (compareDate == null) {
                history.firstOrNull()
            } else {
                history.lastOrNull { !it.time.atZone(zone).toLocalDate().isAfter(compareDate) }
            }
            val baselineDay = baseline?.time?.atZone(zone)?.toLocalDate()
            BodyMapValue(
                latest = latest,
                baseline = baseline,
                changeCentimeters = if (latest != null && baseline != null) latest.centimeters - baseline.centimeters else null,
                baselineDate = baselineDay.takeIf { compareDate != null && it != compareDate },
            )
        }
    }
}
