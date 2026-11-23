package tech.mmarca.openvitals.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A progress photo. Health Connect has no type for photos, so the phone keeps a row and a
 * thumbnail, and the full image goes to the folder the user picked. [saved] turns true once it is
 * there; until then the phone holds the full image too, so a save waits out a folder that is offline.
 */
data class ProgressPhoto(
    val id: String,
    val takenAt: Instant,
    /** The image's name in [ProgressPhotoFolderName], fixed when the photo is added. */
    val fileName: String,
    val saved: Boolean,
)

/** The folder inside the picked folder that holds the full images. */
const val ProgressPhotoFolderName = "photos"

/**
 * The image's file name: the local time it was taken, so a folder listing sorts by date, then the
 * start of its id, so two photos in the same second keep apart. `2026-11-21_183005_1a2b3c4d.jpg`.
 */
fun progressPhotoFileName(id: String, takenAt: Instant, zone: ZoneId): String {
    val stamp = FileStampFormat.format(takenAt.atZone(zone))
    val suffix = id.filter { it.isLetterOrDigit() }.take(FileIdLength).lowercase()
    return "${stamp}_$suffix.jpg"
}

private val FileStampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
private const val FileIdLength = 8
