@file:OptIn(ExperimentalPersonalHealthRecordApi::class)

package tech.mmarca.openvitals.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.MedicalDataSource
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.MedicalResourceId
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.CreateMedicalDataSourceRequest
import androidx.health.connect.client.request.DeleteMedicalResourcesRequest
import androidx.health.connect.client.request.UpsertMedicalResourceRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import kotlin.reflect.KClass

/**
 * The client every reader and writer shares, reporting each write to [onWrite].
 * The writes are spread over a dozen classes; this is the one place they all
 * pass. A write that throws is reported too, since part of it may have landed.
 */
internal class WriteCountingHealthConnectClient(
    private val delegate: HealthConnectClient,
    private val onWrite: () -> Unit,
) : HealthConnectClient by delegate {

    private inline fun <T> counted(write: () -> T): T =
        try {
            write()
        } finally {
            onWrite()
        }

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse =
        counted { delegate.insertRecords(records) }

    override suspend fun updateRecords(records: List<Record>) =
        counted { delegate.updateRecords(records) }

    override suspend fun deleteRecords(
        recordType: KClass<out Record>,
        recordIdsList: List<String>,
        clientRecordIdsList: List<String>,
    ) = counted { delegate.deleteRecords(recordType, recordIdsList, clientRecordIdsList) }

    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) =
        counted { delegate.deleteRecords(recordType, timeRangeFilter) }

    override suspend fun upsertMedicalResources(requests: List<UpsertMedicalResourceRequest>): List<MedicalResource> =
        counted { delegate.upsertMedicalResources(requests) }

    override suspend fun deleteMedicalResources(ids: List<MedicalResourceId>) =
        counted { delegate.deleteMedicalResources(ids) }

    override suspend fun deleteMedicalResources(request: DeleteMedicalResourcesRequest) =
        counted { delegate.deleteMedicalResources(request) }

    override suspend fun createMedicalDataSource(request: CreateMedicalDataSourceRequest): MedicalDataSource =
        counted { delegate.createMedicalDataSource(request) }

    override suspend fun deleteMedicalDataSourceWithData(id: String) =
        counted { delegate.deleteMedicalDataSourceWithData(id) }
}
