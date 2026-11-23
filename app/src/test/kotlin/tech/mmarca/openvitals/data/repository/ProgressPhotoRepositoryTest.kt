package tech.mmarca.openvitals.data.repository

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.data.local.photos.ProgressPhotoDao
import tech.mmarca.openvitals.data.local.photos.ProgressPhotoEntity
import tech.mmarca.openvitals.util.MainDispatcherRule

/** In-memory stand-in for [ProgressPhotoDao]: each `@Query`'s SQL reimplemented over a map by id. */
private class FakeProgressPhotoDao : ProgressPhotoDao {
    val rows = MutableStateFlow<Map<String, ProgressPhotoEntity>>(emptyMap())
    var failInserts = false

    override suspend fun between(startMillis: Long, endMillis: Long): List<ProgressPhotoEntity> =
        rows.value.values.filter { it.takenAtMillis in startMillis until endMillis }.sortedByDescending { it.takenAtMillis }

    override suspend fun all(): List<ProgressPhotoEntity> = rows.value.values.sortedBy { it.takenAtMillis }

    override suspend fun pending(): List<ProgressPhotoEntity> = all().filter { !it.saved }

    override fun pendingCount(): Flow<Int> = rows.map { all -> all.values.count { !it.saved } }

    override suspend fun insert(photo: ProgressPhotoEntity) {
        check(!failInserts) { "disk full" }
        check(photo.id !in rows.value) { "duplicate id" }
        rows.value = rows.value + (photo.id to photo)
    }

    override suspend fun markSaved(id: String) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(saved = true)) }
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value - id
    }
}

class ProgressPhotoRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")
    private val dao = FakeProgressPhotoDao()
    private var nextId = 0
    private val directory: File by lazy { File(temporaryFolder.root, "progress_photos") }
    private val repository by lazy {
        ProgressPhotoRepositoryImpl(directory, dao, mainDispatcherRule.dispatcherProvider, { zone }) {
            "id-${++nextId}-abcdef"
        }
    }

    private fun at(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(zone).toInstant()

    private val image = byteArrayOf(1, 2, 3)
    private val thumbnail = byteArrayOf(4, 5)

    @Test fun `a new photo keeps both files, starts unsaved and is named by its local time`() = runTest {
        val photo = repository.add(at("2026-11-21T18:30:05"), image, thumbnail)

        assertFalse(photo.saved)
        assertEquals("2026-11-21_183005_id1abcde.jpg", photo.fileName)
        assertArrayEquals(image, File(directory, "${photo.id}.jpg").readBytes())
        assertArrayEquals(thumbnail, repository.thumbnailFile(photo).readBytes())
        assertArrayEquals(image, repository.pendingImage(photo))
        assertEquals(1, repository.pendingCount().first())
    }

    @Test fun `a saved photo drops its full image and keeps its thumbnail`() = runTest {
        val photo = repository.add(at("2026-11-21T18:30:05"), image, thumbnail)

        repository.markSaved(photo)

        val saved = repository.all().single()
        assertTrue(saved.saved)
        assertFalse(File(directory, "${photo.id}.jpg").exists())
        assertTrue(repository.thumbnailFile(saved).exists())
        assertNull(repository.pendingImage(saved))
        assertEquals(emptyList<Any>(), repository.pending())
        assertEquals(0, repository.pendingCount().first())
    }

    @Test fun `deleting removes the row and both files`() = runTest {
        val photo = repository.add(at("2026-11-21T18:30:05"), image, thumbnail)

        repository.delete(photo)

        assertEquals(emptyList<Any>(), repository.all())
        assertFalse(File(directory, "${photo.id}.jpg").exists())
        assertFalse(repository.thumbnailFile(photo).exists())
    }

    @Test fun `a failed insert leaves no files behind`() = runTest {
        dao.failInserts = true

        val result = runCatching { repository.add(at("2026-11-21T18:30:05"), image, thumbnail) }

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }

    @Test fun `a period takes its days whole in the device's zone, newest first`() = runTest {
        val beforeStart = repository.add(at("2026-10-31T23:59"), image, thumbnail)
        val firstMinute = repository.add(at("2026-11-01T00:00"), image, thumbnail)
        val lastMinute = repository.add(at("2026-11-30T23:59"), image, thumbnail)
        val afterEnd = repository.add(at("2026-12-01T00:00"), image, thumbnail)

        val photos = repository.photos(DatePeriod(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")))

        assertEquals(listOf(lastMinute.id, firstMinute.id), photos.map { it.id })
        assertEquals(listOf(beforeStart.id, firstMinute.id, lastMinute.id, afterEnd.id), repository.all().map { it.id })
    }
}
