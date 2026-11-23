package tech.mmarca.openvitals.data.repository.contract

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.domain.model.ProgressPhoto

/** In-memory stand-in for [ProgressPhotoRepository]. Days are read in the system zone. */
class FakeProgressPhotoRepository(
    initial: List<ProgressPhoto> = emptyList(),
) : ProgressPhotoRepository {
    private val store = MutableStateFlow(initial)
    private val images = mutableMapOf<String, ByteArray>()
    private var nextId = 1

    val photos: List<ProgressPhoto> get() = store.value

    /** The periods each read asked for, in order. */
    val reads = mutableListOf<DatePeriod>()

    var failAdds: Throwable? = null

    /** Gives a waiting photo a full image, as [add] does. */
    fun putImage(photo: ProgressPhoto, bytes: ByteArray) {
        images[photo.id] = bytes
    }

    override suspend fun photos(period: DatePeriod): List<ProgressPhoto> {
        reads += period
        val zone = ZoneId.systemDefault()
        return store.value
            .filter { photo ->
                val day = photo.takenAt.atZone(zone).toLocalDate()
                !day.isBefore(period.start) && !day.isAfter(period.end)
            }
            .sortedByDescending { it.takenAt }
    }

    override suspend fun all(): List<ProgressPhoto> = store.value.sortedBy { it.takenAt }

    override suspend fun pending(): List<ProgressPhoto> = all().filter { !it.saved }

    override fun pendingCount(): Flow<Int> = store.map { photos -> photos.count { !it.saved } }

    override suspend fun add(takenAt: Instant, image: ByteArray, thumbnail: ByteArray): ProgressPhoto {
        failAdds?.let { throw it }
        val id = "photo-${nextId++}"
        val photo = ProgressPhoto(id = id, takenAt = takenAt, fileName = "$id.jpg", saved = false)
        images[id] = image
        store.value = store.value + photo
        return photo
    }

    override suspend fun pendingImage(photo: ProgressPhoto): ByteArray? =
        images[photo.id].takeIf { store.value.any { it.id == photo.id && !it.saved } }

    override suspend fun markSaved(photo: ProgressPhoto) {
        images.remove(photo.id)
        store.value = store.value.map { if (it.id == photo.id) it.copy(saved = true) else it }
    }

    override suspend fun delete(photo: ProgressPhoto) {
        images.remove(photo.id)
        store.value = store.value.filterNot { it.id == photo.id }
    }

    override fun thumbnailFile(photo: ProgressPhoto): File = File("thumbnails", "${photo.id}.thumb.jpg")
}
