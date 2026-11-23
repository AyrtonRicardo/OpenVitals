package tech.mmarca.openvitals.features.progressphotos

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import tech.mmarca.openvitals.data.repository.contract.FakeBodyFolderRepository
import tech.mmarca.openvitals.data.repository.contract.FakeProgressPhotoRepository

class ProgressPhotoSaverTest {

    private val photos = FakeProgressPhotoRepository()
    private val folder = FakeBodyFolderRepository(initialUri = "content://tree/body")
    private val saver = ProgressPhotoSaver(photos, folder, { ZoneId.of("UTC") }, { Instant.parse("2026-11-21T18:00:00Z") })

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private suspend fun addPhoto(time: String, bytes: ByteArray = byteArrayOf(7)) =
        photos.add(Instant.parse(time), bytes, byteArrayOf(1))

    private fun indexedFiles(): List<String> =
        Json.parseToJsonElement(folder.text("photos.json")!!).jsonObject.getValue("photos").jsonArray
            .map { it.jsonObject.getValue("file").jsonPrimitive.content }

    @Test fun `waiting photos go to the photos folder, are marked saved and listed in the index`() = runTest {
        val first = addPhoto("2026-11-20T08:00:00Z", byteArrayOf(1, 1))
        val second = addPhoto("2026-11-21T08:00:00Z", byteArrayOf(2, 2))

        val outcome = saver.savePending()

        assertEquals(ProgressPhotoSaveOutcome(saved = 2, remaining = 0), outcome)
        assertArrayEquals(byteArrayOf(1, 1), folder.files["photos/${first.fileName}"])
        assertArrayEquals(byteArrayOf(2, 2), folder.files["photos/${second.fileName}"])
        assertTrue(photos.photos.all { it.saved })
        assertEquals(listOf("photos/${first.fileName}", "photos/${second.fileName}"), indexedFiles())
    }

    @Test fun `without a folder nothing is tried and the photos keep waiting`() = runTest {
        val noFolder = FakeBodyFolderRepository(initialUri = null)
        addPhoto("2026-11-21T08:00:00Z")

        val outcome = ProgressPhotoSaver(photos, noFolder, { ZoneId.of("UTC") }, { Instant.EPOCH }).savePending()

        assertEquals(ProgressPhotoSaveOutcome(saved = 0, remaining = 1, folderMissing = true), outcome)
        assertFalse(photos.photos.single().saved)
    }

    @Test fun `an offline folder leaves every photo waiting with its image on the phone`() = runTest {
        val photo = addPhoto("2026-11-21T08:00:00Z", byteArrayOf(9))
        folder.offline = true

        val outcome = saver.savePending()

        assertEquals(ProgressPhotoSaveOutcome(saved = 0, remaining = 1), outcome)
        assertArrayEquals(byteArrayOf(9), photos.pendingImage(photos.photos.single()))
        assertNull(folder.text("photos.json"))
        assertFalse(photos.photos.single { it.id == photo.id }.saved)
    }

    @Test fun `one refused photo does not hold back the others`() = runTest {
        val refused = addPhoto("2026-11-20T08:00:00Z")
        val accepted = addPhoto("2026-11-21T08:00:00Z")
        folder.refusedNames += refused.fileName

        val outcome = saver.savePending()

        assertEquals(ProgressPhotoSaveOutcome(saved = 1, remaining = 1), outcome)
        assertEquals(listOf("photos/${accepted.fileName}"), indexedFiles())
    }

    @Test fun `deleting a saved photo removes it from the folder and the index`() = runTest {
        addPhoto("2026-11-20T08:00:00Z")
        val kept = addPhoto("2026-11-21T08:00:00Z")
        saver.savePending()
        val removed = photos.photos.first { it.id != kept.id }

        saver.delete(removed)

        assertEquals(listOf("photos/${removed.fileName}"), folder.deleted)
        assertEquals(listOf("photos/${kept.fileName}"), indexedFiles())
        assertEquals(listOf(kept.id), photos.photos.map { it.id })
    }

    @Test fun `deleting a waiting photo touches nothing in the folder`() = runTest {
        val photo = addPhoto("2026-11-21T08:00:00Z")

        saver.delete(photo)

        assertEquals(emptyList<String>(), folder.deleted)
        assertEquals(emptyList<Any>(), photos.photos)
    }

    @Test fun `deleting a saved photo while the folder is offline still deletes it here`() = runTest {
        addPhoto("2026-11-21T08:00:00Z")
        saver.savePending()
        folder.offline = true

        saver.delete(photos.photos.single())

        assertEquals(emptyList<Any>(), photos.photos)
    }
}
