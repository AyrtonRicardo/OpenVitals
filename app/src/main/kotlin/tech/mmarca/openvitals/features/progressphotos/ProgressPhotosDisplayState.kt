package tech.mmarca.openvitals.features.progressphotos

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openvitals.domain.model.ProgressPhoto

/** One day's photos, newest first: the day is what a photo is linked to. */
@Immutable
data class ProgressPhotoDay(
    val date: LocalDate,
    val photos: List<ProgressPhoto>,
)

/** What the screen shows for a period, prepared by the ViewModel. */
@Immutable
data class ProgressPhotosDisplayState(
    val daysNewestFirst: List<ProgressPhotoDay> = emptyList(),
) {
    val isEmpty: Boolean get() = daysNewestFirst.isEmpty()
}

internal object ProgressPhotosPresentationMapper {
    /** [photos] are the period's photos in any order; days and photos come out newest first. */
    fun build(photos: List<ProgressPhoto>, zone: ZoneId): ProgressPhotosDisplayState =
        ProgressPhotosDisplayState(
            daysNewestFirst = photos
                .sortedByDescending { it.takenAt }
                .groupBy { it.takenAt.atZone(zone).toLocalDate() }
                .map { (date, dayPhotos) -> ProgressPhotoDay(date, dayPhotos) },
        )
}
