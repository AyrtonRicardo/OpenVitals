package tech.mmarca.openvitals.features.circumference

import androidx.compose.runtime.Immutable
import tech.mmarca.openvitals.domain.model.CircumferenceEntry

/**
 * What the screen shows for one site and period, prepared by the ViewModel. Values stay in
 * centimetres; the screen formats them in the user's unit.
 */
@Immutable
data class CircumferenceDisplayState(
    val latest: CircumferenceEntry? = null,
    /** Newest minus oldest in the period. Null until the period holds two measurements. */
    val changeCentimeters: Double? = null,
    val averageCentimeters: Double? = null,
    val entriesNewestFirst: List<CircumferenceEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = entriesNewestFirst.isEmpty()
}

internal object CircumferencePresentationMapper {
    /** [entries] are one site's measurements in the period, in any order. */
    fun build(entries: List<CircumferenceEntry>): CircumferenceDisplayState {
        if (entries.isEmpty()) return CircumferenceDisplayState()
        val oldestFirst = entries.sortedBy { it.time }
        val latest = oldestFirst.last()
        return CircumferenceDisplayState(
            latest = latest,
            changeCentimeters = if (oldestFirst.size > 1) latest.centimeters - oldestFirst.first().centimeters else null,
            averageCentimeters = oldestFirst.sumOf { it.centimeters } / oldestFirst.size,
            entriesNewestFirst = oldestFirst.asReversed(),
        )
    }
}
