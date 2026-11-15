package tech.mmarca.openvitals.sharing

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import tech.mmarca.openvitals.core.privacy.PrivacySentinels
import tech.mmarca.openvitals.domain.model.HealthConnectAvailability
import tech.mmarca.openvitals.features.activity.ActivityWorkoutFitFile
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.healthconnect.HealthConnectSyncGate
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus
import tech.mmarca.openvitals.sharing.contract.WorkoutFitRequest

/** A file cannot say what is missing from it, so anything short of the whole file is a refusal with a reason. */
class WorkoutFitAnswerTest {
    private val store = mockk<DataSharingStore> { every { sharingEnabled } returns true }
    private val healthConnect = mockk<HealthConnectManager> {
        every { availability() } returns HealthConnectAvailability.AVAILABLE
        coEvery { readsOtherAppsDataNow() } returns true
    }
    private val syncGate = mockk<HealthConnectSyncGate> { every { isEnabled } returns true }
    private val fitFile = mockk<ActivityWorkoutFitFile>()
    private val queries = DataSharingQueries(store, healthConnect, syncGate, mockk(), mockk(), fitFile)
    private val request = WorkoutFitRequest(PrivacySentinels.Uuid, withRoute = true)

    @Test
    fun `the whole file is handed over as written`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { fitFile.bytes(PrivacySentinels.Uuid, true) } returns bytes

        assertThat((queries.workoutFit(request) as WorkoutFitAnswer.File).bytes).isEqualTo(bytes)
    }

    @Test
    fun `sharing off refuses before anything is read`() = runTest {
        every { store.sharingEnabled } returns false

        assertThat(queries.workoutFit(request)).isEqualTo(WorkoutFitAnswer.Refused(DataSharingStatus.SHARING_OFF))
    }

    @Test
    fun `a closed Health Connect gate refuses with its reason`() = runTest {
        coEvery { healthConnect.readsOtherAppsDataNow() } returns false

        assertThat(queries.workoutFit(request)).isEqualTo(WorkoutFitAnswer.Refused(DataSharingStatus.BACKGROUND_READ_MISSING))
    }

    @Test
    fun `an id no workout has is not found`() = runTest {
        coEvery { fitFile.bytes(any(), any()) } returns null

        assertThat(queries.workoutFit(request)).isEqualTo(WorkoutFitAnswer.Refused(DataSharingStatus.NOT_FOUND))
    }

    @Test
    fun `a failed read refuses rather than hand out part of a file`() = runTest {
        coEvery { fitFile.bytes(any(), any()) } throws IllegalStateException("read of ${PrivacySentinels.Uuid} failed")

        assertThat(queries.workoutFit(request) {}).isEqualTo(WorkoutFitAnswer.Refused(DataSharingStatus.PARTIAL))
    }

    @Test
    fun `a refusal carries a status and nothing of the request`() = runTest {
        coEvery { fitFile.bytes(any(), any()) } throws IllegalStateException("read of ${PrivacySentinels.Uuid} failed")

        assertThat(queries.workoutFit(request) {}.toString()).doesNotContain(PrivacySentinels.Uuid)
    }
}
