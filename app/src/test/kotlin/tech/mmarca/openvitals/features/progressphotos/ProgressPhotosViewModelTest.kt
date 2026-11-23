package tech.mmarca.openvitals.features.progressphotos

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openvitals.core.period.PeriodRangePreferenceKey
import tech.mmarca.openvitals.core.period.TimeRange
import tech.mmarca.openvitals.data.repository.contract.FakeBodyFolderRepository
import tech.mmarca.openvitals.data.repository.contract.FakePreferences
import tech.mmarca.openvitals.data.repository.contract.FakeProgressPhotoRepository
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.navigation.SELECTED_DAY_ARG
import tech.mmarca.openvitals.util.MainDispatcherRule

private class FakeImporter : ProgressPhotoImporter {
    val encoded = mutableListOf<String>()
    var failure: Throwable? = null

    override suspend fun encode(uri: String): EncodedProgressPhoto {
        failure?.let { throw it }
        encoded += uri
        return EncodedProgressPhoto(image = byteArrayOf(1, 2), thumbnail = byteArrayOf(3))
    }
}

private class CountingScheduler : ProgressPhotoSaveScheduling {
    var enqueued = 0

    override fun enqueue() {
        enqueued++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressPhotosViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.systemDefault()
    private val now = LocalDate.now().atTime(LocalTime.of(9, 30)).atZone(zone).toInstant()
    private val today = now.atZone(zone).toLocalDate()

    private val repository = FakeProgressPhotoRepository()
    private val folder = FakeBodyFolderRepository(initialUri = "content://tree/body")
    private val importer = FakeImporter()
    private val scheduler = CountingScheduler()

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

    private fun photo(id: String, day: LocalDate, saved: Boolean = true, hour: Int = 8) = ProgressPhoto(
        id = id,
        takenAt = day.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant(),
        fileName = "$id.jpg",
        saved = saved,
    )

    private fun viewModel(
        preferences: FakePreferences = FakePreferences(initialRange = TimeRange.MONTH),
        selectedDay: LocalDate? = null,
        photoFolder: FakeBodyFolderRepository = folder,
    ) = ProgressPhotosViewModel(
        repository = repository,
        folder = photoFolder,
        importer = importer,
        saver = ProgressPhotoSaver(repository, photoFolder, { zone }, { now }),
        saveScheduling = scheduler,
        periodPreferences = preferences,
        savedStateHandle = SavedStateHandle(listOfNotNull(selectedDay?.let { SELECTED_DAY_ARG to it.toString() }).toMap()),
        dispatchers = mainDispatcherRule.dispatcherProvider,
        zone = { zone },
        now = { now },
    )

    @Test fun `the first load shows the month's photos by day, newest first`() = runTest {
        val repo = repository
        repo.add(today.atTime(7, 0).atZone(zone).toInstant(), byteArrayOf(1), byteArrayOf(1))
        repo.add(today.atTime(8, 0).atZone(zone).toInstant(), byteArrayOf(1), byteArrayOf(1))
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(TimeRange.MONTH, state.selectedRange)
        assertEquals(listOf(today), state.display.daysNewestFirst.map { it.date })
        assertEquals(listOf("photo-2", "photo-1"), state.display.daysNewestFirst.single().photos.map { it.id })
        assertEquals(2, state.pendingCount)
        assertTrue(state.folderPicked)
    }

    @Test fun `the remembered range is the photos' own`() = runTest {
        val preferences = FakePreferences(initialRange = TimeRange.MONTH)
        val vm = viewModel(preferences)
        advanceUntilIdle()

        vm.selectRange(TimeRange.YEAR)
        advanceUntilIdle()

        assertEquals(TimeRange.YEAR, preferences.timeRangeFor(PeriodRangePreferenceKey.PROGRESS_PHOTOS))
        assertEquals(TimeRange.YEAR, vm.uiState.value.selectedRange)
    }

    @Test fun `adding a photo encodes it, keeps it, queues its save and shows it`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.addPhoto("content://picker/1")
        advanceUntilIdle()

        assertEquals(listOf("content://picker/1"), importer.encoded)
        assertEquals(now, repository.photos.single().takenAt)
        assertEquals(1, scheduler.enqueued)
        assertFalse(vm.uiState.value.isAdding)
        assertEquals(1, vm.uiState.value.display.daysNewestFirst.single().photos.size)
    }

    @Test fun `in Day mode on an earlier day the photo is linked to that day`() = runTest {
        val earlier = today.minusDays(3)
        val vm = viewModel(FakePreferences(initialRange = TimeRange.DAY), selectedDay = earlier)
        advanceUntilIdle()

        vm.addPhoto("content://picker/1")
        advanceUntilIdle()

        assertEquals(earlier.atTime(LocalTime.NOON).atZone(zone).toInstant(), repository.photos.single().takenAt)
    }

    @Test fun `outside Day mode the photo is taken now, whatever period is on screen`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.previousPeriod()
        advanceUntilIdle()

        vm.addPhoto("content://picker/1")
        advanceUntilIdle()

        assertEquals(now, repository.photos.single().takenAt)
    }

    @Test fun `an image that cannot be read shows an error and keeps nothing`() = runTest {
        importer.failure = IOException("Not an image")
        val vm = viewModel()
        advanceUntilIdle()

        vm.addPhoto("content://picker/broken")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.error != null)
        assertFalse(vm.uiState.value.isAdding)
        assertEquals(emptyList<ProgressPhoto>(), repository.photos)
        assertEquals(0, scheduler.enqueued)
    }

    @Test fun `deleting a photo removes it and rereads the period`() = runTest {
        val kept = repository.add(now, byteArrayOf(1), byteArrayOf(1))
        val removed = repository.add(now.minusSeconds(60), byteArrayOf(1), byteArrayOf(1))
        val vm = viewModel()
        advanceUntilIdle()

        vm.delete(removed)
        advanceUntilIdle()

        assertEquals(listOf(kept.id), repository.photos.map { it.id })
        assertEquals(listOf(kept.id), vm.uiState.value.display.daysNewestFirst.single().photos.map { it.id })
    }

    @Test fun `back on screen, waiting photos are queued again when a folder is picked`() = runTest {
        repository.add(now, byteArrayOf(1), byteArrayOf(1))
        val vm = viewModel()
        advanceUntilIdle()

        vm.resumeCurrentPeriod()
        advanceUntilIdle()

        assertEquals(1, scheduler.enqueued)
    }

    @Test fun `without a folder or without waiting photos nothing is queued`() = runTest {
        val noFolder = viewModel(photoFolder = FakeBodyFolderRepository(initialUri = null))
        repository.add(now, byteArrayOf(1), byteArrayOf(1))
        advanceUntilIdle()
        noFolder.retryPendingSaves()
        assertFalse(noFolder.uiState.value.folderPicked)

        repository.markSaved(repository.photos.single())
        val allSaved = viewModel()
        advanceUntilIdle()
        allSaved.retryPendingSaves()

        assertEquals(0, scheduler.enqueued)
    }
}
