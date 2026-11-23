package tech.mmarca.openvitals.features.progressphotos

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import tech.mmarca.openvitals.data.repository.contract.BodyFolderRepository
import tech.mmarca.openvitals.data.repository.contract.ProgressPhotoRepository
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.domain.model.ProgressPhotoFolderName
import tech.mmarca.openvitals.features.bodyfolder.BodyFolderDocuments

/** What one pass over the waiting photos did. */
data class ProgressPhotoSaveOutcome(
    val saved: Int,
    /** Photos still waiting: the folder refused them, or it is offline. */
    val remaining: Int,
    /** No folder is picked, so nothing was tried. */
    val folderMissing: Boolean = false,
)

/**
 * Moves the full images of waiting photos into the picked folder and keeps `photos.json` there in
 * step. A photo that fails stays waiting with its full image on the phone, for the next pass.
 */
class ProgressPhotoSaver(
    private val photos: ProgressPhotoRepository,
    private val folder: BodyFolderRepository,
    private val zone: () -> ZoneId,
    private val now: () -> Instant,
) {
    @Inject
    constructor(photos: ProgressPhotoRepository, folder: BodyFolderRepository) :
        this(photos, folder, ZoneId::systemDefault, Instant::now)

    suspend fun savePending(): ProgressPhotoSaveOutcome {
        val pending = photos.pending()
        if (folder.folderUri.value == null) {
            return ProgressPhotoSaveOutcome(saved = 0, remaining = pending.size, folderMissing = true)
        }
        var saved = 0
        pending.forEach { photo ->
            if (saveOne(photo)) saved++
        }
        if (saved > 0) writeIndex()
        if (saved < pending.size) Log.w(TAG, "Photos left waiting count=${pending.size - saved}")
        return ProgressPhotoSaveOutcome(saved = saved, remaining = pending.size - saved)
    }

    /** Removes the photo here and, when it was saved, from the folder too, as far as the folder lets. */
    suspend fun delete(photo: ProgressPhoto) {
        photos.delete(photo)
        if (!photo.saved) return
        attempt { folder.delete(ProgressPhotoFolderName, photo.fileName) }
        writeIndex()
    }

    private suspend fun saveOne(photo: ProgressPhoto): Boolean {
        val image = photos.pendingImage(photo) ?: return false
        return attempt {
            folder.write(ProgressPhotoFolderName, photo.fileName, JpegMimeType, image)
            photos.markSaved(photo)
        }
    }

    private suspend fun writeIndex() {
        attempt {
            val document = BodyFolderDocuments.photos(photos.all(), zone(), now())
            folder.write(
                directory = null,
                name = BodyFolderDocuments.PhotosFileName,
                mimeType = BodyFolderDocuments.JsonMimeType,
                bytes = document.toByteArray(Charsets.UTF_8),
            )
        }
    }

    /** A folder that refuses is expected (a share can be offline), so it is a false, not a crash. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Folder write failed", error)
            false
        }

    private companion object {
        const val TAG = "ProgressPhotoSaver"
        const val JpegMimeType = "image/jpeg"
    }
}
