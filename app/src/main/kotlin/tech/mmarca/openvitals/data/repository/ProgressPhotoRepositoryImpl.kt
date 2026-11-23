package tech.mmarca.openvitals.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.data.local.photos.ProgressPhotoDao
import tech.mmarca.openvitals.data.local.photos.ProgressPhotoEntity
import tech.mmarca.openvitals.data.repository.contract.ProgressPhotoRepository
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.domain.model.progressPhotoFileName

/**
 * Progress photos live in Room and `files/progress_photos` because Health Connect has no type for
 * a photo. `<id>.thumb.jpg` stays; `<id>.jpg`, the full image, is deleted once it is saved.
 */
@Singleton
class ProgressPhotoRepositoryImpl(
    private val directory: File,
    private val dao: ProgressPhotoDao,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProgressPhotoRepository {

    @Inject
    constructor(@ApplicationContext context: Context, dao: ProgressPhotoDao) :
        this(File(context.filesDir, ProgressPhotosDirectory), dao)

    override suspend fun photos(period: DatePeriod): List<ProgressPhoto> = withContext(dispatchers.io) {
        val zone = zone()
        val start = period.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = period.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.between(start, end).map { it.toDomain() }
    }

    override suspend fun all(): List<ProgressPhoto> = withContext(dispatchers.io) {
        dao.all().map { it.toDomain() }
    }

    override suspend fun pending(): List<ProgressPhoto> = withContext(dispatchers.io) {
        dao.pending().map { it.toDomain() }
    }

    override fun pendingCount(): Flow<Int> = dao.pendingCount()

    override suspend fun add(takenAt: Instant, image: ByteArray, thumbnail: ByteArray): ProgressPhoto =
        withContext(dispatchers.io) {
            val id = newId()
            val entity = ProgressPhotoEntity(
                id = id,
                takenAtMillis = takenAt.toEpochMilli(),
                fileName = progressPhotoFileName(id, takenAt, zone()),
                saved = false,
            )
            directory.mkdirs()
            val imageFile = imageFile(id)
            val thumbnailFile = thumbnailFile(id)
            imageFile.writeBytes(image)
            thumbnailFile.writeBytes(thumbnail)
            // A row without its files would list a photo that cannot show or save.
            runCatching { dao.insert(entity) }
                .onFailure {
                    imageFile.delete()
                    thumbnailFile.delete()
                }
                .getOrThrow()
            entity.toDomain()
        }

    override suspend fun pendingImage(photo: ProgressPhoto): ByteArray? = withContext(dispatchers.io) {
        imageFile(photo.id).takeIf { !photo.saved && it.isFile }?.readBytes()
    }

    override suspend fun markSaved(photo: ProgressPhoto) = withContext(dispatchers.io) {
        dao.markSaved(photo.id)
        imageFile(photo.id).delete()
        Unit
    }

    override suspend fun delete(photo: ProgressPhoto) = withContext(dispatchers.io) {
        dao.delete(photo.id)
        imageFile(photo.id).delete()
        thumbnailFile(photo.id).delete()
        Unit
    }

    override fun thumbnailFile(photo: ProgressPhoto): File = thumbnailFile(photo.id)

    private fun imageFile(id: String) = File(directory, "$id.jpg")

    private fun thumbnailFile(id: String) = File(directory, "$id.thumb.jpg")

    private companion object {
        const val ProgressPhotosDirectory = "progress_photos"
    }
}

private fun ProgressPhotoEntity.toDomain() = ProgressPhoto(
    id = id,
    takenAt = Instant.ofEpochMilli(takenAtMillis),
    fileName = fileName,
    saved = saved,
)
