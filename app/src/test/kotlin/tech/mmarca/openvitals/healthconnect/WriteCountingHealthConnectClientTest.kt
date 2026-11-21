package tech.mmarca.openvitals.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteCountingHealthConnectClientTest {

    private val delegate = mockk<HealthConnectClient>(relaxed = true)
    private var writes = 0
    private val client = WriteCountingHealthConnectClient(delegate) { writes += 1 }
    private val window = TimeRangeFilter.between(Instant.EPOCH, Instant.EPOCH.plusSeconds(60))

    @Test fun `every kind of record write is counted and still reaches Health Connect`() = runTest {
        client.insertRecords(emptyList())
        client.updateRecords(emptyList())
        client.deleteRecords(StepsRecord::class, listOf("id"), emptyList())
        client.deleteRecords(StepsRecord::class, window)

        assertEquals(4, writes)
        coVerify(exactly = 1) { delegate.insertRecords(emptyList()) }
        coVerify(exactly = 1) { delegate.updateRecords(emptyList()) }
        coVerify(exactly = 1) { delegate.deleteRecords(StepsRecord::class, listOf("id"), emptyList()) }
        coVerify(exactly = 1) { delegate.deleteRecords(StepsRecord::class, window) }
    }

    @Test fun `a read is not a write`() = runTest {
        client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), window))

        assertEquals(0, writes)
    }

    @Test fun `a write that throws is counted, since part of it may have landed`() = runTest {
        coEvery { delegate.insertRecords(any()) } throws IllegalStateException("refused")

        val failure = runCatching { client.insertRecords(emptyList()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, writes)
    }

    @Test fun `members the interface gives a default body go to the real client, not to the default`() {
        val features = mockk<HealthConnectFeatures>()
        every { delegate.features } returns features

        assertSame(features, client.features)
    }
}
