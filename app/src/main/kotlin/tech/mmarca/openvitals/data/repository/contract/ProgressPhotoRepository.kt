package tech.mmarca.openvitals.data.repository.contract

import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.domain.model.ProgressPhoto

/**
 * Progress photos, which Health Connect has no type for. A thin seam over Room and the phone's
 * files: it stores the thumbnail and, until the photo is saved to the picked folder, the full
 * image. Writing to that folder is [BodyFolderRepository]'s job.
 */
interface ProgressPhotoRepository {
    /** The photos taken on the days of [period], both ends included, newest first. */
    suspend fun photos(period: DatePeriod): List<ProgressPhoto>

    /** Every photo, oldest first. */
    suspend fun all(): List<ProgressPhoto>

    /** The photos whose full image is not in the picked folder yet, oldest first. */
    suspend fun pending(): List<ProgressPhoto>

    /** How many photos wait for a save, emitted again on every change. */
    fun pendingCount(): Flow<Int>

    /** Keeps a new photo: its full image and thumbnail, both JPEG. It starts unsaved. */
    suspend fun add(takenAt: Instant, image: ByteArray, thumbnail: ByteArray): ProgressPhoto

    /** The full image while it waits for a save; null once saved or when the file is gone. */
    suspend fun pendingImage(photo: ProgressPhoto): ByteArray?

    /** The full image is in the picked folder: drop the phone's copy, keep the thumbnail. */
    suspend fun markSaved(photo: ProgressPhoto)

    /** Removes the row and the phone's files. The folder's copy is the caller's to remove. */
    suspend fun delete(photo: ProgressPhoto)

    /** Where the photo's thumbnail is, for the screen to decode. */
    fun thumbnailFile(photo: ProgressPhoto): File
}
