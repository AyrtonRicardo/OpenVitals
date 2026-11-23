package tech.mmarca.openvitals.features.bodyfolder

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
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
import tech.mmarca.openvitals.data.repository.contract.BodyRepository
import tech.mmarca.openvitals.data.repository.contract.FakeBodyCircumferenceRepository
import tech.mmarca.openvitals.data.repository.contract.FakeBodyFolderRepository
import tech.mmarca.openvitals.data.repository.contract.FakeProgressPhotoRepository
import tech.mmarca.openvitals.domain.model.WeightEntry
import tech.mmarca.openvitals.features.progressphotos.ProgressPhotoSaveScheduling
import tech.mmarca.openvitals.util.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class BodyFolderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val photos = FakeProgressPhotoRepository()
    private var enqueued = 0
    private val scheduler = object : ProgressPhotoSaveScheduling {
        override fun enqueue() {
            enqueued++
        }
    }
    private var weightGate: CompletableDeferred<Unit>? = null
    private var weightFailure: Throwable? = null
    private val body = mockk<BodyRepository>().also { repo ->
        coEvery { repo.loadWeightEntries(any(), any()) } coAnswers {
            weightGate?.await()
            weightFailure?.let { throw it }
            listOf(WeightEntry(Instant.parse("2026-11-20T07:00:00Z"), 80.0, "scale"))
        }
        coEvery { repo.loadHeightEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadBodyFatEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadLeanBodyMassEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadBoneMassEntries(any(), any()) } returns emptyList()
        coEvery { repo.loadBodyWaterMassEntries(any(), any()) } returns emptyList()
    }

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

    private fun viewModel(folder: FakeBodyFolderRepository) = BodyFolderViewModel(
        folder = folder,
        photos = photos,
        exporter = BodyMeasurementsExporter(
            body,
            FakeBodyCircumferenceRepository(),
            folder,
            { LocalDate.parse("2026-11-21") },
            { Instant.parse("2026-11-21T18:00:00Z") },
        ),
        saveScheduling = scheduler,
    )

    @Test fun `a picked folder shows its name and the photos waiting for it`() = runTest {
        photos.add(Instant.EPOCH, byteArrayOf(1), byteArrayOf(1))
        val vm = viewModel(FakeBodyFolderRepository(initialUri = "content://tree/body", name = "Body"))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.folderPicked)
        assertEquals("Body", state.folderName)
        assertEquals(1, state.pendingPhotos)
    }

    @Test fun `picking a folder keeps it and starts the photo queue`() = runTest {
        val folder = FakeBodyFolderRepository(initialUri = null, name = "Share")
        val vm = viewModel(folder)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.folderPicked)

        vm.onFolderPicked("content://tree/share")
        advanceUntilIdle()

        assertEquals("content://tree/share", folder.folderUri.value)
        assertTrue(vm.uiState.value.folderPicked)
        assertEquals("Share", vm.uiState.value.folderName)
        assertEquals(1, enqueued)
    }

    @Test fun `a folder the system will not grant shows an error and queues nothing`() = runTest {
        val folder = FakeBodyFolderRepository(initialUri = null).apply { failChoose = SecurityException("no grant") }
        val vm = viewModel(folder)
        advanceUntilIdle()

        vm.onFolderPicked("content://tree/share")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.error != null)
        assertEquals(0, enqueued)
    }

    @Test fun `forgetting the folder clears it`() = runTest {
        val folder = FakeBodyFolderRepository(initialUri = "content://tree/body")
        val vm = viewModel(folder)
        advanceUntilIdle()

        vm.forgetFolder()
        advanceUntilIdle()

        assertNull(folder.folderUri.value)
        assertFalse(vm.uiState.value.folderPicked)
    }

    @Test fun `an export reports how many entries it wrote, and a second tap waits for the first`() = runTest {
        val folder = FakeBodyFolderRepository(initialUri = "content://tree/body")
        val vm = viewModel(folder)
        advanceUntilIdle()
        weightGate = CompletableDeferred()

        vm.exportNow()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isExporting)
        vm.exportNow()
        weightGate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isExporting)
        assertEquals(1, vm.uiState.value.exportedEntries)
        assertTrue(folder.files.containsKey("measurements.json"))
    }

    @Test fun `a failed export shows the error`() = runTest {
        weightFailure = IOException("Health Connect refused")
        val vm = viewModel(FakeBodyFolderRepository(initialUri = "content://tree/body"))
        advanceUntilIdle()

        vm.exportNow()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isExporting)
        assertNull(vm.uiState.value.exportedEntries)
        assertTrue(vm.uiState.value.error != null)
    }

    @Test fun `without a folder neither the export nor the photo queue starts`() = runTest {
        val folder = FakeBodyFolderRepository(initialUri = null)
        val vm = viewModel(folder)
        advanceUntilIdle()

        vm.exportNow()
        vm.savePhotosNow()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isExporting)
        assertEquals(emptyMap<String, ByteArray>(), folder.files)
        assertEquals(0, enqueued)
    }
}
