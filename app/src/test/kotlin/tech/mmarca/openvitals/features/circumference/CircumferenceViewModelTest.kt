package tech.mmarca.openvitals.features.circumference

import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openvitals.core.period.PeriodRangePreferenceKey
import tech.mmarca.openvitals.core.period.TimeRange
import tech.mmarca.openvitals.core.presentation.ScreenError
import tech.mmarca.openvitals.data.repository.contract.FakeBodyCircumferenceRepository
import tech.mmarca.openvitals.data.repository.contract.FakePreferences
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.navigation.SELECTED_DAY_ARG
import tech.mmarca.openvitals.util.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class CircumferenceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.now()

    private fun entry(site: CircumferenceSite, day: LocalDate, cm: Double, hour: Int = 8) = CircumferenceEntry(
        site = site,
        time = day.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant(),
        centimeters = cm,
    )

    private fun viewModel(
        repository: FakeBodyCircumferenceRepository = FakeBodyCircumferenceRepository(),
        preferences: FakePreferences = FakePreferences(initialRange = TimeRange.MONTH),
        selectedDay: LocalDate? = null,
    ) = CircumferenceViewModel(
        repository = repository,
        periodPreferences = preferences,
        dispatchers = mainDispatcherRule.dispatcherProvider,
        savedStateHandle = SavedStateHandle(listOfNotNull(selectedDay?.let { SELECTED_DAY_ARG to it.toString() }).toMap()),
    )

    @Test fun `the first load shows the waist for the current month`() = runTest {
        val repository = FakeBodyCircumferenceRepository(
            listOf(
                entry(CircumferenceSite.WAIST, today, 82.0),
                entry(CircumferenceSite.HIPS, today, 98.0),
            ),
        )
        val vm = viewModel(repository)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(CircumferenceSite.WAIST, state.site)
        assertEquals(TimeRange.MONTH, state.selectedRange)
        assertEquals(listOf(82.0), state.display.entriesNewestFirst.map { it.centimeters })
        assertEquals(CircumferenceSite.WAIST, repository.reads.single().first)
    }

    @Test fun `selecting a site loads that site alone`() = runTest {
        val repository = FakeBodyCircumferenceRepository(
            listOf(
                entry(CircumferenceSite.WAIST, today, 82.0),
                entry(CircumferenceSite.HIPS, today, 98.0),
            ),
        )
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.selectSite(CircumferenceSite.HIPS)
        advanceUntilIdle()

        assertEquals(CircumferenceSite.HIPS, vm.uiState.value.site)
        assertEquals(listOf(98.0), vm.uiState.value.display.entriesNewestFirst.map { it.centimeters })
    }

    @Test fun `selecting the site already shown does not reload`() = runTest {
        val repository = FakeBodyCircumferenceRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.selectSite(CircumferenceSite.WAIST)
        advanceUntilIdle()

        assertEquals(1, repository.reads.size)
    }

    @Test fun `the range is remembered under its own key`() = runTest {
        val preferences = FakePreferences()
        val vm = viewModel(preferences = preferences)

        vm.selectRange(TimeRange.WEEK)

        assertEquals(TimeRange.WEEK, preferences.timeRangeFor(PeriodRangePreferenceKey.CIRCUMFERENCE))
        assertEquals(TimeRange.WEEK, vm.uiState.value.selectedRange)
    }

    @Test fun `previousPeriod moves back one period of the selected range`() = runTest {
        val vm = viewModel()

        val steppedBack = listOf(TimeRange.WEEK, TimeRange.MONTH, TimeRange.YEAR).associateWith { range ->
            vm.selectRange(range)
            vm.selectDate(today)
            vm.previousPeriod()
            vm.uiState.value.selectedDate
        }

        assertEquals(
            mapOf(
                TimeRange.WEEK to today.minusWeeks(1),
                TimeRange.MONTH to today.minusMonths(1),
                TimeRange.YEAR to today.minusYears(1),
            ),
            steppedBack,
        )
    }

    @Test fun `nextPeriod stops at the period holding today`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val before = vm.uiState.value.selectedDate

        vm.nextPeriod()

        assertEquals(before, vm.uiState.value.selectedDate)
    }

    @Test fun `nextPeriod advances from a past anchor`() = runTest {
        val vm = viewModel()
        vm.selectDate(today.minusMonths(2))
        val before = vm.uiState.value.selectedDate

        vm.nextPeriod()

        assertEquals(before.plusMonths(1), vm.uiState.value.selectedDate)
    }

    @Test fun `a past period only shows its own measurements`() = runTest {
        val lastMonth = today.minusMonths(1).withDayOfMonth(1)
        val repository = FakeBodyCircumferenceRepository(
            listOf(
                entry(CircumferenceSite.WAIST, lastMonth, 84.0),
                entry(CircumferenceSite.WAIST, today, 82.0),
            ),
        )
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.previousPeriod()
        advanceUntilIdle()

        assertEquals(listOf(84.0), vm.uiState.value.display.entriesNewestFirst.map { it.centimeters })
    }

    @Test fun `a selected day from the route anchors the first period`() = runTest {
        val day = today.minusYears(1)
        val vm = viewModel(selectedDay = day)
        advanceUntilIdle()

        assertEquals(day, vm.uiState.value.selectedDate)
    }

    @Test fun `a stale load cannot overwrite the newer one it lost to`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeBodyCircumferenceRepository(
            listOf(
                entry(CircumferenceSite.WAIST, today, 82.0),
                entry(CircumferenceSite.HIPS, today, 98.0),
            ),
        )
        // The waist read is held on the wire until the hips read has landed.
        repository.beforeRead = { site, _ -> if (site == CircumferenceSite.WAIST) gate.await() }
        val vm = viewModel(repository)
        runCurrent()

        vm.selectSite(CircumferenceSite.HIPS)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(CircumferenceSite.HIPS, state.site)
        assertEquals(listOf(98.0), state.display.entriesNewestFirst.map { it.centimeters })
        assertFalse(state.isLoading)
    }

    @Test fun `a failed read becomes a screen error and clears loading`() = runTest {
        val repository = FakeBodyCircumferenceRepository()
        repository.beforeRead = { _, _ -> throw RuntimeException("disk") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(ScreenError.Message("disk"), vm.uiState.value.error)
    }

    @Test fun `deleting a measurement removes it and reloads`() = runTest {
        val kept = entry(CircumferenceSite.WAIST, today, 82.0, hour = 8)
        val removed = entry(CircumferenceSite.WAIST, today, 83.0, hour = 9)
        val repository = FakeBodyCircumferenceRepository(listOf(kept, removed))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.delete(removed)
        advanceUntilIdle()

        assertEquals(listOf(kept), repository.entries)
        assertEquals(listOf(kept), vm.uiState.value.display.entriesNewestFirst)
    }

    @Test fun `a failed delete keeps the measurement and shows an error`() = runTest {
        val measurement = entry(CircumferenceSite.WAIST, today, 82.0)
        val repository = FakeBodyCircumferenceRepository(listOf(measurement))
        repository.failDeletes = RuntimeException("locked")
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.delete(measurement)
        advanceUntilIdle()

        assertEquals(listOf(measurement), vm.uiState.value.display.entriesNewestFirst)
        assertEquals(ScreenError.Message("locked"), vm.uiState.value.error)
    }

    @Test fun `resuming rereads what the entry form saved`() = runTest {
        val repository = FakeBodyCircumferenceRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.display.isEmpty)

        repository.save(entry(CircumferenceSite.WAIST, today, 81.5))
        vm.resumeCurrentPeriod()
        advanceUntilIdle()

        assertEquals(listOf(81.5), vm.uiState.value.display.entriesNewestFirst.map { it.centimeters })
    }

    @Test fun `the figure compares latest with first by default`() = runTest {
        val repository = FakeBodyCircumferenceRepository(
            listOf(
                entry(CircumferenceSite.WAIST, today.minusDays(40), 88.0),
                entry(CircumferenceSite.WAIST, today, 86.5),
            ),
        )
        val vm = viewModel(repository)
        advanceUntilIdle()

        val waist = vm.uiState.value.bodyMap.getValue(CircumferenceSite.WAIST)
        assertNull(vm.uiState.value.compareDate)
        assertEquals(-1.5, waist.changeCentimeters!!, 1e-9)
        assertNull(waist.baselineDate)
    }

    @Test fun `picking a date compares with it, and clearing goes back`() = runTest {
        val first = today.minusDays(40)
        val repository = FakeBodyCircumferenceRepository(
            listOf(entry(CircumferenceSite.WAIST, first, 88.0), entry(CircumferenceSite.WAIST, today, 86.5)),
        )
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.selectCompareDate(today.minusDays(20))
        advanceUntilIdle()
        assertEquals(today.minusDays(20), vm.uiState.value.compareDate)
        assertEquals(first, vm.uiState.value.bodyMap.getValue(CircumferenceSite.WAIST).baselineDate)

        vm.clearCompareDate()
        advanceUntilIdle()
        assertNull(vm.uiState.value.compareDate)
        assertNull(vm.uiState.value.bodyMap.getValue(CircumferenceSite.WAIST).baselineDate)
    }

    @Test fun `a future comparison date is ignored`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectCompareDate(today.plusDays(1))
        advanceUntilIdle()

        assertNull(vm.uiState.value.compareDate)
    }

    @Test fun `a new measurement updates the figure`() = runTest {
        val repository = FakeBodyCircumferenceRepository(listOf(entry(CircumferenceSite.WAIST, today.minusDays(3), 88.0)))
        val vm = viewModel(repository)
        advanceUntilIdle()

        repository.saveDay(today, mapOf(CircumferenceSite.WAIST to 87.0))
        advanceUntilIdle()

        assertEquals(87.0, vm.uiState.value.bodyMap.getValue(CircumferenceSite.WAIST).latest!!.centimeters, 1e-9)
    }

    @Test fun `a figure that cannot be read says so, and a period reload does not hide it`() = runTest {
        val repository = FakeBodyCircumferenceRepository().apply { failAll = IllegalStateException("database closed") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.previousPeriod()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.bodyMapError != null)
        assertNull(vm.uiState.value.error)
    }
}
