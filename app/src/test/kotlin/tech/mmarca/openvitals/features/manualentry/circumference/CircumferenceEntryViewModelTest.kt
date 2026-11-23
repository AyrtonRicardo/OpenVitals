package tech.mmarca.openvitals.features.manualentry.circumference

import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openvitals.data.repository.contract.FakeBodyCircumferenceRepository
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.preferences.UnitSystem
import tech.mmarca.openvitals.navigation.CIRCUMFERENCE_SITE_ARG
import tech.mmarca.openvitals.util.MainDispatcherRule

class CircumferenceEntryInputTest {

    @Test fun `centimetres are kept and inches become centimetres`() {
        assertEquals(82.5, circumferenceCentimetersOrNull("82.5", UnitSystem.METRIC)!!, 1e-9)
        assertEquals(81.28, circumferenceCentimetersOrNull("32", UnitSystem.IMPERIAL)!!, 1e-9)
    }

    @Test fun `a comma works as the decimal separator and spaces are ignored`() {
        assertEquals(82.5, circumferenceCentimetersOrNull(" 82,5 ", UnitSystem.METRIC)!!, 1e-9)
    }

    @Test fun `text, zero, negatives and values no tape could read are refused`() {
        listOf("", "abc", "0", "-80", "300.1").forEach { input ->
            assertNull(input, circumferenceCentimetersOrNull(input, UnitSystem.METRIC))
        }
        // 120 inches is 304.8 cm, past the limit even though 120 cm would pass.
        assertNull(circumferenceCentimetersOrNull("120", UnitSystem.IMPERIAL))
    }

    @Test fun `the unit label follows the height setting`() {
        assertEquals("cm", circumferenceInputUnitLabel(UnitSystem.METRIC))
        assertEquals("in", circumferenceInputUnitLabel(UnitSystem.IMPERIAL))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CircumferenceEntryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.parse("2026-11-21")
    private val zone = ZoneId.systemDefault()

    private fun entry(site: CircumferenceSite, date: String, cm: Double, time: LocalTime = LocalTime.NOON) =
        CircumferenceEntry(site, LocalDate.parse(date).atTime(time).atZone(zone).toInstant(), cm)

    private fun viewModel(
        repository: FakeBodyCircumferenceRepository = FakeBodyCircumferenceRepository(),
        site: CircumferenceSite? = null,
    ) = CircumferenceEntryViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(listOfNotNull(site?.let { CIRCUMFERENCE_SITE_ARG to it.name }).toMap()),
        today = { today },
        zone = zone,
    )

    @Test fun `the route's part is selected first and the date is today`() = runTest {
        val vm = viewModel(site = CircumferenceSite.HIPS)
        advanceUntilIdle()

        assertEquals(CircumferenceSite.HIPS, vm.uiState.value.selected)
        assertEquals(today, vm.uiState.value.date)
    }

    @Test fun `a route naming a part that no longer exists opens on the waist`() = runTest {
        val vm = CircumferenceEntryViewModel(
            repository = FakeBodyCircumferenceRepository(),
            savedStateHandle = SavedStateHandle(mapOf(CIRCUMFERENCE_SITE_ARG to "UPPER_ARM")),
            today = { today },
            zone = zone,
        )
        advanceUntilIdle()

        assertEquals(CircumferenceSite.WAIST, vm.uiState.value.selected)
    }

    @Test fun `typed values survive switching parts`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateInput("86.5")
        vm.select(CircumferenceSite.HIPS)
        vm.updateInput("99.5")
        vm.select(CircumferenceSite.WAIST)

        assertEquals(mapOf(CircumferenceSite.WAIST to "86.5", CircumferenceSite.HIPS to "99.5"), vm.uiState.value.inputs)
        assertEquals(CircumferenceSite.WAIST, vm.uiState.value.selected)
    }

    @Test fun `save stores every typed part on the date`() = runTest {
        val repository = FakeBodyCircumferenceRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("86.5")
        vm.select(CircumferenceSite.HIPS)
        vm.updateInput("99,5")

        vm.save(UnitSystem.METRIC)
        advanceUntilIdle()

        assertEquals(listOf(today to mapOf(CircumferenceSite.WAIST to 86.5, CircumferenceSite.HIPS to 99.5)), repository.savedDays)
        assertTrue(vm.uiState.value.saveCompleted)
    }

    @Test fun `an invalid value selects its part and saves nothing`() = runTest {
        val repository = FakeBodyCircumferenceRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("86.5")
        vm.select(CircumferenceSite.HIPS)
        vm.updateInput("abc")
        vm.select(CircumferenceSite.CHEST)

        vm.save(UnitSystem.METRIC)
        advanceUntilIdle()

        assertEquals(CircumferenceSite.HIPS, vm.uiState.value.invalidSite)
        assertEquals(CircumferenceSite.HIPS, vm.uiState.value.selected)
        assertEquals(emptyList<Any>(), repository.savedDays)
    }

    @Test fun `inches are stored as centimetres`() = runTest {
        val repository = FakeBodyCircumferenceRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("34")

        vm.save(UnitSystem.IMPERIAL)
        advanceUntilIdle()

        assertEquals(86.36, repository.savedDays.single().second.getValue(CircumferenceSite.WAIST), 1e-9)
    }

    @Test fun `a part that has a value on the date is flagged`() = runTest {
        val repository = FakeBodyCircumferenceRepository(listOf(entry(CircumferenceSite.WAIST, "2026-11-21", 87.0, LocalTime.of(23, 45))))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.updateInput("86.5")

        assertEquals(listOf(CircumferenceSite.WAIST), vm.uiState.value.replaces)
        assertEquals(listOf(87.0), vm.uiState.value.existingOnDate.getValue(CircumferenceSite.WAIST))
    }

    @Test fun `every value a part holds on the date is named, oldest first, since saving replaces them all`() = runTest {
        val repository = FakeBodyCircumferenceRepository(
            listOf(
                entry(CircumferenceSite.WAIST, "2026-11-21", 87.0, LocalTime.of(20, 0)),
                entry(CircumferenceSite.WAIST, "2026-11-21", 86.0, LocalTime.of(7, 0)),
                entry(CircumferenceSite.HIPS, "2026-11-21", 99.0),
            ),
        )
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf(86.0, 87.0), vm.uiState.value.existingOnDate.getValue(CircumferenceSite.WAIST))
        assertEquals(listOf(99.0), vm.uiState.value.existingOnDate.getValue(CircumferenceSite.HIPS))
    }

    @Test fun `changing the date re-checks what would be replaced`() = runTest {
        val repository = FakeBodyCircumferenceRepository(listOf(entry(CircumferenceSite.WAIST, "2026-11-21", 87.0)))
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("86.5")

        vm.selectDate(LocalDate.parse("2026-11-20"))
        advanceUntilIdle()

        assertEquals(LocalDate.parse("2026-11-20"), vm.uiState.value.date)
        assertEquals(emptyList<CircumferenceSite>(), vm.uiState.value.replaces)
        assertEquals("86.5", vm.uiState.value.inputs[CircumferenceSite.WAIST])
    }

    @Test fun `the last value before the date is offered as a hint`() = runTest {
        val older = entry(CircumferenceSite.WAIST, "2026-10-21", 88.0)
        val repository = FakeBodyCircumferenceRepository(listOf(older, entry(CircumferenceSite.WAIST, "2026-11-21", 87.0)))
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertEquals(older, vm.uiState.value.lastBefore[CircumferenceSite.WAIST])
    }

    @Test fun `save asks before replacing, and replaces on confirm`() = runTest {
        val repository = FakeBodyCircumferenceRepository(listOf(entry(CircumferenceSite.WAIST, "2026-11-21", 87.0)))
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("86.5")

        vm.save(UnitSystem.METRIC)
        advanceUntilIdle()
        assertEquals(listOf(CircumferenceSite.WAIST), vm.uiState.value.confirmReplace)
        assertEquals(emptyList<Any>(), repository.savedDays)

        vm.confirmReplace(UnitSystem.METRIC)
        advanceUntilIdle()

        assertNull(vm.uiState.value.confirmReplace)
        assertEquals(listOf(today to mapOf(CircumferenceSite.WAIST to 86.5)), repository.savedDays)
    }

    @Test fun `dismissing the question saves nothing`() = runTest {
        val repository = FakeBodyCircumferenceRepository(listOf(entry(CircumferenceSite.WAIST, "2026-11-21", 87.0)))
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("86.5")
        vm.save(UnitSystem.METRIC)
        advanceUntilIdle()

        vm.dismissReplace()
        advanceUntilIdle()

        assertNull(vm.uiState.value.confirmReplace)
        assertEquals(emptyList<Any>(), repository.savedDays)
        assertFalse(vm.uiState.value.saveCompleted)
    }

    @Test fun `a future date is not accepted`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectDate(LocalDate.parse("2026-11-22"))

        assertEquals(today, vm.uiState.value.date)
    }

    @Test fun `nothing typed cannot be saved`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.updateInput("   ")

        assertFalse(vm.uiState.value.canSave)
    }

    @Test fun `a failed write shows the error and keeps the values`() = runTest {
        val repository = FakeBodyCircumferenceRepository().apply { failSaves = IOException("disk full") }
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("86.5")

        vm.save(UnitSystem.METRIC)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.writeError != null)
        assertFalse(vm.uiState.value.isSaving)
        assertEquals("86.5", vm.uiState.value.inputs[CircumferenceSite.WAIST])
    }

    @Test fun `moving to a day that has a value flags it`() = runTest {
        val repository = FakeBodyCircumferenceRepository(listOf(entry(CircumferenceSite.WAIST, "2026-11-20", 87.0)))
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.updateInput("86.5")
        assertEquals(emptyList<CircumferenceSite>(), vm.uiState.value.replaces)

        vm.selectDate(LocalDate.parse("2026-11-20"))
        advanceUntilIdle()

        assertEquals(listOf(CircumferenceSite.WAIST), vm.uiState.value.replaces)
    }

    @Test fun `nothing can be saved until the day has been read`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeBodyCircumferenceRepository(listOf(entry(CircumferenceSite.WAIST, "2026-11-21", 87.0)))
            .apply { beforeDayRead = { gate.await() } }
        val vm = viewModel(repository)
        vm.updateInput("86.5")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canSave)
        vm.save(UnitSystem.METRIC)
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), repository.savedDays)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canSave)
        assertEquals(listOf(CircumferenceSite.WAIST), vm.uiState.value.replaces)
    }

    @Test fun `a day that cannot be read cannot be saved over`() = runTest {
        val repository = FakeBodyCircumferenceRepository().apply { beforeDayRead = { throw IOException("database locked") } }
        val vm = viewModel(repository)
        vm.updateInput("86.5")
        advanceUntilIdle()

        vm.save(UnitSystem.METRIC)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canSave)
        assertTrue(vm.uiState.value.dayLoadError != null)
        assertEquals(emptyList<Any>(), repository.savedDays)
    }
}
